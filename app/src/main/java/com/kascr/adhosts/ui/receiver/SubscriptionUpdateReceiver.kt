package com.kascr.adhosts.ui.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * 订阅更新广播接收器
 * 用于接收 SettingsFragment 发出的订阅数据变更通知。
 * 实际 UI 刷新逻辑由 HostsFragment 通过 LocalBroadcastManager 监听处理。
 */
class SubscriptionUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // 空实现：HostsFragment 内部通过 LocalBroadcastManager 监听并刷新
    }
}
