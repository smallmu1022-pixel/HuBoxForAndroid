package io.nekohasekai.sagernet.utils

import io.nekohasekai.sagernet.SagerNet
import java.io.File

/**
 * "Service session alive" marker (HuBox v1.2.0).
 *
 * The marker file exists exactly while a VPN/proxy service session is
 * connected: [markConnected] writes it when the service reaches the
 * Connected state, [clear] removes it on a clean stop. If the background
 * process starts and still finds a marker, the previous connected session
 * ended without a clean stop (core panic, process kill, force stop, …)
 * and an abnormal-exit record is written to [CrashLogStore].
 *
 * Pure file logic (directory injected via the constructor) for unit tests;
 * all operations are failure-tolerant and never throw.
 */
class SessionMarker(private val markerFile: File) {

    data class Info(
        val versionCode: Int,
        val connectedAtMillis: Long,
        val state: String,
    )

    fun markConnected(versionCode: Int, connectedAtMillis: Long) {
        try {
            markerFile.parentFile?.mkdirs()
            val tmp = File(markerFile.parentFile, markerFile.name + ".tmp")
            tmp.writeText(
                "version=$versionCode\n" +
                    "since=$connectedAtMillis\n" +
                    "state=Connected\n"
            )
            if (!tmp.renameTo(markerFile)) {
                tmp.copyTo(markerFile, overwrite = true)
                tmp.delete()
            }
        } catch (e: Exception) {
            // Best-effort only: a missing marker just skips one record.
        }
    }

    /** The stored marker, or null when there is none / it is unreadable. */
    fun read(): Info? {
        return try {
            if (!markerFile.isFile) return null
            var version = 0
            var since = 0L
            var state = ""
            markerFile.forEachLine { line ->
                val parts = line.split("=", limit = 2)
                if (parts.size == 2) {
                    when (parts[0].trim()) {
                        "version" -> version = parts[1].trim().toIntOrNull() ?: 0
                        "since" -> since = parts[1].trim().toLongOrNull() ?: 0L
                        "state" -> state = parts[1].trim()
                    }
                }
            }
            Info(version, since, state)
        } catch (e: Exception) {
            null
        }
    }

    fun clear() {
        try {
            if (markerFile.exists()) markerFile.delete()
        } catch (e: Exception) {
            // Best-effort only.
        }
    }

    companion object {
        const val FILE_NAME = "service_session.marker"

        @Volatile
        private var defaultInstance: SessionMarker? = null

        fun get(): SessionMarker {
            return defaultInstance ?: synchronized(this) {
                defaultInstance
                    ?: SessionMarker(File(SagerNet.application.filesDir, FILE_NAME))
                        .also { defaultInstance = it }
            }
        }
    }
}
