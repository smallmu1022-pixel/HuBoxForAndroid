package io.nekohasekai.sagernet.bg

/**
 * Pure decision logic for the core self-healing watchdog (HuBox v1.2.0).
 *
 * The watchdog is fed one health-probe result per traffic-stats tick from
 * TrafficLooper (the probe is the stats read that already happens there —
 * no extra timers, no network traffic). It answers exactly one question:
 * should the service be restarted *now*?
 *
 * A restart is decided only when ALL of these hold:
 *  - the core is supposed to be running and the stop was not user-initiated,
 *  - the probe failed [failureThreshold] times in a row,
 *  - at least [cooldownMillis] passed since the previous watchdog restart,
 *  - fewer than [maxRestartsPerSession] watchdog restarts happened this session.
 *
 * A successful probe resets the consecutive-failure counter. The class has
 * no Android dependencies so the decision table is fully unit-testable.
 */
class CoreWatchdog(
    val failureThreshold: Int = DEFAULT_FAILURE_THRESHOLD,
    val cooldownMillis: Long = DEFAULT_COOLDOWN_MILLIS,
    val maxRestartsPerSession: Int = DEFAULT_MAX_RESTARTS_PER_SESSION,
) {

    var consecutiveFailures: Int = 0
        private set

    var lastRestartAtMillis: Long = 0L
        private set

    var restartsThisSession: Int = 0
        private set

    /**
     * Feeds one probe tick.
     *
     * @param coreRunning whether the core is supposed to be up right now.
     * @param probeOk whether the health probe succeeded on this tick.
     * @param nowMillis current wall-clock time in milliseconds.
     * @param userInitiatedStop whether the user is stopping the service;
     *        a user stop must never be "healed" back into a restart.
     * @return true exactly once, on the tick that decides to restart.
     */
    @Synchronized
    fun onTick(
        coreRunning: Boolean,
        probeOk: Boolean,
        nowMillis: Long,
        userInitiatedStop: Boolean,
    ): Boolean {
        if (userInitiatedStop || !coreRunning) {
            // Not a hang: the core is down on purpose (or not at all).
            consecutiveFailures = 0
            return false
        }
        if (probeOk) {
            consecutiveFailures = 0
            return false
        }

        consecutiveFailures++
        if (consecutiveFailures < failureThreshold) return false
        if (restartsThisSession >= maxRestartsPerSession) return false
        if (lastRestartAtMillis > 0L && nowMillis - lastRestartAtMillis < cooldownMillis) {
            return false
        }

        restartsThisSession++
        lastRestartAtMillis = nowMillis
        consecutiveFailures = 0
        return true
    }

    /** Clears all counters; call when a genuinely new session begins. */
    @Synchronized
    fun resetSession() {
        consecutiveFailures = 0
        lastRestartAtMillis = 0L
        restartsThisSession = 0
    }

    companion object {
        const val DEFAULT_FAILURE_THRESHOLD = 3
        const val DEFAULT_COOLDOWN_MILLIS = 10L * 60L * 1000L
        const val DEFAULT_MAX_RESTARTS_PER_SESSION = 3

        /**
         * Process-wide instance. It deliberately lives outside TrafficLooper:
         * a watchdog-triggered restart creates a fresh looper, and the
         * per-session restart cap must still count the restarts that the
         * watchdog itself caused. Counters reset when the app process dies,
         * or on a user-initiated service start (see TrafficLooper.start).
         */
        val shared: CoreWatchdog = CoreWatchdog()

        /**
         * Set immediately before a watchdog-triggered restart so the next
         * TrafficLooper.start() keeps [shared]'s counters instead of
         * resetting them; consumed (cleared) by that start.
         */
        @Volatile
        var sharedRestartByWatchdog: Boolean = false
    }
}
