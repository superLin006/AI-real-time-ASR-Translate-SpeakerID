package com.k2fsa.sherpa.onnx.pipeline

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.TAG
import kotlinx.coroutines.*

/**
 * 音频录制器封装
 * 职责：管理 AudioRecord 生命周期，提供简洁的录制接口
 */
class AudioRecorder(
    private val sampleRate: Int = 16000,
    private val intervalSeconds: Float = 0.1f  // 每次读取的音频长度（秒）
) {
    private var audioRecord: AudioRecord? = null
    private var recordingJob: Job? = null
    private var isRecording = false
    
    /**
     * 检查是否正在录制
     */
    fun isRecording(): Boolean = isRecording
    
    /**
     * 启动录制
     * @param onAudioData 音频数据回调，运行在 IO 线程
     * @return true=成功启动，false=启动失败
     */
    fun start(onAudioData: suspend (FloatArray) -> Unit): Boolean {
        if (isRecording) {
            Log.w(TAG, "AudioRecorder already started")
            return false
        }
        
        try {
            // 配置 AudioRecord
            val audioSource = MediaRecorder.AudioSource.MIC
            val channelConfig = AudioFormat.CHANNEL_IN_MONO
            val audioFormat = AudioFormat.ENCODING_PCM_16BIT
            
            val minBufferSize = AudioRecord.getMinBufferSize(sampleRate, channelConfig, audioFormat)
            if (minBufferSize == AudioRecord.ERROR || minBufferSize == AudioRecord.ERROR_BAD_VALUE) {
                Log.e(TAG, "Invalid AudioRecord buffer size: $minBufferSize")
                return false
            }
            
            audioRecord = AudioRecord(
                audioSource,
                sampleRate,
                channelConfig,
                audioFormat,
                minBufferSize * 2  // 使用 2 倍缓冲区
            )
            
            // 检查状态
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord initialization failed")
                audioRecord?.release()
                audioRecord = null
                return false
            }
            
            // 开始录制
            audioRecord?.startRecording()
            isRecording = true
            
            // 启动采集协程
            recordingJob = CoroutineScope(Dispatchers.IO).launch {
                captureAudio(onAudioData)
            }
            
            Log.i(TAG, "AudioRecorder started (sampleRate=$sampleRate, interval=${intervalSeconds}s)")
            return true
            
        } catch (e: SecurityException) {
            Log.e(TAG, "AudioRecord permission denied", e)
            cleanup()
            return false
        } catch (e: Exception) {
            Log.e(TAG, "AudioRecord start failed", e)
            cleanup()
            return false
        }
    }
    
    /**
     * 停止录制
     */
    fun stop() {
        if (!isRecording) return
        
        isRecording = false
        recordingJob?.cancel()
        recordingJob = null
        
        cleanup()
        
        Log.i(TAG, "AudioRecorder stopped")
    }
    
    /**
     * 音频采集循环
     */
    private suspend fun captureAudio(onAudioData: suspend (FloatArray) -> Unit) {
        val bufferSize = (intervalSeconds * sampleRate).toInt()
        val buffer = ShortArray(bufferSize)
        
        try {
            while (isRecording && audioRecord != null) {
                val readCount = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                
                if (readCount > 0) {
                    // 转换为 Float 并回调
                    val samples = FloatArray(readCount) { buffer[it] / 32768.0f }
                    onAudioData(samples)
                } else if (readCount < 0) {
                    Log.e(TAG, "AudioRecord read error: $readCount")
                    break
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Audio capture error", e)
        } finally {
            // 发送结束信号（空数组）
            onAudioData(FloatArray(0))
        }
    }
    
    /**
     * 清理资源
     */
    private fun cleanup() {
        try {
            audioRecord?.stop()
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord stop error: ${e.message}")
        }
        
        try {
            audioRecord?.release()
        } catch (e: Exception) {
            Log.w(TAG, "AudioRecord release error: ${e.message}")
        }
        
        audioRecord = null
    }
    
    /**
     * 析构时确保资源释放
     */
    protected fun finalize() {
        stop()
    }
}
