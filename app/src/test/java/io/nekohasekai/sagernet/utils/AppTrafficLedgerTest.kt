package io.nekohasekai.sagernet.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class AppTrafficLedgerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var dir: File
    private lateinit var store: AppTrafficLedger

    @Before
    fun setUp() {
        dir = File(tempFolder.root, AppTrafficLedger.DIR_NAME)
        store = AppTrafficLedger(dir)
    }

    private fun assertTotals(
        snapshot: Map<String, LongArray>,
        key: String,
        upload: Long,
        download: Long,
    ) {
        val acc = snapshot[key]
        assertTrue("expected an entry for $key", acc != null)
        assertEquals(upload, acc!![0])
        assertEquals(download, acc[1])
    }

    @Test
    fun addAccumulatesPerKeyAndDate() {
        store.add("2026-11-14", "com.example.app", 100L, 200L)
        store.add("2026-11-14", "com.example.app", 50L, 25L)
        store.add("2026-11-14", "host:example.com", 10L, 20L)
        store.add("2026-11-13", "com.example.app", 7L, 8L)

        val today = store.snapshot("2026-11-14")
        assertEquals(2, today.size)
        assertTotals(today, "com.example.app", 150L, 225L)
        assertTotals(today, "host:example.com", 10L, 20L)

        val yesterday = store.snapshot("2026-11-13")
        assertEquals(1, yesterday.size)
        assertTotals(yesterday, "com.example.app", 7L, 8L)

        assertTrue(store.snapshot("2026-11-12").isEmpty())
    }

    @Test
    fun zeroDeltasAreNotRecorded() {
        store.add("2026-11-14", "com.example.app", 0L, 0L)
        assertTrue(store.snapshot("2026-11-14").isEmpty())
    }

    @Test
    fun snapshotReturnsCopies() {
        store.add("2026-11-14", "com.example.app", 100L, 200L)
        val first = store.snapshot("2026-11-14")
        first.getValue("com.example.app")[0] = -1L
        assertTotals(store.snapshot("2026-11-14"), "com.example.app", 100L, 200L)
    }

    @Test
    fun flushAndReloadRoundTrips() {
        store.add("2026-11-14", "com.example.app", 100L, 200L)
        store.add("2026-11-14", "host:example.com", 10L, 20L)
        store.add("2026-11-13", "com.example.other", 1L, 2L)
        store.flush()

        assertTrue(File(dir, AppTrafficLedger.FILE_NAME).isFile)

        val reloaded = AppTrafficLedger(dir)
        val today = reloaded.snapshot("2026-11-14")
        assertEquals(2, today.size)
        assertTotals(today, "com.example.app", 100L, 200L)
        assertTotals(today, "host:example.com", 10L, 20L)
        assertTotals(reloaded.snapshot("2026-11-13"), "com.example.other", 1L, 2L)
    }

    @Test
    fun flushWithoutChangesWritesNothing() {
        store.flush()
        assertTrue(!File(dir, AppTrafficLedger.FILE_NAME).exists())
    }

    @Test
    fun pruneDropsOlderThanThirtyDaysAndKeepsBoundary() {
        // 2026-11-14 minus 30 days is 2026-10-15 (the boundary, kept).
        store.add("2026-11-14", "com.example.app", 1L, 1L)
        store.add("2026-10-15", "com.example.app", 2L, 2L)
        store.add("2026-10-14", "com.example.app", 4L, 4L)
        store.add("2020-01-01", "com.example.app", 8L, 8L)

        store.prune("2026-11-14")

        assertTotals(store.snapshot("2026-11-14"), "com.example.app", 1L, 1L)
        assertTotals(store.snapshot("2026-10-15"), "com.example.app", 2L, 2L)
        assertTrue(store.snapshot("2026-10-14").isEmpty())
        assertTrue(store.snapshot("2020-01-01").isEmpty())
    }

    @Test
    fun malformedLinesAreIgnoredOnLoad() {
        dir.mkdirs()
        File(dir, AppTrafficLedger.FILE_NAME).writeText(
            buildString {
                append("2026-11-14\tcom.example.app\t100\t200\n")
                append("not-a-valid-line\n")
                append("2026-11-14\tcom.example.app\tNaN\t200\n")
                append("2026-11-14\t\t100\t200\n")
                append("2026-13-40\tcom.example.app\t100\t200\n")
                append("2026-11-14\tcom.example.app\t1\t2\textra\n")
                append("\n")
                append("2026-11-14\tcom.example.app\t5\t6\n")
            }
        )

        val loaded = AppTrafficLedger(dir)
        val today = loaded.snapshot("2026-11-14")
        // Only the two well-formed lines for the key, accumulated.
        assertEquals(1, today.size)
        assertTotals(today, "com.example.app", 105L, 206L)
    }
}
