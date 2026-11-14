package io.nekohasekai.sagernet.bg

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreWatchdogTest {

    private val t0 = 1_700_000_000_000L
    private val cooldown = CoreWatchdog.DEFAULT_COOLDOWN_MILLIS

    private fun fail(w: CoreWatchdog, now: Long) =
        w.onTick(coreRunning = true, probeOk = false, nowMillis = now, userInitiatedStop = false)

    private fun ok(w: CoreWatchdog, now: Long) =
        w.onTick(coreRunning = true, probeOk = true, nowMillis = now, userInitiatedStop = false)

    @Test
    fun healthyTicksNeverFire() {
        val w = CoreWatchdog()
        repeat(100) { i ->
            assertFalse(ok(w, t0 + i * 1000L))
        }
        assertEquals(0, w.restartsThisSession)
        assertEquals(0, w.consecutiveFailures)
    }

    @Test
    fun twoFailuresDoNotFireThirdDoes() {
        val w = CoreWatchdog()
        assertFalse(fail(w, t0))
        assertFalse(fail(w, t0 + 1000L))
        assertTrue("3rd consecutive failure must fire", fail(w, t0 + 2000L))
        assertEquals(1, w.restartsThisSession)
        assertEquals(0, w.consecutiveFailures)
    }

    @Test
    fun recoveryResetsFailureCounter() {
        val w = CoreWatchdog()
        assertFalse(fail(w, t0))
        assertFalse(fail(w, t0 + 1000L))
        assertFalse(ok(w, t0 + 2000L))
        assertEquals(0, w.consecutiveFailures)
        assertFalse(fail(w, t0 + 3000L))
        assertFalse(fail(w, t0 + 4000L))
        assertTrue(fail(w, t0 + 5000L))
    }

    @Test
    fun cooldownBlocksSecondRestart() {
        val w = CoreWatchdog()
        fail(w, t0); fail(w, t0 + 1); assertTrue(fail(w, t0 + 2))

        // One minute later the core hangs again: still inside the cooldown.
        val t1 = t0 + 60_000L
        assertFalse(fail(w, t1))
        assertFalse(fail(w, t1 + 1000L))
        assertFalse("cooldown must block the restart", fail(w, t1 + 2000L))
        assertEquals(1, w.restartsThisSession)

        // After the cooldown expired, the next failed probe fires at once
        // (failures kept accumulating while blocked).
        assertTrue(fail(w, t0 + cooldown + 3000L))
        assertEquals(2, w.restartsThisSession)
    }

    @Test
    fun sessionCapBlocksFourthRestart() {
        val w = CoreWatchdog()
        var now = t0
        repeat(3) { round ->
            assertFalse(fail(w, now))
            assertFalse(fail(w, now + 1))
            assertTrue("restart #${round + 1} must fire", fail(w, now + 2))
            now += cooldown + 10_000L
        }
        assertEquals(3, w.restartsThisSession)

        // Even long after the cooldown, the session cap blocks a 4th restart.
        assertFalse(fail(w, now))
        assertFalse(fail(w, now + 1))
        assertFalse("session cap must block the 4th restart", fail(w, now + 2))
        assertEquals(3, w.restartsThisSession)
    }

    @Test
    fun userInitiatedStopNeverFires() {
        val w = CoreWatchdog()
        repeat(10) { i ->
            assertFalse(
                w.onTick(
                    coreRunning = true,
                    probeOk = false,
                    nowMillis = t0 + i * 1000L,
                    userInitiatedStop = true,
                )
            )
        }
        assertEquals(0, w.restartsThisSession)
        assertEquals(0, w.consecutiveFailures)
    }

    @Test
    fun coreNotRunningNeverFires() {
        val w = CoreWatchdog()
        repeat(10) { i ->
            assertFalse(
                w.onTick(
                    coreRunning = false,
                    probeOk = false,
                    nowMillis = t0 + i * 1000L,
                    userInitiatedStop = false,
                )
            )
        }
        assertEquals(0, w.restartsThisSession)
    }

    @Test
    fun resetSessionAllowsRestartingAgain() {
        val w = CoreWatchdog()
        fail(w, t0); fail(w, t0 + 1); assertTrue(fail(w, t0 + 2))
        w.resetSession()
        assertEquals(0, w.restartsThisSession)
        assertEquals(0, w.consecutiveFailures)
        assertEquals(0L, w.lastRestartAtMillis)
        // No cooldown carried over after an explicit session reset.
        assertFalse(fail(w, t0 + 3))
        assertFalse(fail(w, t0 + 4))
        assertTrue(fail(w, t0 + 5))
    }
}
