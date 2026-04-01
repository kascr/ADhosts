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

class HostsFragment : BaseFragment<FragmentHostsBinding>(R.layout.fragment_hosts) {

    private lateinit var adapter: SubscriptionAdapter

    private val dnsServers = mapOf(
        "谷歌 DNS" to Pair("8.8.8.8", "8.8.4.4"),
        "阿里云 DNS" to Pair("223.5.5.5", "223.6.6.6"),
        "腾讯 DNS" to Pair("119.29.29.29", "182.254.116.116"),
        "默认" to Pair("", "")
    )
    private val dnsOptions = dnsServers.keys.toTypedArray()
    private var currentDnsPair: Pair<String, String>? = null

    private val systemHostsPath = "/data/adb/modules/AD_lite/system/etc/hosts"
    private val metaModuleHostsPath = "/data/adb/metamodule/mnt/AD_lite/system/etc/hosts"
    private val realHostsPath = "/system/etc/hosts"
    private val modulesUpdatePath = "/data/adb/modules_update/AD_lite/system/etc/hosts"

    private val subscriptionUpdateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            loadSubscriptions()
        }
    }

    override fun createBinding(view: View): FragmentHostsBinding {
        return FragmentHostsBinding.bind(view)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initConfiguration()
        setupRecyclerView()
        initClickListeners()
        updateStatusUI()
    }

    override fun onStart() {
        super.onStart()
        LocalBroadcastManager.getInstance(requireContext())
            .registerReceiver(subscriptionUpdateReceiver, IntentFilter(ACTION_SUBSCRIPTION_UPDATED))
    }

    override fun onStop() {
        super.onStop()
        LocalBroadcastManager.getInstance(requireContext())
            .unregisterReceiver(subscriptionUpdateReceiver)
    }

    private fun initConfiguration() {
        val sharedPref = activity?.getPreferences(Context.MODE_PRIVATE)
        val selectedDnsName = sharedPref?.getString("selected_dns_name", "默认") ?: "默认"
        currentDnsPair = dnsServers[selectedDnsName]
        binding.dnsValueText.text = selectedDnsName
    }

    private fun setupRecyclerView() {
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        adapter = SubscriptionAdapter(requireContext(), emptyList()) { url ->
            HostsSubscriptionManager.removeSubscription(requireContext(), url)
            loadSubscriptions()
            Toast.makeText(requireContext(), getString(R.string.subscription_deleted), Toast.LENGTH_SHORT).show()
        }
        binding.recyclerView.adapter = adapter
        ItemTouchHelper(SwipeToDeleteCallback()).attachToRecyclerView(binding.recyclerView)
        loadSubscriptions()
    }

    private fun initClickListeners() {
        binding.openButton.setOnClickListener { applyMergedHosts() }
        binding.closeButton.setOnClickListener { restoreDefaultHosts() }
        binding.outlinedButton.setOnClickListener { showAddSubscriptionDialog() }
        binding.updateButton.setOnClickListener { syncSubscriptions() }
        binding.dnsChip.setOnClickListener { showDnsSelectionDialog() }
    }

    private fun updateStatusUI() {
        lifecycleScope.launch(Dispatchers.IO) {
            val enabledRules = Shell.cmd("sh -c \"grep -vE '^[[:space:]]*#|^[[:space:]]*$' /system/etc/hosts | wc -l\"")
                .exec().out.firstOrNull()?.trim()?.toIntOrNull() ?: 0
            val isEnabled = enabledRules > 2

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

    private fun applyMergedHosts() {
        lifecycleScope.launch(Dispatchers.IO) {
            if (Shell.cmd("[ -f \"$modulesUpdatePath\" ]").exec().isSuccess) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "检测到模块升级待生效，请重启设备后再开启拦截", Toast.LENGTH_LONG).show()
                }
                return@launch
            }

            val sourcePath = requireContext().filesDir.absolutePath + "/ADhosts"
            val sourceFile = java.io.File(sourcePath)

            if (!sourceFile.exists()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "尚未下载订阅源，请先点击「更新」", Toast.LENGTH_LONG).show()
                }
                return@launch
            }

            val ruleCount = sourceFile.readLines().count { line ->
                (line.startsWith("0.0.0.0 ") || line.startsWith("127.0.0.1 ")) && !line.contains("localhost")
            }

            if (ruleCount < 3) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "有效规则不足 3 条，无法开启拦截，请先更新订阅源", Toast.LENGTH_LONG).show()
                }
                return@launch
            }

            applyMergedHostsInternal(ruleCount)
        }
    }

    private suspend fun applyMergedHostsInternal(ruleCount: Int) {
        val sourcePath = requireContext().filesDir.absolutePath + "/ADhosts"
        val commands = mutableListOf(
            "dd if=\"$sourcePath\" of=\"$systemHostsPath\"",
            "chmod 644 \"$systemHostsPath\""
        )
        if (Shell.cmd("[ -f \"$metaModuleHostsPath\" ]").exec().isSuccess) {
            commands.add("dd if=\"$sourcePath\" of=\"$metaModuleHostsPath\"")
            commands.add("chmod 644 \"$metaModuleHostsPath\"")
        }

        val result = Shell.cmd(*commands.toTypedArray()).exec()
        if (result.isSuccess) {
            currentDnsPair?.let { applyDnsToSystem(it.first, it.second) }
        }

        val actualRuleCount = if (result.isSuccess) {
            Shell.cmd("sh -c \"grep -vE '^[[:space:]]*#|^[[:space:]]*$' /system/etc/hosts | wc -l\"")
                .exec().out.firstOrNull()?.trim()?.toIntOrNull() ?: 0
        } else 0

        withContext(Dispatchers.Main) {
            if (result.isSuccess) {
                binding.statusText.text = getString(R.string.enabled)
                binding.statusCard.setBackgroundResource(R.drawable.status_card_enabled_bg)
                binding.statusRulesText.visibility = View.VISIBLE
                binding.statusRulesText.text = getString(R.string.rules_enabled, actualRuleCount)
                Toast.makeText(requireContext(), "Hosts 拦截已开启/更新，共 $actualRuleCount 条规则", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(requireContext(), "操作失败：请检查 Root 权限或模块是否安装", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun restoreDefaultHosts() {
        lifecycleScope.launch(Dispatchers.IO) {
            val defaultContent = "127.0.0.1 localhost\n::1 localhost\n"
            val commands = mutableListOf(
                "printf '%s' \"$defaultContent\" > \"$systemHostsPath\"",
                "chmod 644 \"$systemHostsPath\""
            )
            if (Shell.cmd("[ -f \"$metaModuleHostsPath\" ]").exec().isSuccess) {
                commands.add("printf '%s' \"$defaultContent\" > \"$metaModuleHostsPath\"")
                commands.add("chmod 644 \"$metaModuleHostsPath\"")
            }

            val result = Shell.cmd(*commands.toTypedArray()).exec()
            if (result.isSuccess) {
                applyDnsToSystem("", "")
            }

            withContext(Dispatchers.Main) {
                if (result.isSuccess) {
                    binding.statusText.text = getString(R.string.disabled)
                    binding.statusCard.setBackgroundResource(R.drawable.status_card_disabled_bg)
                    binding.statusRulesText.visibility = View.GONE
                    Toast.makeText(requireContext(), "Hosts 拦截已关闭 (已恢复默认)", Toast.LENGTH_SHORT).show()
                } else {
                    Toast.makeText(requireContext(), "操作失败", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun syncSubscriptions() {
        lifecycleScope.launch(Dispatchers.IO) {
            if (Shell.cmd("[ -f \"$modulesUpdatePath\" ]").exec().isSuccess) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "检测到模块升级待生效，请重启设备后再更新", Toast.LENGTH_LONG).show()
                }
                return@launch
            }

            try {
                HostsSubscriptionManager.downloadAndMergeSubscriptions(requireContext())
                withContext(Dispatchers.Main) {
                    loadSubscriptions()
                    applyMergedHosts()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(requireContext(), "更新失败: ${e.message}", Toast.LENGTH_LONG).show()
                    loadSubscriptions()
                }
            }
        }
    }

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
            if (url.matches("^(https?)://[^\\s/\$.?#][^\\s]*$".toRegex())) {
                HostsSubscriptionManager.addSubscription(requireContext(), url)
                loadSubscriptions()
                Toast.makeText(requireContext(), getString(R.string.subscription_added), Toast.LENGTH_SHORT).show()
                syncSubscriptions()
                dialog.dismiss()
            } else {
                Toast.makeText(requireContext(), "请输入有效的 URL 地址", Toast.LENGTH_SHORT).show()
            }
        }
        editText.setOnEditorActionListener { _, _, _ ->
            btnConfirm.performClick()
            true
        }
        dialog.show()
    }

    inner class SwipeToDeleteCallback : ItemTouchHelper.SimpleCallback(0, ItemTouchHelper.LEFT or ItemTouchHelper.RIGHT) {
        private val maxSwipeDp = 100f

        override fun onMove(r: RecyclerView, v: RecyclerView.ViewHolder, t: RecyclerView.ViewHolder) = false

        override fun onSwiped(viewHolder: RecyclerView.ViewHolder, direction: Int) {}

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
                    if (holder.contentCard.translationX >= 0 && dX > 0) translationX = 0f
                    if (translationX < maxSwipe) translationX = maxSwipe
                    holder.contentCard.translationX = translationX
                } else {
                    holder.contentCard.translationX =
                        if (holder.contentCard.translationX < maxSwipe / 2) maxSwipe else 0f
                }
            }
        }

        override fun clearView(recyclerView: RecyclerView, viewHolder: RecyclerView.ViewHolder) {}
    }
}
