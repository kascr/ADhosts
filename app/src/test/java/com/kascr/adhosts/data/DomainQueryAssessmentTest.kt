package com.kascr.adhosts.data

import org.junit.Assert.assertEquals
import org.junit.Test

class DomainQueryAssessmentTest {
    @Test fun zeroHostsMappingAndLoopbackProbeAreBothBlockingTargets() {
        assertEquals(DomainQueryAssessment.State.BLOCKED,
            assess("127.0.0.1", null, listOf("0.0.0.0")))
    }

    @Test fun compressedIpv6LoopbackMatchesExpandedHostsAddress() {
        assertEquals(DomainQueryAssessment.State.BLOCKED,
            assess(null, "::1", listOf("0:0:0:0:0:0:0:1")))
    }

    @Test fun workingIpv6AlongsideBlockedIpv4IsPartialBlocking() {
        assertEquals(DomainQueryAssessment.State.PARTIAL,
            assess("0.0.0.0", "2001:db8::8", listOf("0.0.0.0")))
    }

    @Test fun publicResolutionDoesNotClaimAnExistingHostsBlockIsEffective() {
        assertEquals(DomainQueryAssessment.State.NOT_EFFECTIVE,
            assess("203.0.113.8", null, listOf("0.0.0.0")))
    }

    @Test fun ordinaryCustomMappingIsNotAnAdBlockingAddress() {
        assertEquals(DomainQueryAssessment.State.RESOLVED,
            assess("192.168.1.8", null, listOf("192.168.1.8")))
    }

    @Test fun unresolvedOrTimedOutProbesCannotProveBlocking() {
        val result = LocalDomainLookup.Result(
            LocalDomainLookup.Answer(LocalDomainLookup.Status.UNRESOLVED),
            LocalDomainLookup.Answer(LocalDomainLookup.Status.TIMEOUT))
        assertEquals(DomainQueryAssessment.State.UNKNOWN,
            DomainQueryAssessment.assess("ads.example", result, listOf("0.0.0.0")))
    }

    @Test fun loopbackWithoutReadableHostsIsReportedWithoutAttributingItToHosts() {
        assertEquals(DomainQueryAssessment.State.LOCAL_ADDRESS,
            assess("127.0.0.1", null, null))
    }

    @Test fun localhostIsALocalDestinationRatherThanAnAdBlockingRule() {
        val result = LocalDomainLookup.Result(resolved("127.0.0.1"), resolved("::1"))
        assertEquals(DomainQueryAssessment.State.LOCAL_ADDRESS,
            DomainQueryAssessment.assess("localhost", result, listOf("127.0.0.1", "::1")))
    }

    @Test fun mappedIpv4LoopbackRemainsABlockingAddress() {
        assertEquals(DomainQueryAssessment.State.BLOCKED,
            assess(null, "::ffff:127.0.0.1", listOf("0.0.0.0")))
    }

    @Test fun ipv6LoopbackCannotBeAttributedToAnIpv4OnlyHostsRule() {
        assertEquals(DomainQueryAssessment.State.LOCAL_ADDRESS,
            assess(null, "::1", listOf("0.0.0.0")))
    }

    @Test fun ipv4LoopbackCannotBeAttributedToAnIpv6OnlyHostsRule() {
        assertEquals(DomainQueryAssessment.State.LOCAL_ADDRESS,
            assess("127.0.0.1", null, listOf("::")))
    }

    private fun assess(ipv4: String?, ipv6: String?, hosts: List<String>?) =
        DomainQueryAssessment.assess("ads.example", LocalDomainLookup.Result(resolved(ipv4), resolved(ipv6)), hosts)

    private fun resolved(address: String?) = if (address == null)
        LocalDomainLookup.Answer(LocalDomainLookup.Status.UNRESOLVED)
    else LocalDomainLookup.Answer(LocalDomainLookup.Status.RESOLVED, listOf(address))
}
