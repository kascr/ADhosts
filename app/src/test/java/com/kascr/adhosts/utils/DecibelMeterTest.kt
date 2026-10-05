package com.kascr.adhosts.utils

import android.Manifest
import androidx.appcompat.app.AppCompatActivity
import com.kascr.adhosts.R
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
@LooperMode(LooperMode.Mode.PAUSED)
class DecibelMeterTest {
    private lateinit var activity: AppCompatActivity
    private lateinit var meter: DecibelMeter
    private var started = 0
    private var stopped = 0

    @Before fun setUp() {
        activity = Robolectric.buildActivity(AppCompatActivity::class.java).get().apply {
            setTheme(R.style.AppTheme)
        }
        Shadows.shadowOf(activity.application).grantPermissions(Manifest.permission.RECORD_AUDIO)
        meter = DecibelMeter(activity, object : DecibelMeter.DecibelCallback {
            override fun onMeasurementStarted() { ++started }
            override fun onMeasurementStopped() { ++stopped }
            override fun onDecibelUpdate(db: Double) = Unit
        })
    }

    @After fun cleanUp() {
        meter.stop(notifyStopped = false)
        ShadowLooper.idleMainLooper()
    }

    @Test fun negativeReadStopsAndReleasesTheRecorderOnce() {
        val recorder = FakeRecorder { -6 }
        meter.recorderFactory = { recorder }
        meter.start()
        awaitStopped()
        assertEquals(1, started)
        assertEquals(1, stopped)
        assertEquals(1, recorder.reads.get())
        assertEquals(1, recorder.releases.get())
        meter.stop()
        assertEquals(1, stopped)
    }

    @Test fun readExceptionRestoresStoppedStateAndAllowsAnotherStart() {
        val failing = FakeRecorder { throw IllegalStateException("invalid recorder") }
        meter.recorderFactory = { failing }
        meter.start()
        awaitStopped()
        assertEquals(1, failing.releases.get())
        val replacement = FakeRecorder { 0 }
        meter.recorderFactory = { replacement }
        meter.start()
        assertEquals(2, started)
        meter.stop()
        assertEquals(2, stopped)
        assertEquals(1, replacement.releases.get())
    }

    @Test fun zeroReadsWaitInsteadOfSpinning() {
        val recorder = FakeRecorder { 0 }
        meter.recorderFactory = { recorder }
        meter.start()
        assertTrue(recorder.firstRead.await(2, TimeUnit.SECONDS))
        Thread.sleep(100)
        meter.stop()
        assertTrue("Zero reads must yield between retries", recorder.reads.get() <= 10)
        assertEquals(1, recorder.releases.get())
        assertEquals(1, stopped)
    }

    @Test fun oldReaderCompletionCannotStopAReplacementSession() {
        val returnOldRead = CountDownLatch(1)
        val old = FakeRecorder { returnOldRead.await(2, TimeUnit.SECONDS); -6 }
        meter.recorderFactory = { old }
        meter.start()
        assertTrue(old.firstRead.await(2, TimeUnit.SECONDS))
        meter.stop()
        val replacement = FakeRecorder { 0 }
        meter.recorderFactory = { replacement }
        meter.start()
        returnOldRead.countDown()
        assertTrue(replacement.firstRead.await(2, TimeUnit.SECONDS))
        Thread.sleep(50)
        ShadowLooper.idleMainLooper()
        assertEquals(2, started)
        assertEquals(1, stopped)
        assertEquals(1, old.releases.get())
        assertEquals(0, replacement.releases.get())
        meter.stop()
        assertEquals(2, stopped)
        assertEquals(1, replacement.releases.get())
    }

    private fun awaitStopped() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (stopped == 0 && System.nanoTime() < deadline) {
            ShadowLooper.idleMainLooper()
            Thread.sleep(5)
        }
        assertEquals("Reader failure must notify the UI", 1, stopped)
    }

    private class FakeRecorder(private val readResult: () -> Int) : DecibelMeter.Recorder {
        override val bufferSampleCount = 32
        val firstRead = CountDownLatch(1)
        val reads = AtomicInteger()
        val releases = AtomicInteger()
        override fun start() = Unit
        override fun read(buffer: ShortArray): Int {
            reads.incrementAndGet()
            firstRead.countDown()
            return readResult()
        }
        override fun stop() = Unit
        override fun release() { releases.incrementAndGet() }
    }
}
