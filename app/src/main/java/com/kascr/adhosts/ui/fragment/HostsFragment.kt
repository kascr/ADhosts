package com.kascr.adhosts.ui.fragment

import android.annotation.SuppressLint
import android.app.Dialog
import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import com.google.android.material.button.MaterialButton
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.kascr.adhosts.data.HostsOperationJournal
import com.kascr.adhosts.data.HostsOperationLock
import com.kascr.adhosts.data.HostsSubscriptionManager
import com.kascr.adhosts.data.HostsUpdateManager
import com.kascr.adhosts.data.ManualHostsEditor
import com.kascr.adhosts.data.ManualHostsRuleManager
import com.kascr.adhosts.data.RecommendedHosts
import com.kascr.adhosts.data.RootEnvironment.quote as shellQuote
import com.kascr.adhosts.data.RootHostsStore
import com.kascr.adhosts.data.UpdatePreview
import com.kascr.adhosts.databinding.FragmentHostsBinding
import com.kascr.adhosts.R
import com.kascr.adhosts.ui.adapter.SubscriptionAdapter
import com.kascr.adhosts.ui.base.BaseFragment
import com.kascr.adhosts.ui.dialog.DomainQueryDialog
import com.kascr.adhosts.ui.fragment.SettingsFragment.Companion.ACTION_SUBSCRIPTION_UPDATED
import com.kascr.adhosts.utils.GlassDialog
import com.topjohnwu.superuser.Shell
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class HostsFragment : BaseFragment<FragmentHostsBinding>(R.layout.fragment_hosts) {

    private lateinit var adapter: SubscriptionAdapter
    private var isStatusCheckRunning = false
    private var lastRenderedStatus: HostsStatus? = null
    private var isManualEditorCheckRunning = false
    private val dnsOptions by lazy {
        listOf(
            DnsOption("google", getString(R.string.dns_google), listOf("8.8.8.8", "8.8.4.4")),
            DnsOption("aliyun", getString(R.string.dns_aliyun), listOf("223.5.5.5", "223.6.6.6")),
            DnsOption("tencent", getString(R.string.dns_tencent), listOf("119.29.29.29", "182.254.116.116")),
            DnsOption("default", getString(R.string.restore_default), emptyList())
        )
    }
    private val dnsOptionLabels by lazy { dnsOptions.map { it.label }.toTypedArray() }
    private val defaultDnsOption by lazy { dnsOptions.last() }
    private var currentDnsOption: DnsOption? = null
    private val recommendedSources by lazy {
        listOf(
            RecommendedSource(
                name = getString(R.string.recommended_autumn_name),
                summary = getString(R.string.recommended_autumn_summary),
                subscriptionUrl = RecommendedHosts.AUTUMN_HOSTS_URL,
                websiteUrl = RecommendedHosts.AUTUMN_WEBSITE_URL
            ),
            RecommendedSource(
                name = getString(R.string.recommended_github520_name),
                summary = getString(R.string.recommended_github520_summary),
                subscriptionUrl = RecommendedHosts.GITHUB520_HOSTS_URL,
                websiteUrl = RecommendedHosts.GITHUB520_WEBSITE_URL
            )
        )
    }

    override fun createBinding(view: View): FragmentHostsBinding {
        return FragmentHostsBinding.bind(view)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        isStatusCheckRunning = false
        lastRenderedStatus = null
        binding.toolsPanel.visibility = if (savedInstanceState?.getBoolean("hosts_tools_expanded") == true)
            View.VISIBLE else View.GONE
        binding.toolsButton.isSelected = binding.toolsPanel.visibility == View.VISIBLE
        initDnsConfiguration()
        initRecyclerView()
        initClickListeners()
        observeSubscriptionUpdates()
        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                ManualHostsEditor.savedEvents.collect { checkReturnedManualRules() }
            }
        }
        renderHostsStatus(HostsStatus.Checking)
        updateStatusUI()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean("hosts_tools_expanded", bindingOrNull?.toolsPanel?.visibility == View.VISIBLE)
    }

    override fun onResume() {
        super.onResume()
        if (bindingOrNull == null) return
        loadSubscriptions()
        refreshManualRulesSummary()
        refreshPendingPreview()
        binding.restorePreviousButton.isEnabled = HostsUpdateManager.hasPrevious(requireContext())
        updateStatusUI()
        recoverInterruptedRootWrite()
        if (HostsOperationLock.mutex.isLocked) {
            setHostsActionsEnabled(false)
            viewLifecycleOwner.lifecycleScope.launch {
                HostsOperationLock.mutex.withLock { }
                if (bindingOrNull == null) return@launch
                setHostsActionsEnabled(true)
                loadSubscriptions()
                refreshManualRulesSummary()
                refreshPendingPreview()
                updateStatusUI()
                checkReturnedManualRules()
            }
        }
        checkReturnedManualRules()
    }

    private fun recoverInterruptedRootWrite() {
        HostsOperationLock.scope.launch(Dispatchers.IO) {
            HostsOperationLock.mutex.withLock {
                val pending = runCatching {
                    Shell.cmd("test -d /data/adb/.adhosts-hosts-journal").exec().isSuccess
                }.onFailure { Log.w("HostsFragment", "Could not inspect Root Hosts journal", it) }
                    .getOrDefault(false)
                if (!pending) return@withLock
                val recovered = runCatching { writeModuleHosts("") }
                    .onFailure { Log.e("HostsFragment", "Could not recover Root Hosts files", it) }
                    .getOrDefault(false)
                if (recovered) {
                    withContext(Dispatchers.Main) {
                        if (bindingOrNull != null) updateStatusUI()
                    }
                } else withContext(Dispatchers.Main) {
                    if (bindingOrNull != null) showToast(R.string.hosts_operation_failed_root_module)
                }
            }
        }
    }

    private fun initDnsConfiguration() {
        val context = requireContext()
        val sharedPreferences = dnsPreferences(context)
        val legacyValue = activity?.getPreferences(Context.MODE_PRIVATE)
            ?.getString(KEY_SELECTED_DNS_ID, null)
        val storedValue = sharedPreferences.getString(KEY_SELECTED_DNS_ID, legacyValue)
            ?: defaultDnsOption.id

        currentDnsOption = dnsOptions.firstOrNull { it.id == storedValue }
            ?: dnsOptions.firstOrNull { it.label == storedValue }
            ?: defaultDnsOption
        if (currentDnsOption?.id != storedValue) {
            sharedPreferences.edit().putString(KEY_SELECTED_DNS_ID, currentDnsOption?.id).apply()
        } else if (!sharedPreferences.contains(KEY_SELECTED_DNS_ID)) {
            sharedPreferences.edit().putString(KEY_SELECTED_DNS_ID, storedValue).apply()
        }
        binding.dnsValueText.text = currentDnsOption?.label
    }

    private fun initRecyclerView() {
        binding.recyclerView.layoutManager = LinearLayoutManager(requireContext())
        adapter = SubscriptionAdapter(
            requireContext(),
            emptyList(),
            onDeleteClick = { url ->
                runHostsOperation { appContext ->
                    val failure = try {
                        withContext(Dispatchers.IO) {
                            deleteSubscription(appContext, url)
                        }
                        null
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Exception) {
                        error
                    }
                    withContext(Dispatchers.Main) ui@{
                        if (bindingOrNull == null) return@ui
                        loadSubscriptions()
                        refreshManualRulesSummary()
                        refreshPendingPreview()
                        updateStatusUI()
                        if (failure == null) {
                            showToast(R.string.subscription_deleted)
                        } else {
                            showToast(
                                getString(R.string.update_failed, failure.message.orEmpty()),
                                long = true
                            )
                        }
                    }
                    if (failure != null) throw ReportedHostsFailure(failure)
                }
            },
            onToggleClick = ::toggleSubscription
        )
        binding.recyclerView.adapter = adapter
        loadSubscriptions()
    }

    private fun toggleSubscription(url: String, enabled: Boolean) {
        val started = runHostsOperation { appContext ->
            val failure = try {
                withContext(Dispatchers.IO) {
                    updateSubscriptionSelection(appContext, url, enabled)
                }
                null
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                error
            }
            withContext(Dispatchers.Main) ui@{
                if (bindingOrNull == null) return@ui
                loadSubscriptions()
                if (failure != null) {
                    adapter.refreshRow(url)
                    updateStatusUI()
                }
                if (failure == null) {
                    showToast(
                        if (enabled) R.string.subscription_included else R.string.subscription_paused
                    )
                } else {
                    showToast(
                        getString(R.string.subscription_toggle_failed, failure.message.orEmpty()),
                        long = true
                    )
                }
            }
            if (failure != null) throw ReportedHostsFailure(failure)
        }
        if (!started) adapter.refreshRow(url)
    }

    private suspend fun deleteSubscription(appContext: Context, url: String) {
        val previousSubscriptions = HostsSubscriptionManager.getSubscriptions(appContext)
        val removed = previousSubscriptions.firstOrNull { it.url == url }
            ?: throw IOException(getString(R.string.operation_failed))
        val wasApplied = isAppHostsConfigured()
        if (wasApplied && removed.enabled && isModuleUpdatePending()) {
            throw IOException(getString(R.string.module_update_pending_update))
        }
        val mergedFile = mergedHostsFile(appContext)
        val previousMergedSnapshot = if (wasApplied && removed.enabled) {
            HostsSubscriptionManager.createMergedHostsSnapshot(appContext)
        } else null
        var removedFromStorage = false

        try {
            HostsSubscriptionManager.removeSubscription(appContext, url)
            removedFromStorage = true
            HostsSubscriptionManager.clearMergedHostsCache(appContext)
            if (!wasApplied || !removed.enabled) {
                rebuildMergedHostsFromSavedSources(appContext)
                return
            }

            val enabledSources = HostsSubscriptionManager.getSubscriptions(appContext)
                .filter { it.enabled }
            val hasManualRules = ManualHostsRuleManager.getRules(appContext).isNotEmpty()
            if (enabledSources.isEmpty() && !hasManualRules) {
                if (!restoreDefaultHostsLocked(appContext, showFeedback = false)) {
                    throw IOException(getString(R.string.exec_failed))
                }
            } else {
                if (enabledSources.any {
                        !HostsSubscriptionManager.hasCachedSource(appContext, it.url)
                    }
                ) {
                    throw IOException(getString(R.string.subscription_not_downloaded))
                }
                val ruleCount = HostsSubscriptionManager.downloadAndMergeSubscriptions(
                    appContext,
                    refreshRemote = false
                )
                if (ruleCount == 0 || !applyMergedHostsInternal(
                        appContext,
                        mergedFile.absolutePath,
                        showFeedback = false,
                        applyDns = false
                    )
                ) {
                    throw IOException(getString(R.string.exec_failed))
                }
            }
        } catch (error: Exception) {
            if (removedFromStorage) {
                runCatching {
                    HostsSubscriptionManager.saveSubscriptions(appContext, previousSubscriptions)
                }
                val cacheRestored = runCatching {
                    HostsSubscriptionManager.restoreMergedHostsCache(appContext, previousMergedSnapshot)
                }.isSuccess
                if (wasApplied && removed.enabled && cacheRestored && previousMergedSnapshot != null) {
                    try {
                        applyMergedHostsInternal(
                            appContext,
                            mergedFile.absolutePath,
                            showFeedback = false,
                            applyDns = false
                        )
                    } catch (_: Exception) {
                        // Report the original failure; the next update can retry the module.
                    }
                }
            }
            throw error
        } finally {
            previousMergedSnapshot?.delete()
        }
    }

    private fun rebuildMergedHostsFromSavedSources(appContext: Context) {
        val enabledSources = HostsSubscriptionManager.getSubscriptions(appContext)
            .filter { it.enabled }
        if (enabledSources.all {
                HostsSubscriptionManager.hasCachedSource(appContext, it.url)
            }
        ) {
            HostsSubscriptionManager.downloadAndMergeSubscriptions(appContext, refreshRemote = false)
        }
    }

    private suspend fun updateSubscriptionSelection(
        appContext: Context,
        url: String,
        enabled: Boolean
    ) {
        val wasApplied = isAppHostsConfigured()
        if (wasApplied && isModuleUpdatePending()) {
            throw IOException(getString(R.string.module_update_pending_update))
        }
        val previous = HostsSubscriptionManager.getSubscriptions(appContext)
            .firstOrNull { it.url == url }
            ?: throw IOException(getString(R.string.operation_failed))
        val mergedFile = mergedHostsFile(appContext)
        val previousMergedSnapshot = if (wasApplied) {
            HostsSubscriptionManager.createMergedHostsSnapshot(appContext)
        } else null
        var changed = false

        try {
            if (!HostsSubscriptionManager.setSubscriptionEnabled(appContext, url, enabled)) {
                throw IOException(getString(R.string.operation_failed))
            }
            changed = true
            // Build a local merged file when all enabled sources are already cached.
            // A source that has never been downloaded is fetched by the next update/open.
            if (!wasApplied) {
                rebuildMergedHostsFromSavedSources(appContext)
                return
            }
            val enabledSources = HostsSubscriptionManager.getSubscriptions(appContext)
                .filter { it.enabled }
            val hasManualRules = ManualHostsRuleManager.getRules(appContext).isNotEmpty()
            if (enabledSources.isEmpty() && !hasManualRules) {
                HostsSubscriptionManager.clearMergedHostsCache(appContext)
                if (wasApplied && !restoreDefaultHostsLocked(appContext, showFeedback = false)) {
                    throw IOException(getString(R.string.exec_failed))
                }
            } else {
                // Only the source being enabled may need a download. Do not let an
                // unrelated missing cache make this switch wait for network timeouts.
                if (enabledSources.any { source ->
                        source.url != url &&
                            !HostsSubscriptionManager.hasCachedSource(appContext, source.url)
                    }
                ) {
                    throw IOException(getString(R.string.subscription_not_downloaded))
                }
                val ruleCount = HostsSubscriptionManager.downloadAndMergeSubscriptions(
                    appContext,
                    refreshRemote = false
                )
                if (ruleCount == 0 || enabledSources.any {
                        !HostsSubscriptionManager.hasCachedSource(appContext, it.url)
                    }
                ) {
                    throw IOException(getString(R.string.subscription_not_downloaded))
                }
                if (!applyMergedHostsInternal(
                        appContext,
                        mergedFile.absolutePath,
                        showFeedback = false,
                        applyDns = false
                    )
                ) {
                    throw IOException(getString(R.string.exec_failed))
                }
            }
        } catch (error: Exception) {
            if (changed) {
                runCatching {
                    HostsSubscriptionManager.setSubscriptionEnabled(
                        appContext,
                        url,
                        previous.enabled
                    )
                }
                val cacheRestored = runCatching {
                    HostsSubscriptionManager.restoreMergedHostsCache(appContext, previousMergedSnapshot)
                }.isSuccess
                if (wasApplied && cacheRestored && previousMergedSnapshot != null) {
                    try {
                        applyMergedHostsInternal(
                            appContext,
                            mergedFile.absolutePath,
                            showFeedback = false,
                            applyDns = false
                        )
                    } catch (_: Exception) {
                        // The original failure is reported; the next update can retry the module.
                    }
                }
            }
            throw error
        } finally {
            previousMergedSnapshot?.delete()
        }
    }

    private fun isAppHostsConfigured(): Boolean {
        val marker = "'^# Merged ADhosts - '"
        return Shell.cmd(
            "grep -q $marker \"$systemHostsPath\" 2>/dev/null || " +
                "grep -q $marker \"$metaModuleHostsPath\" 2>/dev/null || " +
                "grep -q $marker \"$runtimeHostsPath\" 2>/dev/null"
        ).exec().isSuccess
    }

    private fun initClickListeners() {
        binding.openButton.setOnClickListener { applyMergedHosts() }
        binding.closeButton.setOnClickListener { restoreDefaultHosts() }
        binding.recommendedButton.setOnClickListener { showRecommendedSubscriptionsDialog() }
        binding.outlinedButton.setOnClickListener { showAddSourceTypeDialog() }
        binding.updateButton.setOnClickListener { syncSubscriptions() }
        binding.domainQueryButton.setOnClickListener { showDomainQueryDialog() }
        binding.restorePreviousButton.setOnClickListener { confirmRestorePrevious() }
        binding.pendingPreviewText.setOnClickListener {
            HostsUpdateManager.pendingPreview(requireContext())?.let(::showUpdatePreviewDialog)
                ?: refreshPendingPreview()
        }
        binding.dnsChip.setOnClickListener { showDnsSelectionDialog() }
        binding.toolsButton.setOnClickListener {
            val expanded = binding.toolsPanel.visibility != View.VISIBLE
            binding.toolsPanel.visibility = if (expanded) View.VISIBLE else View.GONE
            binding.toolsButton.isSelected = expanded
            binding.headerScroll.post {
                bindingOrNull?.let { current ->
                    current.headerScroll.smoothScrollTo(0,
                        if (expanded) current.headerScroll.getChildAt(0).height else 0)
                }
            }
        }
    }

    private fun observeSubscriptionUpdates() {
        parentFragmentManager.setFragmentResultListener(
            ACTION_SUBSCRIPTION_UPDATED,
            viewLifecycleOwner
        ) { _, _ ->
            loadSubscriptions()
            refreshManualRulesSummary()
        }
    }

    private fun updateStatusUI() {
        if (isStatusCheckRunning) return
        isStatusCheckRunning = true
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val status = withContext(Dispatchers.IO) { detectHostsStatus() }
                if (bindingOrNull != null) {
                    renderHostsStatus(status)
                }
            } finally {
                isStatusCheckRunning = false
            }
        }
    }

    private fun applyMergedHosts() {
        runHostsOperation { appContext ->
            applyMergedHostsLocked(appContext)
        }
    }

    @SuppressLint("SuspiciousIndentation")
    private suspend fun applyMergedHostsLocked(appContext: Context) {
        withContext(Dispatchers.IO) operation@{
            if (isModuleUpdatePending()) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, R.string.module_update_pending_enable, Toast.LENGTH_LONG).show()
                }
                return@operation
            }

            val sourceFile = mergedHostsFile(appContext)
            if (!sourceFile.exists()) {
                val enabledSources = HostsSubscriptionManager.getSubscriptions(appContext)
                    .filter { it.enabled }
                val canUseCache = enabledSources.all { source ->
                    HostsSubscriptionManager.hasCachedSource(appContext, source.url)
                }
                if (!canUseCache) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            appContext,
                            R.string.subscription_not_downloaded,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@operation
                }
                try {
                    HostsSubscriptionManager.downloadAndMergeSubscriptions(
                        appContext,
                        refreshRemote = false
                    )
                } catch (error: Exception) {
                    withContext(Dispatchers.Main) {
                        showToast(getString(R.string.update_failed, error.message.orEmpty()), long = true)
                    }
                    throw ReportedHostsFailure(error)
                }
                if (!sourceFile.exists()) {
                    withContext(Dispatchers.Main) {
                        Toast.makeText(
                            appContext,
                            R.string.subscription_not_downloaded,
                            Toast.LENGTH_LONG
                        ).show()
                    }
                    return@operation
                }
            }

            val ruleCount = countEffectiveRules(sourceFile)
            val hasManualRules = ManualHostsRuleManager.getRules(appContext).isNotEmpty()
            val minimumRules = if (hasManualRules) 1 else MIN_EFFECTIVE_RULES
            if (ruleCount < minimumRules) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(appContext, R.string.rules_not_enough, Toast.LENGTH_LONG).show()
                }
                return@operation
            }

            applyMergedHostsInternal(appContext, sourceFile.absolutePath)
        }
    }

    private suspend fun applyMergedHostsInternal(
        appContext: Context,
        sourcePath: String,
        showFeedback: Boolean = true,
        applyDns: Boolean = true,
        recordSnapshot: Boolean = true
    ): Boolean {
        val written = writeModuleHosts(sourcePath)
        if (!written) throw IOException(appContext.getString(R.string.hosts_operation_failed_root_module))
        if (written && recordSnapshot) {
            HostsUpdateManager.recordApplied(appContext, File(sourcePath))
        }
        val dnsApplied = if (written && applyDns) {
            currentDnsOption?.let(::applyDnsToSystem) ?: true
        } else written

        val status = if (written) detectHostsStatus() else HostsStatus.Disabled
        withContext(Dispatchers.Main) ui@{
            if (bindingOrNull == null) return@ui
            if (written) {
                renderHostsStatus(status)
                if (showFeedback && dnsApplied) {
                    showToast(getHostsEnabledToast(status), long = true)
                } else if (showFeedback) {
                    showToast(R.string.hosts_enabled_dns_failed, long = true)
                }
            } else if (showFeedback) {
                showToast(R.string.hosts_operation_failed_root_module, long = true)
            }
        }
        return written
    }

    private fun writeModuleHosts(sourcePath: String): Boolean = RootHostsStore.write(sourcePath)

    private fun restoreDefaultHosts() {
        runHostsOperation { appContext ->
            restoreDefaultHostsLocked(appContext)
        }
    }

    private suspend fun restoreDefaultHostsLocked(
        appContext: Context,
        showFeedback: Boolean = true
    ): Boolean {
        return withContext(Dispatchers.IO) {
            val defaultFile = File.createTempFile("default_hosts_", ".txt", appContext.cacheDir)
            val written = try {
                defaultFile.writeText(DEFAULT_HOSTS_CONTENT, Charsets.UTF_8)
                writeModuleHosts(defaultFile.absolutePath)
            } finally {
                defaultFile.delete()
            }
            if (!written) throw IOException(appContext.getString(R.string.hosts_operation_failed_root_module))
            val dnsRestored = applyDnsToSystem(defaultDnsOption)

            val status = if (written) detectHostsStatus() else HostsStatus.Disabled
            withContext(Dispatchers.Main) ui@{
                if (bindingOrNull == null) return@ui
                if (written) {
                    renderHostsStatus(status)
                    if (showFeedback && dnsRestored) {
                        showToast(getHostsDisabledToast(status), long = true)
                    } else if (showFeedback) {
                        showToast(R.string.hosts_disabled_dns_failed, long = true)
                    }
                } else if (showFeedback) {
                    showToast(R.string.operation_failed)
                }
            }
            written
        }
    }

    private fun detectHostsStatus(): HostsStatus {
        val runtimeRules = countHostsRules(runtimeHostsPath)
        val moduleStates = listOf(
            readHostsRuleState(systemHostsPath),
            readHostsRuleState(metaModuleHostsPath)
        )
        val moduleRules = moduleStates.firstOrNull { it.exists }?.ruleCount ?: 0
        val runtimeConfigured = Shell.cmd("grep -q '^# Merged ADhosts - ' '$runtimeHostsPath'").exec().isSuccess
        val expectedPath = if (moduleStates.first().exists) systemHostsPath else metaModuleHostsPath
        val runtimeMatches = RootHostsStore.matchesRuntimePath(expectedPath)

        return when {
            runtimeConfigured && runtimeRules >= MIN_EFFECTIVE_RULES &&
                moduleRules <= MIN_HOSTS_LINES -> HostsStatus.PendingDisable(runtimeRules)

            runtimeConfigured && runtimeMatches && runtimeRules >= MIN_EFFECTIVE_RULES -> HostsStatus.Enabled(runtimeRules)
            moduleRules >= MIN_EFFECTIVE_RULES -> HostsStatus.PendingEnable(moduleRules)
            else -> HostsStatus.Disabled
        }
    }

    private fun countHostsRules(path: String): Int {
        return readHostsRuleState(path).ruleCount
    }

    private fun readHostsRuleState(path: String): HostsRuleState {
        val command = "sh -c \"if [ -f '$path' ]; then echo present; grep -vE '^[[:space:]]*#|^[[:space:]]*$' '$path' | wc -l; else echo missing; echo 0; fi\""
        val output = Shell.cmd(command).exec().out
        val exists = output.firstOrNull()?.trim() == "present"
        val ruleCount = output.getOrNull(1)?.trim()?.toIntOrNull() ?: 0
        return HostsRuleState(exists, ruleCount)
    }

    private fun renderHostsStatus(status: HostsStatus) {
        if (status == lastRenderedStatus) return
        lastRenderedStatus = status

        when (status) {
            is HostsStatus.Enabled -> {
                binding.statusText.text = getString(R.string.enabled)
                applyStatusTone(
                    cardColorRes = R.color.md_hosts_status_panel,
                    iconContainerColorRes = R.color.md_hosts_status_icon,
                    iconTintColorRes = R.color.md_theme_primary,
                    titleColorRes = R.color.md_theme_onSurface,
                    subtitleColorRes = R.color.md_theme_onSurfaceVariant
                )
                binding.statusRulesText.visibility = View.VISIBLE
                binding.statusRulesText.text = getString(R.string.rules_enabled, status.ruleCount)
            }

            is HostsStatus.PendingEnable -> {
                binding.statusText.text = getString(R.string.hosts_pending_reboot)
                applyStatusTone(
                    cardColorRes = R.color.md_hosts_status_panel,
                    iconContainerColorRes = R.color.md_hosts_status_icon,
                    iconTintColorRes = R.color.md_theme_primary,
                    titleColorRes = R.color.md_theme_onSurface,
                    subtitleColorRes = R.color.md_theme_onSurfaceVariant
                )
                binding.statusRulesText.visibility = View.VISIBLE
                binding.statusRulesText.text = getString(R.string.rules_pending_reboot, status.ruleCount)
            }

            is HostsStatus.PendingDisable -> {
                binding.statusText.text = getString(R.string.hosts_disable_pending_reboot)
                applyStatusTone(
                    cardColorRes = R.color.md_hosts_status_panel,
                    iconContainerColorRes = R.color.md_hosts_status_icon,
                    iconTintColorRes = R.color.md_theme_primary,
                    titleColorRes = R.color.md_theme_onSurface,
                    subtitleColorRes = R.color.md_theme_onSurfaceVariant
                )
                binding.statusRulesText.visibility = View.VISIBLE
                binding.statusRulesText.text = getString(R.string.hosts_reboot_to_finish_disable)
            }

            HostsStatus.Disabled -> {
                binding.statusText.text = getString(R.string.disabled)
                applyStatusTone(
                    cardColorRes = R.color.md_hosts_status_danger,
                    iconContainerColorRes = R.color.md_hosts_status_danger_icon,
                    iconTintColorRes = R.color.md_theme_error,
                    titleColorRes = R.color.md_theme_onSurface,
                    subtitleColorRes = R.color.md_theme_onSurfaceVariant
                )
                binding.statusRulesText.visibility = View.GONE
            }

            HostsStatus.Checking -> {
                binding.statusText.text = getString(R.string.hosts_checking_status)
                applyStatusTone(
                    cardColorRes = R.color.md_hosts_status_panel,
                    iconContainerColorRes = R.color.md_hosts_status_icon,
                    iconTintColorRes = R.color.md_theme_primary,
                    titleColorRes = R.color.md_theme_onSurface,
                    subtitleColorRes = R.color.md_theme_onSurfaceVariant
                )
                binding.statusRulesText.visibility = View.VISIBLE
                binding.statusRulesText.text = getString(R.string.hosts_checking_status_hint)
            }
        }
    }

    private fun applyStatusTone(
        cardColorRes: Int,
        iconContainerColorRes: Int,
        iconTintColorRes: Int,
        titleColorRes: Int,
        subtitleColorRes: Int
    ) {
        val isDangerTone = cardColorRes == R.color.md_hosts_status_danger
        binding.statusCard.setBackgroundResource(
            if (isDangerTone) R.drawable.hosts_status_danger_bg else R.drawable.hosts_status_bg
        )
        binding.statusCard.setCardBackgroundColor(ContextCompat.getColor(requireContext(), cardColorRes))
        binding.statusCard.strokeColor = ContextCompat.getColor(
            requireContext(),
            if (isDangerTone) R.color.md_hosts_status_danger_stroke else R.color.md_hosts_status_stroke
        )
        binding.statusIconContainer.setBackgroundResource(
            if (isDangerTone) R.drawable.hosts_status_danger_icon_bg else R.drawable.hosts_status_icon_bg
        )
        binding.statusIconContainer.backgroundTintList =
            ColorStateList.valueOf(ContextCompat.getColor(requireContext(), iconContainerColorRes))
        binding.statusIcon.setColorFilter(ContextCompat.getColor(requireContext(), iconTintColorRes))
        setStatusTextColors(titleColorRes, subtitleColorRes)
    }

    private fun setStatusTextColors(titleColorRes: Int, subtitleColorRes: Int) {
        binding.statusText.setTextColor(ContextCompat.getColor(requireContext(), titleColorRes))
        binding.statusRulesText.setTextColor(ContextCompat.getColor(requireContext(), subtitleColorRes))
    }

    private fun getHostsEnabledToast(status: HostsStatus): String {
        return when (status) {
            is HostsStatus.Enabled -> getString(R.string.hosts_enabled_toast, status.ruleCount)
            is HostsStatus.PendingEnable -> getString(R.string.hosts_pending_reboot_toast, status.ruleCount)
            else -> getString(R.string.hosts_enabled_unknown_toast)
        }
    }

    private fun getHostsDisabledToast(status: HostsStatus): String {
        return when (status) {
            HostsStatus.Disabled -> getString(R.string.hosts_disabled_toast)
            is HostsStatus.PendingDisable -> getString(R.string.hosts_disable_pending_reboot_toast)
            else -> getString(R.string.hosts_disabled_toast)
        }
    }

    private fun syncSubscriptions() {
        runHostsOperation { appContext ->
            syncSubscriptionsLocked(appContext)
        }
    }

    @SuppressLint("SuspiciousIndentation")
    private suspend fun syncSubscriptionsLocked(appContext: Context) {
        withContext(Dispatchers.IO) operation@{
            try {
                val preview = HostsUpdateManager.prepare(appContext)
                withContext(Dispatchers.Main) ui@{
                    if (bindingOrNull == null) return@ui
                    loadSubscriptions()
                    refreshPendingPreview()
                    showUpdatePreviewDialog(preview)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                withContext(Dispatchers.Main) ui@{
                    if (bindingOrNull == null) return@ui
                    showToast(getString(R.string.update_failed, error.message.orEmpty()), long = true)
                    loadSubscriptions()
                }
            }
        }
    }

    private fun refreshPendingPreview() {
        val context = context ?: return
        val preview = HostsUpdateManager.pendingPreview(context)
        binding.pendingPreviewText.visibility = if (preview == null) View.GONE else View.VISIBLE
        if (preview != null) {
            binding.pendingPreviewText.text = getString(R.string.update_preview_pending_counts,
                preview.added, preview.removed, preview.changed)
        }
    }

    private fun showUpdatePreviewDialog(preview: UpdatePreview) {
        val details = buildString {
            append(getString(R.string.update_preview_counts,
                preview.added, preview.removed, preview.changed))
            append('\n')
            append(getString(R.string.update_preview_total, preview.ruleCount))
            if (preview.cachedSources.isNotEmpty()) {
                append("\n\n")
                append(getString(R.string.update_preview_cached, preview.cachedSources.joinToString()))
            }
            if (!preview.hasChanges) {
                append("\n\n")
                append(getString(R.string.update_preview_no_changes))
            }
        }
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.update_preview_title)
            .setMessage(details)
            .setPositiveButton(R.string.update_preview_apply) { _, _ -> applyPendingPreview() }
            .setNegativeButton(R.string.update_preview_discard) { _, _ ->
                HostsUpdateManager.discard(requireContext())
                refreshPendingPreview()
            }
            .create()
        GlassDialog.show(dialog)
    }

    private fun applyPendingPreview() {
        runHostsOperation { appContext ->
            withContext(Dispatchers.IO) {
                try {
                    val preview = HostsUpdateManager.pendingPreview(appContext)
                        ?: throw IOException(getString(R.string.update_preview_expired))
                    val wasApplied = isAppHostsConfigured()
                    if (wasApplied && isModuleUpdatePending()) {
                        throw IOException(getString(R.string.module_update_pending_update))
                    }
                    if (wasApplied && preview.ruleCount == 0) {
                        throw IOException(getString(R.string.rules_not_enough))
                    }
                    val candidate = HostsUpdateManager.pendingHosts(appContext)
                    val currentSnapshot = File(File(appContext.filesDir, "last_applied_hosts"), "ADhosts")
                    val oldMergedFile = mergedHostsFile(appContext)
                    val matchesCurrentModule = wasApplied && oldMergedFile.isFile && Shell.cmd(
                        "cmp -s \"${oldMergedFile.absolutePath}\" \"$systemHostsPath\" || " +
                            "cmp -s \"${oldMergedFile.absolutePath}\" \"$metaModuleHostsPath\""
                    ).exec().isSuccess
                    if (matchesCurrentModule && !currentSnapshot.isFile) {
                        HostsUpdateManager.recordApplied(appContext, oldMergedFile)
                    }
                    val oldApplied = currentSnapshot.takeIf(File::isFile)
                        ?: oldMergedFile.takeIf { matchesCurrentModule }
                    if (wasApplied && !applyMergedHostsInternal(appContext, candidate.absolutePath,
                            showFeedback = false, applyDns = false, recordSnapshot = false)) {
                        throw IOException(getString(R.string.exec_failed))
                    }
                    try {
                        HostsUpdateManager.commit(appContext)
                        if (wasApplied) {
                            HostsUpdateManager.recordApplied(appContext, mergedHostsFile(appContext))
                        }
                    } catch (error: Exception) {
                        if (wasApplied && oldApplied?.isFile == true) {
                            applyMergedHostsInternal(appContext, oldApplied.absolutePath,
                                showFeedback = false, applyDns = false, recordSnapshot = false)
                        }
                        throw error
                    }
                    withContext(Dispatchers.Main) ui@{
                        if (bindingOrNull == null) return@ui
                        loadSubscriptions()
                        refreshPendingPreview()
                        binding.restorePreviousButton.isEnabled = HostsUpdateManager.hasPrevious(appContext)
                        showToast(when {
                            !wasApplied -> R.string.subscription_updated_not_applied
                            else -> R.string.update_preview_applied
                        })
                        updateStatusUI()
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    withContext(Dispatchers.Main) ui@{
                        if (bindingOrNull == null) return@ui
                        showToast(getString(R.string.update_failed, error.message.orEmpty()), long = true)
                        refreshPendingPreview()
                    }
                    throw ReportedHostsFailure(error)
                }
            }
        }
    }

    private fun showDomainQueryDialog() {
        DomainQueryDialog(this, ::isAppHostsConfigured).show()
    }

    private fun confirmRestorePrevious() {
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.restore_previous_button)
            .setMessage(R.string.restore_previous_confirm)
            .setPositiveButton(R.string.restore_previous_button) { _, _ -> restorePrevious() }
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        GlassDialog.show(dialog)
    }

    private fun restorePrevious() {
        runHostsOperation { appContext ->
            withContext(Dispatchers.IO) {
                try {
                    if (isModuleUpdatePending()) throw IOException(getString(R.string.module_update_pending_update))
                    val previous = HostsUpdateManager.previousHosts(appContext)
                    val oldSubscriptions = HostsSubscriptionManager.getSubscriptions(appContext)
                    val oldManual = ManualHostsRuleManager.getRules(appContext)
                    val oldApplied = File(File(appContext.filesDir, "last_applied_hosts"), "ADhosts")
                    val oldMerged = HostsSubscriptionManager.createMergedHostsSnapshot(appContext)
                    if (!applyMergedHostsInternal(appContext, previous.absolutePath, false, false, false)) {
                        oldMerged?.delete()
                        throw IOException(getString(R.string.exec_failed))
                    }
                    try {
                        HostsUpdateManager.restorePreviousConfiguration(appContext)
                        HostsUpdateManager.recordApplied(appContext, mergedHostsFile(appContext))
                    } catch (error: Exception) {
                        runCatching { HostsSubscriptionManager.saveSubscriptions(appContext, oldSubscriptions) }
                        runCatching { ManualHostsRuleManager.saveRules(appContext, oldManual) }
                        runCatching { HostsSubscriptionManager.restoreMergedHostsCache(appContext, oldMerged) }
                        if (oldApplied.isFile) applyMergedHostsInternal(appContext, oldApplied.absolutePath, false, false, false)
                        throw error
                    } finally {
                        oldMerged?.delete()
                    }
                    withContext(Dispatchers.Main) ui@{
                        if (bindingOrNull == null) return@ui
                        loadSubscriptions()
                        refreshManualRulesSummary()
                        refreshPendingPreview()
                        updateStatusUI()
                        showToast(R.string.restore_previous_success)
                    }
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    withContext(Dispatchers.Main) ui@{
                        if (bindingOrNull == null) return@ui
                        showToast(getString(R.string.update_failed, error.message.orEmpty()), long = true)
                    }
                    throw ReportedHostsFailure(error)
                }
            }
        }
    }

    private fun runHostsOperation(operation: suspend (Context) -> Unit): Boolean {
        if (!HostsOperationLock.mutex.tryLock()) {
            showToast(R.string.hosts_operation_in_progress)
            return false
        }

        val appContext = requireContext().applicationContext
        setHostsActionsEnabled(false)
        // Keep the mutation alive through a view teardown so its commit/rollback can finish.
        HostsOperationLock.scope.launch {
            try {
                HostsOperationJournal.run(appContext) { operation(appContext) }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w("HostsFragment", "Hosts operation rolled back", error)
                if (bindingOrNull != null) {
                    if (error !is ReportedHostsFailure) showToast(R.string.hosts_operation_failed_root_module, long = true)
                    loadSubscriptions()
                    initDnsConfiguration()
                    refreshManualRulesSummary()
                    refreshPendingPreview()
                    updateStatusUI()
                }
            } finally {
                HostsOperationLock.mutex.unlock()
                if (bindingOrNull != null) {
                    setHostsActionsEnabled(true)
                }
            }
        }
        return true
    }

    private fun setHostsActionsEnabled(enabled: Boolean) = with(binding) {
        openButton.isEnabled = enabled
        closeButton.isEnabled = enabled
        updateButton.isEnabled = enabled
        dnsChip.isEnabled = enabled
        recommendedButton.isEnabled = enabled
        outlinedButton.isEnabled = enabled
        domainQueryButton.isEnabled = enabled
        restorePreviousButton.isEnabled = enabled && HostsUpdateManager.hasPrevious(requireContext())
    }

    private fun applyDnsToSystem(option: DnsOption): Boolean {
        val commands = LEGACY_DNS_PROPERTIES.mapIndexed { index, property ->
            val value = option.addresses.getOrNull(index % option.addresses.size.coerceAtLeast(1))
            "setprop $property ${shellQuote(value.orEmpty())}"
        }
        return Shell.cmd(*commands.toTypedArray()).exec().isSuccess
    }


    private fun dnsPreferences(context: Context) =
        context.getSharedPreferences(DNS_PREFERENCES_NAME, Context.MODE_PRIVATE)

    @SuppressLint("SuspiciousIndentation")
    private fun showDnsSelectionDialog() {
        val checkedIndex = dnsOptions.indexOfFirst { it.id == currentDnsOption?.id }.coerceAtLeast(0)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(getString(R.string.select_dns))
            .setSingleChoiceItems(dnsOptionLabels, checkedIndex) { dialog, which ->
                val selectedOption = dnsOptions[which]
                runHostsOperation { appContext ->
                    val applied = withContext(Dispatchers.IO) {
                        applyDnsToSystem(selectedOption)
                    }
                    withContext(Dispatchers.Main) ui@{
                        if (bindingOrNull == null) return@ui
                        if (applied) {
                            currentDnsOption = selectedOption
                            dnsPreferences(appContext).edit()
                                .putString(KEY_SELECTED_DNS_ID, selectedOption.id)
                                .apply()
                            binding.dnsValueText.text = selectedOption.label
                            showToast(getString(R.string.dns_switched, selectedOption.label))
                        } else {
                            showToast(R.string.exec_failed, long = true)
                        }
                    }
                }
                dialog.dismiss()
            }
            .setNegativeButton(getString(R.string.cancel), null)
            .create()
        GlassDialog.show(dialog)
    }

    private fun loadSubscriptions() {
        val subscriptions = HostsSubscriptionManager.getSubscriptions(requireContext())
        val isEmpty = subscriptions.isEmpty()
        binding.subscriptionTitle.text = getString(R.string.subscription_title_count, subscriptions.size)
        binding.recyclerView.visibility = if (isEmpty) View.GONE else View.VISIBLE
        binding.emptyText.visibility = if (isEmpty) View.VISIBLE else View.GONE
        adapter.updateData(subscriptions)
        refreshPendingPreview()
    }

    private fun refreshManualRulesSummary() {
        val appContext = requireContext().applicationContext
        viewLifecycleOwner.lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                ManualHostsRuleManager.getRules(appContext).size
            }
            if (bindingOrNull == null) return@launch
            binding.subscriptionTitle.text = getString(R.string.subscription_title_count, adapter.itemCount)
            if (adapter.itemCount == 0) {
                binding.emptyText.visibility = View.VISIBLE
                binding.emptyText.setText(if (count > 0) R.string.manual_only_hint
                    else R.string.empty_subscription_hint)
            }
        }
    }

    private fun showAddSubscriptionDialog() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_add_subscription, binding.root as? ViewGroup, false)
        dialogView.setBackgroundResource(R.drawable.dialog_glass_background)
        val editTextName = dialogView.findViewById<TextInputEditText>(R.id.editTextName)
        val editText = dialogView.findViewById<TextInputEditText>(R.id.editTextUrl)
        val confirmButton = dialogView.findViewById<Button>(R.id.btnConfirm)
        val cancelButton = dialogView.findViewById<Button>(R.id.btnCancel)

        val dialog = Dialog(requireContext()).apply {
            setContentView(dialogView)
            window?.apply {
                val width = (resources.displayMetrics.widthPixels * DIALOG_WIDTH_RATIO).toInt()
                setLayout(width, WindowManager.LayoutParams.WRAP_CONTENT)
            }
        }

        cancelButton.setOnClickListener { dialog.dismiss() }
        confirmButton.setOnClickListener {
            val name = editTextName.text.toString().trim()
            val url = editText.text.toString().trim()
            if (name.isEmpty()) {
                editTextName.error = getString(R.string.subscription_name_required)
                editTextName.requestFocus()
                return@setOnClickListener
            }
            if (!url.matches(URL_REGEX)) {
                showToast(R.string.invalid_url)
                editText.requestFocus()
                return@setOnClickListener
            }

            when (addSubscriptionSafely(name, url)) {
                true -> {
                    loadSubscriptions()
                    showToast(R.string.subscription_added)
                    syncSubscriptions()
                }
                false -> showToast(R.string.subscription_exists)
                null -> return@setOnClickListener
            }
            dialog.dismiss()
        }
        editText.setOnEditorActionListener { _, _, _ ->
            confirmButton.performClick()
            true
        }
        GlassDialog.show(dialog)
    }

    private fun addSubscriptionSafely(name: String, url: String): Boolean? {
        if (!HostsOperationLock.mutex.tryLock()) {
            showToast(R.string.hosts_operation_in_progress)
            return null
        }
        return try {
            HostsSubscriptionManager.addSubscription(requireContext(), name, url)
        } catch (error: Exception) {
            showToast(getString(R.string.update_failed, error.message.orEmpty()), long = true)
            null
        } finally {
            HostsOperationLock.mutex.unlock()
        }
    }

    private fun showAddSourceTypeDialog() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_add_hosts_source, binding.root as? ViewGroup, false)
        val dialog = Dialog(requireContext()).apply {
            setContentView(dialogView)
            setOnShowListener {
                window?.setLayout(
                    (resources.displayMetrics.widthPixels * DIALOG_WIDTH_RATIO).toInt(),
                    WindowManager.LayoutParams.WRAP_CONTENT
                )
            }
        }

        dialogView.findViewById<View>(R.id.manualRuleOption).setOnClickListener {
            dialog.dismiss()
            openManualRulesEditor()
        }
        dialogView.findViewById<View>(R.id.networkSubscriptionOption).setOnClickListener {
            dialog.dismiss()
            showAddSubscriptionDialog()
        }
        dialogView.findViewById<MaterialButton>(R.id.cancelButton).setOnClickListener {
            dialog.dismiss()
        }
        GlassDialog.show(dialog)
    }

    private fun openManualRulesEditor() {
        if (HostsOperationLock.mutex.isLocked) {
            showToast(R.string.hosts_operation_in_progress)
            return
        }
        val editors = ManualHostsEditor.installedEditors(requireContext())
        if (editors.isEmpty()) {
            val dialog = MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.manual_editor_missing_title)
                .setMessage(R.string.manual_editor_missing_message)
                .setPositiveButton(R.string.manual_editor_website) { _, _ ->
                    openExternalWebsite(ManualHostsEditor.WEBSITE)
                }
                .setNegativeButton(R.string.cancel, null)
                .create()
            GlassDialog.show(dialog)
            return
        }
        if (editors.size == 1) {
            launchManualRulesEditor(editors.first())
            return
        }
        var selected = editors.indexOfFirst {
            it.packageName == ManualHostsEditor.preferredEditor(requireContext())
        }.coerceAtLeast(0)
        val dialog = MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.manual_editor_choose)
            .setSingleChoiceItems(editors.map { it.label }.toTypedArray(), selected) { _, index ->
                selected = index
            }
            .setPositiveButton(R.string.manual_editor_continue) { _, _ ->
                launchManualRulesEditor(editors[selected])
            }
            .setNegativeButton(R.string.cancel, null)
            .create()
        GlassDialog.show(dialog)
    }

    private fun launchManualRulesEditor(editor: ManualHostsEditor.EditorApp) {
        val appContext = requireContext().applicationContext.createConfigurationContext(resources.configuration)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    HostsOperationLock.mutex.withLock { ManualHostsEditor.begin(appContext) }
                }
                if (bindingOrNull == null) return@launch
                startActivity(ManualHostsEditor.intent(appContext, editor.packageName))
                ManualHostsEditor.rememberEditor(appContext, editor.packageName)
            } catch (error: ManualHostsEditor.DraftRecoveryRequired) {
                if (bindingOrNull != null) showManualDraftRecovery()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w("HostsFragment", "Could not open MT editor", error)
                if (bindingOrNull != null) {
                    val dialog = MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.manual_hosts_rules)
                        .setMessage(R.string.manual_editor_open_failed)
                        .setPositiveButton(R.string.manual_editor_continue) { _, _ -> openManualRulesEditor() }
                        .setNeutralButton(R.string.manual_editor_website) { _, _ ->
                            openExternalWebsite(ManualHostsEditor.WEBSITE)
                        }
                        .setNegativeButton(R.string.cancel, null)
                        .create()
                    GlassDialog.show(dialog)
                }
            }
        }
    }

    private fun checkReturnedManualRules() {
        if (isManualEditorCheckRunning || bindingOrNull == null) return
        isManualEditorCheckRunning = true
        val appContext = requireContext().applicationContext.createConfigurationContext(resources.configuration)
        viewLifecycleOwner.lifecycleScope.launch {
            try {
                val edit = withContext(Dispatchers.IO) {
                    HostsOperationLock.mutex.withLock { ManualHostsEditor.pending(appContext) }
                } ?: return@launch
                if (bindingOrNull == null) return@launch
                if (!edit.parsed.isValid) {
                    ManualHostsEditor.markReviewed(appContext, edit.hash)
                    val dialog = MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.manual_editor_invalid_title)
                        .setMessage(getString(R.string.manual_hosts_invalid_lines,
                            edit.parsed.invalidLines.joinToString(", ")))
                        .setPositiveButton(R.string.manual_editor_continue) { _, _ -> openManualRulesEditor() }
                        .setNeutralButton(R.string.manual_editor_discard) { _, _ -> discardManualEdit(appContext) }
                        .setNegativeButton(R.string.cancel, null)
                        .create()
                    GlassDialog.show(dialog)
                } else if (edit.conflicting || (edit.parsed.rules.isEmpty() &&
                        ManualHostsRuleManager.getRules(appContext).isNotEmpty())) {
                    ManualHostsEditor.markReviewed(appContext, edit.hash)
                    val dialog = MaterialAlertDialogBuilder(requireContext())
                        .setTitle(if (edit.parsed.rules.isEmpty()) R.string.manual_editor_clear_title
                            else R.string.manual_hosts_rules)
                        .setMessage(if (edit.conflicting) R.string.manual_editor_conflict
                            else R.string.manual_editor_clear_message)
                        .setPositiveButton(R.string.manual_editor_apply) { _, _ -> importManualEdit(edit, true) }
                        .setNeutralButton(R.string.manual_editor_discard) { _, _ -> discardManualEdit(appContext) }
                        .setNegativeButton(R.string.cancel, null)
                        .create()
                    GlassDialog.show(dialog)
                } else importManualEdit(edit)
            } catch (error: ManualHostsEditor.DraftRecoveryRequired) {
                if (bindingOrNull != null) showManualDraftRecovery()
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w("HostsFragment", "Could not read MT editing copy", error)
                if (bindingOrNull != null) showToast(R.string.manual_editor_open_failed, long = true)
            } finally {
                isManualEditorCheckRunning = false
            }
        }
    }

    private fun discardManualEdit(appContext: Context) {
        viewLifecycleOwner.lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                HostsOperationLock.mutex.withLock {
                    appContext.revokeUriPermission(ManualHostsEditor.uri(appContext),
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                    ManualHostsEditor.discard(appContext)
                }
            }
        }
    }

    private fun showManualDraftRecovery() {
        val appContext = requireContext().applicationContext
        GlassDialog.show(MaterialAlertDialogBuilder(requireContext())
            .setTitle(R.string.manual_hosts_rules)
            .setMessage(R.string.manual_editor_recovery_message)
            .setPositiveButton(R.string.manual_editor_continue) { _, _ ->
                viewLifecycleOwner.lifecycleScope.launch {
                    try {
                        withContext(Dispatchers.IO) {
                            HostsOperationLock.mutex.withLock { ManualHostsEditor.recoverDraft(appContext) }
                        }
                        if (bindingOrNull != null) openManualRulesEditor()
                    } catch (error: CancellationException) { throw error }
                    catch (_: Exception) { if (bindingOrNull != null) showToast(R.string.manual_editor_open_failed) }
                }
            }
            .setNeutralButton(R.string.manual_editor_discard) { _, _ -> discardManualEdit(appContext) }
            .setNegativeButton(R.string.cancel, null)
            .create())
    }

    private fun importManualEdit(edit: ManualHostsEditor.PendingEdit, allowReplacement: Boolean = false) {
        val appContext = requireContext().applicationContext.createConfigurationContext(resources.configuration)
        runHostsOperation {
            val failure = try {
                withContext(Dispatchers.IO) {
                    val latest = ManualHostsEditor.pending(appContext, includeReviewed = true)
                    if (latest == null || latest.hash != edit.hash || !latest.parsed.isValid ||
                        (latest.conflicting && !allowReplacement)) {
                        throw IOException(appContext.getString(R.string.manual_editor_changed))
                    }
                    updateManualRules(appContext, latest.parsed.rules)
                    HostsOperationJournal.afterCommit {
                        if (ManualHostsEditor.complete(appContext, latest.hash)) {
                            appContext.revokeUriPermission(ManualHostsEditor.uri(appContext),
                                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
                        }
                    }
                }
                null
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w("HostsFragment", "Could not import MT editing copy", error)
                ManualHostsEditor.markReviewed(appContext, edit.hash)
                error
            }
            if (bindingOrNull != null) {
                refreshManualRulesSummary()
                refreshPendingPreview()
                updateStatusUI()
                if (failure == null) {
                    showToast(getString(R.string.manual_hosts_saved, edit.parsed.rules.size), long = true)
                } else {
                    val dialog = MaterialAlertDialogBuilder(requireContext())
                        .setTitle(R.string.manual_hosts_rules)
                        .setMessage(getString(R.string.update_failed, failure.message.orEmpty()))
                        .setPositiveButton(R.string.manual_editor_apply) { _, _ -> importManualEdit(edit, allowReplacement) }
                        .setNeutralButton(R.string.manual_editor_continue) { _, _ -> openManualRulesEditor() }
                        .setNegativeButton(R.string.cancel, null)
                        .create()
                    GlassDialog.show(dialog)
                }
            }
            if (failure != null) throw ReportedHostsFailure(failure)
        }
    }

    private suspend fun updateManualRules(
        appContext: Context,
        rules: List<com.kascr.adhosts.data.ManualHostsRule>
    ) {
        val wasApplied = isAppHostsConfigured()
        if (wasApplied && isModuleUpdatePending()) {
            throw IOException(appContext.getString(R.string.module_update_pending_update))
        }

        val previousRules = ManualHostsRuleManager.getRules(appContext)
        val previousMergedSnapshot = HostsSubscriptionManager.createMergedHostsSnapshot(appContext)
        var rulesSaved = false
        try {
            ManualHostsRuleManager.saveRules(appContext, rules)
            rulesSaved = true

            if (!wasApplied) {
                val enabledSources = HostsSubscriptionManager.getSubscriptions(appContext)
                    .filter { it.enabled }
                if (enabledSources.all {
                        HostsSubscriptionManager.hasCachedSource(appContext, it.url)
                    }
                ) {
                    HostsSubscriptionManager.downloadAndMergeSubscriptions(
                        appContext,
                        refreshRemote = false
                    )
                } else {
                    // Prevent Open from applying a merge built with the previous local rules.
                    HostsSubscriptionManager.clearMergedHostsCache(appContext)
                }
                return
            }

            val ruleCount = HostsSubscriptionManager.downloadAndMergeSubscriptions(
                appContext,
                refreshRemote = false
            )
            val applied = if (ruleCount == 0) {
                restoreDefaultHostsLocked(appContext, showFeedback = false)
            } else {
                applyMergedHostsInternal(
                    appContext,
                    mergedHostsFile(appContext).absolutePath,
                    showFeedback = false,
                    applyDns = false
                )
            }
            if (!applied) throw IOException(appContext.getString(R.string.exec_failed))
        } catch (error: Exception) {
            if (rulesSaved) {
                runCatching { ManualHostsRuleManager.saveRules(appContext, previousRules) }
                val cacheRestored = runCatching {
                    HostsSubscriptionManager.restoreMergedHostsCache(
                        appContext,
                        previousMergedSnapshot
                    )
                }.isSuccess
                if (wasApplied && cacheRestored && previousMergedSnapshot != null) {
                    try {
                        applyMergedHostsInternal(
                            appContext,
                            mergedHostsFile(appContext).absolutePath,
                            showFeedback = false,
                            applyDns = false
                        )
                    } catch (_: Exception) {
                        // Keep the original error; a later update can retry the module write.
                    }
                }
            }
            throw error
        } finally {
            previousMergedSnapshot?.delete()
        }
    }

    private fun showRecommendedSubscriptionsDialog() {
        val dialogView = LayoutInflater.from(requireContext())
            .inflate(R.layout.dialog_recommended_subscriptions, binding.root as? ViewGroup, false)
        val dialog = Dialog(requireContext()).apply {
            setContentView(dialogView)
            setOnShowListener {
                window?.setLayout(
                    (resources.displayMetrics.widthPixels * DIALOG_WIDTH_RATIO).toInt(),
                    WindowManager.LayoutParams.WRAP_CONTENT
                )
            }
        }

        bindRecommendedSourceAction(
            addButton = dialogView.findViewById(R.id.autumnAddButton),
            websiteButton = dialogView.findViewById(R.id.autumnWebsiteButton),
            source = recommendedSources[0]
        )
        bindRecommendedSourceAction(
            addButton = dialogView.findViewById(R.id.github520AddButton),
            websiteButton = dialogView.findViewById(R.id.github520WebsiteButton),
            source = recommendedSources[1]
        )
        dialogView.findViewById<MaterialButton>(R.id.closeButton).setOnClickListener {
            dialog.dismiss()
        }
        GlassDialog.show(dialog)
    }

    private fun bindRecommendedSourceAction(
        addButton: MaterialButton,
        websiteButton: MaterialButton,
        source: RecommendedSource
    ) {
        websiteButton.setOnClickListener { openExternalWebsite(source.websiteUrl) }
        updateRecommendedSourceButton(addButton, isRecommendedSourceAdded(source))

        addButton.setOnClickListener {
            val added = addSubscriptionSafely(source.name, source.subscriptionUrl)
            if (added == true) {
                updateRecommendedSourceButton(addButton, added = true)
                loadSubscriptions()
                showToast(R.string.subscription_added)
                syncSubscriptions()
            } else if (added == false) {
                updateRecommendedSourceButton(addButton, added = true)
                showToast(R.string.subscription_exists)
            }
        }
    }

    private fun isRecommendedSourceAdded(source: RecommendedSource): Boolean {
        return HostsSubscriptionManager.hasSubscription(requireContext(), source.subscriptionUrl)
    }

    private fun updateRecommendedSourceButton(button: MaterialButton, added: Boolean) {
        button.isEnabled = !added
        button.alpha = if (added) RECOMMENDED_SOURCE_ADDED_ALPHA else 1f
        button.setText(if (added) R.string.recommended_added else R.string.btn_add)
        button.setIconResource(if (added) R.drawable.ic_check_circle else R.drawable.ic_add)
    }

    private fun openExternalWebsite(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
        } catch (_: Exception) {
            showToast(R.string.recommended_website_unavailable)
        }
    }

    private fun showToast(messageRes: Int, long: Boolean = false) {
        showToast(getString(messageRes), long)
    }

    private fun showToast(message: String, long: Boolean = false) {
        Toast.makeText(
            requireContext(),
            message,
            if (long) Toast.LENGTH_LONG else Toast.LENGTH_SHORT
        ).show()
    }

    private fun mergedHostsFile(context: Context): File {
        return File(context.filesDir, MERGED_HOSTS_FILE_NAME)
    }

    private fun countEffectiveRules(file: File): Int {
        return file.bufferedReader(Charsets.UTF_8).useLines(HostsSubscriptionManager::countEffectiveRules)
    }

    private fun isModuleUpdatePending(): Boolean {
        return Shell.cmd("[ -f \"$modulesUpdatePath\" ]").exec().isSuccess
    }


    private class ReportedHostsFailure(cause: Exception) : IOException(cause)
    private sealed class HostsStatus {
        data class Enabled(val ruleCount: Int) : HostsStatus()
        data class PendingEnable(val ruleCount: Int) : HostsStatus()
        data class PendingDisable(val runtimeRuleCount: Int) : HostsStatus()
        data object Checking : HostsStatus()
        data object Disabled : HostsStatus()
    }

    private data class HostsRuleState(
        val exists: Boolean,
        val ruleCount: Int
    )

    private data class DnsOption(
        val id: String,
        val label: String,
        val addresses: List<String>
    )

    private data class RecommendedSource(
        val name: String,
        val summary: String,
        val subscriptionUrl: String,
        val websiteUrl: String
    )

    companion object {
        private const val MIN_HOSTS_LINES = 2
        private const val MIN_EFFECTIVE_RULES = 3
        private const val DIALOG_WIDTH_RATIO = 0.88f
        private const val RECOMMENDED_SOURCE_ADDED_ALPHA = 0.6f
        private const val DNS_PREFERENCES_NAME = "dns_preferences"
        private const val KEY_SELECTED_DNS_ID = "selected_dns_id"
        private const val MERGED_HOSTS_FILE_NAME = "ADhosts"
        private const val DEFAULT_HOSTS_CONTENT = "127.0.0.1 localhost\n::1 localhost\n"
        private val URL_REGEX = "^https://[^\\s/\$.?#][^\\s]*$".toRegex(RegexOption.IGNORE_CASE)
        private val LEGACY_DNS_PROPERTIES = listOf(
            "net.dns1",
            "net.dns2",
            "net.eth0.dns1",
            "net.eth0.dns2",
            "net.wlan0.dns1",
            "net.wlan0.dns2",
            "net.rmnet0.dns1",
            "net.rmnet0.dns2",
            "net.rmnet_data0.dns1",
            "net.rmnet_data0.dns2"
        )
    }

    private val systemHostsPath = "/data/adb/modules/AD_lite/system/etc/hosts"
    private val metaModuleHostsPath = "/data/adb/metamodule/mnt/AD_lite/system/etc/hosts"
    private val modulesUpdatePath = "/data/adb/modules_update/AD_lite/system/etc/hosts"
    private val runtimeHostsPath = "/system/etc/hosts"
}
