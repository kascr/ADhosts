package com.kascr.adhosts.ui.fragment

import com.kascr.adhosts.data.HostsBackupCodec
import com.kascr.adhosts.data.HostsOperationLock
import com.kascr.adhosts.data.HostsSubscriptionManager
import com.kascr.adhosts.data.ManualHostsRule
import com.kascr.adhosts.data.ManualHostsRuleManager
import com.kascr.adhosts.data.Subscription
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class HostsBackupSnapshotTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @After fun cleanup() {
        File(context.filesDir, "hosts_subscriptions.json").delete()
        File(context.filesDir, "manual_hosts_rules.txt").delete()
    }

    @Test fun exportWaitsUntilSubscriptionsAndManualRulesBelongToOneGeneration() = runBlocking {
        val oldSubscriptions = listOf(Subscription("Old", "https://example.com/old"))
        val oldRules = listOf(ManualHostsRule("0.0.0.0", "old.example"))
        val newSubscriptions = listOf(Subscription("New", "https://example.com/new"))
        val newRules = listOf(ManualHostsRule("0.0.0.0", "new.example"))
        HostsSubscriptionManager.saveSubscriptions(context, oldSubscriptions)
        ManualHostsRuleManager.saveRules(context, oldRules)
        val subscriptionsChanged = CompletableDeferred<Unit>()
        val finishChange = CompletableDeferred<Unit>()
        val writer = launch(Dispatchers.Default) {
            HostsOperationLock.mutex.withLock {
                HostsSubscriptionManager.saveSubscriptions(context, newSubscriptions)
                subscriptionsChanged.complete(Unit)
                finishChange.await()
                ManualHostsRuleManager.saveRules(context, newRules)
            }
        }
        try {
            withTimeout(5_000) { subscriptionsChanged.await() }
            val exporting = async(Dispatchers.Default) { captureHostsBackup(context) }
            assertNull(withTimeoutOrNull(200) { exporting.await() })
            finishChange.complete(Unit)
            val snapshot = withTimeout(5_000) { exporting.await() }
            val backup = HostsBackupCodec.decode(snapshot.json)
            assertEquals(newSubscriptions, backup.subscriptions)
            assertEquals(newRules, backup.manualRules)
            assertEquals(1, snapshot.subscriptionCount)
            assertEquals(1, snapshot.manualRuleCount)
            assertFalse(HostsOperationLock.mutex.isLocked)
        } finally {
            finishChange.complete(Unit)
            writer.join()
        }
    }

    @Test fun maximumLengthManualRulesProduceAnImportableBackup() = runBlocking {
        val rules = (0 until ManualHostsRuleManager.MAX_RULES).map { index ->
            val hostname = index.toString().padStart(5, '0') + "a".repeat(58) + "." +
                "b".repeat(63) + "." + "c".repeat(63) + "." + "d".repeat(61)
            ManualHostsRule("2001:0db8:0000:0000:0000:0000:192.168.100.200", hostname)
        }
        ManualHostsRuleManager.saveRules(context, rules)
        val snapshot = captureHostsBackup(context)
        val bytes = snapshot.json.toByteArray(Charsets.UTF_8).size
        assertTrue(bytes > 2 * 1024 * 1024)
        assertTrue(bytes <= MAX_HOSTS_BACKUP_BYTES)
        assertEquals(rules, HostsBackupCodec.decode(snapshot.json).manualRules)
        assertEquals(ManualHostsRuleManager.MAX_RULES, snapshot.manualRuleCount)
    }
}
