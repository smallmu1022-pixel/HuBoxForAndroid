package io.nekohasekai.sagernet.bg

import android.net.InetAddresses
import android.os.Build
import android.os.SystemClock
import android.system.OsConstants
import androidx.annotation.RequiresApi
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.utils.AppTrafficLedger
import io.nekohasekai.sagernet.utils.PackageCache
import io.nekohasekai.sagernet.utils.TrafficDeltaTracker
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.TimeUnit

/**
 * Feeds the per-app daily traffic ledger (HuBox v1.2.2).
 *
 * [TrafficLooper] calls [sampleTick] once per stats tick while the
 * service is connected; the recorder throttles itself to one Clash API
 * connection-list fetch every [SAMPLE_INTERVAL_MS], converts the
 * cumulative counters into deltas via [TrafficDeltaTracker] and books
 * them into [AppTrafficLedger] under the owning app.
 *
 * Owner resolution per connection (cached per connection id):
 *  1. the core-reported `metadata.processPath` (the Clash API payload
 *     has no `process` field — sing-box emits the resolved package
 *     name, or the uid, under `processPath`),
 *  2. the platform connection-owner lookup (Android Q+,
 *     [android.net.ConnectivityManager.getConnectionOwnerUid]) mapped
 *     from uid to package — the same lookup the core itself uses
 *     through NativeInterface.findConnectionOwner,
 *  3. `"host:<host>"` (or the destination IP) when neither works.
 *
 * Everything runs inside [runCatching]: a broken sample must never
 * disturb the traffic loop or the UI caller.
 */
object AppTrafficRecorder {

    private const val SAMPLE_INTERVAL_MS = 5_000L
    private const val FLUSH_INTERVAL_MS = 15_000L
    const val HOST_PREFIX = "host:"

    private val client = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    private val tracker = TrafficDeltaTracker()
    private val keyCache = HashMap<String, String>()

    @Volatile
    private var ledgerInstance: AppTrafficLedger? = null
    private var lastSampleMs = 0L
    private var lastFlushMs = 0L

    /** The shared ledger (filesDir/app_traffic), created and loaded on first use. */
    fun ledger(): AppTrafficLedger {
        ledgerInstance?.let { return it }
        return synchronized(this) {
            ledgerInstance ?: AppTrafficLedger(
                File(SagerNet.application.filesDir, AppTrafficLedger.DIR_NAME)
            ).also {
                it.load()
                ledgerInstance = it
            }
        }
    }

    /**
     * One sampling step. [force] skips the throttle (used when the
     * ranking UI opens so it can show data up to this second).
     */
    @Synchronized
    fun sampleTick(force: Boolean = false) {
        runCatching {
            if (!DataStore.serviceState.connected) return
            if (!DataStore.enableClashAPI) return
            val now = SystemClock.elapsedRealtime()
            if (!force && now - lastSampleMs < SAMPLE_INTERVAL_MS) return
            lastSampleMs = now

            val snaps = fetchConnections() ?: return
            val deltas = tracker.onSnapshot(snaps)
            val store = ledger()
            val today = AppTrafficLedger.today()
            for ((key, acc) in deltas) {
                store.add(today, key, acc[0], acc[1])
            }
            store.prune(today)
            if (force || now - lastFlushMs >= FLUSH_INTERVAL_MS) {
                store.flush()
                lastFlushMs = now
            }
        }
    }

    /** Persists whatever the ledger still holds in memory. Safe to call anytime. */
    fun onServiceStop() {
        runCatching { ledgerInstance?.flush() }
    }

