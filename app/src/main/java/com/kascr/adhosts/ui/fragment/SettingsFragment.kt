package com.kascr.adhosts.ui.fragment

import android.app.Activity
import android.Manifest
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.net.Uri
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.kascr.adhosts.R
import com.kascr.adhosts.crash.ExpectedExitGuard
import com.kascr.adhosts.data.AppRelease
import com.kascr.adhosts.data.AppUpdateException
import com.kascr.adhosts.data.AppUpdateFailure
import com.kascr.adhosts.data.AppUpdateInstaller
import com.kascr.adhosts.data.GithubAppUpdateManager
import com.kascr.adhosts.data.HostsBackupCodec
import com.kascr.adhosts.data.HostsSubscriptionManager
import com.kascr.adhosts.data.HostsOperationLock
import com.kascr.adhosts.data.ManualHostsRuleManager
import com.kascr.adhosts.data.ScheduledHostsUpdates
import com.kascr.adhosts.databinding.FragmentSettingsBinding
import com.kascr.adhosts.ui.activity.MainActivity
import com.kascr.adhosts.ui.base.BaseFragment
import com.kascr.adhosts.utils.AppBackgroundManager
import com.kascr.adhosts.utils.GlassDialog
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStreamWriter
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class SettingsFragment : BaseFragment<FragmentSettingsBinding>(R.layout.fragment_settings) {

    private val notificationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (!granted && bindingOrNull != null) showToast(R.string.scheduled_update_permission_hint)
    }

    private var crashTestTapCount = 0
    private var firstCrashTestTapAt = 0L

    private val languageOptions by lazy {
        listOf(
            LanguageOption(R.string.language_follow_system, "", null),
            LanguageOption(R.string.language_simplified_chinese, "zh", "简体中文"),
            LanguageOption(R.string.language_english, "en", "English"),
            LanguageOption(R.string.language_japanese, "ja", "日本語"),
            LanguageOption(R.string.language_korean, "ko", "한국어"),
            LanguageOption(R.string.language_german, "de", "Deutsch"),
            LanguageOption(R.string.language_french, "fr", "Français"),
            LanguageOption(R.string.language_spanish, "es", "Español"),
            LanguageOption(R.string.language_portuguese, "pt", "Português"),
            LanguageOption(R.string.language_russian, "ru", "Русский"),
            LanguageOption(R.string.language_vietnamese, "vi", "Tiếng Việt"),
            LanguageOption(R.string.language_indonesian, "id", "Bahasa Indonesia"),
            LanguageOption(R.string.language_thai, "th", "ไทย")
        )
    }

    private val importLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let(::importSubscriptions)
        }
    }

    private val exportLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let(::exportSubscriptions)
        }
    }

    private val backgroundImageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data?.data?.let(::saveCustomBackground)
        } else if (bindingOrNull != null) {
            updateCurrentBackgroundLabel()
        }
    }

    override fun createBinding(view: View): FragmentSettingsBinding {
        return FragmentSettingsBinding.bind(view)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        initContent()
        initClickListeners()
    }

    private fun initContent() {
        val context = context ?: return
        updateCurrentLanguageLabel()
        updateCurrentBackgroundLabel()
        updateScheduledSummary()
        val versionName = context.packageManager
            .getPackageInfo(context.packageName, 0).versionName
        binding.versionText.text = getString(R.string.version_name_label, versionName)
    }

    private fun initClickListeners() {
        binding.languageButton.setOnClickListener { showLanguageDialog() }
        binding.backgroundImageButton.setOnClickListener { showWallpaperDialog() }
        binding.importButton.setOnClickListener { launchImportPicker() }
        binding.exportButton.setOnClickListener { launchExportPicker() }
        binding.qqGroupButton.setOnClickListener { joinQQGroup() }
        binding.githubRepositoryButton.setOnClickListener { openGithubRepository() }
        binding.appUpdateButton.setOnClickListener { checkAppUpdate() }
        binding.scheduledUpdateButton.setOnClickListener { showScheduledUpdateDialog() }
        initCrashTestTrigger()
    }

    private fun updateScheduledSummary() {
        val context = context ?: return
        val label = when {
            !ScheduledHostsUpdates.enabled(context) -> R.string.scheduled_update_off
            ScheduledHostsUpdates.wifiOnly(context) -> R.string.scheduled_update_wifi
            else -> R.string.scheduled_update_daily
        }
        binding.scheduledUpdateSummary.setText(label)
    }

    private fun showScheduledUpdateDialog() {
        val context = requireContext()
        val choices = arrayOf(
            getString(R.string.scheduled_update_off),
            getString(R.string.scheduled_update_daily),
            getString(R.string.scheduled_update_wifi)
        )
        val selected = when {
            !ScheduledHostsUpdates.enabled(context) -> 0
            ScheduledHostsUpdates.wifiOnly(context) -> 2
            else -> 1
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.scheduled_update_title)
            .setSingleChoiceItems(choices, selected) { selectionDialog, index ->
                ScheduledHostsUpdates.configure(context, index != 0, index == 2)
                updateScheduledSummary()
                if (index != 0 && Build.VERSION.SDK_INT >= 33) {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                selectionDialog.dismiss()
            }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        GlassDialog.show(dialog)
    }

    private fun joinQQGroup() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(QQ_GROUP_URI))
        val opened = runCatching {
            startActivity(intent)
            true
        }.getOrDefault(false)

        if (!opened) {
            showToast(R.string.settings_qq_group_unavailable, long = true)
        }
    }

    private fun openGithubRepository() {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse(getString(R.string.settings_github_repository_url)))
        val opened = runCatching {
            startActivity(intent)
            true
        }.getOrDefault(false)

        if (!opened) {
            showToast(R.string.settings_github_unavailable, long = true)
        }
    }

    private fun checkAppUpdate() {
        val context = context ?: return
        val installedVersion = context.packageManager
            .getPackageInfo(context.packageName, 0).versionName.orEmpty()
        binding.appUpdateButton.isEnabled = false
        binding.appUpdateSummary.setText(R.string.app_update_checking)
        viewLifecycleOwner.lifecycleScope.launch {
            val result = runCatching {
                withContext(Dispatchers.IO) { GithubAppUpdateManager.fetchLatestRelease() }
            }
            if (bindingOrNull == null) return@launch
            binding.appUpdateButton.isEnabled = true
            result.fold(
                onSuccess = { release -> showAppUpdateResult(release, installedVersion) },
                onFailure = { error ->
                    if (error is CancellationException) throw error
                    binding.appUpdateSummary.setText(R.string.app_update_check_failed)
                    showToast(getString(R.string.app_update_check_failed), long = true)
                }
            )
        }
    }

    private fun showAppUpdateResult(release: AppRelease?, installedVersion: String) {
        if (release == null) {
            binding.appUpdateSummary.setText(R.string.app_update_no_release)
            showAppUpdateDialog(R.string.app_update_no_release, null)
            return
        }
        val comparison = GithubAppUpdateManager.compareVersions(release.tag, installedVersion)
        val status = when {
            comparison == null -> R.string.app_update_version_unknown
            comparison > 0 -> R.string.app_update_available
            comparison < 0 -> R.string.app_update_local_newer
            else -> R.string.app_update_current
        }
        binding.appUpdateSummary.setText(status)
        val message = buildString {
            append(getString(R.string.app_update_versions, installedVersion, release.tag))
            append("\n\n")
            append(getString(status))
            if (comparison != null && comparison > 0 && release.notes.isNotBlank()) {
                append("\n\n")
                append(release.notes.take(1200))
            }
            if (comparison != null && comparison > 0 && release.apkUrl != null) {
                append("\n\n")
                append(getString(R.string.app_update_root_install_hint))
            }
        }
        showAppUpdateDialog(status, message, release, comparison != null && comparison > 0)
    }

    private fun showAppUpdateDialog(
        title: Int,
        message: String?,
        release: AppRelease? = null,
        updateAvailable: Boolean = false
    ) {
        val builder = MaterialAlertDialogBuilder(requireContext())
            .setTitle(title)
            .setMessage(message)
            .setNegativeButton(R.string.cancel, null)
        if (release != null) {
            val downloadUrl = if (updateAvailable) release.apkUrl else null
            builder.setPositiveButton(
                if (downloadUrl != null) R.string.app_update_download else R.string.app_update_view_release
            ) { _, _ ->
                if (downloadUrl != null) installAppUpdate(release)
                else openAppUpdateUrl(release.pageUrl)
            }
            if (downloadUrl != null) {
                builder.setNeutralButton(R.string.app_update_view_release) { _, _ ->
                    openAppUpdateUrl(release.pageUrl)
                }
            }
        }
        GlassDialog.show(builder.create())
    }

    private fun installAppUpdate(release: AppRelease) {
        val appContext = requireContext().applicationContext
        val viewJob = viewLifecycleOwner.lifecycleScope.coroutineContext[Job]
        val mainHandler = Handler(Looper.getMainLooper())
        binding.appUpdateButton.isEnabled = false
        binding.appUpdateSummary.setText(R.string.app_update_downloading)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    AppUpdateInstaller.requireRoot()
                    val apk = AppUpdateInstaller.createTemporaryApk(appContext)
                    try {
                        GithubAppUpdateManager.downloadApk(release, apk) { percent ->
                            viewJob?.ensureActive()
                            mainHandler.post {
                                bindingOrNull?.appUpdateSummary?.text =
                                    getString(R.string.app_update_download_progress, percent)
                            }
                        }
                        viewJob?.ensureActive()
                        mainHandler.post {
                            bindingOrNull?.appUpdateSummary?.setText(R.string.app_update_verifying)
                        }
                        AppUpdateInstaller.verifyApk(appContext, release, apk)
                        viewJob?.ensureActive()
                        mainHandler.post {
                            bindingOrNull?.appUpdateSummary?.setText(R.string.app_update_installing)
                        }
                        AppUpdateInstaller.installAsRoot(apk)
                    } finally {
                        apk.delete()
                    }
                }
                if (bindingOrNull != null) {
                    binding.appUpdateSummary.setText(R.string.app_update_installed)
                    showToast(R.string.app_update_installed, long = true)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                if (bindingOrNull != null) {
                    val message = when ((error as? AppUpdateException)?.reason) {
                        AppUpdateFailure.ROOT_REQUIRED -> R.string.app_update_root_required
                        AppUpdateFailure.SIGNATURE_MISMATCH -> R.string.app_update_signature_mismatch
                        AppUpdateFailure.VERSION_NOT_NEWER -> R.string.app_update_not_newer
                        AppUpdateFailure.WRONG_PACKAGE,
                        AppUpdateFailure.INVALID_APK,
                        AppUpdateFailure.RELEASE_MISMATCH -> R.string.app_update_invalid_apk
                        AppUpdateFailure.INSTALL_FAILED -> R.string.app_update_install_failed
                        null -> R.string.app_update_download_failed
                    }
                    binding.appUpdateSummary.setText(message)
                    showToast(message, long = true)
                }
            } finally {
                if (bindingOrNull != null) binding.appUpdateButton.isEnabled = true
            }
        }
    }

    private fun openAppUpdateUrl(url: String) {
        val opened = runCatching {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            true
        }.getOrDefault(false)
        if (!opened) showToast(R.string.app_update_open_failed, long = true)
    }

    private fun initCrashTestTrigger() {
        val context = context ?: return
        val isDebuggable = context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
        if (!isDebuggable) return

        binding.versionText.setOnClickListener { handleCrashTestTap() }
    }

    private fun handleCrashTestTap() {
        val now = SystemClock.elapsedRealtime()
        if (firstCrashTestTapAt == 0L || now - firstCrashTestTapAt > CRASH_TEST_TAP_WINDOW_MS) {
            firstCrashTestTapAt = now
            crashTestTapCount = 0
        }

        crashTestTapCount += 1
        val tapsRemaining = CRASH_TEST_REQUIRED_TAPS - crashTestTapCount
        when {
            tapsRemaining <= 0 -> {
                crashTestTapCount = 0
                firstCrashTestTapAt = 0L
                showCrashTestDialog()
            }

            tapsRemaining <= CRASH_TEST_REVEAL_COUNT -> {
                showToast(getString(R.string.crash_test_taps_remaining, tapsRemaining))
            }
        }
    }

    private fun showCrashTestDialog() {
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.crash_test_title)
            .setMessage(R.string.crash_test_message)
            .setNegativeButton(R.string.cancel, null)
            .setPositiveButton(R.string.crash_test_confirm) { _, _ -> triggerTestCrash() }
            .create()
        GlassDialog.show(dialog)
    }

    private fun triggerTestCrash() {
        ExpectedExitGuard.reset()
        Handler(Looper.getMainLooper()).post {
            throw IllegalStateException(MANUAL_CRASH_TEST_MESSAGE)
        }
    }

    private fun showLanguageDialog() {
        val currentTag = currentLanguageTag()
        val checkedIndex = languageOptions.indexOfFirst { it.tag == currentTag }.coerceAtLeast(0)
        val labels = languageOptions.map { option ->
            if (option.tag.isEmpty()) getString(option.labelRes) else option.nativeDisplayName()
        }.toTypedArray()

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.settings_language))
            .setSingleChoiceItems(labels, checkedIndex) { dialog, which ->
                val selectedOption = languageOptions[which]
                if (selectedOption.tag == currentTag) {
                    dialog.dismiss()
                    return@setSingleChoiceItems
                }

                dialog.dismiss()
                AppCompatDelegate.setApplicationLocales(selectedOption.toLocaleList())
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .create()
        GlassDialog.show(dialog)
    }

    private fun updateCurrentLanguageLabel() {
        val currentTag = currentLanguageTag()
        val currentOption = languageOptions.firstOrNull { it.tag == currentTag } ?: languageOptions.first()
        binding.currentLanguageText.setText(currentOption.labelRes)
    }

    private fun currentLanguageTag(): String {
        return AppCompatDelegate.getApplicationLocales()
            .toLanguageTags()
            .split(",")
            .firstOrNull()
            ?.trim()
            .orEmpty()
    }

    private fun launchBackgroundPicker() {
        backgroundImageLauncher.launch(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "image/*"
            }
        )
    }

    private fun showWallpaperDialog() {
        val options = listOf(
            AppBackgroundManager.WallpaperMode.CUSTOM to R.string.settings_wallpaper_option_custom,
            AppBackgroundManager.WallpaperMode.NIGHT_SKY to R.string.settings_wallpaper_option_night_sky,
            AppBackgroundManager.WallpaperMode.MIKU to R.string.settings_wallpaper_option_miku
        )
        val currentMode = AppBackgroundManager.getWallpaperMode(requireContext())
        val checkedIndex = options.indexOfFirst { it.first == currentMode }.coerceAtLeast(0)
        val labels = options.map { getString(it.second) }.toTypedArray()

        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.settings_wallpaper_title)
            .setSingleChoiceItems(labels, checkedIndex) { selectionDialog, which ->
                when (options[which].first) {
                    AppBackgroundManager.WallpaperMode.CUSTOM -> {
                        selectionDialog.dismiss()
                        launchBackgroundPicker()
                    }

                    else -> {
                        selectionDialog.dismiss()
                        applyBundledWallpaper(options[which].first)
                    }
                }
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .create()
        GlassDialog.show(dialog)
    }

    private fun saveCustomBackground(uri: Uri) {
        val context = context ?: return
        val appContext = context.applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    AppBackgroundManager.saveCustomBackground(appContext, uri)
                }
                if (bindingOrNull == null) return@launch
                updateCurrentBackgroundLabel()
                (activity as? MainActivity)?.onCustomBackgroundPreferenceChanged()
                showToast(R.string.settings_background_applied)
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                showToast(getString(R.string.settings_background_failed, error.message.orEmpty()), long = true)
            }
        }
    }

    private fun applyBundledWallpaper(mode: AppBackgroundManager.WallpaperMode) {
        val context = context ?: return
        AppBackgroundManager.selectBundledWallpaper(context, mode)
        if (bindingOrNull == null) return
        updateCurrentBackgroundLabel()
        (activity as? MainActivity)?.onCustomBackgroundPreferenceChanged()
        showToast(R.string.settings_background_applied)
    }

    private fun updateCurrentBackgroundLabel() {
        val context = context ?: return
        val labelRes = when (AppBackgroundManager.getWallpaperMode(context)) {
            AppBackgroundManager.WallpaperMode.CUSTOM -> R.string.settings_background_custom
            AppBackgroundManager.WallpaperMode.NIGHT_SKY -> R.string.settings_background_night_sky
            AppBackgroundManager.WallpaperMode.MIKU -> R.string.settings_background_miku
        }
        binding.currentBackgroundText.setText(labelRes)
    }

    private fun launchImportPicker() {
        importLauncher.launch(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("application/json", "text/plain", "*/*"))
            }
        )
    }

    private fun importSubscriptions(uri: Uri) {
        val context = context ?: return
        val appContext = context.applicationContext
        val tooLargeMessage = getString(
            R.string.settings_import_too_large,
            MAX_IMPORT_BYTES / (1024 * 1024)
        )
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val counts = withContext(Dispatchers.IO) {
                    val json = appContext.contentResolver.openInputStream(uri)?.use { input ->
                        readUtf8TextWithLimit(input, tooLargeMessage)
                    } ?: error(getString(R.string.settings_import_open_failed))

                    val backup = HostsBackupCodec.decode(json)
                    val subscriptions = backup.subscriptions
                        .filter { subscription -> subscription.url.matches(URL_REGEX) }
                    if (subscriptions.isEmpty() && backup.manualRules.isNullOrEmpty()) {
                        error(getString(R.string.settings_import_no_valid_url))
                    }

                    HostsOperationLock.mutex.withLock {
                        val previousSubscriptions = HostsSubscriptionManager.getSubscriptions(appContext)
                        val previousManualRules = backup.manualRules?.let {
                            ManualHostsRuleManager.getRules(appContext)
                        }
                        val previousMergedSnapshot =
                            HostsSubscriptionManager.createMergedHostsSnapshot(appContext)
                        try {
                            HostsSubscriptionManager.saveSubscriptions(appContext, subscriptions)
                            backup.manualRules?.let { ManualHostsRuleManager.saveRules(appContext, it) }
                            // Never apply a merged file built from the previous configuration.
                            HostsSubscriptionManager.clearMergedHostsCache(appContext)
                        } catch (error: Exception) {
                            runCatching {
                                HostsSubscriptionManager.saveSubscriptions(
                                    appContext,
                                    previousSubscriptions
                                )
                            }
                            previousManualRules?.let { oldRules ->
                                runCatching { ManualHostsRuleManager.saveRules(appContext, oldRules) }
                            }
                            runCatching {
                                HostsSubscriptionManager.restoreMergedHostsCache(
                                    appContext,
                                    previousMergedSnapshot
                                )
                            }
                            throw error
                        } finally {
                            previousMergedSnapshot?.delete()
                        }
                        subscriptions.size to (
                            backup.manualRules?.size ?: ManualHostsRuleManager.getRules(appContext).size
                        )
                    }
                }
                if (bindingOrNull == null) return@launch
                notifyHostsFragmentRefresh()
                showToast(getString(R.string.settings_import_success, counts.first, counts.second))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                showToast(getString(R.string.settings_import_failed, error.message.orEmpty()), long = true)
            }
        }
    }

    private fun readUtf8TextWithLimit(input: InputStream, tooLargeMessage: String): String {
        val output = ByteArrayOutputStream()
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        var totalBytes = 0L
        while (true) {
            val read = input.read(buffer)
            if (read == -1) break
            totalBytes += read
            if (totalBytes > MAX_IMPORT_BYTES) throw IOException(tooLargeMessage)
            output.write(buffer, 0, read)
        }
        return output.toString(Charsets.UTF_8.name())
    }

    private fun notifyHostsFragmentRefresh() {
        parentFragmentManager.setFragmentResult(ACTION_SUBSCRIPTION_UPDATED, Bundle.EMPTY)
    }

    private fun launchExportPicker() {
        exportLauncher.launch(
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE)
                type = "application/json"
                putExtra(Intent.EXTRA_TITLE, "adhosts_rules_backup.json")
            }
        )
    }

    private fun exportSubscriptions(uri: Uri) {
        val context = context ?: return
        val appContext = context.applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val counts = withContext(Dispatchers.IO) {
                    val subscriptions = HostsSubscriptionManager.getSubscriptions(appContext)
                    val manualRules = ManualHostsRuleManager.getRules(appContext)
                    if (subscriptions.isEmpty() && manualRules.isEmpty()) {
                        error(getString(R.string.settings_export_empty))
                    }

                    appContext.contentResolver.openOutputStream(uri)?.use { output ->
                        OutputStreamWriter(output, Charsets.UTF_8).use { writer ->
                            writer.write(HostsBackupCodec.encode(subscriptions, manualRules))
                        }
                    } ?: error(getString(R.string.settings_export_create_failed))

                    subscriptions.size to manualRules.size
                }
                if (bindingOrNull == null) return@launch
                showToast(getString(R.string.settings_export_success, counts.first, counts.second))
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                val message = error.message.orEmpty()
                val isEmptyExport = message == getString(R.string.settings_export_empty)
                showToast(
                    if (isEmptyExport) message else getString(R.string.settings_export_failed, message),
                    long = !isEmptyExport
                )
            }
        }
    }

    private fun showToast(messageRes: Int, long: Boolean = false) {
        showToast(getString(messageRes), long)
    }

    private fun showToast(message: String, long: Boolean = false) {
        val context = context ?: return
        Toast.makeText(
            context,
            message,
            if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        ).show()
    }

    private data class LanguageOption(
        val labelRes: Int,
        val tag: String,
        val nativeName: String?
    ) {
        fun nativeDisplayName(): String {
            val locale = Locale.forLanguageTag(tag)
            return locale.getDisplayLanguage(locale)
        }

        fun toLocaleList(): LocaleListCompat {
            return if (tag.isEmpty()) {
                LocaleListCompat.getEmptyLocaleList()
            } else {
                LocaleListCompat.forLanguageTags(tag)
            }
        }
    }

    companion object {
        const val ACTION_SUBSCRIPTION_UPDATED = "com.kascr.adhosts.SUBSCRIPTION_UPDATED"
        private const val CRASH_TEST_REQUIRED_TAPS = 7
        private const val CRASH_TEST_REVEAL_COUNT = 3
        private const val CRASH_TEST_TAP_WINDOW_MS = 5_000L
        private const val MANUAL_CRASH_TEST_MESSAGE = "Manual crash test from Settings"
        private const val MAX_IMPORT_BYTES = 2L * 1024 * 1024
        private const val QQ_GROUP_URI =
            "mqqopensdkapi://bizAgent/qm/qr?url=http%3A%2F%2Fqm.qq.com%2Fcgi-bin%2Fqm%2Fqr%3Ffrom%3Dapp%26p%3Dandroid%26jump_from%3Dwebapi%26k%3DCKq_oAB1qvAMWEwr98XEfuojKfLvt-i1"
        private val URL_REGEX = "^https://[^\\s/\$.?#][^\\s]*$".toRegex(RegexOption.IGNORE_CASE)
    }
}
