package com.kascr.adhosts.ui.fragment

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.kascr.adhosts.R
import com.kascr.adhosts.data.HostsSubscriptionManager
import com.kascr.adhosts.databinding.FragmentSettingsBinding
import com.kascr.adhosts.ui.base.BaseFragment
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter

/**
 * 设置 Fragment
 * 提供：切换语言、导入订阅配置、导出订阅配置（备份）
 */
class SettingsFragment : BaseFragment<FragmentSettingsBinding>(R.layout.fragment_settings) {

    // 语言选项：显示名称 -> BCP-47 标签（空字符串=跟随系统）
    private val languages = arrayOf("跟随系统", "简体中文", "English", "日本語")
    private val languageTags = arrayOf("", "zh", "en", "ja")

    // 导入文件选择器（JSON）
    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri -> importSubscriptions(uri) }
        }
    }

    // 导出文件选择器（JSON）
    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let { uri -> exportSubscriptions(uri) }
        }
    }

    override fun createBinding(view: View): FragmentSettingsBinding {
        return FragmentSettingsBinding.bind(view)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        updateCurrentLanguageLabel()
        initClickListeners()
        // 动态设置版本号
        val versionName = requireContext().packageManager
            .getPackageInfo(requireContext().packageName, 0).versionName
        binding.versionText.text = "v$versionName"
    }

    private fun initClickListeners() {
        // 切换语言
        binding.languageButton.setOnClickListener { showLanguageDialog() }
        // 导入订阅配置
        binding.importButton.setOnClickListener { launchImportPicker() }
        // 导出订阅配置
        binding.exportButton.setOnClickListener { launchExportPicker() }
    }

    // ─────────────────────────── 语言切换 ───────────────────────────

    private fun showLanguageDialog() {
        val currentTag = AppCompatDelegate.getApplicationLocales()
            .toLanguageTags().split(",").firstOrNull()?.trim() ?: ""
        val checkedIndex = languageTags.indexOfFirst { it == currentTag }.coerceAtLeast(0)

        MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.settings_language))
            .setSingleChoiceItems(languages, checkedIndex) { dialog, which ->
                val tag = languageTags[which]
                val localeList = if (tag.isEmpty()) {
                    LocaleListCompat.getEmptyLocaleList()
                } else {
                    LocaleListCompat.forLanguageTags(tag)
                }
                dialog.dismiss()
                AppCompatDelegate.setApplicationLocales(localeList)
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .show()
    }

    private fun updateCurrentLanguageLabel() {
        val currentTag = AppCompatDelegate.getApplicationLocales()
            .toLanguageTags().split(",").firstOrNull()?.trim() ?: ""
        val idx = languageTags.indexOfFirst { it == currentTag }.coerceAtLeast(0)
        binding.currentLanguageText.text = languages[idx]
    }

    // ─────────────────────────── 导入 ───────────────────────────

    private fun launchImportPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            // 同时接受 .json 和通用类型
            putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "*/*"))
        }
        importLauncher.launch(intent)
    }

    private fun importSubscriptions(uri: Uri) {
        try {
            val inputStream = requireContext().contentResolver.openInputStream(uri)
                ?: throw Exception("无法打开文件")
            val json = BufferedReader(InputStreamReader(inputStream)).use { it.readText() }
            inputStream.close()

            // 解析并覆盖写入
            val gson = com.google.gson.Gson()
            val type = object : com.google.gson.reflect.TypeToken<List<com.kascr.adhosts.data.Subscription>>() {}.type
            val imported: List<com.kascr.adhosts.data.Subscription> = gson.fromJson(json, type)
                ?: throw Exception("文件格式无效")

            HostsSubscriptionManager.saveSubscriptions(requireContext(), imported)

            Toast.makeText(
                requireContext(),
                getString(R.string.settings_import_success, imported.size),
                Toast.LENGTH_SHORT
            ).show()

            // 通知 HostsFragment 刷新列表
            notifyHostsFragmentRefresh()

        } catch (e: Exception) {
            Toast.makeText(
                requireContext(),
                getString(R.string.settings_import_failed, e.message ?: ""),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    /**
     * 通过 Activity 广播通知 HostsFragment 刷新订阅列表
     */
    private fun notifyHostsFragmentRefresh() {
        val intent = Intent(ACTION_SUBSCRIPTION_UPDATED)
        LocalBroadcastManager.getInstance(requireContext()).sendBroadcast(intent)
    }

    // ─────────────────────────── 导出 ───────────────────────────

    private fun launchExportPicker() {
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/json"
            putExtra(Intent.EXTRA_TITLE, "adhosts_subscriptions_backup.json")
        }
        exportLauncher.launch(intent)
    }

    private fun exportSubscriptions(uri: Uri) {
        try {
            val subscriptions = HostsSubscriptionManager.getSubscriptions(requireContext())
            if (subscriptions.isEmpty()) {
                Toast.makeText(requireContext(), getString(R.string.settings_export_empty), Toast.LENGTH_SHORT).show()
                return
            }
            val json = com.google.gson.Gson().toJson(subscriptions)
            val outputStream = requireContext().contentResolver.openOutputStream(uri)
                ?: throw Exception("无法创建文件")
            OutputStreamWriter(outputStream).use { it.write(json) }
            outputStream.close()

            Toast.makeText(
                requireContext(),
                getString(R.string.settings_export_success, subscriptions.size),
                Toast.LENGTH_SHORT
            ).show()
        } catch (e: Exception) {
            Toast.makeText(
                requireContext(),
                getString(R.string.settings_export_failed, e.message ?: ""),
                Toast.LENGTH_LONG
            ).show()
        }
    }

    companion object {
        const val ACTION_SUBSCRIPTION_UPDATED = "com.kascr.adhosts.SUBSCRIPTION_UPDATED"
    }
}
