package com.kascr.adhosts.crash

import android.content.Context
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CrashReportStoreTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        CrashReportStore.clear(context)
        CrashLoopTracker.clear(context)
    }

    @After
    fun tearDown() {
        CrashReportStore.clear(context)
        CrashLoopTracker.clear(context)
    }

    @Test
    fun saveAndLoad_preservesDiagnosticFields() {
        val error = IllegalStateException("Subscription update failed")

        val saved = CrashReportStore.save(context, Thread.currentThread(), error)
        val loaded = CrashReportStore.load(context)

        assertNotNull(saved)
        assertNotNull(loaded)
        assertEquals(IllegalStateException::class.java.name, loaded?.exceptionName)
        assertEquals("Subscription update failed", loaded?.message)
        assertTrue(loaded?.stackTrace?.contains("IllegalStateException") == true)
        assertTrue(CrashReportStore.hasPending(context))
    }

    @Test
    fun save_redactsPrivateAppPaths() {
        val privatePath = context.filesDir.absolutePath
        val error = IllegalArgumentException("Invalid file: $privatePath/private-hosts.txt")

        CrashReportStore.save(context, Thread.currentThread(), error)
        val loaded = requireNotNull(CrashReportStore.load(context))

        assertFalse(loaded.fullText.contains(privatePath))
        assertTrue(loaded.fullText.contains("<app-files>/private-hosts.txt"))
    }

    @Test
    fun clear_removesPendingReport() {
        CrashReportStore.save(context, Thread.currentThread(), RuntimeException("test"))

        CrashReportStore.clear(context)

        assertFalse(CrashReportStore.hasPending(context))
    }
}
