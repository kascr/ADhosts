package com.kascr.adhosts.data

import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RecommendedHostsSetupTest {

    private val context get() = RuntimeEnvironment.getApplication()
    private val subscriptionsFile get() = File(context.filesDir, "hosts_subscriptions.json")

    @Before
    @After
    fun clearSubscriptions() {
        subscriptionsFile.delete()
    }

    @Test
    fun `recommended subscription is never added twice`() {
        assertTrue(
            HostsSubscriptionManager.addSubscription(
                context,
                "GitHub520",
                RecommendedHosts.GITHUB520_HOSTS_URL
            )
        )
        assertFalse(
            HostsSubscriptionManager.addSubscription(
                context,
                "GitHub520 duplicate",
                RecommendedHosts.GITHUB520_HOSTS_URL
            )
        )
        assertEquals(1, HostsSubscriptionManager.getSubscriptions(context).size)
    }
}
