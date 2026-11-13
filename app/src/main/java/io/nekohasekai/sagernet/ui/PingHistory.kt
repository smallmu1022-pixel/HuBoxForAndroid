package io.nekohasekai.sagernet.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.dp2px
import io.nekohasekai.sagernet.utils.Palette

/**
 * HuBox v1.1.0: per-node latency history.
 *
 * Every URL test result is appended to a small ring buffer (last [MAX_POINTS]
 * samples, stored as "timeMs:ping" pairs in SharedPreferences). Tapping a
 * node's latency opens a dialog with a sparkline chart and summary stats.
 */
object PingHistory {

    private const val PREFS = "ping_history"
    private const val KEY_PREFIX = "profile_"
    private const val MAX_POINTS = 30

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun record(context: Context, profileId: Long, ping: Int) {
        if (profileId <= 0 || ping <= 0) return
        val key = KEY_PREFIX + profileId
        val p = prefs(context)
        val existing = p.getString(key, null)
        val samples = parse(existing).toMutableList()
        samples.add(System.currentTimeMillis() to ping)
        val trimmed = samples.takeLast(MAX_POINTS)
        p.edit()
            .putString(key, trimmed.joinToString(";") { "${it.first}:${it.second}" })
            .apply()
    }

    /** Samples as (timestampMs, pingMs), oldest first. */
    fun samples(context: Context, profileId: Long): List<Pair<Long, Int>> =
        parse(prefs(context).getString(KEY_PREFIX + profileId, null))

    private fun parse(raw: String?): List<Pair<Long, Int>> {
        if (raw.isNullOrBlank()) return emptyList()
        return raw.split(';').mapNotNull { part ->
            val pieces = part.split(':')
            if (pieces.size != 2) return@mapNotNull null
            val time = pieces[0].toLongOrNull() ?: return@mapNotNull null
            val ping = pieces[1].toIntOrNull() ?: return@mapNotNull null
            time to ping
        }
    }

    fun showDialog(context: Context, profileId: Long, profileName: String) {
        val samples = samples(context, profileId)
        val container = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp2px(20), dp2px(6), dp2px(20), dp2px(2))
        }
        if (samples.isEmpty()) {
            val empty = TextView(context).apply {
                text = context.getString(R.string.ping_history_empty)
                textSize = 14f
            }
            container.addView(empty)
        } else {
            val chart = PingChartView(context).apply {
                setData(samples.map { it.second }, Palette.primary(context))
            }
            container.addView(
                chart,
                LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    dp2px(96),
                ),
            )
            val pings = samples.map { it.second }
            val stats = TextView(context).apply {
                text = context.getString(
                    R.string.ping_history_stats,
                    pings.last(),
                    pings.average().toInt(),
                    pings.minOrNull() ?: 0,
                    pings.maxOrNull() ?: 0,
                )
                textSize = 13f
                setPadding(0, dp2px(10), 0, 0)
            }
            container.addView(stats)
        }
        MaterialAlertDialogBuilder(context)
            .setTitle("$profileName · ${context.getString(R.string.ping_history_title)}")
            .setView(container)
            .setPositiveButton(android.R.string.ok, null)
            .show()
    }
}

/** Minimal sparkline: polyline + soft fill, scaled to the sample range. */
class PingChartView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {

    private var pings: List<Int> = emptyList()
    private var strokeColor: Int = 0xFF1668E3.toInt()

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = 2f * resources.displayMetrics.density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }

    fun setData(values: List<Int>, color: Int) {
        pings = values
        strokeColor = color
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (pings.isEmpty()) return
        val pad = 6f * resources.displayMetrics.density
        val w = width - pad * 2
        val h = height - pad * 2
        if (w <= 0 || h <= 0) return

        var min = pings.minOrNull() ?: 0
        var max = pings.maxOrNull() ?: 0
        if (max - min < 20) {
            val mid = (max + min) / 2
            min = (mid - 10).coerceAtLeast(0)
            max = mid + 10
        }
        val range = (max - min).toFloat().coerceAtLeast(1f)

        fun xAt(index: Int): Float =
            if (pings.size == 1) pad + w / 2 else pad + w * index / (pings.size - 1)

        fun yAt(ping: Int): Float = pad + h * (1f - (ping - min) / range)

        val line = Path()
        pings.forEachIndexed { index, ping ->
            val x = xAt(index)
            val y = yAt(ping)
            if (index == 0) line.moveTo(x, y) else line.lineTo(x, y)
        }

        val fill = Path(line).apply {
            lineTo(xAt(pings.lastIndex), pad + h)
            lineTo(xAt(0), pad + h)
            close()
        }
        fillPaint.color = (strokeColor and 0x00FFFFFF) or 0x26000000
        canvas.drawPath(fill, fillPaint)

        linePaint.color = strokeColor
        canvas.drawPath(line, linePaint)

        dotPaint.color = strokeColor
        val lastX = xAt(pings.lastIndex)
        val lastY = yAt(pings.last())
        canvas.drawCircle(lastX, lastY, 3f * resources.displayMetrics.density, dotPaint)
    }
}
