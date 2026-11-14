package io.nekohasekai.sagernet.utils

import android.content.Context
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.Key
import io.nekohasekai.sagernet.ktx.Logs
import java.io.File

/**
 * Pre-upgrade database backup (HuBox v1.2.0).
 *
 * Runs as the very first step of Application.onCreate — before anything in
 * the process can open the profile database. When the stored "last seen
 * versionCode" (a plain marker file in filesDir, deliberately NOT the Room
 * database itself) is lower than the running build, the current database
 * file plus its -wal/-shm siblings are copied to
 * `filesDir/db_backups/pre-upgrade-<from>-to-<to>.db`. Only the
 * [MAX_BACKUPS] newest backups are kept. If a migration ever goes wrong,
 * the pre-upgrade data still exists on-device instead of being silently
 * replaced by an empty database.
 *
 * Everything is best-effort: any failure is logged and swallowed, because a
 * backup problem must never break app startup. The marker is only advanced
 * after a successful copy, so a failed backup is retried on the next start.
 *
 * The core logic ([runInternal]) takes plain files/ints so it is covered by
 * JVM unit tests.
 */
object UpgradeBackup {

    private const val MARKER_FILE_NAME = "last_seen_version_code"
    private const val BACKUP_DIR_NAME = "db_backups"
    private const val MAX_BACKUPS = 2
    private const val CLAIM_STALE_MILLIS = 10L * 60L * 1000L

    /** Entry point for Application.onCreate. Never throws. */
    fun runIfNeeded(context: Context) {
        runCatching {
            val filesDir = context.filesDir ?: return
            runInternal(
                filesDir = filesDir,
                databaseFile = context.getDatabasePath(Key.DB_PROFILE),
                currentVersionCode = BuildConfig.VERSION_CODE,
                nowMillis = System.currentTimeMillis(),
                tmpSuffix = android.os.Process.myPid().toString(),
            )
        }.onFailure { Logs.w("UpgradeBackup failed (ignored)", it) }
    }

    internal fun runInternal(
        filesDir: File,
        databaseFile: File,
        currentVersionCode: Int,
        nowMillis: Long,
        tmpSuffix: String,
    ) {
        val markerFile = File(filesDir, MARKER_FILE_NAME)
        val lastSeen = readMarker(markerFile)

        if (lastSeen == currentVersionCode) return

        if (lastSeen != null && lastSeen > currentVersionCode) {
            // Downgrade: just re-baseline so the next real upgrade is seen.
            writeMarker(markerFile, currentVersionCode, tmpSuffix)
            return
        }

        if (!databaseFile.isFile) {
            // Fresh install: no database yet, just record the version.
            writeMarker(markerFile, currentVersionCode, tmpSuffix)
            return
        }

        // lastSeen == null (first run of this mechanism on an existing
        // install) is treated as an upgrade from an unknown version (0):
        // backing up once is harmless and protects exactly the first
        // upgrade this feature ships with.
        val fromVersion = lastSeen ?: 0
        val backupDir = File(filesDir, BACKUP_DIR_NAME).also { it.mkdirs() }

        // Both the main and the :bg process start after an update; a claim
        // file makes sure only one of them copies (the other sees the
        // advanced marker on its next start). A claim older than
        // CLAIM_STALE_MILLIS is considered abandoned and taken over.
        val claim = File(backupDir, ".claim-$fromVersion-$currentVersionCode")
        if (claim.exists() && nowMillis - claim.lastModified() < CLAIM_STALE_MILLIS) {
            return
        }
        if (!claim.createNewFile()) {
            if (nowMillis - claim.lastModified() < CLAIM_STALE_MILLIS) return
            claim.setLastModified(nowMillis)
        }

        try {
            val baseName = "pre-upgrade-$fromVersion-to-$currentVersionCode"
            copyWithSiblings(databaseFile, File(backupDir, "$baseName.db"), tmpSuffix)
            pruneBackups(backupDir)
            writeMarker(markerFile, currentVersionCode, tmpSuffix)
            Logs.i("UpgradeBackup: backed up database before upgrade $fromVersion -> $currentVersionCode")
        } finally {
            claim.delete()
        }
    }

    private fun copyWithSiblings(source: File, target: File, tmpSuffix: String) {
        copyAtomic(source, target, tmpSuffix)
        val wal = File(source.path + "-wal")
        if (wal.isFile) copyAtomic(wal, File(target.path + "-wal"), tmpSuffix)
        val shm = File(source.path + "-shm")
        if (shm.isFile) copyAtomic(shm, File(target.path + "-shm"), tmpSuffix)
    }

    private fun copyAtomic(source: File, target: File, tmpSuffix: String) {
        val tmp = File(target.parentFile, target.name + ".tmp-" + tmpSuffix)
        source.copyTo(tmp, overwrite = true)
        if (!tmp.renameTo(target)) {
            tmp.copyTo(target, overwrite = true)
            tmp.delete()
        }
    }

    private fun pruneBackups(backupDir: File) {
        val backups = backupDir.listFiles { f ->
            f.isFile && f.name.startsWith("pre-upgrade-") && f.name.endsWith(".db")
        }?.sortedWith(
            compareByDescending<File> { toVersionOf(it) }.thenByDescending { it.lastModified() }
        ) ?: return
        backups.drop(MAX_BACKUPS).forEach { old ->
            runCatching { old.delete() }
            runCatching { File(old.path + "-wal").delete() }
            runCatching { File(old.path + "-shm").delete() }
        }
    }

    private fun toVersionOf(file: File): Int {
        // pre-upgrade-<from>-to-<to>.db
        return file.name.removeSuffix(".db").substringAfterLast("-to-").toIntOrNull() ?: 0
    }

    private fun readMarker(markerFile: File): Int? {
        return try {
            if (!markerFile.isFile) null
            else markerFile.readText().trim().toIntOrNull()
        } catch (e: Exception) {
            null
        }
    }

    private fun writeMarker(markerFile: File, versionCode: Int, tmpSuffix: String) {
        val tmp = File(markerFile.parentFile, markerFile.name + ".tmp-" + tmpSuffix)
        tmp.writeText(versionCode.toString())
        if (!tmp.renameTo(markerFile)) {
            tmp.copyTo(markerFile, overwrite = true)
            tmp.delete()
        }
    }
}
