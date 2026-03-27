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
        fun onDecibelUpdate(db: Double)
    }

    private var audioRecord: AudioRecord? = null
    private val handler = Handler(Looper.getMainLooper())

    companion object {
        private const val TAG = "DecibelMeter"
        private const val SAMPLE_RATE = 44100  // 采样率 44.1kHz
    }

    // 动态计算最小缓冲区大小
    private val BUFFER_SIZE = AudioRecord.getMinBufferSize(
        SAMPLE_RATE,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
    )

    /**
     * 启动分贝仪（自动处理权限请求）
     */
    fun start() {
        if (ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.RECORD_AUDIO
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            // 请求录音权限
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
        try {
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                BUFFER_SIZE
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("AudioRecord 初始化失败")
            }

            audioRecord?.startRecording()
            handler.postDelayed(updateDbRunnable, 500)

        } catch (e: SecurityException) {
            Toast.makeText(activity, "权限被拒绝，无法启动分贝仪", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * 周期性读取音频数据并计算分贝值，通过回调通知 UI
     */
    private val updateDbRunnable = object : Runnable {
        override fun run() {
            audioRecord?.let { recorder ->
                val buffer = ShortArray(BUFFER_SIZE)
                val readResult = recorder.read(buffer, 0, buffer.size)

                if (readResult > 0) {
                    val amplitude = calculateAmplitude(buffer)
                    if (amplitude > 0) {
                        val db = 20.0 * log10(amplitude)
                        // 通过回调传递分贝值（已在主线程）
                        callback.onDecibelUpdate(db)
                    }
                }
            }
            handler.postDelayed(this, 500)
        }
    }

    /**
     * 计算音频信号的 RMS 幅度（均方根）
     */
    private fun calculateAmplitude(buffer: ShortArray): Double {
        var sum = 0.0
        for (sample in buffer) {
            sum += sample.toDouble() * sample.toDouble()
        }
        return sqrt(sum / buffer.size)
    }

    /**
     * 处理权限请求结果回调
     */
    fun onRequestPermissionsResult(requestCode: Int, grantResults: IntArray) {
        if (requestCode == permissionRequestCode) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                startRecording()
            } else {
                Toast.makeText(activity, "权限被拒绝，无法使用分贝仪", Toast.LENGTH_SHORT).show()
            }
        }
    }

    /**
     * 停止分贝仪并释放资源
     */
    fun stop() {
        handler.removeCallbacks(updateDbRunnable)
        audioRecord?.apply {
            stop()
            release()
        }
        audioRecord = null
    }
}
