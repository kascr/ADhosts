package com.kascr.adhosts.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class HostsBackupCodecTest {

    @Test
    fun exportsAndImportsNetworkAndManualRules() {
        val subscriptions = listOf(
            Subscription("Example", "https://example.com/hosts", enabled = false),
            Subscription("Active", "https://example.com/active", enabled = true)
        )
        val manualRules = listOf(
            ManualHostsRule("0.0.0.0", "ads.example.com"),
            ManualHostsRule("127.0.0.1", "tracker.example.com")
        )

        val restored = HostsBackupCodec.decode(
            HostsBackupCodec.encode(subscriptions, manualRules)
        )

        assertEquals(subscriptions, restored.subscriptions)
        assertEquals(manualRules, restored.manualRules)
    }

    @Test
    fun supportsManualOnlyBackup() {
        val rules = listOf(ManualHostsRule("0.0.0.0", "ads.example.com"))
        val restored = HostsBackupCodec.decode(HostsBackupCodec.encode(emptyList(), rules))

        assertEquals(emptyList<Subscription>(), restored.subscriptions)
        assertEquals(rules, restored.manualRules)
    }

    @Test
    fun legacySubscriptionArrayLeavesManualRulesUntouched() {
        val restored = HostsBackupCodec.decode(
            """[{"name":"Old","url":"https://example.com/old-hosts"}]"""
        )

        assertEquals(1, restored.subscriptions.size)
        assertEquals(true, restored.subscriptions.single().enabled)
        assertNull(restored.manualRules)
    }

    @Test
    fun rejectsInvalidManualRuleBeforeImport() {
        val invalid = """
            {
              "formatVersion": 2,
              "subscriptions": [],
              "manualRules": ["0.0.0.0 ads.example.com", "*.example.com"]
            }
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            HostsBackupCodec.decode(invalid)
        }
    }
}
