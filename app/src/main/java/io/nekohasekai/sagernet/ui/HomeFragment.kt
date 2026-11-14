package io.nekohasekai.sagernet.ui

import android.animation.ObjectAnimator
import android.content.Intent
import android.graphics.Color
import android.graphics.PorterDuff
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.text.format.Formatter
import android.view.View
import android.view.animation.LinearInterpolator
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.graphics.ColorUtils
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.updatePadding
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.utils.LandingIpManager
import io.nekohasekai.sagernet.utils.Palette
import io.nekohasekai.sagernet.utils.Theme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * HuBox v1.1.0 glass home page.
 *
 * Sticky: top bar (halo logo, log / about shortcuts) + connect button.
 * Scrolling: current node card, proxy mode segmented control, then the
 * customizable modules in the user's order (see [HomeModules]).
 */
class HomeFragment : ToolbarFragment(R.layout.layout_home) {

    private lateinit var connectBg: View
    private lateinit var connectHalo: ImageView
    private lateinit var connectArc: ImageView
    private lateinit var stateText: TextView
    private lateinit var stateHint: TextView
    private lateinit var nodeName: TextView
    private lateinit var nodeMeta: TextView
    private lateinit var nodePing: TextView
    private lateinit var modulesContainer: LinearLayout

    private var arcAnimator: ObjectAnimator? = null
    private var arcDurationMs: Long = 0L

    // Halo ring rotation: brisk while connecting, a slow ambient spin once
    // connected (single small view, GPU-cheap, no per-frame work on CPU).
    // HuBox v1.1.3: the arc radius was shrunk so the ring stays well inside
    // the button disc and never reads as a shadow outside it.
    private val ARC_CONNECTING_MS = 1100L
    private val ARC_CONNECTED_MS = 6000L
    private var sessionJob: Job? = null
    private var sessionInitRunning = false
    private var sessionTx: Long = 0L
    private var sessionRx: Long = 0L
    private var trafficTxView: TextView? = null
    private var trafficRxView: TextView? = null
    private var sessionValueView: TextView? = null
    private var batteryValueView: TextView? = null
    private var routeValueView: TextView? = null
    private var todayValueView: TextView? = null
    private var ipValueView: TextView? = null
    private var ipQueryInFlight = false
    private var ipRefreshJob: Job? = null
    private var ipRetryCount = 0
    private var skipNextResumeRefresh = false

    private val connect = registerForActivityResult(VpnRequestActivity.StartService()) {
        if (it) {
            view?.let { v -> Snackbar.make(v, R.string.vpn_permission_denied, Snackbar.LENGTH_LONG).show() }
        }
        renderState(DataStore.serviceState)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        Palette.applyWindowBackground(view)

        val topbar = view.findViewById<View>(R.id.home_topbar)
        ViewCompat.setOnApplyWindowInsetsListener(topbar) { v, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            v.updatePadding(top = bars.top + (12 * resources.displayMetrics.density).toInt())
            insets
        }
        ViewCompat.requestApplyInsets(topbar)

        // Top bar halo (first ImageView child) follows the palette;
        // the launcher icon does not.
        (topbar as? LinearLayout)?.let { bar ->
            for (i in 0 until bar.childCount) {
                val child = bar.getChildAt(i)
                if (child is ImageView) {
                    child.setColorFilter(Palette.primary(requireContext()), PorterDuff.Mode.SRC_IN)
                    break
                }
            }
        }

        view.findViewById<View>(R.id.btn_home_about).setOnClickListener {
            (activity as? MainActivity)?.displayFragmentWithId(R.id.nav_about)
        }

        connectBg = view.findViewById(R.id.connect_bg)
        connectHalo = view.findViewById(R.id.connect_halo)
        connectArc = view.findViewById(R.id.connect_arc)
        stateText = view.findViewById(R.id.home_state_text)
        stateHint = view.findViewById(R.id.home_state_hint)
        nodeName = view.findViewById(R.id.home_node_name)
        nodeMeta = view.findViewById(R.id.home_node_meta)
        nodePing = view.findViewById(R.id.home_node_ping)
        modulesContainer = view.findViewById(R.id.home_modules)

        view.findViewById<View>(R.id.connect_button).setOnClickListener {
            if (DataStore.serviceState.canStop) {
                SagerNet.stopService()
            } else {
                connect.launch(null)
            }
        }

        val nodeCard = view.findViewById<View>(R.id.node_card)
        nodeCard.setOnClickListener { openNodeSheet() }
        nodeCard.setOnLongClickListener { openCustomize(); true }

        view.findViewById<View>(R.id.home_btn_mode_smart).setOnClickListener {
            if (DataStore.smartMode && !DataStore.globalMode) {
                DataStore.smartMode = false
            } else {
                DataStore.smartMode = true
                DataStore.globalMode = false
            }
            afterModeChanged(it)
        }
        view.findViewById<View>(R.id.home_btn_mode_global).setOnClickListener {
            if (DataStore.globalMode) {
                DataStore.globalMode = false
            } else {
                DataStore.globalMode = true
                DataStore.smartMode = false
            }
            afterModeChanged(it)
        }
        view.findViewById<View>(R.id.home_btn_mode_smart).setOnLongClickListener { openCustomize(); true }
        view.findViewById<View>(R.id.home_btn_mode_global).setOnLongClickListener { openCustomize(); true }

        refreshContent()
        // onViewCreated already refreshed; the onResume that immediately
        // follows would rebuild every module card a second time for nothing.
        skipNextResumeRefresh = true
    }