    /**
     * Fetches and resolves the current connection list, or null when the
     * fetch itself failed (a failed fetch must not feed the tracker an
     * empty snapshot — that would evict every baseline).
     */
    private fun fetchConnections(): List<TrafficDeltaTracker.ConnSnap>? {
        val request = Request.Builder()
            .url("http://127.0.0.1:9090/connections")
            .header("Authorization", "Bearer ${DataStore.clashApiSecret}")
            .build()
        val body = runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()
            }
        }.getOrNull() ?: return null
        val connections = runCatching {
            JSONObject(body).optJSONArray("connections")
        }.getOrNull() ?: return null

        val snaps = ArrayList<TrafficDeltaTracker.ConnSnap>()
        val liveIds = HashSet<String>()
        for (i in 0 until connections.length()) {
            val connection = connections.optJSONObject(i) ?: continue
            val id = connection.optString("id").trim()
            if (id.isBlank()) continue
            liveIds.add(id)
            val metadata = connection.optJSONObject("metadata")
            // Blank resolutions are not cached: a later sample may succeed.
            // Host-fallback keys are not cached either: the owner lookup
            // often fails on a connection's first sample (socket not yet
            // in the kernel table), and a later sample should be able to
            // upgrade the row to the owning app.
            val key = keyCache[id]
                ?: resolveKey(metadata).also {
                    if (it.isNotBlank() && !it.startsWith(HOST_PREFIX)) keyCache[id] = it
                }
            if (key.isBlank()) continue
            snaps.add(
                TrafficDeltaTracker.ConnSnap(
                    id = id,
                    key = key,
                    upload = connection.optLong("upload"),
                    download = connection.optLong("download"),
                )
            )
        }
        keyCache.keys.retainAll(liveIds)
        return snaps
    }

    private fun resolveKey(metadata: JSONObject?): String {
        if (metadata == null) return ""
        // (a) Core-reported process info. sing-box serializes it as
        // "processPath" — there is NO "process" field in the Clash API
        // payload (verified against sing-box v1.15.0-alpha.13
        // clashapi/connections.go; reading "process" is exactly why the
        // old live ranking never saw a package name). The value is the
        // package name when the core resolved the owner, sometimes with
        // a " (uid)" / " (user)" suffix, or the bare uid as a string.
        val processPath = metadata.optString("processPath").trim()
            .substringBefore(" (").substringBefore(':').trim()
        if (processPath.isNotEmpty()) {
            if (processPath.contains('.') && !processPath.contains('/') &&
                !processPath.contains(' ')
            ) {
                return processPath
            }
            processPath.toIntOrNull()?.let { uid ->
                packageNameByUid(uid)?.let { return it }
            }
        }
        // (b) Platform owner lookup (same call the core itself makes
        // through NativeInterface.findConnectionOwner).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            packageNameByOwner(metadata)?.let { return it }
        }
        // (c) Host fallback.
        val host = metadata.optString("host").trim()
            .ifBlank { metadata.optString("destinationIP").trim() }
        return if (host.isBlank()) "" else HOST_PREFIX + host
    }

    /** uid -> package name, mirroring NativeInterface.packageNameByUid. */
    private fun packageNameByUid(uid: Int): String? {
        if (uid < 0) return null
        if (uid <= 1000) return "android"
        if (PackageCache.isLoaded()) {
            PackageCache.uidMap[uid]?.firstOrNull()?.let { return it }
        }
        return runCatching {
            SagerNet.application.packageManager.getPackagesForUid(uid)?.firstOrNull()
        }.getOrNull()
    }

    @RequiresApi(Build.VERSION_CODES.Q)
    private fun packageNameByOwner(metadata: JSONObject): String? {
        val proto = when (metadata.optString("network").trim().lowercase()) {
            "tcp" -> OsConstants.IPPROTO_TCP
            "udp" -> OsConstants.IPPROTO_UDP
            else -> return null
        }
        val srcPort = metadata.optString("sourcePort").trim().toIntOrNull() ?: return null
        val dstPort = metadata.optString("destinationPort").trim().toIntOrNull() ?: return null
        val src = numericAddress(metadata.optString("sourceIP").trim(), srcPort) ?: return null
        val dst = numericAddress(metadata.optString("destinationIP").trim(), dstPort) ?: return null
        val uid = runCatching {
            SagerNet.connectivity.getConnectionOwnerUid(proto, src, dst)
        }.getOrDefault(-1)
        return packageNameByUid(uid)
    }

    /** Parses an IP literal without ever triggering DNS resolution. */
    @RequiresApi(Build.VERSION_CODES.Q)
    private fun numericAddress(ip: String, port: Int): InetSocketAddress? {
        if (ip.isEmpty() || port !in 1..65535) return null
        val address = runCatching { InetAddresses.parseNumericAddress(ip) }.getOrNull()
            ?: return null
        return InetSocketAddress(address, port)
    }
}
