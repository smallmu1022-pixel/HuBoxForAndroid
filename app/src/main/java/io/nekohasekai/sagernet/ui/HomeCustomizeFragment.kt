package io.nekohasekai.sagernet.ui

import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.widget.SwitchCompat
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.ktx.dp2px

/**
 * HuBox v1.1.0 "Customize home" page.
 *
 * The connect button, node card and mode segmented control are fixed; the
 * optional modules can be toggled and reordered here. Reached from Settings
 * or by long-pressing a module on the home page.
 */
class HomeCustomizeFragment : ToolbarFragment(R.layout.layout_home_customize) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        io.nekohasekai.sagernet.utils.Palette.applyWindowBackground(view)
        toolbar?.setTitle(R.string.home_customize_title)
        rebuild()
    }

    private fun rebuild() {
        val root = view ?: return
        val container = root.findViewById<LinearLayout>(R.id.customize_container)
        container.removeAllViews()
        val ctx = requireContext()

        val modules = HomeModules.orderedModules(ctx)
        modules.forEachIndexed { index, module ->
            container.addView(moduleRow(module.id, getString(module.titleRes), index, modules.size))
        }
    }

    private fun baseRow(): LinearLayout {
        val ctx = requireContext()
        return LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.bg_glass_card_small)
            setPadding(dp2px(16), dp2px(12), dp2px(10), dp2px(12))
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT,
            ).apply { bottomMargin = dp2px(10) }
        }
    }

    private fun moduleRow(id: String, title: String, index: Int, count: Int): View {
        val ctx = requireContext()
        val row = baseRow()

        val label = TextView(ctx).apply {
            text = title
            textSize = 15f
        }
        row.addView(label, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        val up = ImageButton(ctx).apply {
            setImageResource(android.R.drawable.arrow_up_float)
            contentDescription = getString(R.string.home_move_up)
            background = null
            isEnabled = index > 0
            alpha = if (index > 0) 1f else 0.3f
            setOnClickListener {
                HomeModules.move(ctx, id, -1)
                rebuild()
            }
        }
        row.addView(up, LinearLayout.LayoutParams(dp2px(38), dp2px(38)))

        val down = ImageButton(ctx).apply {
            setImageResource(android.R.drawable.arrow_down_float)
            contentDescription = getString(R.string.home_move_down)
            background = null
            isEnabled = index < count - 1
            alpha = if (index < count - 1) 1f else 0.3f
            setOnClickListener {
                HomeModules.move(ctx, id, +1)
                rebuild()
            }
        }
        row.addView(down, LinearLayout.LayoutParams(dp2px(38), dp2px(38)))

        val toggle = SwitchCompat(ctx).apply {
            isChecked = HomeModules.isEnabled(ctx, id)
            setOnCheckedChangeListener { _, checked ->
                HomeModules.setEnabled(ctx, id, checked)
            }
        }
        row.addView(toggle)
        return row
    }
}
