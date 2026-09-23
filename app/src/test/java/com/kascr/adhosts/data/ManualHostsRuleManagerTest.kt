package com.kascr.adhosts.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.security.MessageDigest

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class ManualHostsRuleManagerTest {

    private val context get() = RuntimeEnvironment.getApplication()

    @Test
    fun parsesDomainOnlyAndCompleteHostsLines() {
        val result = ManualHostsRuleManager.parseEditorText(
            """
                ads.example.com
                127.0.0.1 tracker.example.com metrics.example.com
            """.trimIndent()
        )

        assertTrue(result.isValid)
        assertEquals(
            listOf(
                ManualHostsRule("0.0.0.0", "ads.example.com"),
                ManualHostsRule("127.0.0.1", "tracker.example.com"),
                ManualHostsRule("127.0.0.1", "metrics.example.com")
            ),
            result.rules
        )
    }

    @Test
    fun reportsDuplicateAndUnsupportedRules() {
        val result = ManualHostsRuleManager.parseEditorText(
            """
                ads.example.com
                0.0.0.0 ads.example.com
                *.example.com
                localhost
                127.0.0.1
            """.trimIndent()
        )

        assertFalse(result.isValid)
        assertEquals(listOf(2, 3, 4, 5), result.invalidLines)
    }

    @Test
    fun mergesLocalRulesWithoutSubscriptions() {
        val subscriptions = File(context.filesDir, "hosts_subscriptions.json")
        val manualRules = File(context.filesDir, "manual_hosts_rules.txt")
        val merged = File(context.filesDir, "ADhosts")
        subscriptions.delete()
        manualRules.delete()
        merged.delete()

        try {
            ManualHostsRuleManager.saveRules(
                context,
                listOf(ManualHostsRule("0.0.0.0", "ads.example.com"))
            )
            val count = HostsSubscriptionManager.downloadAndMergeSubscriptions(context)

            assertEquals(1, count)
            assertTrue(merged.readText().contains("# Local rules\n0.0.0.0 ads.example.com"))
        } finally {
            subscriptions.delete()
            manualRules.delete()
            merged.delete()
        }
    }

    @Test
    fun localRulesOverrideCachedNetworkRulesForSameHostname() {
        val url = "https://example.com/test-hosts"
        val subscriptions = File(context.filesDir, "hosts_subscriptions.json")
        val manualRules = File(context.filesDir, "manual_hosts_rules.txt")
        val merged = File(context.filesDir, "ADhosts")
        val cacheDirectory = File(context.filesDir, "hosts_source_cache")
        val hash = MessageDigest.getInstance("SHA-256")
            .digest(url.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
        val cacheFile = File(cacheDirectory, "$hash.hosts")
        val migratedFile = File(File(File(context.filesDir, "hosts_sources"), "enabled"), "$hash.hosts")

        subscriptions.delete()
        manualRules.delete()
        merged.delete()
        try {
            assertTrue(HostsSubscriptionManager.addSubscription(context, "Test", url))
            cacheDirectory.mkdirs()
            cacheFile.writeText(
                "1.2.3.4 ads.example.com\n5.6.7.8 other.example.com\n",
                Charsets.UTF_8
            )
            ManualHostsRuleManager.saveRules(
                context,
                listOf(ManualHostsRule("0.0.0.0", "ads.example.com"))
            )

            assertEquals(
                2,
                HostsSubscriptionManager.downloadAndMergeSubscriptions(
                    context,
                    refreshRemote = false
                )
            )
            val content = merged.readText(Charsets.UTF_8)
            assertFalse(cacheFile.exists())
            assertTrue(migratedFile.isFile)
            assertFalse(content.contains("1.2.3.4 ads.example.com"))
            assertTrue(content.contains("5.6.7.8 other.example.com"))
            assertTrue(content.contains("0.0.0.0 ads.example.com"))
        } finally {
            subscriptions.delete()
            manualRules.delete()
            merged.delete()
            cacheFile.delete()
            migratedFile.delete()
        }
    }
}
