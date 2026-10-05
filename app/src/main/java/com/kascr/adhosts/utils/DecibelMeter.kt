package com.kascr.adhosts.utils

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.kascr.adhosts.R
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * 分贝计工具类
 * 使用回调接口模式，将分贝值实时回调给调用方，解耦 UI 依赖。
 */
class DecibelMeter(
    private val activity: AppCompatActivity,
    private val callback: DecibelCallback,
    private val permissionRequestCode: Int = 1
) {

    /**
     * 分贝值更新回调接口
     */
    interface DecibelCallback {
        fun onMeasurementStarted()
        fun onMeasurementStopped()
        fun onDecibelUpdate(db: Double)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var activeSession: RecordingSession? = null
    private var sessionGeneration = 0L
    private var isPermissionRequestPending = false

    internal interface Recorder {
        val bufferSampleCount: Int
        fun start()
        fun read(buffer: ShortArray): Int
        fun stop()
        fun release()
    }

    internal var recorderFactory: () -> Recorder = { createRecorder() }

    private class RecordingSession(val recorder: Recorder, val generation: Long) {
        @Volatile var cancelled = false
        var worker: Thread? = null
    }

    companion object {
        private const val SAMPLE_RATE = 44100  // 采样率 44.1kHz
        private const val UPDATE_INTERVAL_MS = 500L
        private const val EMPTY_READ_RETRY_MS = 20L
        private const val LOG_TAG = "DecibelMeter"
    }

    /**
     * 启动分贝仪（自动处理权限请求）
     */
    fun start() {
        onMainThread {
            if (activity.isFinishing || activity.isDestroyed ||
                activeSession != null || isPermissionRequestPending
            ) return@onMainThread

            if (ContextCompat.checkSelfPermission(
                    activity,
                    Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                isPermissionRequestPending = true
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(Manifest.permission.RECORD_AUDIO),
                    permissionRequestCode
                )
            } else {
                startRecording()
            }
        }
    }

    /**
     * 开始录音并周期性计算分贝值
     */
    private fun startRecording() {
        if (activeSession != null || activity.isFinishing || activity.isDestroyed) return

        val generation = ++sessionGeneration
        try {
            val session = RecordingSession(recorderFactory(), generation)
            activeSession = session
            session.recorder.start()
            session.worker = Thread({ readMeasurements(session) }, LOG_TAG)
            callback.onMeasurementStarted()
            if (activeSession === session && !session.cancelled) session.worker?.start()

        } catch (e: SecurityException) {
            finishFailedStart(generation)
            Toast.makeText(activity, R.string.decibel_permission_denied_start, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            finishFailedStart(generation)
            Toast.makeText(activity, e.message ?: activity.getString(R.string.decibel_audio_init_failed), Toast.LENGTH_SHORT).show()
            Log.w(LOG_TAG, "Unable to start recording", e)
        }
    }

    private fun readMeasurements(session: RecordingSession) {
        try {
            val buffer = ShortArray(session.recorder.bufferSampleCount)
            var lastUiUpdateAt = 0L
            while (!session.cancelled && activeSession === session) {
                val readResult = session.recorder.read(buffer)
                if (readResult < 0 || readResult > buffer.size) {
                    Log.w(LOG_TAG, "Audio read failed: $readResult")
                    break
                }
                if (readResult == 0) {
                    Thread.sleep(EMPTY_READ_RETRY_MS)
                    continue
                }
                val now = SystemClock.elapsedRealtime()
                if (now - lastUiUpdateAt < UPDATE_INTERVAL_MS) continue
                lastUiUpdateAt = now
                val amplitude = calculateAmplitude(buffer, readResult)
                if (amplitude > 0) {
                    val db = 20.0 * log10(amplitude)
                    mainHandler.post {
                        if (!session.cancelled && activeSession === session) {
                            callback.onDecibelUpdate(db)
                        }
                    }
                }
            }
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
        } catch (error: RuntimeException) {
            Log.w(LOG_TAG, "Recording interrupted", error)
        } finally {
            mainHandler.post { finishSession(session, notifyStopped = true) }
        }
    }

    /**
     * 计算音频信号的 RMS 幅度（均方根）
     */
    private fun calculateAmplitude(buffer: ShortArray, samplesRead: Int): Double {
        var sum = 0.0
        for (index in 0 until samplesRead) {
            val sample = buffer[index].toDouble()
            sum += sample * sample
        }
        return sqrt(sum / samplesRead)
    }

    /**
     * 处理权限请求结果回调
     */
    fun onRequestPermissionsResult(requestCode: Int, grantResults: IntArray) {
        onMainThread {
            if (requestCode != permissionRequestCode || !isPermissionRequestPending) return@onMainThread
            isPermissionRequestPending = false
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startRecording()
            } else {
                callback.onMeasurementStopped()
                Toast.makeText(activity, R.string.decibel_permission_denied_use, Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 停止分贝仪并释放资源
     */
    fun stop(notifyStopped: Boolean = true) {
        onMainThread {
            isPermissionRequestPending = false
            ++sessionGeneration
            activeSession?.let { finishSession(it, notifyStopped) }
        }
    }

    private fun finishFailedStart(generation: Long) {
        val session = activeSession
        if (session?.generation == generation) {
            finishSession(session, notifyStopped = true)
        } else if (sessionGeneration == generation && session == null) {
            callback.onMeasurementStopped()
        }
    }

    private fun finishSession(session: RecordingSession, notifyStopped: Boolean) {
        // A delayed completion from an old reader must never stop a replacement recorder.
        if (activeSession !== session) return
        activeSession = null
        session.cancelled = true
        session.worker?.interrupt()
        try {
            session.recorder.stop()
        } catch (error: RuntimeException) {
            Log.w(LOG_TAG, "Unable to stop recorder", error)
        } finally {
            try {
                session.recorder.release()
            } catch (error: RuntimeException) {
                Log.w(LOG_TAG, "Unable to release recorder", error)
            }
        }
        if (notifyStopped) callback.onMeasurementStopped()
    }

    private fun onMainThread(action: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) action() else mainHandler.post(action)
    }

    private fun createRecorder(): Recorder {
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            throw SecurityException(activity.getString(R.string.decibel_permission_denied_start))
        }
        val minBufferSizeBytes = AudioRecord.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBufferSizeBytes < 2) {
            throw IllegalStateException(activity.getString(R.string.decibel_audio_init_failed))
        }
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            minBufferSizeBytes
        )
        if (recorder.state != AudioRecord.STATE_INITIALIZED) {
            recorder.release()
            throw IllegalStateException(activity.getString(R.string.decibel_audio_init_failed))
        }
        return object : Recorder {
            override val bufferSampleCount = minBufferSizeBytes / 2
            override fun start() = recorder.startRecording()
            override fun read(buffer: ShortArray): Int = recorder.read(buffer, 0, buffer.size)
            override fun stop() = recorder.stop()
            override fun release() = recorder.release()
        }
    }
}
