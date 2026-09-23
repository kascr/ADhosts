package com.kascr.adhosts.module

import java.util.zip.ZipInputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class RootModuleAssetTest {

    @Test
    fun `module archive contains only the declarative overlay`() {
        val entries = linkedMapOf<String, String>()
        RuntimeEnvironment.getApplication().assets.open("AD_lite-v1.0.1.zip").use { input ->
            ZipInputStream(input).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    entries[entry.name] = zip.bufferedReader(Charsets.UTF_8).readText()
                }
            }
        }

        assertEquals(setOf("module.prop", "system/etc/hosts"), entries.keys)
        assertFalse(entries.values.any { it.contains("iptables") || it.contains("service.sh") })
        assertFalse(entries.values.any { it.contains("\r\n") })
    }
}
