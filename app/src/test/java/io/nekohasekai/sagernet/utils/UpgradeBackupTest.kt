package io.nekohasekai.sagernet.utils

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class UpgradeBackupTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var filesDir: File
    private lateinit var databaseFile: File

    @Before
    fun setUp() {
        filesDir = tempFolder.root
        databaseFile = File(filesDir, "sager_net.db")
    }

    private fun marker(): File = File(filesDir, "last_seen_version_code")
    private fun backupDir(): File = File(filesDir, "db_backups")
    private fun backups(): List<String> =
        backupDir().listFiles { f -> f.name.startsWith("pre-upgrade-") && f.name.endsWith(".db") }
            ?.map { it.name }?.sorted() ?: emptyList()

    private fun run(current: Int, now: Long = 1_700_000_000_000L) {
        UpgradeBackup.runInternal(
            filesDir = filesDir,
            databaseFile = databaseFile,
            currentVersionCode = current,
            nowMillis = now,
            tmpSuffix = "test",
        )
    }

    @Test
    fun freshInstallOnlyRecordsVersion() {
        run(current = 105)
        assertEquals("105", marker().readText())
        assertTrue(backups().isEmpty())
    }

    @Test
    fun sameVersionDoesNothing() {
        databaseFile.writeText("db")
        marker().writeText("105")
        run(current = 105)
        assertEquals("105", marker().readText())
        assertTrue(backups().isEmpty())
    }

    @Test
    fun upgradeCopiesDatabaseAndSiblingsAndAdvancesMarker() {
        databaseFile.writeText("db-content")
        File(filesDir, "sager_net.db-wal").writeText("wal-content")
        File(filesDir, "sager_net.db-shm").writeText("shm-content")
        marker().writeText("100")

        run(current = 105)

        val backup = File(backupDir(), "pre-upgrade-100-to-105.db")
        assertTrue(backup.isFile)
        assertEquals("db-content", backup.readText())
        assertEquals("wal-content", File(backupDir(), "pre-upgrade-100-to-105.db-wal").readText())
        assertEquals("shm-content", File(backupDir(), "pre-upgrade-100-to-105.db-shm").readText())
        assertEquals("105", marker().readText())
        // The claim file must be cleaned up.
        assertFalse(
            backupDir().listFiles { f -> f.name.startsWith(".claim") }!!.isNotEmpty()
        )
    }

    @Test
    fun firstRunWithExistingDatabaseBacksUpFromUnknownVersion() {
        databaseFile.writeText("db-content")
        // No marker: this feature itself is new, the install is not.
        run(current = 105)
        assertTrue(File(backupDir(), "pre-upgrade-0-to-105.db").isFile)
        assertEquals("105", marker().readText())
    }

    @Test
    fun downgradeRebaselinesWithoutBackup() {
        databaseFile.writeText("db-content")
        marker().writeText("200")
        run(current = 105)
        assertEquals("105", marker().readText())
        assertTrue(backups().isEmpty())
    }

    @Test
    fun retentionKeepsOnlyTwoNewestBackups() {
        backupDir().mkdirs()
        File(backupDir(), "pre-upgrade-90-to-95.db").writeText("old1")
        File(backupDir(), "pre-upgrade-90-to-95.db-wal").writeText("old1-wal")
        File(backupDir(), "pre-upgrade-95-to-100.db").writeText("old2")
        databaseFile.writeText("db-content")
        marker().writeText("104")

        run(current = 105)

        assertEquals(
            listOf("pre-upgrade-104-to-105.db", "pre-upgrade-95-to-100.db"),
            backups()
        )
        assertFalse(File(backupDir(), "pre-upgrade-90-to-95.db-wal").exists())
    }

    @Test
    fun freshClaimFromOtherProcessSkipsCopy() {
        databaseFile.writeText("db-content")
        marker().writeText("100")
        backupDir().mkdirs()
        val claim = File(backupDir(), ".claim-100-105")
        claim.writeText("")
        val now = 1_700_000_000_000L
        claim.setLastModified(now)

        run(current = 105, now = now)

        assertTrue(backups().isEmpty())
        assertEquals("100", marker().readText())
    }
}
