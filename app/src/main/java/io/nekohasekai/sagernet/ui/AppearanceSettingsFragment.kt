package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.NotificationManagerCompat
import androidx.core.graphics.drawable.DrawableCompat
import androidx.preference.EditTextPreference
import androidx.preference.MultiSelectListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.SwitchPreferenceCompat
import androidx.preference.TwoStatePreference
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.Action
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.bg.LiveUpdateFormatter
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.FixedLinearLayoutManager
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.ktx.needReload
import io.nekohasekai.sagernet.utils.AppLocale
import io.nekohasekai.sagernet.utils.Palette
import io.nekohasekai.sagernet.utils.Theme
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import moe.matsuri.nb4a.ui.SimpleMenuPreference
import moe.matsuri.nb4a.ui.TelegramGroupedPreferenceDecoration

class AppearanceSettingsFragment : PreferenceFragmentCompat() {

    private lateinit var notificationPermission: Preference

    // HuBox v1.1.3: custom home-screen background (any image format the
    // system gallery offers; the file is copied into internal storage).
    private var homeBackgroundPref: Preference? = null
    private var homeBackgroundClearPref: Preference? = null

    private val pickHomeBackground = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) saveHomeBackground(uri)
    }

    private fun refreshHomeBackgroundSummary() {
        val isSet = DataStore.homeBackground.isNotEmpty()
        homeBackgroundPref?.summary = getString(
            if (isSet) R.string.home_background_set else R.string.home_background_not_set
        )
        homeBackgroundClearPref?.isVisible = isSet
    }

    private fun saveHomeBackground(uri: Uri) {
        val ctx = requireContext()
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    // Accept anything the system can decode (jpg/png/webp/
                    // gif/bmp/heic/...); reject non-images.
                    val resolver = ctx.contentResolver
                    val bounds = android.graphics.BitmapFactory.Options().apply {
                        inJustDecodeBounds = true
                    }
                    resolver.openInputStream(uri)?.use { input ->
                        android.graphics.BitmapFactory.decodeStream(input, null, bounds)
                    } ?: return@runCatching false
                    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching false
                    // HuBox v1.1.4: do not keep the original multi-MB photo.
                    // Decode sampled (long edge lands near 1920px), scale to
                    // at most 1920px and re-encode — the stored copy drops
                    // to a few hundred KB and every later decode is bounded.
                    var sample = 1
                    while (sample <= 32 &&
                        bounds.outWidth / (sample * 2) >= 960 &&
                        bounds.outHeight / (sample * 2) >= 960
                    ) {
                        sample *= 2
                    }
                    val decoded = resolver.openInputStream(uri)?.use { input ->
                        android.graphics.BitmapFactory.decodeStream(
                            input, null,
                            android.graphics.BitmapFactory.Options().apply {
                                inSampleSize = sample
                            },
                        )
                    } ?: return@runCatching false
                    val longEdge = maxOf(decoded.width, decoded.height)
                    val scaled = if (longEdge > 1920) {
                        val ratio = 1920f / longEdge
                        android.graphics.Bitmap.createScaledBitmap(
                            decoded,
                            (decoded.width * ratio).toInt().coerceAtLeast(1),
                            (decoded.height * ratio).toInt().coerceAtLeast(1),
                            true,
                        )
                    } else {
                        decoded
                    }
                    val target = File(ctx.filesDir, "home_background")
                    val saved = target.outputStream().use { out ->
                        if (scaled.hasAlpha()) {
                            scaled.compress(
                                android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                        } else {
                            scaled.compress(
                                android.graphics.Bitmap.CompressFormat.JPEG, 85, out)
                        }
                    }
                    if (scaled !== decoded) scaled.recycle()
                    decoded.recycle()
                    saved
                }.getOrDefault(false)
            }
            if (ok) {
                DataStore.homeBackground = "home_background"
                DataStore.homeBackgroundVersion++
                Toast.makeText(ctx, R.string.home_background_saved, Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(ctx, R.string.home_background_invalid, Toast.LENGTH_SHORT).show()
            }
            refreshHomeBackgroundSummary()
        }
    }

    private fun clearHomeBackground() {
        runCatching { File(requireContext().filesDir, "home_background").delete() }
        DataStore.homeBackground = ""
        DataStore.homeBackgroundVersion++
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        listView.layoutManager = FixedLinearLayoutManager(listView)
        setDivider(null)
        setDividerHeight(0)
        listView.clipToPadding = false
        listView.setPadding(0, dp2px(8), 0, dp2px(120))
        TelegramGroupedPreferenceDecoration.applyTo(listView)
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        preferenceManager.preferenceDataStore = DataStore.configurationStore
        setPreferencesFromResource(R.xml.settings_appearance, rootKey)
        tintPreferenceIcons(preferenceScreen, Theme.getPrimaryColor(requireContext()))

        val profileTrafficStatistics =
            findPreference<SwitchPreferenceCompat>(Key.PROFILE_TRAFFIC_STATISTICS)
        val speedInterval = findPreference<SimpleMenuPreference>(Key.SPEED_INTERVAL)
        if (profileTrafficStatistics != null && speedInterval != null) {
            profileTrafficStatistics.isEnabled = speedInterval.value.toString() != "0"
            speedInterval.setOnPreferenceChangeListener { _, newValue ->
                profileTrafficStatistics.isEnabled = newValue.toString() != "0"
                needReload()
                true
            }
        }

        // HuBox v1.1.3: custom home-screen background image picker.
        homeBackgroundPref = findPreference("homeBackground")
        homeBackgroundClearPref = findPreference("homeBackgroundClear")
        homeBackgroundPref?.setOnPreferenceClickListener {
            pickHomeBackground.launch("image/*")
            true
        }
        homeBackgroundClearPref?.setOnPreferenceClickListener {
            clearHomeBackground()
            refreshHomeBackgroundSummary()
            true
        }
        refreshHomeBackgroundSummary()

        val themePalette = findPreference<Preference>("themePalette")
        fun refreshPaletteSummary() {
            themePalette?.summary = getString(
                when (DataStore.themePalette) {
                    Palette.MINT -> R.string.palette_mint
                    Palette.GRAPE -> R.string.palette_grape
                    Palette.CORAL -> R.string.palette_coral
                    Palette.CUSTOM -> R.string.palette_custom
                    else -> R.string.palette_ocean
                }
            )
        }
        refreshPaletteSummary()
        themePalette?.setOnPreferenceClickListener {
            showPalettePicker { refreshPaletteSummary() }
            true
        }

        val nightTheme = findPreference<SimpleMenuPreference>(Key.NIGHT_THEME)
        nightTheme?.setOnPreferenceChangeListener { _, newTheme ->
            val mode = (newTheme as String).toInt()
            DataStore.nightTheme = mode
            Theme.currentNightMode = mode
            Theme.applyNightTheme()
            activity?.recreate()
            true
        }

        val appLanguage = findPreference<SimpleMenuPreference>(Key.APP_LANGUAGE)
        appLanguage?.setOnPreferenceChangeListener { _, newValue ->
            AppLocale.apply(newValue as String)
            true
        }

        val liveUpdateEnabled = findPreference<TwoStatePreference>(Key.LIVE_UPDATE_ENABLED)
        val liveUpdateShowCapsule = findPreference<TwoStatePreference>(Key.LIVE_UPDATE_SHOW_CAPSULE)
        val liveUpdatePreset = findPreference<SimpleMenuPreference>(Key.LIVE_UPDATE_PRESET)
        val liveUpdateCollapsed = findPreference<EditTextPreference>(Key.LIVE_UPDATE_COLLAPSED_FORMAT)
        val liveUpdateTitle = findPreference<EditTextPreference>(Key.LIVE_UPDATE_TITLE_FORMAT)
        val liveUpdateContent = findPreference<EditTextPreference>(Key.LIVE_UPDATE_CONTENT_FORMAT)
        val liveUpdateSubtext = findPreference<EditTextPreference>(Key.LIVE_UPDATE_SUBTEXT_FORMAT)
        val liveUpdatePreview = findPreference<Preference>("liveUpdatePreview")

        fun notifyNotificationUpdate() {
            if (DataStore.serviceState.started) {
                val intent = Intent(Action.REFRESH_NOTIFICATION)
                intent.setPackage(requireContext().packageName)
                requireContext().sendBroadcast(intent)
            }
        }

        fun updateLiveUpdatePreview() {
            if (liveUpdatePreview == null) return
            val mockInfo = LiveUpdateFormatter.getMockInfo(requireContext())
            val tpls = LiveUpdateFormatter.getTemplates()
            val renderedCollapsed = LiveUpdateFormatter.format(tpls.collapsed, mockInfo)
            val renderedTitle = LiveUpdateFormatter.format(tpls.title, mockInfo)
            val renderedContent = LiveUpdateFormatter.format(tpls.content, mockInfo)
            val renderedSub = LiveUpdateFormatter.format(tpls.subText, mockInfo)
            liveUpdatePreview.summary = if (renderedSub.isNotEmpty()) {
                getString(
                    R.string.live_update_preview_summary_format,
                    renderedCollapsed,
                    renderedTitle,
                    renderedContent,
                    renderedSub
                )
            } else {
                getString(
                    R.string.live_update_preview_summary_format_no_sub,
                    renderedCollapsed,
                    renderedTitle,
                    renderedContent
                )
            }
        }

        liveUpdateEnabled?.setOnPreferenceChangeListener { _, newValue ->
            DataStore.liveUpdateEnabled = newValue as Boolean
            view?.post {
                updateLiveUpdatePreview()
                notifyNotificationUpdate()
            }
            needReload()
            true
        }

        liveUpdateShowCapsule?.setOnPreferenceChangeListener { _, newValue ->
            DataStore.liveUpdateShowCapsule = newValue as Boolean
            view?.post {
                notifyNotificationUpdate()
            }
            true
        }

        updateLiveUpdatePreview()

        liveUpdatePreset?.setOnPreferenceChangeListener { _, newValue ->
            val newPreset = newValue as String
            if (newPreset != LiveUpdateFormatter.PRESET_CUSTOM) {
                val tpls = LiveUpdateFormatter.getPresetTemplates(newPreset)
                DataStore.liveUpdateCollapsedFormat = tpls.collapsed
                DataStore.liveUpdateTitleFormat = tpls.title
                DataStore.liveUpdateContentFormat = tpls.content
                DataStore.liveUpdateSubtextFormat = tpls.subText

                liveUpdateCollapsed?.text = tpls.collapsed
                liveUpdateTitle?.text = tpls.title
                liveUpdateContent?.text = tpls.content
                liveUpdateSubtext?.text = tpls.subText
            }
            DataStore.liveUpdatePreset = newPreset
            view?.post {
                updateLiveUpdatePreview()
                notifyNotificationUpdate()
            }
            needReload()
            true
        }

        fun bindFormatField(pref: EditTextPreference?, onSave: (String) -> Unit) {
            pref?.setOnPreferenceChangeListener { _, newValue ->
                val str = (newValue as? String).orEmpty().trim().replace('｛', '{').replace('｝', '}')
                onSave(str)
                pref.text = str
                DataStore.liveUpdatePreset = LiveUpdateFormatter.PRESET_CUSTOM
                liveUpdatePreset?.value = LiveUpdateFormatter.PRESET_CUSTOM
                view?.post {
                    updateLiveUpdatePreview()
                    notifyNotificationUpdate()
                }
                needReload()
                true
            }
        }

        bindFormatField(liveUpdateCollapsed) { DataStore.liveUpdateCollapsedFormat = it }
        bindFormatField(liveUpdateTitle) { DataStore.liveUpdateTitleFormat = it }
        bindFormatField(liveUpdateContent) { DataStore.liveUpdateContentFormat = it }
        bindFormatField(liveUpdateSubtext) { DataStore.liveUpdateSubtextFormat = it }

        liveUpdatePreview?.setOnPreferenceClickListener {
            val mockInfo = LiveUpdateFormatter.getMockInfo(requireContext())
            val tpls = LiveUpdateFormatter.getTemplates()
            val renderedCollapsed = LiveUpdateFormatter.format(tpls.collapsed, mockInfo)
            val renderedTitle = LiveUpdateFormatter.format(tpls.title, mockInfo)
            val renderedContent = LiveUpdateFormatter.format(tpls.content, mockInfo)
            val renderedSub = LiveUpdateFormatter.format(tpls.subText, mockInfo).ifEmpty {
                getString(androidx.preference.R.string.not_set)
            }

            MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.live_update_preview_dialog_title)
                .setMessage(
                    getString(
                        R.string.live_update_preview_dialog_message,
                        renderedCollapsed,
                        renderedTitle,
                        renderedContent,
                        renderedSub
                    )
                )
                .setPositiveButton(android.R.string.ok, null)
                .show()
            true
        }

        findPreference<TwoStatePreference>("showDirectSpeed")?.setOnPreferenceChangeListener { _, newValue ->
            DataStore.showDirectSpeed = newValue as Boolean
            needReload()
            view?.post {
                updateLiveUpdatePreview()
                notifyNotificationUpdate()
            }
            true
        }

        findPreference<TwoStatePreference>("showGroupInNotification")?.setOnPreferenceChangeListener { _, newValue ->
            DataStore.showGroupInNotification = newValue as Boolean
            view?.post {
                updateLiveUpdatePreview()
                notifyNotificationUpdate()
            }
            true
        }

        findPreference<MultiSelectListPreference>(Key.NOTIFICATION_ACTIONS)?.apply {
            summaryProvider = Preference.SummaryProvider<MultiSelectListPreference> { p ->
                val chosen = p.values
                p.entryValues.indices.filter { p.entryValues[it].toString() in chosen }
                    .joinToString(", ") { p.entries[it] }
                    .ifEmpty { getString(androidx.preference.R.string.not_set) }
            }
            setOnPreferenceChangeListener { _, newValue ->
                val set = (newValue as Set<*>).map { it.toString() }.toMutableSet()
                if (set.size > 3) {
                    Toast.makeText(requireContext(), R.string.notification_actions_too_many, Toast.LENGTH_SHORT).show()
                    false
                } else {
                    DataStore.configurationStore.putStringSet(Key.NOTIFICATION_ACTIONS, set)
                    view?.post { notifyNotificationUpdate() }
                    true
                }
            }
        }

        notificationPermission = findPreference("notificationPermission")!!
        notificationPermission.setOnPreferenceClickListener {
            openNotificationSettings()
            true
        }
    }

    override fun onResume() {
        super.onResume()
        refreshHomeBackgroundSummary()
        if (::notificationPermission.isInitialized) {
            notificationPermission.setSummary(
                if (NotificationManagerCompat.from(requireContext()).areNotificationsEnabled()) {
                    R.string.notification_permission_allowed
                } else {
                    R.string.notification_permission_blocked
                }
            )
        }
    }

    private fun openNotificationSettings() {
        val context = requireContext()
        val app = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
        val intent = if (Build.VERSION.SDK_INT >= 26) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            app
        }
        for (candidate in listOf(intent, app)) {
            try {
                startActivity(candidate)
                return
            } catch (_: Exception) {}
        }
    }

    private fun paletteSwatch(color: Int, selected: Boolean, label: String, onClick: () -> Unit): View {
        val ctx = requireContext()
        val column = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            setPadding(dp2px(4), dp2px(4), dp2px(4), dp2px(4))
            setOnClickListener { onClick() }
        }
        val circle = View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
                if (selected) {
                    setStroke(dp2px(3), Theme.getPrimaryColor(ctx))
                }
            }
        }
        column.addView(circle, LinearLayout.LayoutParams(dp2px(44), dp2px(44)))
        val text = TextView(ctx).apply {
            text = label
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, dp2px(4), 0, 0)
        }
        column.addView(
            text,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ),
        )
        return column
    }

    private fun showPalettePicker(onChanged: () -> Unit) {
        val ctx = requireContext()
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp2px(20), dp2px(8), dp2px(20), dp2px(4))
        }
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }
        val dialog = MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.theme_palette_title)
            .setView(container)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        val entries = listOf(
            Palette.OCEAN to R.string.palette_ocean,
            Palette.MINT to R.string.palette_mint,
            Palette.GRAPE to R.string.palette_grape,
            Palette.CORAL to R.string.palette_coral,
        )
        for ((id, nameRes) in entries) {
            val scheme = Palette.SCHEMES.getValue(id)
            row.addView(
                paletteSwatch(scheme.primary, Palette.currentId() == id, getString(nameRes)) {
                    DataStore.themePalette = id
                    dialog.dismiss()
                    onChanged()
                    activity?.recreate()
                },
                LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f),
            )
        }
        container.addView(row)
        val customRow = TextView(ctx).apply {
            text = getString(R.string.palette_custom) + "  ›"
            textSize = 15f
            setTextColor(Theme.getPrimaryColor(ctx))
            setPadding(dp2px(4), dp2px(14), dp2px(4), dp2px(10))
            isClickable = true
            isFocusable = true
            setOnClickListener {
                dialog.dismiss()
                showCustomColorPicker(onChanged)
            }
        }
        container.addView(customRow)
        dialog.show()
    }

    private fun showCustomColorPicker(onChanged: () -> Unit) {
        val ctx = requireContext()
        val hsv = FloatArray(3)
        Color.colorToHSV(Palette.customSeed(), hsv)
        var hue = hsv[0]
        var sat = hsv[1]
        var brightness = hsv[2]
        val container = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp2px(20), dp2px(8), dp2px(20), dp2px(4))
        }
        val preview = View(ctx).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = dp2px(14).toFloat()
            }
        }
        container.addView(
            preview,
            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp2px(52)),
        )
        fun refreshPreview() {
            val seed = Color.HSVToColor(floatArrayOf(hue, sat, brightness))
            (preview.background as GradientDrawable).setColor(Palette.derive(seed).primary)
        }
        fun addSlider(labelRes: Int, max: Int, initial: Int, onChange: (Int) -> Unit) {
            val label = TextView(ctx).apply {
                text = getString(labelRes)
                textSize = 12f
                setPadding(0, dp2px(10), 0, 0)
            }
            container.addView(label)
            val bar = SeekBar(ctx).apply {
                this.max = max
                progress = initial
                setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                    override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                        onChange(progress)
                        refreshPreview()
                    }

                    override fun onStartTrackingTouch(seekBar: SeekBar?) {}
                    override fun onStopTrackingTouch(seekBar: SeekBar?) {}
                })
            }
            container.addView(
                bar,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ),
            )
        }
        addSlider(R.string.palette_hue, 360, hue.toInt()) { hue = it.toFloat() }
        addSlider(R.string.palette_saturation, 100, (sat * 100).toInt()) { sat = it / 100f }
        addSlider(R.string.palette_value, 100, (brightness * 100).toInt()) { brightness = it / 100f }
        refreshPreview()
        MaterialAlertDialogBuilder(ctx)
            .setTitle(R.string.palette_custom)
            .setView(container)
            .setPositiveButton(android.R.string.ok) { _, _ ->
                DataStore.customThemeColor = Color.HSVToColor(floatArrayOf(hue, sat, brightness))
                DataStore.themePalette = Palette.CUSTOM
                onChanged()
                activity?.recreate()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .show()
    }

    private fun tintPreferenceIcons(group: PreferenceGroup, color: Int) {
        for (i in 0 until group.preferenceCount) {
            val pref = group.getPreference(i)
            if (pref is PreferenceGroup) {
                tintPreferenceIcons(pref, color)
            } else {
                pref.icon?.let { icon ->
                    val tinted = icon.mutate()
                    DrawableCompat.setTint(tinted, color)
                    pref.icon = tinted
                }
            }
        }
    }

    companion object {
        const val ARG_TITLE = "title"
    }
}
