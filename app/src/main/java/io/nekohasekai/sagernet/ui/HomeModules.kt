package io.nekohasekai.sagernet.ui

import android.content.Context
import androidx.annotation.StringRes
import io.nekohasekai.sagernet.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * HuBox v1.1.0 home-screen customization state.
 *
 * Fixed modules (connect button, current node card, proxy mode segmented
 * control) are always shown and are not handled here. The optional modules
 * below can be toggled and reordered from the "Customize home" page.
 */
object HomeModules {

    const val BATTERY = "battery"
    const val TRAFFIC = "traffic"
    const val ROUTE = "route"
    const val TODAY = "today"
    const val IP = "ip"
    const val TOOLS = "tools"

    data class Module(
        val id: String,
        @StringRes val titleRes: Int,
        val defaultEnabled: Boolean,
    )

    val ALL: List<Module> = listOf(
        Module(BATTERY, R.string.home_module_battery, true),
        Module(TRAFFIC, R.string.home_module_traffic, true),
        Module(ROUTE, R.string.home_module_route, true),
        Module(TODAY, R.string.home_module_today, false),
        Module(IP, R.string.home_module_ip, false),
        Module(TOOLS, R.string.home_module_tools, false),
    )

    private const val PREFS = "hubox_home"
    private const val KEY_ORDER = "module_order"
    private const val KEY_ON = "module_on"
    private const val KEY_OFF = "module_off"
    private const val KEY_LAST_TEST_PREFIX = "last_test_"
    private const val KEY_TRAFFIC_DAY = "traffic_day"
    private const val KEY_TRAFFIC_BASE = "traffic_base"
    private const val KEY_SESSION_START = "session_start"
    private const val KEY_SESSION_BASE_TX = "session_base_tx"
    private const val KEY_SESSION_BASE_RX = "session_base_rx"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun orderedIds(context: Context): MutableList<String> {
        val saved = prefs(context).getString(KEY_ORDER, null)
        val ids = saved?.split(',')
            ?.map { it.trim() }
            ?.filter { id -> ALL.any { it.id == id } }
            ?.toMutableList()
            ?: mutableListOf()
        for (module in ALL) {
            if (module.id !in ids) ids.add(module.id)
        }
        return ids
    }

    fun orderedModules(context: Context): List<Module> =
        orderedIds(context).map { id -> ALL.first { it.id == id } }

    fun isEnabled(context: Context, id: String): Boolean {
        val p = prefs(context)
        val on = p.getStringSet(KEY_ON, emptySet()) ?: emptySet()
        val off = p.getStringSet(KEY_OFF, emptySet()) ?: emptySet()
        return when {
            id in off -> false
            id in on -> true
            else -> ALL.firstOrNull { it.id == id }?.defaultEnabled ?: false
        }
    }

    fun setEnabled(context: Context, id: String, enabled: Boolean) {
        val p = prefs(context)
        val on = (p.getStringSet(KEY_ON, emptySet()) ?: emptySet()).toMutableSet()
        val off = (p.getStringSet(KEY_OFF, emptySet()) ?: emptySet()).toMutableSet()
        if (enabled) {
            on.add(id); off.remove(id)
        } else {
            off.add(id); on.remove(id)
        }
        p.edit().putStringSet(KEY_ON, on).putStringSet(KEY_OFF, off).apply()
    }

    fun move(context: Context, id: String, direction: Int) {
        val ids = orderedIds(context)
        val index = ids.indexOf(id)
        if (index < 0) return
        val target = index + direction
        if (target < 0 || target >= ids.size) return
        ids.removeAt(index)
        ids.add(target, id)
        prefs(context).edit().putString(KEY_ORDER, ids.joinToString(",")).apply()
    }

    fun lastTestTime(context: Context, groupId: Long): Long =
        prefs(context).getLong(KEY_LAST_TEST_PREFIX + groupId, 0L)

    fun setLastTestTime(context: Context, groupId: Long, timeMs: Long) {
        prefs(context).edit().putLong(KEY_LAST_TEST_PREFIX + groupId, timeMs).apply()
    }

    /** Relative "last tested" label used by the home node card and the node sheet. */
    fun freshnessText(context: Context, lastTestMs: Long): String {
        if (lastTestMs <= 0L) return context.getString(R.string.home_never_tested)
        val diff = System.currentTimeMillis() - lastTestMs
        val relative = when {
            diff < 60_000L -> context.getString(R.string.home_time_just_now)
            diff < 3_600_000L -> context.getString(R.string.home_time_minutes, (diff / 60_000L).toInt())
            diff < 86_400_000L -> context.getString(R.string.home_time_hours, (diff / 3_600_000L).toInt())
            else -> context.getString(R.string.home_time_days, (diff / 86_400_000L).toInt())
        }
        return context.getString(R.string.home_last_tested, relative)
    }

    /**
     * Approximate today's traffic: the per-profile tx/rx counters are
     * cumulative, so we snapshot their sum at the first read of a day and
     * report the difference afterwards.
     */
    fun todayTraffic(context: Context, currentTotalBytes: Long): Long {
        val p = prefs(context)
        val today = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())
        val storedDay = p.getString(KEY_TRAFFIC_DAY, null)
        if (storedDay != today) {
            p.edit()
                .putString(KEY_TRAFFIC_DAY, today)
                .putLong(KEY_TRAFFIC_BASE, currentTotalBytes)
                .apply()
            return 0L
        }
        val base = p.getLong(KEY_TRAFFIC_BASE, currentTotalBytes)
        return (currentTotalBytes - base).coerceAtLeast(0L)
    }

    // ---- current connection session (start time + traffic baseline) ----

    fun sessionStart(context: Context): Long =
        prefs(context).getLong(KEY_SESSION_START, 0L)

    fun beginSession(context: Context, startMs: Long, baseTx: Long, baseRx: Long) {
        prefs(context).edit()
            .putLong(KEY_SESSION_START, startMs)
            .putLong(KEY_SESSION_BASE_TX, baseTx)
            .putLong(KEY_SESSION_BASE_RX, baseRx)
            .apply()
    }

    fun sessionBase(context: Context): Pair<Long, Long> {
        val p = prefs(context)
        return p.getLong(KEY_SESSION_BASE_TX, 0L) to p.getLong(KEY_SESSION_BASE_RX, 0L)
    }

    fun endSession(context: Context) {
        prefs(context).edit()
            .remove(KEY_SESSION_START)
            .remove(KEY_SESSION_BASE_TX)
            .remove(KEY_SESSION_BASE_RX)
            .apply()
    }
}
