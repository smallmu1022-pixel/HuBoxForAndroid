package io.nekohasekai.sagernet.group

import android.content.Context
import io.nekohasekai.sagernet.SagerNet

/**
 * HuBox v1.1.0: remembers the most recent subscription update failure per
 * group, so the configuration page can show it in red with a retry action
 * (automatic updates used to fail silently). Cleared on the next success.
 */
object SubscriptionStatusStore {

    private const val PREFS = "subscription_status"
    private const val KEY_ERROR_PREFIX = "last_error_"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun recordFailure(groupId: Long, message: String) {
        if (groupId <= 0) return
        prefs(SagerNet.application).edit()
            .putString(KEY_ERROR_PREFIX + groupId, message)
            .apply()
    }

    fun recordSuccess(groupId: Long) {
        if (groupId <= 0) return
        prefs(SagerNet.application).edit()
            .remove(KEY_ERROR_PREFIX + groupId)
            .apply()
    }

    fun lastError(context: Context, groupId: Long): String? =
        prefs(context).getString(KEY_ERROR_PREFIX + groupId, null)?.takeIf { it.isNotBlank() }
}
