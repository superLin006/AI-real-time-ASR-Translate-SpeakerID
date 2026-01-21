package com.k2fsa.sherpa.onnx.pipeline

import android.util.Log
import com.k2fsa.sherpa.onnx.config.ModelConfig
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.SimulateStreamingAsr
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.TAG
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel

/**
 * 语音处理 Pipeline
 * 职责：协调 VAD → ASR → Translation + SpeakerID
 * 完全保留原有逻辑，只是从 Home.kt 中提取出来
 */
class SpeechPipeline(
    private val onIntermediateResult: (PipelineResult) -> Unit,  // 中间结果回调
    private val onFinalResult: (PipelineResult) -> Unit,         // 最终结果回调  // 翻译更新回调 (索引, 译文)
    private val onTranslationUpdate: (Int, String) -> Unit       
) {
    private val samplesChannel = Channel<FloatArray>(capacity = Channel.UNLIMITED)
    private var processingJob: Job? = null
    private var isRunning = false
    
    // 翻译管理
    private val translationJobs = mutableMapOf<Int, Job>()
    private val translationCache = mutableMapOf<String, String>()
    private var lastTranslationTime = 0L
    
    // 当前处理状态
    private var currentResultIndex = -1
    private var recordingStartTime = 0L
    
    /**
     * 启动 Pipeline
     */
    fun start() {
        if (isRunning) return
        
        isRunning = true
        recordingStartTime = System.currentTimeMillis()
        SimulateStreamingAsr.vad.reset()
        
        // 启动处理协程
        processingJob = CoroutineScope(Dispatchers.Default).launch {
            processAudioStream()
        }
    }
    
    /**
     * 停止 Pipeline
     */
    fun stop() {
        isRunning = false
        processingJob?.cancel()
        translationJobs.values.forEach { it.cancel() }
        translationJobs.clear()
    }
    
    /**
     * 输入音频数据
     */
    suspend fun feedAudio(samples: FloatArray) {
        samplesChannel.send(samples)
    }
    
    /**
     * 核心处理流程（完全保留原有逻辑）
     */
    private suspend fun processAudioStream() {
        // 检查所有必需组件是否已初始化
        if (!SimulateStreamingAsr.isVadReady()) {
            Log.e(TAG, "VAD is not ready. Cannot process audio.")
            return
        }

        var buffer = arrayListOf<Float>()
        var offset = 0
        var isSpeechStarted = false
        var speechStartTime = 0L
        var startTime = System.currentTimeMillis()
        var lastText = ""
        var added = false  // 标记是否已添加中间结果

        while (isRunning) {
            for (samples in samplesChannel) {
                if (samples.isEmpty()) break
                
                buffer.addAll(samples.toList())
                
                // ========== VAD 检测 ==========
                while (offset + ModelConfig.Runtime.VAD_WINDOW_SIZE < buffer.size) {
                    SimulateStreamingAsr.vad.acceptWaveform(
                        buffer.subList(offset, offset + ModelConfig.Runtime.VAD_WINDOW_SIZE).toFloatArray()
                    )
                    offset += ModelConfig.Runtime.VAD_WINDOW_SIZE
                    
                    if (!isSpeechStarted && SimulateStreamingAsr.vad.isSpeechDetected()) {
                        isSpeechStarted = true
                        startTime = System.currentTimeMillis()
                        speechStartTime = System.currentTimeMillis()
                    }
                }
                
                // ========== 实时 ASR（中间结果）==========
                val elapsed = System.currentTimeMillis() - startTime
                if (isSpeechStarted && elapsed > ModelConfig.Pipeline.ASR_INTERMEDIATE_INTERVAL) {
                    val stream = SimulateStreamingAsr.recognizer.createStream()
                    stream.acceptWaveform(
                        buffer.subList(0, offset).toFloatArray(),
                        ModelConfig.Runtime.SAMPLE_RATE
                    )
                    SimulateStreamingAsr.recognizer.decode(stream)
                    val result = SimulateStreamingAsr.recognizer.getResult(stream)
                    stream.release()

                    lastText = result.text

                    if (lastText.isNotBlank()) {
                        val timestamp = formatTimestamp(speechStartTime - recordingStartTime)

                        // 🆕 检测语言（中间结果也检测）
                        val intermediateLang = extractLanguageCode(result.lang)
                        val targetLang = SimulateStreamingAsr.getTargetLanguage(intermediateLang)

                        // 创建中间结果
                        val tempResult = PipelineResult(
                            timestamp = timestamp,
                            speakerName = "...",
                            originalText = lastText,
                            translatedText = null,
                            isFinal = false,
                            detectedLanguage = intermediateLang,
                            targetLanguage = targetLang
                        )

                        // 🔥 关键：与原代码逻辑完全一致
                        if (!added) {
                            currentResultIndex++
                            added = true
                            onIntermediateResult(tempResult)  // 新增中间结果
                        } else {
                            onIntermediateResult(tempResult)  // 更新中间结果
                        }

                        // 实时翻译（如果启用）
                        if (ModelConfig.Features.ENABLE_REALTIME_TRANSLATION) {
                            maybeTranslate(lastText, currentResultIndex, isFinal = false, detectedLang = intermediateLang)
                        }
                    }
                    
                    startTime = System.currentTimeMillis()
                }
                
                // ========== 处理完整语音段（VAD 结束）==========
                while (!SimulateStreamingAsr.vad.empty()) {
                    try {
                        val speechSegment = SimulateStreamingAsr.vad.front()
                        val speechSamples = speechSegment.samples
                        
                        Log.i(TAG, "Processing final speech segment, samples: ${speechSamples.size}")
                        
                        // ASR 识别
                        val stream = SimulateStreamingAsr.recognizer.createStream()
                        stream.acceptWaveform(speechSamples, ModelConfig.Runtime.SAMPLE_RATE)
                        SimulateStreamingAsr.recognizer.decode(stream)
                        val asrResult = SimulateStreamingAsr.recognizer.getResult(stream)
                        stream.release()

                        Log.i(TAG, "Final ASR result: ${asrResult.text}")

                        // 🆕 自动语言检测
                        val detectedLang = extractLanguageCode(asrResult.lang)
                        Log.i(TAG, "Detected language: $detectedLang (${asrResult.lang})")
                        
                        // 说话人识别
                        var speakerName = "Unknown"
                        if (ModelConfig.Features.ENABLE_SPEAKER_ID) {
                            try {
                                val embedding = SimulateStreamingAsr.extractEmbedding(
                                    speechSamples,
                                    ModelConfig.Runtime.SAMPLE_RATE
                                )
                                
                                if (embedding != null) {
                                    speakerName = SimulateStreamingAsr.identifyOrRegisterSpeaker(
                                        embedding,
                                        ModelConfig.Pipeline.MAX_SPEAKERS
                                    )
                                }
                            } catch (e: Exception) {
                                Log.e(TAG, "Speaker identification error: ${e.message}", e)
                            }
                        }
                        
                        isSpeechStarted = false
                        SimulateStreamingAsr.vad.pop()
                        buffer = arrayListOf()
                        offset = 0
                        
                        if (asrResult.text.isNotBlank()) {
                            val timestamp = formatTimestamp(speechStartTime - recordingStartTime)

                            // 🆕 确定翻译方向（使用配置化的方法）
                            val targetLang = SimulateStreamingAsr.getTargetLanguage(detectedLang)
                            Log.i(TAG, "Translation direction: $detectedLang → ${targetLang ?: "none"}")

                            // 创建最终结果
                            val finalResult = PipelineResult(
                                timestamp = timestamp,
                                speakerName = speakerName,
                                originalText = asrResult.text,
                                translatedText = null,
                                isFinal = true,
                                detectedLanguage = detectedLang,  // 🆕 添加检测到的语言
                                targetLanguage = targetLang        // 🆕 添加目标语言
                            )
                            
                            // 🔥 关键：与原代码逻辑完全一致
                            if (added) {
                                // 更新已存在的中间结果为最终结果
                                onFinalResult(finalResult)
                            } else {
                                // 直接添加最终结果（没有中间结果的情况）
                                currentResultIndex++
                                onFinalResult(finalResult)
                            }

                            // 翻译最终结果（传入检测到的语言）
                            if (ModelConfig.Features.ENABLE_TRANSLATION) {
                                maybeTranslate(asrResult.text, currentResultIndex, isFinal = true, detectedLang = detectedLang)
                            }
                            
                            added = false  // 重置标记
                        }
                        
                    } catch (e: Exception) {
                        Log.e(TAG, "Error processing speech segment: ${e.message}", e)
                        SimulateStreamingAsr.vad.pop()
                        buffer = arrayListOf()
                        offset = 0
                    }
                }
            }
        }
    }
    
    /**
     * 翻译逻辑（带防抖和缓存）
     * 支持配置化的翻译模式（双向/单向）
     */
    private fun maybeTranslate(text: String, resultIndex: Int, isFinal: Boolean, detectedLang: String? = null) {
        if (!SimulateStreamingAsr.isTranslatorReady()) return

        val now = System.currentTimeMillis()
        if (!isFinal && (now - lastTranslationTime) < ModelConfig.Pipeline.MIN_TRANSLATION_INTERVAL) {
            return  // 防抖：太频繁则跳过
        }

        lastTranslationTime = now

        // 取消旧任务
        translationJobs[resultIndex]?.cancel()

        // 启动新翻译任务
        val job = CoroutineScope(Dispatchers.IO).launch {
            try {
                val mode = ModelConfig.Selection.TRANSLATION_MODE

                // 确定源语言
                val sourceLang = when (mode) {
                    "BIDIRECTIONAL" -> {
                        // 双向模式：使用检测到的语言，如果没有则跳过
                        val lang = detectedLang ?: "auto"
                        // 检查是否支持该语言的翻译
                        val targetLang = SimulateStreamingAsr.getTargetLanguage(lang)
                        if (targetLang == null) {
                            Log.d(TAG, "Language '$lang' not configured for translation, skipping")
                            return@launch
                        }
                        lang
                    }
                    "UNIDIRECTIONAL" -> {
                        // 单向模式：不检查语言，直接翻译（使用通配符）
                        "*"
                    }
                    else -> {
                        Log.w(TAG, "Unknown translation mode: $mode")
                        return@launch
                    }
                }

                // 检查缓存
                val translation = if (ModelConfig.Cache.ENABLE_TRANSLATION_CACHE &&
                                      translationCache.containsKey(text)) {
                    Log.d(TAG, "Using cached translation for: $text")
                    translationCache[text]!!
                } else {
                    val direction = if (mode == "BIDIRECTIONAL") {
                        "$sourceLang→${SimulateStreamingAsr.getTargetLanguage(sourceLang)}"
                    } else {
                        "unidirectional"
                    }
                    Log.d(TAG, "Translating ${if (isFinal) "final" else "intermediate"} [$direction]: $text")

                    val trans = SimulateStreamingAsr.translateText(text, sourceLang)
                    if (trans != null && trans.isNotEmpty()) {
                        if (ModelConfig.Cache.ENABLE_TRANSLATION_CACHE) {
                            translationCache[text] = trans
                        }
                        trans
                    } else null
                }

                // 更新翻译
                if (translation != null) {
                    onTranslationUpdate(resultIndex, translation)
                }
            } catch (e: CancellationException) {
                Log.d(TAG, "Translation cancelled")
            } catch (e: Exception) {
                Log.e(TAG, "Translation error: ${e.message}")
            }
        }
        translationJobs[resultIndex] = job
    }
    
    /**
     * 格式化时间戳
     */
    private fun formatTimestamp(milliseconds: Long): String {
        val totalSeconds = milliseconds / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format("%02d:%02d", minutes, seconds)
    }

    // ========== 🆕 语言检测辅助函数 ==========

    /**
     * 从 ASR 的 lang 字段提取语言代码
     * 支持 SenseVoice 格式 ("<|en|>") 和其他格式 ("en")
     * @param langSymbol ASR 输出的语言标识
     * @return 标准化的语言代码 "en", "zh", "yue", "ja", "ko", 或 "auto"
     */
    private fun extractLanguageCode(langSymbol: String?): String {
        if (langSymbol.isNullOrEmpty()) return "auto"

        // SenseVoice 格式：<|lang|>
        if (langSymbol.startsWith("<|") && langSymbol.endsWith("|>")) {
            val lang = langSymbol.substring(2, langSymbol.length - 2)
            return lang.lowercase()
        }

        // 其他格式：直接返回小写
        return langSymbol.lowercase()
    }
}

/**
 * Pipeline 输出结果
 */
data class PipelineResult(
    val timestamp: String,
    val speakerName: String,
    val originalText: String,
    val translatedText: String?,
    val isFinal: Boolean,
    val detectedLanguage: String = "auto",  // 🆕 检测到的语言
    val targetLanguage: String? = null      // 🆕 目标语言（翻译方向）
)
