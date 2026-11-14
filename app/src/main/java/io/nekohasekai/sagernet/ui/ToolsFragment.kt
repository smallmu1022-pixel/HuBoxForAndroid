package io.nekohasekai.sagernet.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.databinding.LayoutToolsBinding
import io.nekohasekai.sagernet.utils.Palette

class ToolsFragment : ToolbarFragment(R.layout.layout_tools) {

    // HuBox v1.1.8: the network / backup tab switcher became two
    // expandable drawer cards (accordion: opening one closes the other).
    // The two pages stay real fragments, hosted as child fragments inside
    // the drawers, so all of their wiring is unchanged.
    private var networkOpen = false
    private var backupOpen = false

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar.setTitle(R.string.menu_tools)
        Palette.applyWindowBackground(view)

        val binding = LayoutToolsBinding.bind(view)

        // HuBox v1.1.0 quick entries grid
        val mainActivity = activity as? MainActivity
        binding.toolRoute.setOnClickListener {
            mainActivity?.displayFragmentWithId(R.id.nav_route)
        }
        binding.toolDiagnostics.setOnClickListener {
            mainActivity?.displayFragmentWithId(R.id.nav_diagnostics)
        }
        binding.toolDashboard.setOnClickListener {
            mainActivity?.displayFragmentWithId(R.id.nav_dashboard)
        }
        binding.toolApps.setOnClickListener {
            startActivity(Intent(requireContext(), AppManagerActivity::class.java))
        }
        binding.toolTraffic.setOnClickListener {
            startActivity(Intent(requireContext(), AppTrafficActivity::class.java))
        }
        binding.toolLog.setOnClickListener {
            mainActivity?.displayFragmentWithId(R.id.nav_logcat)
        }

        if (childFragmentManager.findFragmentById(R.id.container_network) == null) {
            childFragmentManager.beginTransaction()
                .add(R.id.container_network, NetworkFragment())
                .commit()
        }
        if (childFragmentManager.findFragmentById(R.id.container_backup) == null) {
            childFragmentManager.beginTransaction()
                .add(R.id.container_backup, BackupFragment())
                .commit()
        }

        binding.headerNetwork.setOnClickListener {
            networkOpen = !networkOpen
            if (networkOpen) {
                expandDrawer(binding.containerNetwork, binding.chevronNetwork)
                if (backupOpen) {
                    backupOpen = false
                    collapseDrawer(binding.containerBackup, binding.chevronBackup)
                }
            } else {
                collapseDrawer(binding.containerNetwork, binding.chevronNetwork)
            }
        }
        binding.headerBackup.setOnClickListener {
            backupOpen = !backupOpen
            if (backupOpen) {
                expandDrawer(binding.containerBackup, binding.chevronBackup)
                if (networkOpen) {
                    networkOpen = false
                    collapseDrawer(binding.containerNetwork, binding.chevronNetwork)
                }
            } else {
                collapseDrawer(binding.containerBackup, binding.chevronBackup)
            }
        }
    }

    private fun expandDrawer(container: View, chevron: View) {
        // The hosted pages are ScrollViews of their own; inside the page's
        // NestedScrollView they must give up nested scrolling so they take
        // their full content height and the page scrolls as one.
        (container as? ViewGroup)?.getChildAt(0)?.isNestedScrollingEnabled = false
        container.visibility = View.VISIBLE
        val parentWidth = (container.parent as View).width
        container.measure(
            View.MeasureSpec.makeMeasureSpec(parentWidth, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val target = container.measuredHeight
        container.layoutParams.height = 0
        ValueAnimator.ofInt(0, target).apply {
            duration = 280
            addUpdateListener { animator ->
                container.layoutParams.height = animator.animatedValue as Int
                container.requestLayout()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    container.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    container.requestLayout()
                }
            })
            start()
        }
        chevron.animate().rotation(180f).setDuration(280).start()
    }

    private fun collapseDrawer(container: View, chevron: View) {
        val start = container.height
        if (start <= 0) {
            container.visibility = View.GONE
            chevron.animate().rotation(0f).setDuration(200).start()
            return
        }
        ValueAnimator.ofInt(start, 0).apply {
            duration = 240
            addUpdateListener { animator ->
                container.layoutParams.height = animator.animatedValue as Int
                container.requestLayout()
            }
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationEnd(animation: Animator) {
                    container.visibility = View.GONE
                    container.layoutParams.height = ViewGroup.LayoutParams.WRAP_CONTENT
                    container.requestLayout()
                }
            })
            start()
        }
        chevron.animate().rotation(0f).setDuration(240).start()
    }

}
