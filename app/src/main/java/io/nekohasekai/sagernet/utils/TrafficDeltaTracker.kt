package io.nekohasekai.sagernet.utils

/**
 * Turns successive snapshots of cumulative per-connection counters (as
 * reported by the Clash API connection list) into per-key byte deltas
 * for [AppTrafficLedger] (HuBox v1.2.2).
 *
 * Rules:
 *  - the first sight of a connection id only establishes the baseline —
 *    its already-accumulated bytes are NOT booked (they predate the
 *    observation window);
 *  - afterwards the delta is `now - previous`; if a counter went down
 *    (core restarted and reused the id, counters reset), the current
 *    value itself counts as the delta;
 *  - ids missing from a snapshot are evicted, so a connection that
 *    reappears later starts over as a fresh baseline instead of booking
 *    one huge bogus delta.
 *
 * Pure JVM logic, no Android dependencies, fully unit-testable.
 */
class TrafficDeltaTracker {

    data class ConnSnap(
        val id: String,
        val key: String,
        val upload: Long,
        val download: Long,
    )

    private val previous = mutableMapOf<String, ConnSnap>()

    /**
     * Feeds one snapshot; returns the deltas it produced as
     * key -> [upDelta, downDelta]. Keys with no movement are absent.
     */
    @Synchronized
    fun onSnapshot(snaps: List<ConnSnap>): Map<String, LongArray> {
        val deltas = mutableMapOf<String, LongArray>()
        val seen = HashSet<String>(snaps.size)
        for (snap in snaps) {
            seen.add(snap.id)
            val prev = previous[snap.id]
            previous[snap.id] = snap
            if (prev == null) continue
            val upDelta = if (snap.upload >= prev.upload) snap.upload - prev.upload else snap.upload
            val downDelta = if (snap.download >= prev.download) {
                snap.download - prev.download
            } else {
                snap.download
            }
            if (upDelta == 0L && downDelta == 0L) continue
            val acc = deltas.getOrPut(snap.key) { longArrayOf(0L, 0L) }
            acc[0] += upDelta
            acc[1] += downDelta
        }
        previous.keys.retainAll(seen)
        return deltas
    }

    /** Forgets every baseline (e.g. after a core restart with recycled ids). */
    @Synchronized
    fun reset() {
        previous.clear()
    }
}
