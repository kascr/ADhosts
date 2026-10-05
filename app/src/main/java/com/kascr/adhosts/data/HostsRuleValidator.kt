package com.kascr.adhosts.data

import java.net.IDN
import java.net.InetAddress
import java.util.Locale

/** Shared syntax checks for downloaded and manually entered Hosts rules. */
internal object HostsRuleValidator {
    private val hostnamePattern = Regex(
        "(?=.{1,253}${'$'})(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)*" +
            "[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"
    )
    private val ipv6Characters = Regex("[0-9a-fA-F:.]+")
    private val localHostNames = setOf(
        "localhost",
        "localhost.localdomain",
        "ip6-localhost",
        "ip6-loopback"
    )

    fun isLocalHostname(hostname: String): Boolean = hostname in localHostNames

    fun isNumericIpAddress(value: String): Boolean {
        if (':' in value) {
            // The character check prevents DNS lookups; the parser checks actual IPv6 structure.
            return value.count { it == ':' } >= 2 && ipv6Characters.matches(value) &&
                runCatching { InetAddress.getByName(value) }.isSuccess
        }
        val octets = value.split('.')
        return octets.size == 4 && octets.all { octet ->
            octet.isNotEmpty() && octet.length <= 3 &&
                octet.all { it in '0'..'9' } && octet.toIntOrNull()?.let { it in 0..255 } == true
        }
    }

    fun normalizeHostname(value: String): String? {
        val hostname = value.trimEnd('.').lowercase(Locale.ROOT)
        if (hostname.isEmpty()) return null
        val ascii = if (hostname.any { it.code > 127 }) {
            runCatching { IDN.toASCII(hostname, IDN.USE_STD3_ASCII_RULES) }.getOrNull()
                ?: return null
        } else hostname
        if (ascii.all { it in '0'..'9' || it == '.' }) return null
        return ascii.takeIf(hostnamePattern::matches)
    }
}
