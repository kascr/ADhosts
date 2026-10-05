package com.kascr.adhosts.data

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], shadows = [DirectoryAwareOsShadow::class])
class CompletedJournalCleanupTest {
    @Test
    fun interruptedCleanupNeverLeavesPartlyDeletedRecoveryDataUnderItsActiveName() {
        val directory = Files.createTempDirectory("journal-cleanup-test").toFile()
        val journal = File(directory, "journal")
        val completed = File(directory, "journal.completed")
        try {
            journal.mkdirs()
            File(journal, "ready").writeText("1")
            File(journal, "committed").writeText("1")
            File(journal, "backup").writeText("old rules")
            try {
                CompletedJournalCleanup.finish(journal) {
                    File(completed, "ready").delete()
                    File(completed, "committed").delete()
                    throw AssertionError("simulated termination during cleanup")
                }
                fail("Expected simulated termination")
            } catch (_: AssertionError) {
                assertFalse(journal.exists())
                assertEquals("old rules", File(completed, "backup").readText())
            }
            CompletedJournalCleanup.discardCompleted(journal)
            assertFalse(completed.exists())
        } finally { directory.deleteRecursively() }
    }

    @Test
    fun snapshotRecoveryIgnoresPartlyDeletedCompletedJournalAndKeepsBothGenerations() {
        val context = RuntimeEnvironment.getApplication()
        val journal = File(context.filesDir, "applied_snapshot_journal")
        val completed = File(context.filesDir, "applied_snapshot_journal.completed")
        val current = File(context.filesDir, "last_applied_hosts")
        val previous = File(context.filesDir, "previous_applied_hosts")
        try {
            journal.deleteRecursively()
            completed.mkdirs()
            File(completed, "previous").mkdirs()
            File(completed, "previous/ADhosts").writeText("0.0.0.0 stale.example\n")
            current.mkdirs()
            previous.mkdirs()
            File(current, "ADhosts").writeText("0.0.0.0 current.example\n")
            File(previous, "ADhosts").writeText("0.0.0.0 previous.example\n")

            HostsUpdateManager.recoverAppliedSnapshots(context)
            HostsUpdateManager.recoverAppliedSnapshots(context)

            assertEquals("0.0.0.0 current.example\n", File(current, "ADhosts").readText())
            assertEquals("0.0.0.0 previous.example\n", HostsUpdateManager.previousHosts(context).readText())
            assertFalse(completed.exists())
            assertFalse(journal.exists())
        } finally {
            journal.deleteRecursively(); completed.deleteRecursively()
            current.deleteRecursively(); previous.deleteRecursively()
        }
    }

    @Test
    fun subscriptionRecoveryIgnoresPartlyDeletedCompletedJournalAndKeepsAppliedFiles() {
        val context = RuntimeEnvironment.getApplication()
        val journal = File(context.filesDir, "hosts_update_commit_journal")
        val completed = File(context.filesDir, "hosts_update_commit_journal.completed")
        val merged = File(context.filesDir, "ADhosts")
        try {
            journal.deleteRecursively()
            completed.mkdirs()
            File(completed, "0.old").writeText("0.0.0.0 stale.example\n")
            merged.writeText("0.0.0.0 current.example\n")

            HostsSubscriptionManager.recoverIncompleteCommit(context)
            HostsSubscriptionManager.recoverIncompleteCommit(context)

            assertEquals("0.0.0.0 current.example\n", merged.readText())
            assertFalse(completed.exists())
            assertFalse(journal.exists())
        } finally {
            journal.deleteRecursively(); completed.deleteRecursively(); merged.delete()
        }
    }

    @Test
    fun incompleteActiveJournalsArePreservedAndRejectedInsteadOfDiscarded() {
        val context = RuntimeEnvironment.getApplication()
        val snapshot = File(context.filesDir, "applied_snapshot_journal")
        val subscription = File(context.filesDir, "hosts_update_commit_journal")
        try {
            snapshot.mkdirs()
            File(snapshot, "previous").mkdirs()
            subscription.mkdirs()
            File(subscription, "0.old").writeText("old rules")
            listOf<() -> Unit>(
                { HostsUpdateManager.recoverAppliedSnapshots(context) },
                { HostsSubscriptionManager.recoverIncompleteCommit(context) }
            ).forEach { recovery ->
                try {
                    recovery()
                    fail("Expected incomplete active journal rejection")
                } catch (_: IOException) { /* Recovery data remains available for diagnosis. */ }
            }
            assertTrue(snapshot.isDirectory)
            assertEquals("old rules", File(subscription, "0.old").readText())
        } finally { snapshot.deleteRecursively(); subscription.deleteRecursively() }
    }
}
