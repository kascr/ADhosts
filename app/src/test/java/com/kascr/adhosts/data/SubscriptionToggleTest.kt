package com.kascr.adhosts.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class SubscriptionToggleTest {

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun disabledSubscriptionIsKeptButExcludedFromMergedHosts() {
        val first = Subscription("First", "https://example.com/first")
        val second = Subscription("Second", "https://example.com/second")
        val subscriptionsFile = File(context.filesDir, "hosts_subscriptions.json")
        val manualFile = File(context.filesDir, "manual_hosts_rules.txt")
        val mergedFile = File(context.filesDir, "ADhosts")
        val firstCache = sourceFile(first.url, enabled = true)
        val firstDisabled = sourceFile(first.url, enabled = false)
        val secondCache = sourceFile(second.url, enabled = true)
        subscriptionsFile.delete()
        manualFile.delete()
        mergedFile.delete()

        try {
            HostsSubscriptionManager.saveSubscriptions(context, listOf(first, second))
            firstCache.parentFile?.mkdirs()
            firstCache.writeText("0.0.0.0 first.example.com\n")
            secondCache.writeText("0.0.0.0 second.example.com\n")

            assertTrue(HostsSubscriptionManager.setSubscriptionEnabled(context, first.url, false))
            assertEquals(
                listOf(first.copy(enabled = false), second),
                HostsSubscriptionManager.getSubscriptions(context)
            )
            assertEquals(
                1,
                HostsSubscriptionManager.downloadAndMergeSubscriptions(context, refreshRemote = false)
            )
            val merged = mergedFile.readText()
            assertFalse(merged.contains("first.example.com"))
            assertTrue(merged.contains("second.example.com"))
            assertFalse(firstCache.exists())
            assertTrue(firstDisabled.isFile)
            assertEquals("0.0.0.0 first.example.com\n", firstDisabled.readText())
        } finally {
            subscriptionsFile.delete()
            manualFile.delete()
            mergedFile.delete()
            firstCache.delete()
            firstDisabled.delete()
            secondCache.delete()
        }
    }

    @Test
    fun legacySubscriptionDefaultsToEnabledAndCanBePersistentlyDisabled() {
        val file = File(context.filesDir, "hosts_subscriptions.json")
        val url = "https://example.com/legacy"
        file.delete()
        try {
            file.writeText("""[{"name":"Legacy","url":"$url"}]""")
            assertTrue(HostsSubscriptionManager.getSubscriptions(context).single().enabled)
            assertTrue(HostsSubscriptionManager.setSubscriptionEnabled(context, url, false))
            assertFalse(HostsSubscriptionManager.getSubscriptions(context).single().enabled)
        } finally {
            file.delete()
        }
    }

    @Test
    fun disablingLastSubscriptionClearsMergedRulesWithoutDeletingSource() {
        val url = "https://example.com/only-source"
        val subscriptionsFile = File(context.filesDir, "hosts_subscriptions.json")
        val manualFile = File(context.filesDir, "manual_hosts_rules.txt")
        val mergedFile = File(context.filesDir, "ADhosts")
        val cacheFile = sourceFile(url, enabled = true)
        val disabledFile = sourceFile(url, enabled = false)
        subscriptionsFile.delete()
        manualFile.delete()
        mergedFile.delete()

        try {
            HostsSubscriptionManager.saveSubscriptions(context, listOf(Subscription("Only", url)))
            cacheFile.parentFile?.mkdirs()
            cacheFile.writeText("0.0.0.0 ads.example.com\n")
            mergedFile.writeText("stale merged rules")

            assertTrue(HostsSubscriptionManager.setSubscriptionEnabled(context, url, false))
            assertFalse(mergedFile.exists())
            assertEquals(0, HostsSubscriptionManager.downloadAndMergeSubscriptions(context, false))
            assertFalse(HostsSubscriptionManager.getSubscriptions(context).single().enabled)
            assertFalse(cacheFile.exists())
            assertTrue(disabledFile.isFile)
        } finally {
            subscriptionsFile.delete()
            manualFile.delete()
            mergedFile.delete()
            cacheFile.delete()
            disabledFile.delete()
        }
    }

    @Test
    fun enablingMovesSavedSourceBackAndMergesWithoutNetwork() {
        val url = "https://example.com/paused-source"
        val subscriptionsFile = File(context.filesDir, "hosts_subscriptions.json")
        val mergedFile = File(context.filesDir, "ADhosts")
        val enabledFile = sourceFile(url, enabled = true)
        val disabledFile = sourceFile(url, enabled = false)
        subscriptionsFile.delete()
        mergedFile.delete()
        try {
            HostsSubscriptionManager.saveSubscriptions(
                context,
                listOf(Subscription("Paused", url, enabled = false))
            )
            disabledFile.parentFile?.mkdirs()
            disabledFile.writeText("0.0.0.0 ads.example.com\n")

            assertTrue(HostsSubscriptionManager.setSubscriptionEnabled(context, url, true))
            assertFalse(disabledFile.exists())
            assertTrue(enabledFile.isFile)
            assertEquals(1, HostsSubscriptionManager.downloadAndMergeSubscriptions(context, false))
            assertTrue(mergedFile.readText().contains("ads.example.com"))
        } finally {
            subscriptionsFile.delete()
            mergedFile.delete()
            enabledFile.delete()
            disabledFile.delete()
        }
    }

    @Test
    fun legacyCacheMigratesToTheFolderMatchingSubscriptionState() {
        val url = "https://example.com/legacy-cache"
        val subscriptionsFile = File(context.filesDir, "hosts_subscriptions.json")
        val legacyFile = File(File(context.filesDir, "hosts_source_cache"), sourceFileName(url))
        val enabledFile = sourceFile(url, enabled = true)
        val disabledFile = sourceFile(url, enabled = false)
        subscriptionsFile.delete()
        legacyFile.parentFile?.mkdirs()
        legacyFile.writeText("0.0.0.0 legacy.example.com\n")
        try {
            HostsSubscriptionManager.saveSubscriptions(
                context,
                listOf(Subscription("Legacy", url, enabled = false))
            )
            assertFalse(legacyFile.exists())
            assertFalse(enabledFile.exists())
            assertTrue(disabledFile.isFile)
        } finally {
            subscriptionsFile.delete()
            legacyFile.delete()
            enabledFile.delete()
            disabledFile.delete()
        }
    }

    @Test
    fun importingChangedSelectionMovesEachExistingSourceToItsFolder() {
        val first = Subscription("First", "https://example.com/import-first")
        val second = Subscription("Second", "https://example.com/import-second", enabled = false)
        val subscriptionsFile = File(context.filesDir, "hosts_subscriptions.json")
        val firstEnabled = sourceFile(first.url, enabled = true)
        val firstDisabled = sourceFile(first.url, enabled = false)
        val secondEnabled = sourceFile(second.url, enabled = true)
        val secondDisabled = sourceFile(second.url, enabled = false)
        subscriptionsFile.delete()
        try {
            HostsSubscriptionManager.saveSubscriptions(context, listOf(first, second))
            firstEnabled.parentFile?.mkdirs()
            secondDisabled.parentFile?.mkdirs()
            firstEnabled.writeText("0.0.0.0 first.example.com\n")
            secondDisabled.writeText("0.0.0.0 second.example.com\n")

            HostsSubscriptionManager.saveSubscriptions(
                context,
                listOf(first.copy(enabled = false), second.copy(enabled = true))
            )

            assertFalse(firstEnabled.exists())
            assertTrue(firstDisabled.isFile)
            assertTrue(secondEnabled.isFile)
            assertFalse(secondDisabled.exists())
        } finally {
            subscriptionsFile.delete()
            firstEnabled.delete()
            firstDisabled.delete()
            secondEnabled.delete()
            secondDisabled.delete()
        }
    }

    @Test
    fun unavailableEnabledSourceDoesNotReplaceLastMergedHosts() {
        val valid = Subscription("Valid", "https://example.com/valid-source")
        val invalid = Subscription("Invalid", "https://example.com/invalid-source")
        val subscriptionsFile = File(context.filesDir, "hosts_subscriptions.json")
        val mergedFile = File(context.filesDir, "ADhosts")
        val validFile = sourceFile(valid.url, enabled = true)
        val invalidFile = sourceFile(invalid.url, enabled = true)
        subscriptionsFile.delete()
        mergedFile.delete()
        try {
            HostsSubscriptionManager.saveSubscriptions(context, listOf(valid, invalid))
            validFile.parentFile?.mkdirs()
            validFile.writeText("0.0.0.0 valid.example.com\n")
            invalidFile.writeText("not a hosts rule\n")
            mergedFile.writeText("last working hosts")

            try {
                HostsSubscriptionManager.downloadAndMergeSubscriptions(context, refreshRemote = false)
                fail("An invalid enabled source must stop the merge")
            } catch (_: IOException) {
                assertEquals("last working hosts", mergedFile.readText())
            }
        } finally {
            subscriptionsFile.delete()
            mergedFile.delete()
            validFile.delete()
            invalidFile.delete()
        }
    }

    @Test
    fun emptyValidSourceCanRemoveItsPreviouslyMergedRules() {
        val url = "https://example.com/now-empty"
        val subscriptionsFile = File(context.filesDir, "hosts_subscriptions.json")
        val mergedFile = File(context.filesDir, "ADhosts")
        val sourceFile = sourceFile(url, enabled = true)
        subscriptionsFile.delete()
        mergedFile.delete()
        try {
            HostsSubscriptionManager.saveSubscriptions(
                context,
                listOf(Subscription("Now empty", url))
            )
            sourceFile.parentFile?.mkdirs()
            sourceFile.writeText("# No current rules\n")
            mergedFile.writeText("0.0.0.0 stale.example.com")

            assertEquals(0, HostsSubscriptionManager.downloadAndMergeSubscriptions(context, false))
            assertFalse(mergedFile.readText().contains("stale.example.com"))
        } finally {
            subscriptionsFile.delete()
            mergedFile.delete()
            sourceFile.delete()
        }
    }

    @Test
    fun bucketedMergeDeduplicatesLargeSourcesAndKeepsManualOverride() {
        val first = Subscription("First", "https://example.com/bucket-first")
        val second = Subscription("Second", "https://example.com/bucket-second")
        val subscriptionsFile = File(context.filesDir, "hosts_subscriptions.json")
        val manualFile = File(context.filesDir, "manual_hosts_rules.txt")
        val mergedFile = File(context.filesDir, "ADhosts")
        val firstFile = sourceFile(first.url, enabled = true)
        val secondFile = sourceFile(second.url, enabled = true)
        subscriptionsFile.delete()
        manualFile.delete()
        mergedFile.delete()
        try {
            HostsSubscriptionManager.saveSubscriptions(context, listOf(first, second))
            firstFile.parentFile?.mkdirs()
            firstFile.bufferedWriter().use { writer ->
                repeat(8_000) { index -> writer.appendLine("0.0.0.0 host$index.example.com") }
            }
            secondFile.bufferedWriter().use { writer ->
                for (index in 4_000 until 12_000) {
                    writer.appendLine("127.0.0.1 host$index.example.com")
                }
            }
            ManualHostsRuleManager.saveRules(
                context,
                listOf(ManualHostsRule("1.2.3.4", "host42.example.com"))
            )

            assertEquals(
                12_000,
                HostsSubscriptionManager.downloadAndMergeSubscriptions(context, false)
            )
            val merged = mergedFile.readText(Charsets.UTF_8)
            assertTrue(merged.contains("1.2.3.4 host42.example.com"))
            assertFalse(merged.contains("0.0.0.0 host42.example.com"))
            assertEquals(1, Regex("\\bhost42\\.example\\.com\\b").findAll(merged).count())
        } finally {
            subscriptionsFile.delete()
            manualFile.delete()
            mergedFile.delete()
            firstFile.delete()
            secondFile.delete()
        }
    }

    @Test
    fun mergedHostsSnapshotRestoresTheFileWithoutHoldingItsContent() {
        val mergedFile = File(context.filesDir, "ADhosts")
        mergedFile.delete()
        try {
            val original = buildString {
                repeat(10_000) { index -> appendLine("0.0.0.0 snapshot$index.example.com") }
            }
            mergedFile.writeText(original)
            val snapshot = HostsSubscriptionManager.createMergedHostsSnapshot(context)
            assertTrue(snapshot?.isFile == true)
            mergedFile.writeText("replacement")

            HostsSubscriptionManager.restoreMergedHostsCache(context, snapshot)

            assertEquals(original, mergedFile.readText())
            assertFalse(snapshot?.exists() == true)
        } finally {
            mergedFile.delete()
        }
    }

    private fun sourceFile(url: String, enabled: Boolean): File = File(
        File(File(context.filesDir, "hosts_sources"), if (enabled) "enabled" else "disabled"),
        sourceFileName(url)
    )

    private fun sourceFileName(url: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        return "$digest.hosts"
    }
}