    override fun onResume() {
        super.onResume()
        // HuBox v1.1.4 power: the session ticker and the rotating arc run
        // only while the home page is actually resumed (see onPause).
        when (DataStore.serviceState) {
            BaseService.State.Connected -> {
                if (::connectArc.isInitialized && connectArc.visibility == View.VISIBLE) {
                    startArcAnimation(ARC_CONNECTED_MS)
                }
                if (HomeModules.sessionStart(requireContext()) > 0) startSessionTicker()
            }
            BaseService.State.Connecting -> {
                if (::connectArc.isInitialized && connectArc.visibility == View.VISIBLE) {
                    startArcAnimation(ARC_CONNECTING_MS)
                }
            }
            else -> Unit
        }
        if (skipNextResumeRefresh) {
            skipNextResumeRefresh = false
            return
        }
        if (::modulesContainer.isInitialized) {
            refreshContent()
        }
    }

    override fun onPause() {
        stopArcAnimation()
        stopSessionTicker()
        super.onPause()
    }

    override fun onDestroyView() {
        stopArcAnimation()
        stopSessionTicker()
        super.onDestroyView()
    }

    private fun afterModeChanged(view: View) {
        updateModeButtons()
        updateRouteModule()
        if (DataStore.serviceState.canStop) {
            runCatching { SagerNet.reloadService() }
        }
    }

    private fun openCustomize() {
        (activity as? MainActivity)?.displayFragment(HomeCustomizeFragment())
    }

    private fun openNodeSheet() {
        val sheet = NodeSelectSheet()
        // Selecting a node or finishing a speed test only changes the node
        // card; a full refresh would re-inflate every module for nothing.
        sheet.onChanged = {
            if (isAdded && view != null) {
                renderState(DataStore.serviceState)
                loadNodeInfo()
            }
        }
        sheet.show(childFragmentManager, "node_select")
    }

    // ---- callbacks wired from MainActivity ----

    fun onServiceStateChanged(state: BaseService.State) {
        if (!isAdded || view == null) return
        renderState(state)
        updateTodayModule()
        updateIpModule(force = false)
    }

    fun updateSpeed(txRate: Long, rxRate: Long) {
        val ctx = context ?: return
        trafficTxView?.text = "▲ ${getString(R.string.speed, Formatter.formatFileSize(ctx, txRate))}"
        trafficRxView?.text = "▼ ${getString(R.string.speed, Formatter.formatFileSize(ctx, rxRate))}"
    }

    fun refreshContent() {
        if (!isAdded || view == null) return
        renderState(DataStore.serviceState)
        updateModeButtons()
        rebuildModules()
        loadNodeInfo()
    }

    // ---- rendering ----

