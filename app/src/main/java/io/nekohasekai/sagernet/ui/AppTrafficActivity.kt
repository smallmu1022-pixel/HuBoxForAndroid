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
import io.nekohasekai.sagernet.bg.AppTrafficRecorder
import io.nekohasekai.sagernet.utils.AppTrafficLedger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Per-app traffic ranking.
 *
 * HuBox v1.1.0 read the Clash API connection list directly, which only
 * ever showed the connections open at that moment, keyed by host name
 * (the core leaves `metadata.process` empty on-device, so no app names
 * or icons were possible).
 *
 * HuBox v1.2.2 reads today's persistent ledger instead:
 * [AppTrafficRecorder] samples the connection list from the service,
 * resolves each connection's owning app and books the byte deltas into
 * [AppTrafficLedger]. The ranking therefore covers everything that
 * flowed while connected — including connections that have long closed.
 * Keys are package names, or `"host:<host>"` when the owner of a
 * connection could not be determined.
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
        // One fresh sample first so the ranking includes traffic up to
        // this second; the recorder no-ops when disconnected or throttled
        // by its own failure handling, and the ledger read below still
        // returns everything booked earlier (also while disconnected).
        AppTrafficRecorder.sampleTick(force = true)
        val snapshot = AppTrafficRecorder.ledger().snapshot(AppTrafficLedger.today())

        val pm = packageManager
        return snapshot.entries
            .map { (key, acc) ->
                if (key.startsWith(AppTrafficRecorder.HOST_PREFIX)) {
                    AppTraffic(
                        key,
                        key.removePrefix(AppTrafficRecorder.HOST_PREFIX),
                        null,
                        acc[0],
                        acc[1],
                    )
                } else {
                    val (label, icon) = resolveApp(pm, key)
                    AppTraffic(key, label, icon, acc[0], acc[1])
                }
            }
            .filter { it.total > 0 }
            .sortedByDescending { it.total }
    }

    private var installedAppsCache: List<ApplicationInfo>? = null

    /**
     * Resolve a ledger key to a display label and the real app icon. The
     * key may be a plain package name, a package with a sub-process
     * suffix ("pkg:push"), or a bare process/label string, so resolution
     * tries the package directly first, then matches process name, then
     * the app label across installed packages. Unresolvable keys keep the
     * raw key and the placeholder icon. Runs on the IO thread from
     * fetchRanking.
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
