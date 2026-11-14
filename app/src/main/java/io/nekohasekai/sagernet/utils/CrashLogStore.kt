package io.nekohasekai.sagernet.utils

import io.nekohasekai.sagernet.SagerNet
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * On-device store for crash / abnormal-exit records (HuBox v1.2.0).
 *
 * Everything lives in `filesDir/crash_logs` as plain `.log` files:
 *  - `<kind>-<yyyyMMdd-HHmmss-SSS>.log` records written by [writeRecord]
 *    (kinds used today: `crash`, `abnormal-exit`, `watchdog`, `test`),
 *  - `core.log`, the live sing-box core log (see ConfigBuilder: log.output),
 *    rotated to `core-<yyyyMMdd-HHmmss>.log` by [rotateCoreLogIfNeeded].
 *
 * At most [MAX_FILES] files are kept; the oldest are deleted first.
 * All methods are failure-tolerant: IO errors are swallowed and reported
 * through the return value, because most call sites run on crash or
 * startup paths that must never throw.
 *
 * The class is deliberately free of Android dependencies (the directory is
 * injected) so the file logic can be covered by plain JVM unit tests.
 */
class CrashLogStore(private val dir: File) {

    data class Entry(
        val file: File,
        val timeMillis: Long,
        val title: String,
        val sizeBytes: Long,
    )

    /** Writes one record file and prunes the store. Returns the file, or null on failure. */
    fun writeRecord(kind: String, body: String): File? {
        return try {
            if (!dir.exists()) dir.mkdirs()
            val base = "${sanitizeKind(kind)}-${timestampForFileName(System.currentTimeMillis())}"
            var file = File(dir, "$base.log")
            var suffix = 1
            while (file.exists()) {
                suffix++
                file = File(dir, "$base-$suffix.log")
            }
            file.writeText(body)
            prune()
            file
        } catch (e: Exception) {
            null
        }
    }

    /** All stored entries, newest first. The live `core.log` is included when present. */
    fun listEntries(): List<Entry> {
        return try {
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".log") } ?: return emptyList()
            files.map { file ->
                Entry(
                    file = file,
                    timeMillis = file.lastModified(),
                    title = readFirstLine(file),
                    sizeBytes = file.length(),
                )
            }.sortedWith(compareByDescending<Entry> { it.timeMillis }.thenByDescending { it.file.name })
        } catch (e: Exception) {
            emptyList()
        }
    }

    /** Full text of one entry; empty string when unreadable. */
    fun readEntry(file: File): String {
        return try {
            if (file.isFile) file.readText() else ""
        } catch (e: Exception) {
            ""
        }
    }

    /**
     * If the live core log grew past [maxBytes], rename it to a timestamped
     * archive (the core re-creates `core.log` on its next start) and prune.
     * Safe to call while another process appends to the file: renaming does
     * not disturb the already-open stream on Android/Linux.
     */
    fun rotateCoreLogIfNeeded(maxBytes: Long = CORE_LOG_ROTATE_BYTES) {
        try {
            val core = coreLogFile()
            if (core.isFile && core.length() > maxBytes) {
                val archived = File(dir, "core-${timestampForFileName(core.lastModified())}.log")
                if (!core.renameTo(archived)) {
                    // Fall back to copy+delete if the rename fails for any reason.
                    core.copyTo(archived, overwrite = true)
                    core.delete()
                }
                prune()
            }
        } catch (e: Exception) {
            // Rotation is best-effort only.
        }
    }

    /** Deletes the oldest files until at most [MAX_FILES] remain. */
    fun prune() {
        try {
            val files = dir.listFiles { f -> f.isFile && f.name.endsWith(".log") } ?: return
            if (files.size <= MAX_FILES) return
            files.sortedWith(compareBy<File> { it.lastModified() }.thenBy { it.name })
                .take(files.size - MAX_FILES)
                .forEach { runCatching { it.delete() } }
        } catch (e: Exception) {
            // Pruning is best-effort only.
        }
    }

    fun coreLogFile(): File = File(dir, CORE_LOG_NAME)

    private fun readFirstLine(file: File): String {
        return try {
            file.bufferedReader().use { reader ->
                reader.readLine()?.trim()?.take(MAX_TITLE_CHARS) ?: ""
            }
        } catch (e: Exception) {
            ""
        }
    }

    companion object {
        const val DIR_NAME = "crash_logs"
        const val CORE_LOG_NAME = "core.log"
        const val MAX_FILES = 20
        const val CORE_LOG_ROTATE_BYTES = 512L * 1024L
        private const val MAX_TITLE_CHARS = 160

        @Volatile
        private var defaultInstance: CrashLogStore? = null

        /** Shared store bound to the app files dir. */
        fun get(): CrashLogStore {
            return defaultInstance ?: synchronized(this) {
                defaultInstance ?: CrashLogStore(defaultDir()).also { defaultInstance = it }
            }
        }

        fun defaultDir(): File = File(SagerNet.application.filesDir, DIR_NAME)

        /**
         * Absolute path of the live core log, used as the sing-box log
         * output. Also makes sure the directory exists: the core may refuse
         * to start when it cannot create its log file.
         */
        fun coreLogPath(): String {
            val dir = defaultDir()
            runCatching { if (!dir.exists()) dir.mkdirs() }
            return File(dir, CORE_LOG_NAME).absolutePath
        }

        private fun sanitizeKind(kind: String): String {
            val cleaned = kind.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9-]"), "-")
            return cleaned.ifBlank { "record" }
        }

        private fun timestampForFileName(millis: Long): String {
            return SimpleDateFormat("yyyyMMdd-HHmmss-SSS", Locale.ROOT).format(Date(millis))
        }
    }
}
