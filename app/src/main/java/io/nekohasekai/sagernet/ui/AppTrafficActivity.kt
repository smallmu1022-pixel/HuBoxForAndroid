package io.nekohasekai.sagernet.ui

import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.widget.Toolbar
import androidx.core.view.isVisible
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.database.DataStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * HuBox v1.1.0: per-app traffic ranking.
 *
 * Data source: the Clash API connection list (http://127.0.0.1:9090/
 * connections). Each live connection carries cumulative upload/download
 * counters and — when process lookup is available — the owning process,
 * which on Android is the app package/process name. Connections are
 * aggregated per app and ranked by total bytes. The numbers cover current
 * connections only; they reset when connections close.
 */
class AppTrafficActivity : ThemedActivity() {

    private data class AppTraffic(
        val key: String,
        val label: String,
        val icon: Drawable?,
        val upload: Long,
        val download: Long,
    ) {
        val total: Long get() = upload + download
    }

    private lateinit var listView: RecyclerView
    private lateinit var emptyView: TextView
    private val adapter = TrafficAdapter()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.layout_app_traffic)

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        toolbar.setTitle(R.string.app_traffic_title)
        toolbar.setNavigationIcon(R.drawable.baseline_arrow_back_24)
        toolbar.setNavigationOnClickListener { finish() }

        listView = findViewById(R.id.traffic_list)
        emptyView = findViewById(R.id.traffic_empty)
        listView.layoutManager = LinearLayoutManager(this)
        listView.adapter = adapter

        loadTraffic()
    }

    private fun loadTraffic() {
        lifecycleScope.launch {
            val rows = withContext(Dispatchers.IO) { fetchRanking() }
            adapter.submit(rows)
            emptyView.isVisible = rows.isEmpty()
            listView.isVisible = rows.isNotEmpty()
        }
    }

    private fun fetchRanking(): List<AppTraffic> {
        if (!DataStore.serviceState.connected) return emptyList()
        val client = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .build()
        val request = Request.Builder()
            .url("http://127.0.0.1:9090/connections")
            .header("Authorization", "Bearer ${DataStore.clashApiSecret}")
            .build()
        val body = runCatching {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) null else response.body?.string()
            }
        }.getOrNull() ?: return emptyList()

        val connections = runCatching {
            JSONObject(body).optJSONArray("connections")
        }.getOrNull() ?: return emptyList()

        data class Acc(var upload: Long = 0L, var download: Long = 0L)

        val byKey = mutableMapOf<String, Acc>()
        for (i in 0 until connections.length()) {
            val connection = connections.optJSONObject(i) ?: continue
            val metadata = connection.optJSONObject("metadata") ?: continue
            val process = metadata.optString("process").trim()
            val host = metadata.optString("host").trim()
                .ifBlank { metadata.optString("destinationIP").trim() }
            val key = process.ifBlank { host }
            if (key.isBlank()) continue
            val acc = byKey.getOrPut(key) { Acc() }
            acc.upload += connection.optLong("upload")
            acc.download += connection.optLong("download")
        }

        val pm = packageManager
        return byKey.entries
            .map { (key, acc) ->
                val (label, icon) = resolveApp(pm, key)
                AppTraffic(key, label, icon, acc.upload, acc.download)
            }
            .filter { it.total > 0 }
            .sortedByDescending { it.total }
    }

    private var installedAppsCache: List<ApplicationInfo>? = null

    /**
     * Resolve a connection's process string to a display label and the real
     * app icon. The process field may be a plain package name, a package
     * with a sub-process suffix ("pkg:push"), or a bare process/label
     * string, so resolution tries the package directly first, then matches
     * process name, then the app label across installed packages. Host-keyed
     * rows (no process info) keep the raw key and the placeholder icon.
     * Runs on the IO thread from fetchRanking.
     */
    private fun resolveApp(pm: PackageManager, rawKey: String): Pair<String, Drawable?> {
        val pkg = rawKey.substringBefore(':').trim()
        if (pkg.contains('.') && !pkg.contains(' ')) {
            runCatching {
                val info = pm.getApplicationInfo(pkg, 0)
                return pm.getApplicationLabel(info).toString() to pm.getApplicationIcon(info)
            }
        }
        val installed = installedAppsCache
            ?: runCatching { pm.getInstalledApplications(0) }.getOrDefault(emptyList())
                .also { installedAppsCache = it }
        for (info in installed) {
            if (info.processName == rawKey || info.packageName == pkg) {
                return pm.getApplicationLabel(info).toString() to pm.getApplicationIcon(info)
            }
        }
        for (info in installed) {
            if (pm.getApplicationLabel(info).toString() == rawKey) {
                return rawKey to pm.getApplicationIcon(info)
            }
        }
        return rawKey to null
    }

    private inner class TrafficAdapter : RecyclerView.Adapter<TrafficAdapter.Holder>() {

        private var items: List<AppTraffic> = emptyList()

        fun submit(list: List<AppTraffic>) {
            items = list
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_app_traffic, parent, false)
            return Holder(view)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(position + 1, items[position])
        }

        inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val rank: TextView = itemView.findViewById(R.id.rank)
            private val icon: ImageView = itemView.findViewById(R.id.app_icon)
            private val name: TextView = itemView.findViewById(R.id.app_name)
            private val detail: TextView = itemView.findViewById(R.id.app_detail)
            private val total: TextView = itemView.findViewById(R.id.app_total)

            fun bind(rankNumber: Int, item: AppTraffic) {
                rank.text = rankNumber.toString()
                name.text = item.label
                detail.text = "↑ ${Formatter.formatFileSize(itemView.context, item.upload)}" +
                    " ↓ ${Formatter.formatFileSize(itemView.context, item.download)}"
                total.text = Formatter.formatFileSize(itemView.context, item.total)
                if (item.icon != null) {
                    icon.setImageDrawable(item.icon)
                } else {
                    icon.setImageResource(R.drawable.ic_navigation_apps)
                }
            }
        }
    }
}