    private fun renderState(state: BaseService.State) {
        val ctx = requireContext()
        when (state) {
            BaseService.State.Connected -> {
                connectBg.background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    Palette.connectGradient(ctx),
                ).apply { shape = GradientDrawable.OVAL }
                connectHalo.setImageResource(R.drawable.ic_halo_white)
                connectHalo.clearColorFilter()
                connectArc.visibility = View.VISIBLE
                startArcAnimation(ARC_CONNECTED_MS)
                stateText.text = getString(R.string.home_status_connected)
                stateHint.text = getString(R.string.home_action_disconnect)
                ensureSessionStarted()
                startSessionTicker()
                scheduleIpAutoRefresh()
            }

            BaseService.State.Connecting -> {
                connectBg.background = GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM,
                    Palette.connectGradient(ctx),
                ).apply { shape = GradientDrawable.OVAL }
                connectHalo.setImageResource(R.drawable.ic_halo_white)
                connectHalo.clearColorFilter()
                connectArc.visibility = View.VISIBLE
                startArcAnimation(ARC_CONNECTING_MS)
                stateText.text = getString(R.string.connecting)
                stateHint.text = getString(R.string.home_tap_to_cancel)
            }

            BaseService.State.Stopping -> {
                applyOffBackground()
                stopArcAnimation()
                ipRefreshJob?.cancel()
                connectArc.visibility = View.GONE
                stateText.text = getString(R.string.home_status_stopping)
                stateHint.text = ""
            }

