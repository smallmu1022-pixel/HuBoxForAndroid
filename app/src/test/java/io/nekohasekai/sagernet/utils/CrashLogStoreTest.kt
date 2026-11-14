package io.nekohasekai.sagernet.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class CrashLogStoreTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var dir: File
    private lateinit var store: CrashLogStore

    @Before
    fun setUp() {
        dir = File(tempFolder.root, "crash_logs")
        store = CrashLogStore(dir)
    }

    @Test
    fun writeRecordCreatesFileAndReadBackMatches() {
        val file = store.writeRecord("crash", "Java crash: java.lang.IllegalStateException: boom\nstack line")
        assertNotNull(file)
        assertTrue(file!!.isFile)
        assertTrue(file.name.startsWith("crash-"))
        assertTrue(file.name.endsWith(".log"))
        assertEquals(
            "Java crash: java.lang.IllegalStateException: boom\nstack line",
            store.readEntry(file)
        )
    }

    @Test
    fun listEntriesAreNewestFirstWithFirstLineAsTitle() {
        val first = store.writeRecord("crash", "first crash title\nbody")!!
        val second = store.writeRecord("watchdog", "watchdog title\nbody")!!
        val third = store.writeRecord("test", "test title\nbody")!!

        // Control ordering deterministically through mtimes.
        first.setLastModified(1_000_000L)
        second.setLastModified(2_000_000L)
        third.setLastModified(3_000_000L)

        val entries = store.listEntries()
        assertEquals(3, entries.size)
        assertEquals("test title", entries[0].title)
        assertEquals("watchdog title", entries[1].title)
        assertEquals("first crash title", entries[2].title)
        assertEquals(3_000_000L, entries[0].timeMillis)
    }

    @Test
    fun writeRecordEvictsOldestBeyondRetention() {
        val files = (1..25).map { i ->
            val f = store.writeRecord("crash", "record $i")!!
            // Spread mtimes so "oldest" is unambiguous: record 1 oldest.
            f.setLastModified(1_000_000L + i * 1_000L)
            f
        }
        val remaining = dir.listFiles { f -> f.name.endsWith(".log") }!!.toList()
        assertEquals(CrashLogStore.MAX_FILES, remaining.size)
        // The 5 oldest records were evicted, the newest ones survive.
        assertFalse(files[0].exists())
        assertFalse(files[4].exists())
        assertTrue(files[5].exists())
        assertTrue(files[24].exists())
    }

    @Test
    fun pruneKeepsExactlyMaxFiles() {
        val seeded = (1..30).map { i ->
            File(dir.apply { mkdirs() }, "crash-seed-$i.log").apply {
                writeText("seed $i")
                setLastModified(2_000_000L + i * 1_000L)
            }
        }
        store.prune()
        val remaining = dir.listFiles { f -> f.name.endsWith(".log") }!!.toList()
        assertEquals(CrashLogStore.MAX_FILES, remaining.size)
        assertFalse(seeded[0].exists())
        assertTrue(seeded[29].exists())
    }

    @Test
    fun rotateCoreLogArchivesOversizedFile() {
        dir.mkdirs()
        val core = File(dir, CrashLogStore.CORE_LOG_NAME)
        core.writeBytes(ByteArray((CrashLogStore.CORE_LOG_ROTATE_BYTES + 1024).toInt()) { 65 })

        store.rotateCoreLogIfNeeded()

        assertFalse("live core.log must be renamed away", core.exists())
        val archived = dir.listFiles { f -> f.name.startsWith("core-") && f.name.endsWith(".log") }!!
        assertEquals(1, archived.size)
        assertEquals(CrashLogStore.CORE_LOG_ROTATE_BYTES + 1024, archived[0].length())
        // The archived core log shows up as a normal entry.
        assertTrue(store.listEntries().any { it.file == archived[0] })
    }

    @Test
    fun rotateCoreLogLeavesSmallFileAlone() {
        dir.mkdirs()
        val core = File(dir, CrashLogStore.CORE_LOG_NAME)
        core.writeText("small log")

        store.rotateCoreLogIfNeeded()

        assertTrue(core.exists())
        assertEquals("small log", core.readText())
    }

    @Test
    fun emptyStoreListsNothing() {
        assertTrue(store.listEntries().isEmpty())
        assertEquals("", store.readEntry(File(dir, "missing.log")))
    }
}
