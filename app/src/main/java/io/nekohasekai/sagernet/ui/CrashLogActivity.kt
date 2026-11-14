package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import android.text.format.DateFormat
import android.text.format.Formatter
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import androidx.core.content.FileProvider
import androidx.core.view.isVisible
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.snackbar.Snackbar
import io.nekohasekai.sagernet.BuildConfig
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.databinding.LayoutCrashLogBinding
import io.nekohasekai.sagernet.databinding.LayoutCrashLogDetailBinding
import io.nekohasekai.sagernet.databinding.LayoutCrashLogItemBinding
import io.nekohasekai.sagernet.ktx.FixedLinearLayoutManager
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.utils.CrashLogStore
import java.io.File
import java.util.Date

/**
 * Crash log center (HuBox v1.2.0): lists the records kept by [CrashLogStore]
 * (Java crashes, abnormal exits, watchdog restarts, rotated core logs),
 * newest first. Tapping an entry opens the same activity in detail mode
 * with the full text and a share action. The "write test record" button
 * proves the store pipeline works without waiting for a real crash.
 */
class CrashLogActivity : ThemedActivity() {

    private var rootView: View? = null
    private var detailFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val path = intent.getStringExtra(EXTRA_PATH)
        if (path != null) {
            detailFile = File(path)
            showDetail()
        } else {
            showList()
        }
    }

    override fun snackbarInternal(text: CharSequence): Snackbar {
        return Snackbar.make(rootView ?: findViewById(android.R.id.content), text, Snackbar.LENGTH_LONG)
    }

    // -------------------------------------------------------------- list mode

    private fun showList() {
        val binding = LayoutCrashLogBinding.inflate(layoutInflater)
        rootView = binding.root
        setContentView(binding.root)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            setTitle(R.string.crash_log_title)
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }

        val adapter = EntryAdapter { entry ->
            startActivity(
                Intent(this, CrashLogActivity::class.java)
                    .putExtra(EXTRA_PATH, entry.file.absolutePath)
            )
        }
        binding.recyclerView.layoutManager = FixedLinearLayoutManager(binding.recyclerView)
        binding.recyclerView.adapter = adapter

        binding.writeTestRecord.setOnClickListener {
            writeTestRecord { reloadList(binding, adapter) }
        }
        binding.exportAll.setOnClickListener {
            exportAll()
        }

        reloadList(binding, adapter)
    }

    private fun reloadList(binding: LayoutCrashLogBinding, adapter: EntryAdapter) {
        runOnDefaultDispatcher {
            val entries = CrashLogStore.get().listEntries()
            onMainDispatcher {
                adapter.submit(entries)
                binding.emptyView.isVisible = entries.isEmpty()
                binding.exportAll.isEnabled = entries.isNotEmpty()
            }
        }
    }

    private fun writeTestRecord(onDone: () -> Unit) {
        runOnDefaultDispatcher {
            val file = CrashLogStore.get().writeRecord(
                "test",
                buildString {
                    append("Test record (self-check) - not a real crash\n")
                    append("App: ").append(BuildConfig.VERSION_NAME)
                        .append(" (").append(BuildConfig.VERSION_CODE).append(")\n")
                    append("Generated at (epoch ms): ").append(System.currentTimeMillis()).append('\n')
                    append("This entry was written manually from the crash log center ")
                    append("to verify that crash records are stored and can be viewed/exported.\n")
                }
            )
            onMainDispatcher {
                if (file != null) {
                    snackbar(R.string.crash_log_test_written)
                }
                onDone()
            }
        }
    }

    private fun exportAll() {
        runOnDefaultDispatcher {
            val entries = CrashLogStore.get().listEntries()
            if (entries.isEmpty()) return@runOnDefaultDispatcher
            runCatching {
                val exportDir = File(cacheDir, "crash_log_export").also { it.mkdirs() }
                val out = File(exportDir, "hubox-crash-logs-${System.currentTimeMillis()}.log")
                out.bufferedWriter().use { writer ->
                    writer.append("HuBox crash logs export\n")
                    writer.append("App: ").append(BuildConfig.VERSION_NAME)
                        .append(" (").append(BuildConfig.VERSION_CODE.toString()).append(")\n\n")
                    for (entry in entries) {
                        writer.append("===== ").append(entry.file.name).append(" =====\n")
                        writer.append(CrashLogStore.get().readEntry(entry.file))
                        writer.append("\n\n")
                    }
                }
                onMainDispatcher { shareFile(out) }
            }.onFailure { Logs.w(it) }
        }
    }

    // ------------------------------------------------------------ detail mode

    private fun showDetail() {
        val file = detailFile ?: return
        val binding = LayoutCrashLogDetailBinding.inflate(layoutInflater)
        rootView = binding.root
        setContentView(binding.root)

        setSupportActionBar(findViewById(R.id.toolbar))
        supportActionBar?.apply {
            title = file.name
            setDisplayHomeAsUpEnabled(true)
            setHomeAsUpIndicator(R.drawable.ic_navigation_close)
        }

        runOnDefaultDispatcher {
            val text = runCatching {
                val raw = CrashLogStore.get().readEntry(file)
                if (raw.length > MAX_DETAIL_CHARS) {
                    "(Showing the last part of a large file)\n\n" +
                        raw.takeLast(MAX_DETAIL_CHARS)
                } else {
                    raw
                }
            }.getOrDefault("")
            onMainDispatcher {
                binding.detailText.text = text
            }
        }
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (detailFile != null) {
            menuInflater.inflate(R.menu.crash_log_menu, menu)
            return true
        }
        return super.onCreateOptionsMenu(menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            android.R.id.home -> {
                finish()
                true
            }
            R.id.action_share -> {
                detailFile?.let { shareFile(it) }
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    // ---------------------------------------------------------------- sharing

    private fun shareFile(file: File) {
        runCatching {
            val exportDir = File(cacheDir, "crash_log_export").also { it.mkdirs() }
            val shareTarget = if (file.parentFile == exportDir) {
                file
            } else {
                file.copyTo(File(exportDir, file.name), overwrite = true)
            }
            val uri = FileProvider.getUriForFile(
                this, BuildConfig.APPLICATION_ID + ".cache", shareTarget
            )
            startActivity(
                Intent.createChooser(
                    Intent(Intent.ACTION_SEND).setType("text/x-log")
                        .setFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        .putExtra(Intent.EXTRA_STREAM, uri),
                    getString(R.string.abc_shareactionprovider_share_with)
                )
            )
        }.onFailure { Logs.w(it) }
    }

    // ---------------------------------------------------------------- adapter

    private inner class EntryAdapter(
        private val onClick: (CrashLogStore.Entry) -> Unit,
    ) : RecyclerView.Adapter<EntryAdapter.Holder>() {

        private var entries: List<CrashLogStore.Entry> = emptyList()

        fun submit(newEntries: List<CrashLogStore.Entry>) {
            entries = newEntries
            notifyDataSetChanged()
        }

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
            val binding = LayoutCrashLogItemBinding.inflate(
                LayoutInflater.from(parent.context), parent, false
            )
            return Holder(binding)
        }

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val entry = entries[position]
            holder.binding.entryTitle.text = entry.title.ifBlank { entry.file.name }
            holder.binding.entrySubtitle.text = buildString {
                append(DateFormat.format("yyyy-MM-dd HH:mm:ss", Date(entry.timeMillis)))
                append(" · ")
                append(Formatter.formatFileSize(this@CrashLogActivity, entry.sizeBytes))
            }
            holder.binding.root.setOnClickListener { onClick(entry) }
        }

        override fun getItemCount(): Int = entries.size

        inner class Holder(val binding: LayoutCrashLogItemBinding) :
            RecyclerView.ViewHolder(binding.root)
    }

    companion object {
        private const val EXTRA_PATH = "path"
        private const val MAX_DETAIL_CHARS = 512 * 1024
    }
}
