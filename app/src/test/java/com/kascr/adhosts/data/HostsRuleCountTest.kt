package com.kascr.adhosts.data

import org.junit.Assert.assertEquals
import org.junit.Test

class HostsRuleCountTest {

    @Test
    fun `counts ad blocking and GitHub520 style rules`() {
        val rules = sequenceOf(
            "127.0.0.1 ads.example.com",
            "0.0.0.0 tracker.example.com",
            "140.82.113.3 github.com",
            "2606:50c0:8000::153 github.io"
        )

        assertEquals(4, HostsSubscriptionManager.countEffectiveRules(rules))
    }

    @Test
    fun `ignores comments malformed entries and localhost headers`() {
        val rules = sequenceOf(
            "# generated hosts",
            "",
            "127.0.0.1 localhost",
            "::1 ip6-localhost",
            "||ads.example.com^",
            "999.0.0.1 invalid.example.com"
        )

        assertEquals(0, HostsSubscriptionManager.countEffectiveRules(rules))
    }

    @Test
    fun `accepts multiple hostnames and inline comments`() {
        val rules = sequenceOf(
            "1.2.3.4 one.example.com two.example.com # source note"
        )

        assertEquals(1, HostsSubscriptionManager.countEffectiveRules(rules))
    }
}
