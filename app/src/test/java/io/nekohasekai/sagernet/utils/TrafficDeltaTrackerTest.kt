package io.nekohasekai.sagernet.utils

import io.nekohasekai.sagernet.utils.TrafficDeltaTracker.ConnSnap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class TrafficDeltaTrackerTest {

    private lateinit var tracker: TrafficDeltaTracker

    @Before
    fun setUp() {
        tracker = TrafficDeltaTracker()
    }

    private fun snap(id: String, key: String, upload: Long, download: Long) =
        ConnSnap(id, key, upload, download)

    private fun assertDelta(
        deltas: Map<String, LongArray>,
        key: String,
        upload: Long,
        download: Long,
    ) {
        val acc = deltas[key]
        assertTrue("expected a delta for $key", acc != null)
        assertEquals(upload, acc!![0])
        assertEquals(download, acc[1])
    }

    @Test
    fun firstSightIsBaselineOnly() {
        val deltas = tracker.onSnapshot(listOf(snap("c1", "com.example.app", 500L, 900L)))
        assertTrue(deltas.isEmpty())
    }

    @Test
    fun normalDeltaIsBooked() {
        tracker.onSnapshot(listOf(snap("c1", "com.example.app", 500L, 900L)))
        val deltas = tracker.onSnapshot(listOf(snap("c1", "com.example.app", 650L, 1000L)))
        assertEquals(1, deltas.size)
        assertDelta(deltas, "com.example.app", 150L, 100L)
    }

    @Test
    fun unchangedCountersProduceNoDelta() {
        tracker.onSnapshot(listOf(snap("c1", "com.example.app", 500L, 900L)))
        val deltas = tracker.onSnapshot(listOf(snap("c1", "com.example.app", 500L, 900L)))
        assertTrue(deltas.isEmpty())
    }

    @Test
    fun counterResetCountsCurrentValue() {
        tracker.onSnapshot(listOf(snap("c1", "com.example.app", 500L, 900L)))
        // Core restarted and reused the id: counters restarted from zero.
        val deltas = tracker.onSnapshot(listOf(snap("c1", "com.example.app", 30L, 40L)))
        assertDelta(deltas, "com.example.app", 30L, 40L)
    }

    @Test
    fun vanishedConnectionReappearsAsFreshBaseline() {
        tracker.onSnapshot(listOf(snap("c1", "com.example.app", 500L, 900L)))
        tracker.onSnapshot(emptyList())
        val deltas = tracker.onSnapshot(listOf(snap("c1", "com.example.app", 700L, 1100L)))
        assertTrue(deltas.isEmpty())
    }

    @Test
    fun deltasAggregatePerKeyAcrossConnections() {
        tracker.onSnapshot(
            listOf(
                snap("c1", "com.example.app", 100L, 100L),
                snap("c2", "com.example.app", 200L, 200L),
                snap("c3", "com.example.other", 300L, 300L),
            )
        )
        val deltas = tracker.onSnapshot(
            listOf(
                snap("c1", "com.example.app", 130L, 160L),
                snap("c2", "com.example.app", 210L, 240L),
                snap("c3", "com.example.other", 390L, 300L),
            )
        )
        assertEquals(2, deltas.size)
        assertDelta(deltas, "com.example.app", 40L, 100L)
        assertDelta(deltas, "com.example.other", 90L, 0L)
    }

    @Test
    fun resetForgetsAllBaselines() {
        tracker.onSnapshot(listOf(snap("c1", "com.example.app", 500L, 900L)))
        tracker.reset()
        val deltas = tracker.onSnapshot(listOf(snap("c1", "com.example.app", 800L, 1200L)))
        assertTrue(deltas.isEmpty())
    }
}
