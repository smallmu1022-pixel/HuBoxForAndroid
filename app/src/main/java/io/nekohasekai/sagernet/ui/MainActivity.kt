package io.nekohasekai.sagernet.ui

import android.Manifest.permission.POST_NOTIFICATIONS
import android.annotation.SuppressLint
import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.RemoteException
import android.view.KeyEvent
import android.view.View
import androidx.activity.addCallback
import androidx.annotation.IdRes
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.preference.PreferenceDataStore
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.aidl.ISagerNetService
import io.nekohasekai.sagernet.aidl.SpeedDisplayData
import io.nekohasekai.sagernet.aidl.TrafficDataBatch
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.SagerConnection
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.database.preference.OnPreferenceDataStoreChangeListener
import io.nekohasekai.sagernet.databinding.LayoutMainBinding
import io.nekohasekai.sagernet.utils.LandingIpManager
import io.nekohasekai.sagernet.fmt.AbstractBean
import io.nekohasekai.sagernet.fmt.KryoConverters
import io.nekohasekai.sagernet.fmt.PluginEntry
import io.nekohasekai.sagernet.group.GroupInterfaceAdapter
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.deduplicateProxies
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ktx.alert
import io.nekohasekai.sagernet.ktx.isPreview
import io.nekohasekai.sagernet.ktx.launchCustomTab
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.parseProxies
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ktx.runOnMainDispatcher
import io.nekohasekai.sagernet.ui.MessageStore
import io.nekohasekai.sagernet.ktx.Logs
import moe.matsuri.nb4a.utils.Util

