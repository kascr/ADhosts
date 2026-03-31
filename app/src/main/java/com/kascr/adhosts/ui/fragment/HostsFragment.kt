package com.kascr.adhosts.ui.fragment

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.Toast
import androidx.lifecycle.lifecycleScope
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.recyclerview.widget.ItemTouchHelper
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.kascr.adhosts.R
import com.kascr.adhosts.data.HostsSubscriptionManager
import com.kascr.adhosts.databinding.FragmentHostsBinding
import com.kascr.adhosts.ui.adapter.SubscriptionAdapter
import com.kascr.adhosts.ui.base.BaseFragment
import com.kascr.adhosts.ui.fragment.SettingsFragment.Companion.ACTION_SUBSCRIPTION_UPDATED
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Hosts 管理 Fragment
 */
class HostsFragment : BaseFragment<FragmentHostsBinding>(R.layout.fragment_hosts) {

    private lateinit var adapter: SubscriptionAdapter

    // 预设的 DNS 服务器列表
    private val dnsServers = mapOf(
        "谷歌 DNS" to Pair("8.8.8.8", "8.8.4.4"),
        "阿里云 DNS" to Pair("223.5.5.5", "223.6.6.6"),
        "腾讯 DNS" to Pair("119.29.29.29", "182.254.116.116"),
        "默认" to Pair("", "")
    )
    private val dnsOptions = dnsServers.keys.toTypedArray()

    // 当前选中的 DNS 组合
    private var currentDnsPair: Pair<String, String>? = null

    // 系统 hosts 路径 (Magisk 模块挂载路径)
    private val systemHostsPath = "/data/adb/modules/AD_lite/system/etc/hosts"
    // MetaModule 路径 (特定环境下的挂载路径)
    private val metaModuleHostsPath = "/data/adb/metamodule/mnt/AD_lite/system/etc/hosts"

