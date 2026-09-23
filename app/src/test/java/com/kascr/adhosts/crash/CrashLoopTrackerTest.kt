package com.kascr.adhosts.crash

import android.content.Context
import org.junit.After
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
class CrashLoopTrackerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        CrashLoopTracker.clear(context)
    }

    @After
    fun tearDown() {
        CrashLoopTracker.clear(context)
    }

    @Test
    fun thirdCrashWithinTwoMinutes_enablesLoopProtection() {
        val start = 1_000_000L

        assertFalse(CrashLoopTracker.recordCrash(context, start))
        assertFalse(CrashLoopTracker.recordCrash(context, start + 30_000L))
        assertTrue(CrashLoopTracker.recordCrash(context, start + 60_000L))
        assertTrue(CrashLoopTracker.isLoopDetected(context, start + 60_000L))
    }

    @Test
    fun crashesOutsideWindow_doNotEnableLoopProtection() {
        val start = 1_000_000L

        CrashLoopTracker.recordCrash(context, start)
        CrashLoopTracker.recordCrash(context, start + 30_000L)

        assertFalse(CrashLoopTracker.recordCrash(context, start + 151_000L))
    }

    @Test
    fun clear_disablesLoopProtection() {
        val start = 1_000_000L
        repeat(3) { index ->
            CrashLoopTracker.recordCrash(context, start + index * 1_000L)
        }

        CrashLoopTracker.clear(context)

        assertFalse(CrashLoopTracker.isLoopDetected(context, start + 3_000L))
    }
}
