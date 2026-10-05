package com.kascr.adhosts.data

import android.app.ActivityManager
import android.os.SystemClock
import android.util.Log
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.kascr.adhosts.ui.activity.MainActivity
import kotlinx.coroutines.runBlocking
import java.net.InetAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Executes as the real application UID and SELinux context; never changes Hosts or DNS. */
@RunWith(AndroidJUnit4::class)
class LocalDomainLookupDeviceTest {
    private var foregroundActivity: ActivityScenario<MainActivity>? = null

    @Before fun bringApplicationToForeground() {
        foregroundActivity = ActivityScenario.launch(MainActivity::class.java)
        foregroundActivity!!.moveToState(Lifecycle.State.RESUMED)
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        val deadline = SystemClock.elapsedRealtime() + 5_000L
        val process = ActivityManager.RunningAppProcessInfo()
        do {
            ActivityManager.getMyMemoryState(process)
            if (process.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {
                instrumentation.waitForIdleSync()
                return
            }
            SystemClock.sleep(50L)
        } while (SystemClock.elapsedRealtime() < deadline)
        assertTrue("MainActivity must be in the foreground before resolver checks",
            process.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND)
    }

    @After fun closeForegroundActivity() {
        foregroundActivity?.close()
        foregroundActivity = null
    }
    @Test fun localhostUsesTheSystemHostsResolver() = runBlocking {
        val result = LocalDomainLookup.lookup("localhost")
        Log.i("ADhostsLocalResolverTest", "localhost IPv4=${result.ipv4} IPv6=${result.ipv6}")
        logJavaResolver("localhost")
        assertEquals(LocalDomainLookup.Status.RESOLVED, result.ipv4.status)
        assertTrue(result.ipv4.addresses.any { it.startsWith("127.") })
    }

    @Test fun ordinaryDomainResolvesFromTheRealApplicationProcess() = runBlocking {
        val domain = InstrumentationRegistry.getArguments().getString("localLookupDomain") ?: "www.baidu.com"
        val result = LocalDomainLookup.lookup(domain)
        Log.i("ADhostsLocalResolverTest", "$domain IPv4=${result.ipv4} IPv6=${result.ipv6}")
        logJavaResolver(domain)
        assertEquals("The test device must have working system resolution for $domain",
            LocalDomainLookup.Status.RESOLVED, result.ipv4.status)
        assertTrue(result.ipv4.addresses.isNotEmpty())
    }

    @Test fun existingBlockedDomainIsResolvedInTheRealApplicationContext() = runBlocking {
        val domain = InstrumentationRegistry.getArguments().getString("localLookupBlockedDomain")
            ?: "1500026601.vodplayer.wxamedia.com"
        val result = LocalDomainLookup.lookup(domain)
        Log.i("ADhostsLocalResolverTest", "$domain IPv4=${result.ipv4} IPv6=${result.ipv6}")
        logJavaResolver(domain)
        Log.i("ADhostsLocalResolverTest", "$domain active Root Hosts=${RootHostsStore.runtimeAddresses(domain)}")
        assertEquals("The selected domain must already exist in the device's active blocking Hosts",
            LocalDomainLookup.Status.RESOLVED, result.ipv4.status)
        assertTrue(result.ipv4.addresses.any { it == "0.0.0.0" || it.startsWith("127.") })
    }

    private fun logJavaResolver(domain: String) {
        val result = runCatching { InetAddress.getAllByName(domain).mapNotNull { it.hostAddress } }
        Log.i("ADhostsLocalResolverTest", "$domain Java resolver=$result")
    }
}
