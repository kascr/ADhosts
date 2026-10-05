package com.kascr.adhosts.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertThrows
import org.junit.Test

class RootHostsRuntimeQueryTest {
    @Test
    fun exactDomainGetsAllValidAddressesAndIgnoresCommentsAndSuffixMatches() {
        assertEquals(
            listOf("0.0.0.0", "127.0.0.9", "2001:db8::9", "::1"),
            RootHostsStore.parseRuntimeAddresses("TARGET.example.", fixtureLines())
        )
    }

    @Test
    fun internationalQueryMatchesPunycodeAndUnicodeHostsEntries() {
        assertEquals(
            listOf("192.0.2.30", "2001:db8::30"),
            RootHostsStore.parseRuntimeAddresses("  BÜCHER.example.  ", fixtureLines())
        )
        assertEquals(
            listOf("192.0.2.30", "2001:db8::30"),
            RootHostsStore.parseRuntimeAddresses("xn--bcher-kva.example", fixtureLines())
        )
    }

    @Test
    fun unrelatedAndInvalidQueriesDoNotReturnAddresses() {
        assertEquals(emptyList<String>(), RootHostsStore.parseRuntimeAddresses("missing.example", fixtureLines()))
        assertEquals(emptyList<String>(), RootHostsStore.parseRuntimeAddresses("*.target.example", fixtureLines()))
        assertEquals(emptyList<String>(), RootHostsStore.parseRuntimeAddresses("target.example; reboot", fixtureLines()))
    }

    @Test
    fun commandReadsOnlyRuntimeHostsAndRejectsUnnormalizedOrUnsafeArguments() {
        val command = RootHostsStore.runtimeAddressesCommand("xn--bcher-kva.example")
        assertTrue(command.startsWith("awk -v host='xn--bcher-kva.example' "))
        assertTrue(command.endsWith(" '/system/etc/hosts'"))
        assertFalse(command.contains("/data/adb"))
        assertFalse(command.contains("setprop"))
        for (input in listOf("TARGET.example", "BÜCHER.example", "target.example; reboot", "target.example'", "")) {
            assertThrows(IllegalArgumentException::class.java) {
                RootHostsStore.runtimeAddressesCommand(input)
            }
        }
    }

    private fun fixtureLines(): Sequence<String> =
        requireNotNull(javaClass.getResourceAsStream("/runtime-hosts-fixture.txt"))
            .bufferedReader(Charsets.UTF_8).use { it.readLines().asSequence() }
}