package com.rayneo.visionclaw.ui

import androidx.fragment.app.Fragment
import androidx.fragment.app.FragmentActivity
import androidx.viewpager2.adapter.FragmentStateAdapter
import com.rayneo.visionclaw.ui.panels.chat.ChatPanelFragment

class MainPagerAdapter(
    activity: FragmentActivity,
    private val chatFragment: ChatPanelFragment
) : FragmentStateAdapter(activity) {

    override fun getItemCount(): Int = 1

    override fun createFragment(position: Int): Fragment = chatFragment

    fun getFragment(position: Int): Fragment = chatFragment
}
