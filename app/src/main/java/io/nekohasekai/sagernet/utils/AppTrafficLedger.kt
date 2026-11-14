package io.nekohasekai.sagernet.utils

import java.io.File
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Persistent per-app daily traffic ledger (HuBox v1.2.2).
 *
 * The recorder in the service process samples the Clash API connection
 * list, turns cumulative per-connection counters into deltas and books
 * them here, keyed by owning app (package name) or `"host:<host>"` when
 * the owner cannot be determined. Unlike the live connection list, the
 * ledger survives closed connections, service restarts and reboots.
 *
 * Storage is a single tab-separated file (`app_traffic_ledger.tsv` in the
 * injected directory), one line per (day, key):
 * `yyyy-MM-dd<TAB>key<TAB>uploadBytes<TAB>downloadBytes`. Keys never
 * contain tabs (package names and hosts don't). Only the last
 * [RETENTION_DAYS] days are kept; [prune] drops anything older.
 *
 * The class is deliberately free of Android dependencies and never calls
 * the app logger (which would drag the native core library into plain
 * JVM unit tests), so the whole store is unit-testable. All public
 * methods are synchronized; callers may sit on any thread.
 */
class AppTrafficLedger(private val dir: File) {

    /** date -> key -> [upload, download] */
    private val data = mutableMapOf<String, MutableMap<String, LongArray>>()
    private var loaded = false
    private var dirty = false

    private val storeFile: File get() = File(dir, FILE_NAME)

    /** (Re)reads the store file, replacing the in-memory state. Malformed lines are ignored. */
    @Synchronized
    fun load() {
        data.clear()
        loaded = true
        dirty = false
        val file = storeFile
        if (!file.isFile) return
        try {
            file.forEachLine { line ->
                val parts = line.split('\t')
                if (parts.size != 4) return@forEachLine
                val date = parts[0]
                if (parseDate(date) == null) return@forEachLine
                val key = parts[1]
                if (key.isEmpty()) return@forEachLine
                val upload = parts[2].toLongOrNull() ?: return@forEachLine
                val download = parts[3].toLongOrNull() ?: return@forEachLine
                val acc = data.getOrPut(date) { mutableMapOf() }
                    .getOrPut(key) { longArrayOf(0L, 0L) }
                acc[0] += upload
                acc[1] += download
            }
        } catch (e: Exception) {
            // A partially unreadable ledger must never break accounting.
        }
    }

    private fun ensureLoaded() {
        if (!loaded) load()
    }

    /** Books one delta for [key] on [date]. Zero deltas are not recorded. */
    @Synchronized
    fun add(date: String, key: String, upDelta: Long, downDelta: Long) {
        if (upDelta == 0L && downDelta == 0L) return
        ensureLoaded()
        val acc = data.getOrPut(date) { mutableMapOf() }
            .getOrPut(key) { longArrayOf(0L, 0L) }
        acc[0] += upDelta
        acc[1] += downDelta
        dirty = true
    }

    /** A deep copy of the booked totals for [date] (key -> [upload, download]). */
    @Synchronized
    fun snapshot(date: String): Map<String, LongArray> {
        ensureLoaded()
        val day = data[date] ?: return emptyMap()
        val copy = LinkedHashMap<String, LongArray>(day.size)
        for ((key, acc) in day) copy[key] = acc.copyOf()
        return copy
    }

    /** Drops every day older than [RETENTION_DAYS] days before [today]. */
    @Synchronized
    fun prune(today: String) {
        ensureLoaded()
        val todayDate = parseDate(today) ?: return
        val cutoff = todayDate.minusDays(RETENTION_DAYS)
        var removed = false
        val iterator = data.entries.iterator()
        while (iterator.hasNext()) {
            val date = parseDate(iterator.next().key)
            if (date == null || date.isBefore(cutoff)) {
                iterator.remove()
                removed = true
            }
        }
        if (removed) dirty = true
    }

    /** Atomically persists all in-memory data (temp file + rename). No-op unless dirty. */
    @Synchronized
    fun flush() {
        ensureLoaded()
        if (!dirty) return
        try {
            if (!dir.exists()) dir.mkdirs()
            val tmp = File(dir, "$FILE_NAME.tmp")
            tmp.bufferedWriter().use { writer ->
                for (date in data.keys.sorted()) {
                    val day = data.getValue(date)
                    for (key in day.keys.sorted()) {
                        val acc = day.getValue(key)
                        writer.write(date)
                        writer.write("\t")
                        writer.write(key)
                        writer.write("\t")
                        writer.write(acc[0].toString())
                        writer.write("\t")
                        writer.write(acc[1].toString())
                        writer.newLine()
                    }
                }
            }
            val target = storeFile
            if (!tmp.renameTo(target)) {
                tmp.copyTo(target, overwrite = true)
                tmp.delete()
            }
            dirty = false
        } catch (e: Exception) {
            // Stay dirty so a later flush retries.
        }
    }

    companion object {
        const val DIR_NAME = "app_traffic"
        const val FILE_NAME = "app_traffic_ledger.tsv"
        const val RETENTION_DAYS = 30L

        private val FORMATTER: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

        fun today(): String = LocalDate.now().format(FORMATTER)

        internal fun parseDate(value: String): LocalDate? = try {
            LocalDate.parse(value, FORMATTER)
        } catch (e: Exception) {
            null
        }
    }
}
