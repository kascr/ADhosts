package com.kascr.adhosts.utils

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
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

    private var audioRecord: AudioRecord? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var isMeasuring = false
    @Volatile private var measurementThread: Thread? = null
    private var isPermissionRequestPending = false

    companion object {
        private const val SAMPLE_RATE = 44100  // 采样率 44.1kHz
        private const val UPDATE_INTERVAL_MS = 500L
    }

    // 动态计算最小缓冲区大小
    private val minBufferSizeBytes = AudioRecord.getMinBufferSize(
        SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )
    private val bufferSizeBytes = minBufferSizeBytes
    private val bufferSampleCount = bufferSizeBytes / 2

    /**
     * 启动分贝仪（自动处理权限请求）
     */
    fun start() {
        if (isMeasuring || audioRecord != null || isPermissionRequestPending) return

        if (ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // 请求录音权限
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

    /**
     * 开始录音并周期性计算分贝值
     */
    private fun startRecording() {
        if (isMeasuring || audioRecord != null) return

        try {
            if (minBufferSizeBytes <= 0) {
                throw IllegalStateException(activity.getString(R.string.decibel_audio_init_failed))
            }

            val recorder = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSizeBytes
            )

            if (recorder.state != AudioRecord.STATE_INITIALIZED) {
                recorder.release()
                throw IllegalStateException(activity.getString(R.string.decibel_audio_init_failed))
            }

            audioRecord = recorder
            recorder.startRecording()
            isMeasuring = true
            callback.onMeasurementStarted()
            measurementThread = Thread(
                { readMeasurements(recorder) },
                "DecibelMeter"
            ).apply { start() }

        } catch (e: SecurityException) {
            releaseRecorder()
            callback.onMeasurementStopped()
            Toast.makeText(activity, R.string.decibel_permission_denied_start, Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            releaseRecorder()
            callback.onMeasurementStopped()
            Toast.makeText(activity, e.message ?: activity.getString(R.string.decibel_audio_init_failed), Toast.LENGTH_SHORT).show()
            e.printStackTrace()
        }
    }

    private fun readMeasurements(recorder: AudioRecord) {
        val buffer = ShortArray(bufferSampleCount)
        var lastUiUpdateAt = 0L

        while (isMeasuring && audioRecord === recorder) {
            val readResult = try {
                recorder.read(buffer, 0, buffer.size)
            } catch (_: IllegalStateException) {
                break
            }

            if (readResult <= 0) continue

            val now = System.currentTimeMillis()
            if (now - lastUiUpdateAt < UPDATE_INTERVAL_MS) continue
            lastUiUpdateAt = now

            val amplitude = calculateAmplitude(buffer, readResult)
            if (amplitude > 0) {
                val db = 20.0 * log10(amplitude)
                mainHandler.post {
                    if (isMeasuring && audioRecord === recorder) {
                        callback.onDecibelUpdate(db)
                    }
                }
            }
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
        if (requestCode == permissionRequestCode) {
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
        val hadActiveRecorder = audioRecord != null || isMeasuring
        releaseRecorder()
        if (notifyStopped && hadActiveRecorder) {
            callback.onMeasurementStopped()
        }
    }

    private fun releaseRecorder() {
        isMeasuring = false
        val recorder = audioRecord
        audioRecord = null
        measurementThread?.interrupt()
        measurementThread = null
        recorder?.apply {
            try {
                if (recordingState == AudioRecord.RECORDSTATE_RECORDING) {
                    stop()
                }
            } catch (_: IllegalStateException) {
                // The recorder can be released concurrently with its worker thread.
            } finally {
                release()
            }
        }
    }
}
