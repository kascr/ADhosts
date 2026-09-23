package com.kascr.adhosts.ui.activity

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StartActivityTest {

    @Test
    fun `KernelSU 3 and newer require a metamodule`() {
        assertTrue(StartActivity.kernelSuRequiresMetaModule("3.0.0 (uapi: 5)"))
        assertTrue(StartActivity.kernelSuRequiresMetaModule("ksud v3.3.0 (uapi: 7)"))
    }

    @Test
    fun `older or unknown KernelSU versions do not trigger a false warning`() {
        assertFalse(StartActivity.kernelSuRequiresMetaModule("2.0.1"))
        assertFalse(StartActivity.kernelSuRequiresMetaModule("unknown"))
    }
}