            else -> {
                applyOffBackground()
                stopArcAnimation()
                ipRefreshJob?.cancel()
                connectArc.visibility = View.GONE
                stopSessionTicker()
                if (HomeModules.sessionStart(ctx) > 0) {
                    HomeModules.endSession(ctx)
                }
                sessionValueView?.visibility = View.GONE
                stateText.text = getString(R.string.not_connected)
                stateHint.text = getString(R.string.home_action_connect)
            }
        }
    }

    private fun applyOffBackground() {
        val ctx = requireContext()
        val night = Theme.usingNightMode(ctx)
        val scheme = Palette.scheme()
        connectBg.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            if (night) {
                intArrayOf(scheme.darkBgEnd, scheme.darkBgStart)
            } else {
                intArrayOf(Color.WHITE, scheme.bgStart)
            },
        ).apply {
            shape = GradientDrawable.OVAL
            setStroke(
                (1.5f * resources.displayMetrics.density).toInt().coerceAtLeast(1),
                ColorUtils.setAlphaComponent(Palette.primary(ctx), 130),
            )
        }
        connectHalo.setImageResource(R.drawable.ic_halo)
        connectHalo.setColorFilter(Palette.primary(ctx), PorterDuff.Mode.SRC_IN)
    }

    private fun startArcAnimation(durationMs: Long) {
        if (arcAnimator?.isRunning == true && arcDurationMs == durationMs) return
        stopArcAnimation()
        arcDurationMs = durationMs
        arcAnimator = ObjectAnimator.ofFloat(connectArc, View.ROTATION, 0f, 360f).apply {
            duration = durationMs
            repeatCount = ObjectAnimator.INFINITE
            interpolator = LinearInterpolator()
            start()
        }
    }

    private fun stopArcAnimation() {
        arcAnimator?.cancel()
        arcAnimator = null
        arcDurationMs = 0L
        if (::connectArc.isInitialized) connectArc.rotation = 0f
    }

    // HuBox v1.1.4: the custom background image moved to MainActivity
    // (window background, shared by every page). See applyAppBackgroundIfNeeded.

    private fun loadNodeInfo() {
        val selectedId = DataStore.selectedProxy
        viewLifecycleOwner.lifecycleScope.launch {
            val snapshot = withContext(Dispatchers.IO) {
                if (selectedId <= 0) {
                    null
                } else {
                    runCatching {
                        val entity = SagerDatabase.proxyDao.getById(selectedId) ?: return@runCatching null
                        val group = runCatching { SagerDatabase.groupDao.getById(entity.groupId) }.getOrNull()
                        Triple(entity, group?.displayName(), entity.displayType())
                    }.getOrNull()
                }
            }
            if (!isAdded) return@launch
            if (snapshot == null) {
                nodeName.text = getString(R.string.home_no_profile_selected)
                nodeMeta.text = ""
                nodePing.text = ""
                return@launch
            }
            val (entity, groupName, type) = snapshot
            nodeName.text = entity.displayName()
            val freshness = HomeModules.freshnessText(
                requireContext(),
                HomeModules.lastTestTime(requireContext(), entity.groupId),
            )
            nodeMeta.text = listOfNotNull(groupName, type, freshness)
                .filter { it.isNotBlank() }
                .joinToString(" · ")
            when (entity.status) {
                1 -> {
                    nodePing.text = if (entity.ping > 0) "${entity.ping}ms" else ""
                    nodePing.setTextColor(
                        requireContext().getColor(
                            if (entity.ping in 1..100) R.color.hubox_latency_good else R.color.hubox_latency_bad
                        )
                    )
                }

                3 -> {
                    nodePing.text = "—"
                    nodePing.setTextColor(requireContext().getColor(R.color.hubox_latency_error))
                }

                else -> nodePing.text = ""
            }
        }
    }

    private fun updateModeButtons() {
        val root = view ?: return
        // Selected mode is highlighted green in both light and dark mode
        // (Kenji v1.1.1); unselected stays the default variant color.
        val accent = ContextCompat.getColor(requireContext(), R.color.hubox_highlight_green)
        val variant = requireContext().getColorAttr(R.attr.colorOnSurfaceVariant)
        val isGlobal = DataStore.globalMode
        val isSmart = DataStore.smartMode && !isGlobal
        applyModeButton(
            root.findViewById(R.id.home_btn_mode_smart),
            root.findViewById(R.id.home_icon_mode_smart),
            root.findViewById(R.id.home_text_mode_smart),
            isSmart, accent, variant,
        )
        applyModeButton(
            root.findViewById(R.id.home_btn_mode_global),
            root.findViewById(R.id.home_icon_mode_global),
            root.findViewById(R.id.home_text_mode_global),
            isGlobal, accent, variant,
        )
    }

    private fun applyModeButton(
        btn: View?,
        icon: ImageView?,
        text: TextView?,
        active: Boolean,
        accent: Int,
        variant: Int,
    ) {
        btn ?: return
        if (active) {
            val density = resources.displayMetrics.density
            btn.background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 14 * density
                setColor(ColorUtils.setAlphaComponent(accent, 26))
                setStroke((1.5f * density).toInt().coerceAtLeast(1), accent)
            }
            icon?.drawable?.mutate()?.setTint(accent)
            text?.setTextColor(accent)
        } else {
            btn.setBackgroundResource(R.drawable.bg_proxy_mode_btn_inactive)
            icon?.drawable?.mutate()?.setTint(variant)
            text?.setTextColor(variant)
        }
    }

    // ---- customizable modules ----

    private fun rebuildModules() {
        val ctx = requireContext()
        modulesContainer.removeAllViews()
        trafficTxView = null
        trafficRxView = null
        sessionValueView = null
        batteryValueView = null
        routeValueView = null
        todayValueView = null
        ipValueView = null

        val inflater = layoutInflater
        for (module in HomeModules.orderedModules(ctx)) {
            if (!HomeModules.isEnabled(ctx, module.id)) continue
            when (module.id) {
                HomeModules.BATTERY -> {
                    val card = inflater.inflate(R.layout.layout_home_module_battery, modulesContainer, false)
                    batteryValueView = card.findViewById(R.id.module_battery_value)
                    card.setOnClickListener { showBatteryDialog() }
                    card.setOnLongClickListener { openCustomize(); true }
                    modulesContainer.addView(card)
                }

                HomeModules.TRAFFIC -> {
                    val card = inflater.inflate(R.layout.layout_home_module_traffic, modulesContainer, false)
                    trafficTxView = card.findViewById(R.id.module_traffic_tx)
                    trafficRxView = card.findViewById(R.id.module_traffic_rx)
                    sessionValueView = card.findViewById(R.id.module_session_value)
                    updateSpeed(0, 0)
                    card.setOnLongClickListener { openCustomize(); true }
                    modulesContainer.addView(card)
                }

                HomeModules.ROUTE -> {
                    // Display only (Kenji v1.1.1): route rules are edited from
                    // the Tools grid, this card just shows the current state.
                    val card = inflater.inflate(R.layout.layout_home_module_route, modulesContainer, false)
                    routeValueView = card.findViewById(R.id.module_route_value)
                    card.setOnLongClickListener { openCustomize(); true }
                    modulesContainer.addView(card)
                }

                HomeModules.TODAY -> {
                    val card = inflater.inflate(R.layout.layout_home_module_today, modulesContainer, false)
                    todayValueView = card.findViewById(R.id.module_today_value)
                    card.setOnLongClickListener { openCustomize(); true }
                    modulesContainer.addView(card)
                }

                HomeModules.IP -> {
                    val card = inflater.inflate(R.layout.layout_home_module_ip, modulesContainer, false)
                    ipValueView = card.findViewById(R.id.module_ip_value)
                    card.setOnClickListener { updateIpModule(force = true) }
                    card.setOnLongClickListener { openCustomize(); true }
                    modulesContainer.addView(card)
                }

                HomeModules.TOOLS -> {
                    val card = inflater.inflate(R.layout.layout_home_module_tools, modulesContainer, false)
                    card.findViewById<View>(R.id.module_tool_test).setOnClickListener {
                        (activity as? MainActivity)?.displayFragmentWithId(R.id.nav_configuration)
                    }
                    card.findViewById<View>(R.id.module_tool_diag).setOnClickListener {
                        (activity as? MainActivity)?.displayFragmentWithId(R.id.nav_diagnostics)
                    }
                    card.findViewById<View>(R.id.module_tool_lan).setOnClickListener {
                        startActivity(Intent(requireContext(), LanSharingActivity::class.java))
                    }
                    card.setOnLongClickListener { openCustomize(); true }
                    modulesContainer.addView(card)
                }
            }
        }
        updateBatteryModule()
        updateRouteModule()
        updateTodayModule()
        updateIpModule(force = false)
    }

    private fun showBatteryDialog() {
        val modes = arrayOf("performance", "balanced", "extreme")
        val labels = arrayOf(
            getString(R.string.battery_saver_performance),
            getString(R.string.battery_saver_balanced),
            getString(R.string.battery_saver_extreme),
        )
        val selected = modes.indexOf(DataStore.batterySaverMode).takeIf { it >= 0 } ?: 1
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.battery_saver_title)
            .setSingleChoiceItems(labels, selected) { dialog, which ->
                DataStore.batterySaverMode = modes[which]
                updateBatteryModule()
                dialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun updateBatteryModule() {
        batteryValueView?.text = when (DataStore.batterySaverMode) {
            "performance" -> getString(R.string.battery_saver_performance)
            "extreme" -> getString(R.string.battery_saver_extreme)
            else -> getString(R.string.battery_saver_balanced)
        }
        batteryValueView?.setTextColor(Palette.primary(requireContext()))
        batteryValueView?.background = null
    }

    private fun updateRouteModule() {
        routeValueView?.text = when {
            DataStore.globalMode -> getString(R.string.status_global_active)
            DataStore.smartMode -> getString(R.string.status_smart_routing)
            else -> getString(R.string.status_rule_active)
        }
    }

    private fun updateTodayModule() {
        val target = todayValueView ?: return
        val ctx = requireContext()
        viewLifecycleOwner.lifecycleScope.launch {
            val today = withContext(Dispatchers.IO) {
                val total = runCatching {
                    SagerDatabase.proxyDao.getAll().sumOf { it.tx + it.rx }
                }.getOrDefault(0L)
                HomeModules.todayTraffic(ctx, total)
            }
            if (isAdded) target.text = Formatter.formatFileSize(ctx, today)
        }
    }

    private fun updateIpModule(force: Boolean) {
        val target = ipValueView ?: return
        val ctx = requireContext()
        if (!DataStore.serviceState.connected) {
            target.text = getString(R.string.home_exit_ip_unknown)
            return
        }
        val cached = LandingIpManager.getCachedInfo()
        if (cached != null && !force) {
            target.text = "${cached.countryFlag} ${cached.ip}"
            return
        }
        if (ipQueryInFlight) return
        ipQueryInFlight = true
        if (force) target.text = "…"
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                LandingIpManager.queryLandingIp(DataStore.selectedProxy, force)
            }.getOrNull()
            ipQueryInFlight = false
            if (!isAdded) return@launch
            result?.onSuccess { info ->
                target.text = "${info.countryFlag} ${info.ip}"
            }?.onFailure {
                if (force && ipRetryCount < 1 && DataStore.serviceState.connected) {
                    // The auto refresh can race the tunnel coming up; retry once.
                    ipRetryCount++
                    ipRefreshJob = viewLifecycleOwner.lifecycleScope.launch {
                        delay(2000)
                        if (isAdded && DataStore.serviceState.connected) {
                            updateIpModule(force = true)
                        }
                    }
                } else if (force) {
                    target.text = getString(R.string.home_exit_ip_unknown)
                }
            }
        }
    }

    // HuBox v1.1.2: refresh the exit IP automatically once the tunnel is up,
    // instead of waiting for a manual tap on the IP card.
    private fun scheduleIpAutoRefresh() {
        ipRefreshJob?.cancel()
        ipRetryCount = 0
        ipRefreshJob = viewLifecycleOwner.lifecycleScope.launch {
            delay(1200)
            if (isAdded && DataStore.serviceState.connected) {
                updateIpModule(force = true)
            }
        }
    }

    // ---- connection session (duration + this-session traffic) ----

    private fun ensureSessionStarted() {
        val ctx = context ?: return
        if (HomeModules.sessionStart(ctx) > 0 || sessionInitRunning) return
        sessionInitRunning = true
        viewLifecycleOwner.lifecycleScope.launch {
            val totals = withContext(Dispatchers.IO) {
                runCatching {
                    val all = SagerDatabase.proxyDao.getAll()
                    all.sumOf { it.tx } to all.sumOf { it.rx }
                }.getOrDefault(0L to 0L)
            }
            sessionInitRunning = false
            if (!isAdded) return@launch
            if (HomeModules.sessionStart(ctx) <= 0 &&
                DataStore.serviceState == BaseService.State.Connected
            ) {
                HomeModules.beginSession(ctx, System.currentTimeMillis(), totals.first, totals.second)
                sessionTx = 0L
                sessionRx = 0L
            }
        }
    }

    private fun startSessionTicker() {
        if (sessionJob?.isActive == true) return
        sessionJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive && DataStore.serviceState == BaseService.State.Connected) {
                updateSessionUi()
                delay(1000)
            }
        }
    }

    private fun stopSessionTicker() {
        sessionJob?.cancel()
        sessionJob = null
    }

    private fun updateSessionUi() {
        val ctx = context ?: return
        val start = HomeModules.sessionStart(ctx)
        if (start <= 0) return
        val elapsedSeconds = ((System.currentTimeMillis() - start) / 1000).coerceAtLeast(0)
        stateHint.text = formatDuration(elapsedSeconds) +
            " · ${getString(R.string.session_label)} " +
            Formatter.formatFileSize(ctx, sessionTx + sessionRx)
    }

    // HuBox v1.1.4 power: session traffic comes from the looper's speed
    // broadcast (SpeedDisplayData totals are per-session already) instead
    // of a full proxy-table DB read every 5 seconds.
    fun updateSessionTraffic(txTotal: Long, rxTotal: Long) {
        sessionTx = txTotal
        sessionRx = rxTotal
        val ctx = context ?: return
        if (HomeModules.sessionStart(ctx) <= 0) return
        sessionValueView?.let { valueView ->
            valueView.text = "${getString(R.string.session_label)} " +
                "↑ ${Formatter.formatFileSize(ctx, sessionTx)} " +
                "↓ ${Formatter.formatFileSize(ctx, sessionRx)}"
            valueView.visibility = View.VISIBLE
        }
    }

    private fun formatDuration(totalSeconds: Long): String {
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        return if (hours > 0) {
            String.format(java.util.Locale.US, "%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format(java.util.Locale.US, "%02d:%02d", minutes, seconds)
        }
    }
}