class MainActivity : ThemedActivity(),
    SagerConnection.Callback,
    OnPreferenceDataStoreChangeListener {

    lateinit var binding: LayoutMainBinding
    private var currentMainFragment: ToolbarFragment? = null

    // HuBox v1.1.4: the custom background image lives on the activity
    // window so it covers every page, the top bars and the area around
    // the floating bottom nav. One shared decoded bitmap for the whole
    // activity; page roots are transparent and let it show through.
    private var appBgVersionApplied = -1
    private var appBgBitmap: Bitmap? = null
    private var appBgJob: Job? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        MessageStore.setCurrentActivity(this)

        binding = LayoutMainBinding.inflate(layoutInflater)
        binding.bottomNav.setOnItemSelectedListener { item ->
            if (syncingBottomNav) true else displayFragmentWithId(item.itemId)
        }
        if (io.nekohasekai.sagernet.utils.Palette.isCustom()) {
            // Custom palette: the base style stays ocean, so tint the
            // navigation selection with the derived color directly.
            val palettePrimary = io.nekohasekai.sagernet.utils.Palette.primary(this)
            binding.bottomNav.itemTextColor = ColorStateList(
                arrayOf(
                    intArrayOf(android.R.attr.state_checked),
                    intArrayOf(),
                ),
                intArrayOf(
                    palettePrimary,
                    getColorAttr(android.R.attr.textColorSecondary),
                ),
            )
            binding.bottomNav.itemRippleColor =
                ColorStateList.valueOf(ColorUtils.setAlphaComponent(palettePrimary, 40))
        }

        if (savedInstanceState == null) {
            displayFragmentWithId(R.id.nav_home)
        } else {
            currentMainFragment =
                supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ToolbarFragment
            val currentNavigationItem = when (currentMainFragment) {
                is HomeFragment -> R.id.nav_home
                is ConfigurationFragment -> R.id.nav_configuration
                is GroupFragment -> R.id.nav_group
                is RouteFragment -> R.id.nav_route
                is SettingsFragment -> R.id.nav_settings
                is WebviewFragment -> R.id.nav_dashboard
                is DiagnosticsFragment -> R.id.nav_diagnostics
                is ToolsFragment -> R.id.nav_tools
                is LogcatFragment -> R.id.nav_logcat
                is AboutFragment -> R.id.nav_about
                else -> null
            }
            currentNavigationItem?.let { setCheckedItem(it) }
        }
        onBackPressedDispatcher.addCallback {
            if (binding.menuScrimOverlay?.visibility == View.VISIBLE) {
                hideMenuScrim()
                val fragment = supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ToolbarFragment
                if (fragment?.isToolbarInitialized == true) {
                    try {
                        fragment.toolbar.dismissPopupMenus()
                    } catch (_: Throwable) {}
                }
                return@addCallback
            }
            val fragment = supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ToolbarFragment
            if (fragment?.onBackPressed() == true) return@addCallback
            if (fragment is HomeFragment) {
                moveTaskToBack(true)
            } else {
                displayFragmentWithId(R.id.nav_home)
            }
        }

        setContentView(binding.root)
        currentMainFragment =
            supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ToolbarFragment
                ?: currentMainFragment
        changeState(BaseService.State.Idle)
        connection.connect(this, this)
        DataStore.configurationStore.registerChangeListener(this)
        GroupManager.userInterface = GroupInterfaceAdapter(this)

        if (intent?.action == Intent.ACTION_VIEW) {
            onNewIntent(intent)
        }

        // sdk 33 notification
        if (Build.VERSION.SDK_INT >= 33) {
            val checkPermission =
                ContextCompat.checkSelfPermission(this@MainActivity, POST_NOTIFICATIONS)
            if (checkPermission != PackageManager.PERMISSION_GRANTED) {
                //动态申请
                ActivityCompat.requestPermissions(
                    this@MainActivity, arrayOf(POST_NOTIFICATIONS), 0
                )
            }
        }

        val isPreRelease = isPreview && (BuildConfig.PRE_VERSION_NAME.contains("preview", true) || BuildConfig.PRE_VERSION_NAME.contains("beta", true) || BuildConfig.PRE_VERSION_NAME.contains("alpha", true))
        if (isPreRelease && DataStore.previewHintDismissedVersion != BuildConfig.PRE_VERSION_NAME) {
            MaterialAlertDialogBuilder(this)
                .setTitle(BuildConfig.PRE_VERSION_NAME)
                .setMessage(R.string.preview_version_hint)
                .setPositiveButton(android.R.string.ok, null)
                .setNeutralButton(R.string.preview_hint_dont_show_again) { _, _ ->
                    DataStore.previewHintDismissedVersion = BuildConfig.PRE_VERSION_NAME
                }
                .show()
        }

        applyAppBackgroundIfNeeded()
    }

    private fun applyAppBackgroundIfNeeded() {
        val version = DataStore.homeBackgroundVersion
        if (version == appBgVersionApplied) return
        appBgVersionApplied = version
        appBgJob?.cancel()
        val path = DataStore.homeBackground
        if (path.isEmpty()) {
            setWindowBackground(null)
            return
        }
        val appCtx = applicationContext
        val metrics = resources.displayMetrics
        // Cap the decode at the window size, long edge at most 1440px —
        // a full-res phone photo would cost ~10MB of bitmap; at 1440px
        // behind the scrim and glass cards it is ~4MB and visually the
        // same. The stored copy is already downscaled at pick time.
        var targetW = metrics.widthPixels.coerceAtLeast(1)
        var targetH = metrics.heightPixels.coerceAtLeast(1)
        val longEdge = maxOf(targetW, targetH)
        if (longEdge > 1440) {
            val ratio = 1440f / longEdge
            targetW = (targetW * ratio).toInt().coerceAtLeast(1)
            targetH = (targetH * ratio).toInt().coerceAtLeast(1)
        }
        val night = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) ==
            Configuration.UI_MODE_NIGHT_YES
        appBgJob = lifecycleScope.launch {
            val bitmap = withContext(Dispatchers.IO) {
                runCatching {
                    val file = File(appCtx.filesDir, path)
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(file.absolutePath, bounds)
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
                    var sample = 1
                    while (sample <= 32 &&
                        bounds.outWidth / (sample * 2) >= targetW &&
                        bounds.outHeight / (sample * 2) >= targetH
                    ) {
                        sample *= 2
                    }
                    val raw = BitmapFactory.decodeFile(
                        file.absolutePath,
                        BitmapFactory.Options().apply { inSampleSize = sample },
                    ) ?: return@runCatching null
                    val scale = (targetW.toFloat() / raw.width)
                        .coerceAtLeast(targetH.toFloat() / raw.height)
                    val scaledW = (raw.width * scale).toInt().coerceAtLeast(1)
                    val scaledH = (raw.height * scale).toInt().coerceAtLeast(1)
                    val scaled = Bitmap.createScaledBitmap(raw, scaledW, scaledH, true)
                    if (scaled !== raw) raw.recycle()
                    val cropW = targetW.coerceAtMost(scaledW)
                    val cropH = targetH.coerceAtMost(scaledH)
                    val x = ((scaledW - cropW) / 2).coerceAtLeast(0)
                    val y = ((scaledH - cropH) / 2).coerceAtLeast(0)
                    val cropped = Bitmap.createBitmap(scaled, x, y, cropW, cropH)
                    if (cropped !== scaled) scaled.recycle()
                    val out = cropped.copy(Bitmap.Config.ARGB_8888, true)
                    if (out !== cropped) cropped.recycle()
                    Canvas(out).drawColor(
                        if (night) Color.argb(96, 6, 14, 30) else Color.argb(80, 255, 255, 255)
                    )
                    out
                }.getOrNull()
            }
            if (isFinishing || isDestroyed) {
                bitmap?.recycle()
                return@launch
            }
            setWindowBackground(bitmap)
        }
    }

    private fun setWindowBackground(bitmap: Bitmap?) {
        val old = appBgBitmap
        appBgBitmap = bitmap
        window.setBackgroundDrawable(
            if (bitmap != null) {
                BitmapDrawable(resources, bitmap)
            } else {
                ContextCompat.getDrawable(this, R.drawable.bg_glass_window)
            }
        )
        if (old != null && old !== bitmap && !old.isRecycled) old.recycle()
    }

    override fun onResume() {
        super.onResume()
        MessageStore.setCurrentActivity(this)
        applyAppBackgroundIfNeeded()

        if (DataStore.hideFromRecentApps) {
            applyHideFromRecentApps(DataStore.hideFromRecentApps)
        }

        checkClipboardOnResume()
    }

    private var lastPromptedClipboard: String = ""

    private fun checkClipboardOnResume() {
        try {
            val text = SagerNet.getClipboardText().trim()
            if (text.isBlank() || text == lastPromptedClipboard || text.length < 8) return
            val looksLikeProxy = text.startsWith("vless://", ignoreCase = true) ||
                    text.startsWith("vmess://", ignoreCase = true) ||
                    text.startsWith("ss://", ignoreCase = true) ||
                    text.startsWith("ssr://", ignoreCase = true) ||
                    text.startsWith("trojan://", ignoreCase = true) ||
                    text.startsWith("hysteria://", ignoreCase = true) ||
                    text.startsWith("hysteria2://", ignoreCase = true) ||
                    text.startsWith("hy2://", ignoreCase = true) ||
                    text.startsWith("tuic://", ignoreCase = true) ||
                    text.startsWith("sn://", ignoreCase = true) ||
                    text.startsWith("clash://", ignoreCase = true)

            if (!looksLikeProxy) return
            lastPromptedClipboard = text

            com.google.android.material.dialog.MaterialAlertDialogBuilder(this)
                .setTitle(R.string.action_import)
                .setMessage(R.string.import_clipboard_prompt)
                .setPositiveButton(R.string.action_import) { _, _ ->
                    runOnDefaultDispatcher {
                        try {
                            val rawProxies = io.nekohasekai.sagernet.group.RawUpdater.parseRaw(text)
                            val currentGroup = DataStore.currentGroup()
                            val targetId = DataStore.selectedGroupForImport()
                            val targetGroup = io.nekohasekai.sagernet.database.SagerDatabase.groupDao.getById(targetId)
                            val shouldDeduplicate = (targetGroup?.subscription?.deduplication == true) || (currentGroup.subscription?.deduplication == true)
                            val proxies = if (shouldDeduplicate) rawProxies?.deduplicateProxies() else rawProxies
                            if (!proxies.isNullOrEmpty()) {
                                proxies.forEach { profile ->
                                    ProfileManager.createProfile(targetId, profile)
                                }
                                onMainDispatcher {
                                    displayFragmentWithId(R.id.nav_configuration)
                                    snackbar(resources.getQuantityString(R.plurals.added, proxies.size, proxies.size)).show()
                                }
                            }
                        } catch (e: io.nekohasekai.sagernet.ktx.SubscriptionFoundException) {
                            if (e.link.startsWith("sn://")) {
                                importSubscription(android.net.Uri.parse(e.link))
                            } else {
                                onMainDispatcher {
                                    val subscriptionLink = android.net.Uri.parse(e.link).getQueryParameter("url") ?: e.link
                                    startActivity(Intent(this@MainActivity, GroupSettingsActivity::class.java).apply {
                                        putExtra(GroupSettingsActivity.EXTRA_FROM_CLIPBOARD, true)
                                        putExtra(GroupSettingsActivity.EXTRA_GROUP_SUBSCRIPTION_LINK, subscriptionLink)
                                    })
                                }
                            }
                        } catch (e: Exception) {
                            io.nekohasekai.sagernet.ktx.Logs.w(e)
                            onMainDispatcher {
                                snackbar(e.readableMessage).show()
                            }
                        }
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        } catch (e: Exception) {
            io.nekohasekai.sagernet.ktx.Logs.w(e)
        }
    }

    override fun onPostResume() {
        super.onPostResume()
        val restoredFragment =
            supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ToolbarFragment
        if (restoredFragment != null && restoredFragment !== currentMainFragment) {
            currentMainFragment = restoredFragment
        }
    }

    fun applyHideFromRecentApps(hide: Boolean) {
        try {
            val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val tasks = activityManager.appTasks
            if (tasks.isNotEmpty()) {
                val task = tasks[0]
                task.setExcludeFromRecents(hide)
            }
        } catch (e: Exception) {
            Logs.w("Failed to set excludeFromRecents: ${e.message}")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)

        val uri = intent.data ?: return

        runOnDefaultDispatcher {
            if (uri.scheme == "sn" && uri.host == "subscription" || uri.scheme == "clash") {
                importSubscription(uri)
            } else {
                importProfile(uri)
            }
        }
    }

    fun urlTest(): Int {
        if (!DataStore.serviceState.connected || connection.service == null) {
            error("not started")
        }
        return connection.service!!.urlTest()
    }

    suspend fun importSubscription(uri: Uri) {
        val group: ProxyGroup

        val url = uri.getQueryParameter("url")
        if (!url.isNullOrBlank()) {
            group = ProxyGroup(type = GroupType.SUBSCRIPTION)
            val subscription = SubscriptionBean()
            group.subscription = subscription

            // cleartext format
            subscription.link = url
            group.name = uri.getQueryParameter("name")
        } else {
            val data = uri.encodedQuery.takeIf { !it.isNullOrBlank() } ?: return
            try {
                group = KryoConverters.deserialize(
                    ProxyGroup().apply { export = true }, Util.zlibDecompress(Util.b64Decode(data))
                ).apply {
                    export = false
                }
            } catch (e: Exception) {
                onMainDispatcher {
                    alert(e.readableMessage).show()
                }
                return
            }
        }

        val name = group.name.takeIf { !it.isNullOrBlank() } ?: group.subscription?.link
        ?: group.subscription?.token
        if (name.isNullOrBlank()) return

        if (group.name.isNullOrBlank()) {
            val candidate = group.subscription?.link?.takeIf { it.isNotBlank() }?.let {
                io.nekohasekai.sagernet.group.RawUpdater.extractAirportName(it)
            }
            group.name = candidate ?: ("Subscription #" + System.currentTimeMillis())
        }

        onMainDispatcher {

            displayFragmentWithId(R.id.nav_group)

            MaterialAlertDialogBuilder(this@MainActivity).setTitle(R.string.subscription_import)
                .setMessage(getString(R.string.subscription_import_message, name))
                .setPositiveButton(R.string.yes) { _, _ ->
                    runOnDefaultDispatcher {
                        finishImportSubscription(group)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()

        }

    }

    private suspend fun finishImportSubscription(subscription: ProxyGroup) {
        GroupManager.createGroup(subscription)
        GroupUpdater.startUpdate(subscription, true)
    }

    suspend fun importProfile(uri: Uri) {
        val profile = try {
            parseProxies(uri.toString()).getOrNull(0) ?: error(getString(R.string.no_proxies_found))
        } catch (e: Exception) {
            onMainDispatcher {
                alert(e.readableMessage).show()
            }
            return
        }

        onMainDispatcher {
            MaterialAlertDialogBuilder(this@MainActivity).setTitle(R.string.profile_import)
                .setMessage(getString(R.string.profile_import_message, profile.displayName()))
                .setPositiveButton(R.string.yes) { _, _ ->
                    runOnDefaultDispatcher {
                        finishImportProfile(profile)
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
        }

    }

    private suspend fun finishImportProfile(profile: AbstractBean) {
        val targetId = DataStore.selectedGroupForImport()

        ProfileManager.createProfile(targetId, profile)

        onMainDispatcher {
            displayFragmentWithId(R.id.nav_configuration)

            snackbar(resources.getQuantityString(R.plurals.added, 1, 1)).show()
        }
    }

    override fun missingPlugin(profileName: String, pluginName: String) {
        val pluginEntity = PluginEntry.find(pluginName)

        // unknown exe or neko plugin
        if (pluginEntity == null) {
            snackbar(getString(R.string.plugin_unknown, pluginName)).show()
            return
        }

        // official exe

        MaterialAlertDialogBuilder(this).setTitle(R.string.missing_plugin)
            .setMessage(
                getString(
                    R.string.profile_requiring_plugin, profileName, pluginEntity.displayName
                )
            )
            .setPositiveButton(R.string.action_download) { _, _ ->
                showDownloadDialog(pluginEntity)
            }
            .setNeutralButton(android.R.string.cancel, null)
            .show()
    }

    private fun showDownloadDialog(pluginEntry: PluginEntry) {
        var index = 0
        var playIndex = -1
        var fdroidIndex = -1

        val items = mutableListOf<String>()
        if (pluginEntry.downloadSource.playStore) {
            items.add(getString(R.string.install_from_play_store))
            playIndex = index++
        }
        if (pluginEntry.downloadSource.fdroid) {
            items.add(getString(R.string.install_from_fdroid))
            fdroidIndex = index++
        }

        items.add(getString(R.string.download))
        val downloadIndex = index

        MaterialAlertDialogBuilder(this).setTitle(pluginEntry.name)
            .setItems(items.toTypedArray()) { _, which ->
                when (which) {
                    playIndex -> launchCustomTab("https://play.google.com/store/apps/details?id=${pluginEntry.packageName}")
                    fdroidIndex -> launchCustomTab("https://f-droid.org/packages/${pluginEntry.packageName}/")
                    downloadIndex -> launchCustomTab(pluginEntry.downloadSource.downloadLink)
                }
            }
            .show()
    }

    fun isCurrentFragment(@IdRes id: Int): Boolean {
        val current = currentMainFragment
            ?: supportFragmentManager.findFragmentById(R.id.fragment_holder)
        return when (id) {
            R.id.nav_home -> current is HomeFragment
            R.id.nav_configuration -> current is ConfigurationFragment
            R.id.nav_group -> current is GroupFragment
            R.id.nav_route -> current is RouteFragment
            R.id.nav_settings -> current is SettingsFragment
            R.id.nav_dashboard -> current is WebviewFragment
            R.id.nav_diagnostics -> current is DiagnosticsFragment
            R.id.nav_tools -> current is ToolsFragment
            R.id.nav_logcat -> current is LogcatFragment
            R.id.nav_about -> current is AboutFragment
            else -> false
        }
    }

    fun setCheckedItem(@IdRes id: Int) {
        if (id == R.id.nav_home || id == R.id.nav_configuration ||
            id == R.id.nav_tools || id == R.id.nav_settings
        ) {
            syncingBottomNav = true
            binding.bottomNav.selectedItemId = id
            syncingBottomNav = false
        }
    }

    private var syncingBottomNav = false

    // HuBox v1.1.1: the four bottom-nav pages stay alive once created and are
    // switched with show/hide instead of destroy-and-rebuild, which removes
    // the visible stutter on every tab change. Hidden pages are capped at
    // STARTED, so their onPause/onResume semantics (timers, refresh on
    // return) behave exactly as they did with replace().
    private val tabTags = mapOf(
        R.id.nav_home to "tab_home",
        R.id.nav_configuration to "tab_configuration",
        R.id.nav_tools to "tab_tools",
        R.id.nav_settings to "tab_settings",
    )

    private fun createTabFragment(@IdRes id: Int): ToolbarFragment = when (id) {
        R.id.nav_home -> HomeFragment()
        R.id.nav_configuration -> ConfigurationFragment()
        R.id.nav_tools -> ToolsFragment()
        else -> SettingsFragment()
    }

    private fun displayTab(@IdRes id: Int) {
        val tag = tabTags.getValue(id)
        val target = supportFragmentManager.findFragmentByTag(tag) as? ToolbarFragment
            ?: createTabFragment(id)
        val tx = supportFragmentManager.beginTransaction()
        for (other in supportFragmentManager.fragments) {
            if (other.id != R.id.fragment_holder || other === target) continue
            if (other.tag in tabTags.values) {
                tx.hide(other)
                tx.setMaxLifecycle(other, Lifecycle.State.STARTED)
            } else {
                tx.remove(other)
            }
        }
        if (target.isAdded) {
            tx.show(target)
        } else {
            tx.add(R.id.fragment_holder, target, tag)
        }
        tx.setMaxLifecycle(target, Lifecycle.State.RESUMED)
        tx.commitAllowingStateLoss()
        currentMainFragment = target
    }

    @SuppressLint("CommitTransaction")
    fun displayFragment(fragment: ToolbarFragment) {
        currentMainFragment = fragment
        val tx = supportFragmentManager.beginTransaction()
        // Keep cached tab pages alive underneath non-tab screens.
        for (other in supportFragmentManager.fragments) {
            if (other.id != R.id.fragment_holder) continue
            if (other.tag in tabTags.values) {
                tx.hide(other)
                tx.setMaxLifecycle(other, Lifecycle.State.STARTED)
            } else {
                tx.remove(other)
            }
        }
        tx.add(R.id.fragment_holder, fragment)
        tx.setMaxLifecycle(fragment, Lifecycle.State.RESUMED)
        tx.commitAllowingStateLoss()
    }

    private fun refreshConfigurationProfileState() {
        val fragment = currentMainFragment
            ?: supportFragmentManager.findFragmentById(R.id.fragment_holder)
        (fragment as? ConfigurationFragment)?.refreshProfileState()
    }

    fun displayFragmentWithId(@IdRes id: Int): Boolean {
        when (id) {
            R.id.nav_home -> displayTab(R.id.nav_home)

            R.id.nav_configuration -> displayTab(R.id.nav_configuration)

            R.id.nav_group -> displayFragment(GroupFragment())
            R.id.nav_route -> displayFragment(RouteFragment())
            R.id.nav_settings -> displayTab(R.id.nav_settings)
            R.id.nav_dashboard -> displayFragment(WebviewFragment())
            R.id.nav_diagnostics -> displayFragment(DiagnosticsFragment())
            R.id.nav_apps -> {
                startActivity(Intent(this, AppManagerActivity::class.java))
                return false
            }
            R.id.nav_tools -> displayTab(R.id.nav_tools)
            R.id.nav_logcat -> displayFragment(LogcatFragment())

            R.id.nav_about -> displayFragment(AboutFragment())

            else -> return false
        }
        setCheckedItem(id)
        return true
    }

    private fun changeState(
        state: BaseService.State,
        msg: String? = null,
    ) {
        DataStore.serviceState = state
        refreshConfigurationProfileState()
        io.nekohasekai.sagernet.widget.HuBoxWidgetProvider.updateWidgets(this)

        (currentMainFragment as? HomeFragment)?.onServiceStateChanged(state)
        if (msg != null) snackbar(getString(R.string.vpn_error, msg)).show()
    }

    override fun snackbarInternal(text: CharSequence): Snackbar {
        return Snackbar.make(binding.coordinator, text, Snackbar.LENGTH_LONG).apply {
            // HuBox v1.1.2: light glass snackbar — rounded, inset from the
            // screen edges, and no elevation shadow above the bottom nav.
            val density = resources.displayMetrics.density
            view.elevation = 0f
            view.background = GradientDrawable().apply {
                cornerRadius = 14f * density
                setColor(getColor(R.color.hubox_snackbar_bg))
                setStroke(
                    (1f * density).toInt().coerceAtLeast(1),
                    getColor(R.color.card_stroke),
                )
            }
            view.findViewById<TextView>(com.google.android.material.R.id.snackbar_text)
                ?.setTextColor(getColor(R.color.hubox_snackbar_text))
            // HuBox v1.1.3: prompts are shown centered on screen instead of
            // docked at the bottom. Params are finalized once the view is
            // attached, so apply again via post{} to make it stick.
            val sideMargin = (16 * density).toInt()
            fun centerSnackbar() {
                (view.layoutParams as? androidx.coordinatorlayout.widget.CoordinatorLayout.LayoutParams)?.let { lp ->
                    lp.gravity = android.view.Gravity.CENTER
                    lp.setMargins(sideMargin, 0, sideMargin, 0)
                    view.layoutParams = lp
                }
            }
            centerSnackbar()
            view.post { centerSnackbar() }
        }
    }

    override fun stateChanged(state: BaseService.State, profileName: String?, msg: String?) {
        changeState(state, msg)
    }

    val connection = SagerConnection(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND, true)
    override fun onServiceConnected(service: ISagerNetService) = changeState(
        try {
            BaseService.State.values()[service.state]
        } catch (_: RemoteException) {
            BaseService.State.Idle
        }
    )

    override fun onServiceDisconnected() = changeState(BaseService.State.Idle)
    override fun onBinderDied() {
        connection.disconnect(this)
        connection.connect(this, this)
    }

    // may NOT called when app is in background
    // ONLY do UI update here, write DB in bg process
    override fun cbSpeedUpdate(stats: SpeedDisplayData) {
        (currentMainFragment as? HomeFragment)?.updateSpeed(stats.txRateProxy, stats.rxRateProxy)
        (currentMainFragment as? HomeFragment)?.updateSessionTraffic(stats.txTotal, stats.rxTotal)
    }

    override suspend fun cbTrafficUpdate(data: TrafficDataBatch) {
        ProfileManager.postUpdate(data.items)
    }

    override fun cbSelectorUpdate(id: Long) {
        val old = DataStore.selectedProxy
        DataStore.selectedProxy = id
        DataStore.currentProfile = id
        refreshConfigurationProfileState()
        (currentMainFragment as? HomeFragment)?.refreshContent()
        runOnDefaultDispatcher {
            ProfileManager.postUpdate(old, true)
            ProfileManager.postUpdate(id, true)
        }
    }

    override fun onPreferenceDataStoreChanged(store: PreferenceDataStore, key: String) {
        runOnMainDispatcher {
            if (isDestroyed || isFinishing) return@runOnMainDispatcher
            when (key) {
                Key.SERVICE_MODE -> onBinderDied()
                Key.PROFILE_ID -> {
                    LandingIpManager.clearCache()
                }
                Key.PROXY_APPS, Key.BYPASS_MODE, Key.INDIVIDUAL -> {
                    if (DataStore.serviceState.canStop) {
                        snackbar(getString(R.string.need_reload)).setAction(R.string.apply) {
                            SagerNet.reloadService()
                        }.show()
                    }
                }
            }
        }
    }

    override fun onStart() {
        connection.updateConnectionId(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_FOREGROUND)
        super.onStart()
    }

    override fun onStop() {
        connection.updateConnectionId(SagerConnection.CONNECTION_ID_MAIN_ACTIVITY_BACKGROUND)
        super.onStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        appBgJob?.cancel()
        appBgBitmap?.recycle()
        appBgBitmap = null
        GroupManager.userInterface = null
        DataStore.configurationStore.unregisterChangeListener(this)
        connection.disconnect(this)
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (super.onKeyDown(keyCode, event)) return true

        val fragment =
            supportFragmentManager.findFragmentById(R.id.fragment_holder) as? ToolbarFragment
        return fragment != null && fragment.onKeyDown(keyCode, event)
    }

    private var activeMenuToken: Any? = null

    fun showMenuScrim(token: Any? = null, onScrimClick: (() -> Unit)? = null) {
        val scrim = binding.menuScrimOverlay ?: return
        activeMenuToken = token ?: Any()
        scrim.visibility = View.VISIBLE
        scrim.animate().cancel()
        scrim.animate().alpha(1f).setDuration(200).start()
        scrim.setOnClickListener {
            onScrimClick?.invoke()
            hideMenuScrim(token)
        }
    }

    fun hideMenuScrim(token: Any? = null) {
        if (token != null && activeMenuToken != null && token != activeMenuToken) {
            return
        }
        activeMenuToken = null
        val scrim = binding.menuScrimOverlay ?: return
        scrim.animate().cancel()
        scrim.animate().alpha(0f).setDuration(200).withEndAction {
            if (activeMenuToken == null) {
                scrim.visibility = View.GONE
                scrim.setOnClickListener(null)
            }
        }.start()
    }

}
