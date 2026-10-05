package com.kascr.adhosts.ui.dialog

import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.textfield.TextInputEditText
import com.kascr.adhosts.R
import com.kascr.adhosts.data.DomainMatch
import com.kascr.adhosts.data.DomainQueryAssessment
import com.kascr.adhosts.data.HostsOperationLock
import com.kascr.adhosts.data.HostsUpdateManager
import com.kascr.adhosts.data.LocalDomainLookup
import com.kascr.adhosts.data.RootHostsStore
import com.kascr.adhosts.utils.GlassDialog
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Owns the input, progress and result dialogs for one domain query. */
internal class DomainQueryDialog(
    private val fragment: Fragment,
    private val isHostsConfigured: () -> Boolean
) {
    private val context = fragment.requireContext()
    private val owner = fragment.viewLifecycleOwner

    private data class QueryResult(
        val rule: DomainMatch?,
        val hostsAddresses: List<String>?,
        val resolution: LocalDomainLookup.Result
    )

    fun show() {
        val input = TextInputEditText(context).apply {
            hint = context.getString(R.string.domain_query_hint)
            setSingleLine(true)
            setPadding(32, 24, 32, 24)
        }
        val dialog = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.domain_query_button)
            .setView(input)
            .setPositiveButton(R.string.domain_query_button, null)
            .setNegativeButton(android.R.string.cancel, null)
            .create()
        showDialog(dialog)
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val domain = HostsUpdateManager.normalizeDomain(input.text?.toString().orEmpty())
            if (domain == null) {
                input.error = context.getString(R.string.domain_query_invalid)
                return@setOnClickListener
            }
            dialog.dismiss()
            lookup(domain)
        }
    }

    private fun lookup(domain: String) {
        var queryJob: Job? = null
        val progress = MaterialAlertDialogBuilder(context)
            .setTitle(R.string.domain_query_button)
            .setMessage(context.getString(R.string.domain_query_loading, domain))
            .setNegativeButton(android.R.string.cancel) { _, _ -> queryJob?.cancel() }
            .create()
        progress.setOnCancelListener { queryJob?.cancel() }
        showDialog(progress)
        val observer = object : DefaultLifecycleObserver {
            override fun onStop(owner: LifecycleOwner) { queryJob?.cancel() }
        }
        owner.lifecycle.addObserver(observer)
        queryJob = owner.lifecycleScope.launch {
            val result = try {
                withContext(Dispatchers.IO) {
                    HostsOperationLock.mutex.withLock {
                        val rule = HostsUpdateManager.findDomain(
                            context.applicationContext, domain, isHostsConfigured()
                        )
                        val addresses = RootHostsStore.runtimeAddresses(domain)
                        QueryResult(rule, addresses, LocalDomainLookup.lookup(domain))
                    }
                }
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                Log.w("DomainQueryDialog", "Local domain lookup failed", error)
                if (fragment.view != null) {
                    Toast.makeText(context, R.string.domain_query_resolution_failed, Toast.LENGTH_SHORT).show()
                }
                return@launch
            } finally {
                owner.lifecycle.removeObserver(observer)
                progress.dismiss()
            }
            if (fragment.view == null) return@launch
            showDialog(MaterialAlertDialogBuilder(context)
                .setTitle(R.string.domain_query_result)
                .setMessage(formatResult(domain, result))
                .setPositiveButton(android.R.string.ok, null)
                .create())
        }
    }

    private fun formatResult(domain: String, result: QueryResult): String {
        val (rule, addresses, resolution) = result
        val ruleDetails = if (rule == null) context.getString(R.string.domain_query_no_match, domain)
        else context.getString(
            R.string.domain_query_match,
            rule.hostname,
            rule.address,
            if (rule.manual) context.getString(R.string.manual_hosts_rules) else rule.source,
            context.getString(if (rule.applied) R.string.domain_query_applied else R.string.domain_query_not_applied)
        )
        val hostsDetails = when {
            addresses == null -> context.getString(R.string.domain_query_runtime_unavailable)
            addresses.isEmpty() -> context.getString(R.string.domain_query_runtime_no_match)
            else -> context.getString(R.string.domain_query_runtime_hosts, addresses.joinToString(", "))
        }
        val stateResource = when (DomainQueryAssessment.assess(domain, resolution, addresses)) {
            DomainQueryAssessment.State.BLOCKED -> R.string.domain_query_state_blocked
            DomainQueryAssessment.State.LOCAL_ADDRESS -> R.string.domain_query_state_local
            DomainQueryAssessment.State.PARTIAL -> R.string.domain_query_state_partial
            DomainQueryAssessment.State.NOT_EFFECTIVE -> R.string.domain_query_state_not_effective
            DomainQueryAssessment.State.RESOLVED -> R.string.domain_query_state_resolved
            DomainQueryAssessment.State.UNKNOWN -> R.string.domain_query_state_unknown
        }
        return listOf(
            context.getString(R.string.domain_query_status, context.getString(stateResource)),
            context.getString(
                R.string.domain_query_resolution_result,
                describe(resolution.ipv4),
                describe(resolution.ipv6)
            ),
            hostsDetails,
            ruleDetails,
            context.getString(R.string.domain_query_limit)
        ).joinToString("\n\n")
    }

    private fun describe(answer: LocalDomainLookup.Answer): String = when (answer.status) {
        LocalDomainLookup.Status.RESOLVED -> answer.addresses.joinToString("\n")
        LocalDomainLookup.Status.UNRESOLVED -> context.getString(R.string.domain_query_unresolved)
        LocalDomainLookup.Status.TIMEOUT -> context.getString(R.string.domain_query_timeout)
        LocalDomainLookup.Status.FAILED -> context.getString(R.string.domain_query_resolution_failed)
        LocalDomainLookup.Status.UNAVAILABLE -> context.getString(R.string.domain_query_resolution_unavailable)
    }

    private fun showDialog(dialog: AlertDialog) {
        val observer = object : DefaultLifecycleObserver {
            override fun onDestroy(owner: LifecycleOwner) { dialog.dismiss() }
        }
        owner.lifecycle.addObserver(observer)
        dialog.setOnDismissListener { owner.lifecycle.removeObserver(observer) }
        GlassDialog.show(dialog)
    }
}
