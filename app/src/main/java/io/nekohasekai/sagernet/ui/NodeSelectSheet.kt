package io.nekohasekai.sagernet.ui

import android.content.res.ColorStateList
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.core.graphics.ColorUtils
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.bottomsheet.BottomSheetDialogFragment
import com.google.android.material.button.MaterialButton
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.bg.proto.UrlTest
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.ProxyEntity
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.getColorAttr
import io.nekohasekai.sagernet.ktx.readableMessage
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * HuBox v1.1.0 node selection bottom sheet, opened from the home node card.
 *
 * Lists the profiles of the current group with their last latencies, offers
 * a one-tap speed test for the whole group (top-right button), shows when
 * the group was last tested, and auto-retests in the background when the
 * data is stale (> 30 minutes).
 */
class NodeSelectSheet : BottomSheetDialogFragment() {

    var onChanged: (() -> Unit)? = null

    private var groupId: Long = 0L
    private var profiles: List<ProxyEntity> = emptyList()
    private var testing = false
    private var testJob: Job? = null

    private lateinit var listView: RecyclerView
    private lateinit var groupLabel: TextView
    private lateinit var freshnessLabel: TextView
    private lateinit var testButton: MaterialButton
    private lateinit var adapter: NodeAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ): View = inflater.inflate(R.layout.layout_sheet_node_select, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        listView = view.findViewById(R.id.sheet_list)
        groupLabel = view.findViewById(R.id.sheet_group)
        freshnessLabel = view.findViewById(R.id.sheet_freshness)
        testButton = view.findViewById(R.id.btn_sheet_test)

        adapter = NodeAdapter(
            onSelect = { entity -> selectProfile(entity) },
            onPingClick = { entity ->
                PingHistory.showDialog(requireContext(), entity.id, entity.displayName())
            },
        )
        listView.layoutManager = LinearLayoutManager(requireContext())
        listView.adapter = adapter

        testButton.setOnClickListener { startGroupTest(manual = true) }

        loadGroup()
    }

    override fun onDestroyView() {
        testJob?.cancel()
        super.onDestroyView()
    }

    private fun loadGroup() {
        viewLifecycleOwner.lifecycleScope.launch {
            val resolved = withContext(Dispatchers.IO) {
                val selected = DataStore.selectedProxy
                val selectedEntity = if (selected > 0) {
                    runCatching { SagerDatabase.proxyDao.getById(selected) }.getOrNull()
                } else {
                    null
                }
                val gid = selectedEntity?.groupId?.takeIf { it > 0 }
                    ?: DataStore.currentGroupId()
                val group = runCatching { SagerDatabase.groupDao.getById(gid) }.getOrNull()
                val list = runCatching { SagerDatabase.proxyDao.getByGroup(gid) }
                    .getOrDefault(emptyList())
                    .sortedBy { it.userOrder }
                Triple(gid, group?.displayName(), list)
            }
            if (!isAdded) return@launch
            groupId = resolved.first
            groupLabel.text = resolved.second.orEmpty()
            profiles = resolved.third
            adapter.submit(profiles, DataStore.selectedProxy)
            capListHeight()
            updateFreshness()

            val last = HomeModules.lastTestTime(requireContext(), groupId)
            val stale = System.currentTimeMillis() - last > STALE_MS
            if (stale && profiles.isNotEmpty() && !DataStore.runningTest) {
                startGroupTest(manual = false)
            }
        }
    }

    private fun capListHeight() {
        if (profiles.size > MAX_VISIBLE_ROWS) {
            val density = resources.displayMetrics.density
            listView.layoutParams = listView.layoutParams.apply {
                height = (400 * density).toInt()
            }
        }
    }

    private fun updateFreshness() {
        if (!isAdded) return
        freshnessLabel.text = HomeModules.freshnessText(
            requireContext(),
            HomeModules.lastTestTime(requireContext(), groupId),
        )
    }

    private fun selectProfile(entity: ProxyEntity) {
        val newId = entity.id
        runOnDefaultDispatcher {
            val old = DataStore.selectedProxy
            DataStore.selectedProxy = newId
            if (old != newId && old > 0) {
                ProfileManager.postUpdate(old, true)
            }
            if (DataStore.serviceState != BaseService.State.Stopped) {
                runCatching { SagerNet.reloadService() }
            }
        }
        onChanged?.invoke()
        dismiss()
    }

    private fun startGroupTest(manual: Boolean) {
        if (testing || profiles.isEmpty()) return
        if (DataStore.runningTest) return
        testing = true
        DataStore.runningTest = true
        testButton.isEnabled = false
        testButton.text = getString(R.string.home_speed_testing)

        val targets = profiles
        val appCtx = requireContext().applicationContext
        testJob = viewLifecycleOwner.lifecycleScope.launch {
            try {
                coroutineScope {
                    val channel = Channel<ProxyEntity>(Channel.UNLIMITED)
                    targets.forEach { channel.trySend(it) }
                    channel.close()
                    val url = UrlTest(DataStore.groupUrlTestUrl(groupId).takeIf { it.isNotBlank() }
                        ?: DataStore.connectionTestURL)
                    val results = ConcurrentLinkedQueue<ProxyEntity>()
                    val workers = DataStore.connectionTestConcurrent.coerceAtLeast(1)
                    repeat(workers) {
                        launch(Dispatchers.IO) {
                            for (entity in channel) {
                                runCatching {
                                    entity.status = 0
                                    SagerDatabase.proxyDao.updatePingResult(
                                        entity.id, entity.status, entity.ping, entity.error,
                                    )
                                }
                                var failure: String? = null
                                val result = try {
                                    withTimeoutOrNull(
                                        DataStore.connectionTestTimeout.toLong() * 2 + 2500L,
                                    ) {
                                        url.doTest(entity)
                                    }
                                } catch (e: Exception) {
                                    failure = e.readableMessage
                                    null
                                }
                                if (result != null) {
                                    entity.ping = result
                                    entity.status = 1
                                    entity.error = null
                                } else {
                                    entity.ping = 0
                                    entity.status = 3
                                    entity.error = failure ?: "timeout"
                                }
                                runCatching {
                                    SagerDatabase.proxyDao.updatePingResult(
                                        entity.id, entity.status, entity.ping, entity.error,
                                    )
                                    if (entity.status == 1) {
                                        PingHistory.record(appCtx, entity.id, entity.ping)
                                    }
                                    ProfileManager.postUpdate(entity)
                                }
                                results.add(entity)
                                withContext(Dispatchers.Main) {
                                    val index = profiles.indexOfFirst { it.id == entity.id }
                                    if (index >= 0) {
                                        profiles = profiles.toMutableList().also { it[index] = entity }
                                        adapter.notifyItemChanged(index)
                                    }
                                }
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                if (manual && isAdded) {
                    android.widget.Toast.makeText(
                        requireContext(), e.readableMessage, android.widget.Toast.LENGTH_SHORT,
                    ).show()
                }
            } finally {
                testing = false
                DataStore.runningTest = false
                if (isAdded) {
                    HomeModules.setLastTestTime(appCtx, groupId, System.currentTimeMillis())
                    testButton.isEnabled = true
                    testButton.text = getString(R.string.home_speed_test)
                    updateFreshness()
                    onChanged?.invoke()
                }
            }
        }
    }

    private inner class NodeAdapter(
        private val onSelect: (ProxyEntity) -> Unit,
        private val onPingClick: (ProxyEntity) -> Unit,
    ) : RecyclerView.Adapter<NodeAdapter.Holder>() {

        private var items: List<ProxyEntity> = emptyList()
        private var selectedId: Long = 0L

        fun submit(list: List<ProxyEntity>, selected: Long) {
            items = list
            selectedId = selected
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val itemView = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_sheet_node, parent, false)
            return Holder(itemView)
        }

        override fun getItemCount(): Int = items.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            holder.bind(items[position], items[position].id == selectedId)
        }

        inner class Holder(itemView: View) : RecyclerView.ViewHolder(itemView) {
            private val name: TextView = itemView.findViewById(R.id.item_node_name)
            private val type: TextView = itemView.findViewById(R.id.item_node_type)
            private val ping: TextView = itemView.findViewById(R.id.item_node_ping)
            private val check: ImageView = itemView.findViewById(R.id.item_node_check)
            private val defaultBackground = itemView.background

            fun bind(entity: ProxyEntity, selected: Boolean) {
                name.text = entity.displayName()
                type.text = entity.displayType()
                when (entity.status) {
                    1 -> {
                        ping.text = if (entity.ping > 0) "${entity.ping}ms" else ""
                        ping.setTextColor(
                            itemView.context.getColor(
                                if (entity.ping in 1..100) R.color.hubox_latency_good
                                else R.color.hubox_latency_bad
                            )
                        )
                    }

                    0 -> {
                        ping.text = "…"
                        ping.setTextColor(itemView.context.getColorAttr(android.R.attr.textColorSecondary))
                    }

                    3 -> {
                        ping.text = "—"
                        ping.setTextColor(itemView.context.getColor(R.color.hubox_latency_error))
                    }

                    else -> {
                        ping.text = ""
                    }
                }
                itemView.alpha = if (entity.status == 3) 0.55f else 1f
                check.visibility = if (selected) View.VISIBLE else View.GONE
                // HuBox v1.1.1: the checked node reads in highlight green so the
                // selection stays obvious in both light and dark mode.
                if (selected) {
                    val green = itemView.context.getColor(R.color.hubox_highlight_green)
                    name.setTextColor(green)
                    ping.setTextColor(green)
                    check.imageTintList = ColorStateList.valueOf(green)
                    itemView.background = GradientDrawable().apply {
                        cornerRadius = 12f * itemView.resources.displayMetrics.density
                        setColor(ColorUtils.setAlphaComponent(green, 31))
                    }
                } else {
                    name.setTextColor(
                        itemView.context.getColorAttr(android.R.attr.textColorPrimary)
                    )
                    check.imageTintList = null
                    itemView.background = defaultBackground
                }
                itemView.setOnClickListener { onSelect(entity) }
                ping.setOnClickListener { onPingClick(entity) }
            }
        }
    }

    companion object {
        private const val STALE_MS = 30 * 60 * 1000L
        private const val MAX_VISIBLE_ROWS = 7
    }
}
