package io.nekohasekai.sagernet.utils

import android.content.Context
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.View
import androidx.core.graphics.ColorUtils
import io.nekohasekai.sagernet.database.DataStore

/**
 * HuBox v1.1.0 glass theme palettes.
 *
 * Four presets with hand-picked values (light + dark), plus a custom mode
 * where the user picks only a primary color and every other role is derived
 * with readability guarantees:
 *  - light background: same hue, saturation <= 12%, value 96% -> 88%
 *  - dark background: same hue, value 8% -> 14%
 *  - dark primary: primary lightened to value >= 68%
 *  - light mode interactive primary: if the seed value > 55%, it is darkened
 *    to 45% value so white text on buttons keeps enough contrast.
 *
 * Semantic colors (success / warning / latency) never follow the palette.
 * The launcher icon stays blue-white and does not follow the palette either.
 */
object Palette {

    const val OCEAN = "ocean"
    const val MINT = "mint"
    const val GRAPE = "grape"
    const val CORAL = "coral"
    const val CUSTOM = "custom"

    val PRESET_IDS = listOf(OCEAN, MINT, GRAPE, CORAL)

    data class Scheme(
        val primary: Int,
        val primaryDark: Int,
        val accent: Int,
        val bgStart: Int,
        val bgEnd: Int,
        val darkPrimary: Int,
        val darkBgStart: Int,
        val darkBgEnd: Int,
    )

    private fun hex(value: String) = Color.parseColor(value)

    val SCHEMES: Map<String, Scheme> = mapOf(
        OCEAN to Scheme(
            hex("#1668E3"), hex("#0F4DB0"), hex("#2E7CF6"),
            hex("#F0F7FF"), hex("#CEE3FA"),
            hex("#6EA8FF"), hex("#0A1A36"), hex("#10305E"),
        ),
        MINT to Scheme(
            hex("#0D9488"), hex("#0B7C72"), hex("#14B8A6"),
            hex("#EEFDFA"), hex("#CCFBF1"),
            hex("#5EEAD4"), hex("#06231F"), hex("#0A3532"),
        ),
        GRAPE to Scheme(
            hex("#7C5CD6"), hex("#6545B8"), hex("#9F7EFA"),
            hex("#F6F3FF"), hex("#E2DAFC"),
            hex("#C4B5FD"), hex("#1B1035"), hex("#2A1B4E"),
        ),
        CORAL to Scheme(
            hex("#E86A4A"), hex("#C9502F"), hex("#FF8A66"),
            hex("#FFF7F0"), hex("#FEE6D6"),
            hex("#FCA97F"), hex("#2B1409"), hex("#46220F"),
        ),
    )

    fun currentId(): String = DataStore.themePalette.takeIf { it in SCHEMES || it == CUSTOM } ?: OCEAN

    fun isCustom(): Boolean = currentId() == CUSTOM

    /** The stored custom color is a raw RGB int (alpha may be zero). */
    fun customSeed(): Int {
        val raw = DataStore.customThemeColor
        return Color.rgb(Color.red(raw), Color.green(raw), Color.blue(raw))
    }

    fun scheme(): Scheme {
        return if (isCustom()) derive(customSeed()) else SCHEMES[currentId()] ?: SCHEMES.getValue(OCEAN)
    }

    fun derive(seed: Int): Scheme {
        val hsv = FloatArray(3)
        Color.colorToHSV(seed, hsv)
        val hue = hsv[0]
        val sat = hsv[1]
        val value = hsv[2]

        fun hsvColor(h: Float, s: Float, v: Float): Int =
            Color.HSVToColor(floatArrayOf(h, s.coerceIn(0f, 1f), v.coerceIn(0f, 1f)))

        val lightSat = minOf(sat, 0.12f)
        val darkBgSat = sat.coerceIn(0.25f, 0.85f)
        val interactive = if (value > 0.55f) hsvColor(hue, sat, 0.45f) else seed
        return Scheme(
            primary = interactive,
            primaryDark = ColorUtils.blendARGB(interactive, Color.BLACK, 0.22f),
            accent = ColorUtils.blendARGB(interactive, Color.WHITE, 0.28f),
            bgStart = hsvColor(hue, lightSat, 0.96f),
            bgEnd = hsvColor(hue, lightSat, 0.88f),
            darkPrimary = hsvColor(hue, minOf(sat, 0.75f), maxOf(value, 0.68f)),
            darkBgStart = hsvColor(hue, darkBgSat, 0.08f),
            darkBgEnd = hsvColor(hue, darkBgSat, 0.14f),
        )
    }

    /** Interactive primary for the current mode (buttons, selected states, halos). */
    fun primary(context: Context): Int {
        val scheme = scheme()
        return if (Theme.usingNightMode(context)) scheme.darkPrimary else scheme.primary
    }

    fun backgroundColors(context: Context): IntArray {
        val scheme = scheme()
        return if (Theme.usingNightMode(context)) {
            intArrayOf(scheme.darkBgStart, scheme.darkBgEnd)
        } else {
            intArrayOf(scheme.bgStart, scheme.bgEnd)
        }
    }

    /** Connected-state gradient for the home connect button. */
    fun connectGradient(context: Context): IntArray {
        val scheme = scheme()
        return if (Theme.usingNightMode(context)) {
            intArrayOf(
                ColorUtils.blendARGB(scheme.darkPrimary, Color.WHITE, 0.12f),
                scheme.darkPrimary,
            )
        } else {
            intArrayOf(scheme.accent, scheme.primary)
        }
    }

    fun applyWindowBackground(view: View) {
        // HuBox v1.1.7: when a custom background photo is set, page roots
        // must stay transparent — this opaque gradient otherwise covers
        // the photo on the activity window everywhere except the strip
        // outside the fragment holder (the v1.1.4–v1.1.6 "photo only
        // behind the bottom nav" bug).
        if (DataStore.homeBackground.isNotEmpty()) {
            view.background = null
            return
        }
        view.background = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            backgroundColors(view.context),
        )
    }
}
