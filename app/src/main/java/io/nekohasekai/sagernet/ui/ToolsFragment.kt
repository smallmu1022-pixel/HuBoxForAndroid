package io.nekohasekai.sagernet.ui

import android.content.Intent
import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.google.android.material.tabs.TabLayoutMediator
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.databinding.LayoutToolsBinding
import io.nekohasekai.sagernet.utils.Palette

class ToolsFragment : ToolbarFragment(R.layout.layout_tools) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        toolbar.setTitle(R.string.menu_tools)
        Palette.applyWindowBackground(view)

        val tools = mutableListOf<NamedFragment>()
        tools.add(NetworkFragment())
        tools.add(BackupFragment())

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

        binding.toolsPager.adapter = ToolsAdapter(tools)

        TabLayoutMediator(binding.toolsTab, binding.toolsPager) { tab, position ->
            tab.text = tools[position].name()
            tab.view.setOnLongClickListener { // clear toast
                true
            }
        }.attach()
    }

    inner class ToolsAdapter(val tools: List<Fragment>) : FragmentStateAdapter(this) {

        override fun getItemCount() = tools.size

        override fun createFragment(position: Int) = tools[position]
    }

}