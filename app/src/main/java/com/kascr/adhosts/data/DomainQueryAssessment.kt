package com.kascr.adhosts.data

import java.net.InetAddress

/** Classifies observed resolver targets, rather than ICMP replies or saved rules alone. */
internal object DomainQueryAssessment {
    enum class State { BLOCKED, LOCAL_ADDRESS, PARTIAL, NOT_EFFECTIVE, RESOLVED, UNKNOWN }

    fun assess(domain: String, result: LocalDomainLookup.Result, runtimeAddresses: List<String>?): State {
        val observed = listOf(result.ipv4, result.ipv6)
            .filter { it.status == LocalDomainLookup.Status.RESOLVED }
            .flatMap { it.addresses }
            .mapNotNull(::numericAddress)
        if (observed.isEmpty()) return State.UNKNOWN

        val blocked = observed.count { it.isAnyLocalAddress || it.isLoopbackAddress }
        val hostsBlockingAddresses = runtimeAddresses.orEmpty().mapNotNull(::numericAddress)
            .filter { it.isAnyLocalAddress || it.isLoopbackAddress }
        val hostsHasBlockingAddress = hostsBlockingAddresses.isNotEmpty()
        return when {
            blocked in 1 until observed.size -> State.PARTIAL
            blocked == 0 -> if (hostsHasBlockingAddress) State.NOT_EFFECTIVE else State.RESOLVED
            HostsRuleValidator.isLocalHostname(domain) || domain.endsWith(".localhost") -> State.LOCAL_ADDRESS
            observed.all { actual -> hostsBlockingAddresses.any { it.address.size == actual.address.size } } -> State.BLOCKED
            else -> State.LOCAL_ADDRESS
        }
    }

    private fun numericAddress(value: String): InetAddress? {
        val address = value.substringBefore('%')
        if (!HostsRuleValidator.isNumericIpAddress(address)) return null
        return runCatching { InetAddress.getByName(address) }.getOrNull()
    }

}