    // 监听导入订阅后的刷新广播
    private val subscriptionUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            loadSubscriptions()
            updateStatusUI()
        }
    }

    override fun createBinding(view: View): FragmentHostsBinding {
        return FragmentHostsBinding.bind(view)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        initConfiguration()      // 初始化本地配置
        setupRecyclerView()      // 初始化列表
        initClickListeners()     // 绑定点击事件
        updateStatusUI()         // 刷新当前状态
    }

    override fun onStart() {
        super.onStart()
        // 注册订阅更新广播（来自 SettingsFragment 导入操作）
        val filter = IntentFilter(ACTION_SUBSCRIPTION_UPDATED)
        LocalBroadcastManager.getInstance(requireContext())
            .registerReceiver(subscriptionUpdateReceiver, filter)
    }

    override fun onStop() {
        super.onStop()
        LocalBroadcastManager.getInstance(requireContext())
            .unregisterReceiver(subscriptionUpdateReceiver)
    }

    /**
     * 从 SharedPreferences 加载保存的 DNS 选择
     */
    private fun initConfiguration() {
        val sharedPref = activity?.getPreferences(Context.MODE_PRIVATE)
        val selectedDnsName = sharedPref?.getString("selected_dns_name", "默认") ?: "默认"
        currentDnsPair = dnsServers[selectedDnsName]
        binding.dnsValueText.text = selectedDnsName
    }

    /**
     * 配置订阅列表的 RecyclerView 和滑动删除功能
     */
    private fun setupRecyclerView() {
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        adapter = SubscriptionAdapter(requireContext(), emptyList()) { url ->
            // 执行真正的删除逻辑
            HostsSubscriptionManager.removeSubscription(requireContext(), url)
            loadSubscriptions()
            Toast.makeText(requireContext(), getString(R.string.subscription_deleted), Toast.LENGTH_SHORT).show()
        }
        binding.recyclerView.adapter = adapter

        // 启用向左滑动删除订阅项
        ItemTouchHelper(SwipeToDeleteCallback()).attachToRecyclerView(binding.recyclerView)

        loadSubscriptions()
    }

    /**
     * 绑定界面上所有按钮的点击逻辑
     */
    private fun initClickListeners() {
        // 开启/更新拦截按钮 (逻辑一致：直接将合并后的内容写入系统路径)
        binding.openButton.setOnClickListener { applyMergedHosts() }

        // 关闭拦截按钮 (逻辑：将系统 hosts 恢复为最简状态)
        binding.closeButton.setOnClickListener { restoreDefaultHosts() }

        // 添加新订阅按钮
        binding.outlinedButton.setOnClickListener { showAddSubscriptionDialog() }

        // 同步更新所有订阅按钮
        binding.updateButton.setOnClickListener { syncSubscriptions() }

        // DNS 切换行点击
        binding.dnsChip.setOnClickListener { showDnsSelectionDialog() }
    }

    /**
     * 检查系统 hosts 文件行数并更新 UI 状态
     * 同时统计排除注释后的有效规则行数
     */
    private fun updateStatusUI() {
        lifecycleScope.launch(Dispatchers.IO) {
            val lineCount = Shell.cmd("wc -l < \"$systemHostsPath\"").exec().out.firstOrNull()?.toIntOrNull() ?: 0
            val isEnabled = lineCount > 10

            // 统计排除注释行（以 # 开头）和空行后的有效规则数
            val enabledRules = Shell.cmd(
                "grep -v '^#' \"$systemHostsPath\" | grep -v '^[[:space:]]*\$' | wc -l"
            ).exec().out.firstOrNull()?.trim()?.toIntOrNull() ?: 0

            withContext(Dispatchers.Main) {
                if (isEnabled) {
                    binding.statusText.text = getString(R.string.enabled)
                    binding.statusCard.setBackgroundResource(R.drawable.status_card_enabled_bg)
                    binding.statusRulesText.visibility = View.VISIBLE
                    binding.statusRulesText.text = getString(R.string.rules_enabled, enabledRules)
                } else {
                    binding.statusText.text = getString(R.string.disabled)
                    binding.statusCard.setBackgroundResource(R.drawable.status_card_disabled_bg)
                    binding.statusRulesText.visibility = View.GONE
                }
            }
        }
    }

    /**
     * 应用合并后的 Hosts 内容到系统路径
     */
    private fun applyMergedHosts() {
        lifecycleScope.launch(Dispatchers.IO) {
            val sourcePath = "/data/data/com.kascr.adhosts/files/ADhosts"

            // 准备执行命令列表
            val commands = mutableListOf(
                "dd if=\"$sourcePath\" of=\"$systemHostsPath\"",
                "chmod 644 \"$systemHostsPath\""
            )

            // 检测并同步 MetaModule 路径
            val hasMetaPath = Shell.cmd("[ -f \"$metaModuleHostsPath\" ]").exec().isSuccess
            if (hasMetaPath) {
                commands.add("dd if=\"$sourcePath\" of=\"$metaModuleHostsPath\"")
                commands.add("chmod 644 \"$metaModuleHostsPath\"")
            }

            val result = Shell.cmd(*commands.toTypedArray()).exec()

            if (result.isSuccess) {
                currentDnsPair?.let { applyDnsToSystem(it.first, it.second) }
            }

            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    updateStatusUI()
                    Toast.makeText(requireContext(), "Hosts 拦截已开启/更新", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "操作失败：请检查 Root 权限或模块是否安装", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * 恢复系统 Hosts 为默认最简状态
     */
    private fun restoreDefaultHosts() {
        lifecycleScope.launch(Dispatchers.IO) {
            val defaultContent = "127.0.0.1 localhost\\n::1 localhost\\n"
            val commands = mutableListOf(
                "printf \"$defaultContent\" > \"$systemHostsPath\"",
                "chmod 644 \"$systemHostsPath\""
            )

            // 检测并同步 MetaModule 路径
            val hasMetaPath = Shell.cmd("[ -f \"$metaModuleHostsPath\" ]").exec().isSuccess
            if (hasMetaPath) {
                commands.add("printf \"$defaultContent\" > \"$metaModuleHostsPath\"")
                commands.add("chmod 644 \"$metaModuleHostsPath\"")
            }

            val result = Shell.cmd(*commands.toTypedArray()).exec()

            if (result.isSuccess) {
                applyDnsToSystem("", "") // 清除 DNS
            }

            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    updateStatusUI()
                    Toast.makeText(requireContext(), "Hosts 拦截已关闭 (已恢复默认)", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "操作失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    /**
     * 异步下载并合并所有订阅源
     */
    private fun syncSubscriptions() {
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                HostsSubscriptionManager.downloadAndMergeSubscriptions(requireContext())
                withContext(Dispatchers.Main) {
                    loadSubscriptions()
                    // 同步完成后自动应用到系统
                    applyMergedHosts()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "更新失败: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    /**
     * 通过 setprop 命令将 DNS 应用到系统各个网络接口
     */
    private fun applyDnsToSystem(dns1: String, dns2: String) {
        val props = arrayOf(
            "net.dns1", "net.dns2",
            "net.eth0.dns1", "net.eth0.dns2",
            "net.wlan0.dns1", "net.wlan0.dns2",
            "net.rmnet0.dns1", "net.rmnet0.dns2",
            "net.rmnet_data0.dns1", "net.rmnet_data0.dns2"
        )
        val commands = props.flatMap { listOf("setprop $it $dns1", "setprop $it $dns2") }
        Shell.cmd(*commands.toTypedArray()).exec()
    }

    /**
     * 弹出 DNS 选择对话框并保存用户偏好
     */
    private fun showDnsSelectionDialog() {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.select_dns))
            .setItems(dnsOptions) { dialog, which ->
                val selectedName = dnsOptions[which]
                currentDnsPair = dnsServers[selectedName]

                activity?.getPreferences(Context.MODE_PRIVATE)?.edit()?.apply {
                    putString("selected_dns_name", selectedName)
                    apply()
                }

                binding.dnsValueText.text = selectedName

                lifecycleScope.launch(Dispatchers.IO) {
                    currentDnsPair?.let { applyDnsToSystem(it.first, it.second) }
                }

                Toast.makeText(requireContext(), "DNS 已切换为: $selectedName", Toast.LENGTH_SHORT).show()
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    /**
     * 从管理器加载订阅列表并更新 UI
     */
    private fun loadSubscriptions() {
        val subscriptions = HostsSubscriptionManager.getSubscriptions(requireContext())
        if (subscriptions.isEmpty()) {
            binding.recyclerView.visibility = View.GONE
            binding.emptyText.visibility = View.VISIBLE
        } else {
            binding.recyclerView.visibility = View.VISIBLE
            binding.emptyText.visibility = View.GONE
        }
        adapter.updateData(subscriptions)
    }

    /**
     * 弹出添加订阅的对话框
     */
    private fun showAddSubscriptionDialog() {
        val dialogView = LayoutInflater.from(requireContext()).inflate(R.layout.dialog_add_subscription, null)
        val editText = dialogView.findViewById<com.google.android.material.textfield.TextInputEditText>(R.id.editTextUrl)
        val btnConfirm = dialogView.findViewById<android.widget.Button>(R.id.btnConfirm)
        val btnCancel = dialogView.findViewById<android.widget.Button>(R.id.btnCancel)

        val dialog = android.app.Dialog(requireContext())
        dialog.setContentView(dialogView)
        dialog.window?.apply {
            setBackgroundDrawableResource(android.R.color.transparent)
            val dm = resources.displayMetrics
            val width = (dm.widthPixels * 0.88).toInt()
            setLayout(width, android.view.WindowManager.LayoutParams.WRAP_CONTENT)
        }

        btnCancel.setOnClickListener { dialog.dismiss() }

        btnConfirm.setOnClickListener {
            val url = editText.text.toString().trim()
            if (url.matches("^(https?|ftp|http)://[^\\s/$.?#].\\S*$".toRegex())) {
                HostsSubscriptionManager.addSubscription(requireContext(), url)
                loadSubscriptions()
                Toast.makeText(requireContext(), getString(R.string.subscription_added), Toast.LENGTH_SHORT).show()
                syncSubscriptions()
                dialog.dismiss()
            } else {
                Toast.makeText(requireContext(), "请输入有效的 URL 地址", Toast.LENGTH_SHORT).show()
            }
        }

        // 键盘完成键触发确认
        editText.setOnEditorActionListener { _, _, _ ->
            btnConfirm.performClick()
            true
        }

        dialog.show()
    }

    /**
     * 滑动删除订阅项的回调实现 - 稳定停止版
     */
    inner class SwipeToDeleteCallback : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
        private val maxSwipeDp = 100f

        override fun onMove(r: RecyclerView, v: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {
            // 禁用 onSwiped 的默认行为，防止瞬间回弹
        }

        override fun getSwipeThreshold(viewHolder: RecyclerView.ViewHolder): Float = 1.0f
        override fun getSwipeEscapeVelocity(defaultValue: Float): Float = Float.MAX_VALUE

        override fun onChildDraw(
            c: android.graphics.Canvas,
            recyclerView: RecyclerView,
            viewHolder: RecyclerView.ViewHolder,
            dX: Float,
            dY: Float,
            actionState: Int,
            isCurrentlyActive: Boolean
        ) {
            val holder = viewHolder as SubscriptionAdapter.SubscriptionViewHolder
            val maxSwipe = -(maxSwipeDp * resources.displayMetrics.density)

            if (actionState == ItemTouchHelper.ACTION_STATE_SWIPE) {
                if (isCurrentlyActive) {
                    var translationX = dX
                    if (holder.contentCard.translationX >= 0 && dX > 0) {
                        translationX = 0f
                    }
                    if (translationX < maxSwipe) {
                        translationX = maxSwipe
                    }
                    holder.contentCard.translationX = translationX
                } else {
                    if (holder.contentCard.translationX < maxSwipe / 2) {
                        holder.contentCard.translationX = maxSwipe
                    } else {
                        holder.contentCard.translationX = 0f
                    }
                }
            }
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {
            // 覆盖父类方法，防止 ItemTouchHelper 自动重置 translationX
        }
    }
}
