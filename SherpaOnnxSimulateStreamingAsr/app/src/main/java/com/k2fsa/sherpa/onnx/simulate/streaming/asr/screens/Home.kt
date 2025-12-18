package com.k2fsa.sherpa.onnx.simulate.streaming.asr.screens

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import android.widget.Toast
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.R
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.SimulateStreamingAsr
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.TAG
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlin.math.absoluteValue

private var audioRecord: AudioRecord? = null
private const val sampleRateInHz = 16000
private var samplesChannel = Channel<FloatArray>(capacity = Channel.UNLIMITED)

// 带时间戳、说话人标签和翻译的结果数据类
data class SpeakerResult(
    val timestamp: String,          // 新增：时间戳
    val speakerName: String,
    val originalText: String,
    val translatedText: String?,    // null 表示还没翻译，空字符串也保留
    val isFinal: Boolean = true
)

@Composable
fun HomeScreen() {
    val context = LocalContext.current
    val clipboardManager = LocalClipboardManager.current
    val activity = LocalContext.current as Activity
    
    var isStarted by remember { mutableStateOf(false) }
    val resultList: MutableList<SpeakerResult> = remember { mutableStateListOf() }
    val lazyColumnListState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()
    
    // 录音开始时间（用于计算时间戳）
    val recordingStartTime = remember { mutableStateOf(0L) }
    
    // 翻译管理
    val translationJobs = remember { mutableMapOf<Int, Job>() }
    val translationCache = remember { mutableMapOf<String, String>() }
    
    // 防抖动：最小翻译间隔（毫秒）
    val minTranslationInterval = 500L
    val lastTranslationTime = remember { mutableStateOf(0L) }

    val onRecordingButtonClick: () -> Unit = {
        isStarted = !isStarted
        if (isStarted) {
            if (ActivityCompat.checkSelfPermission(
                    activity,
                    Manifest.permission.RECORD_AUDIO
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                Log.i(TAG, "Recording is not allowed")
            } else {
                // 🕐 记录录音开始时间
                recordingStartTime.value = System.currentTimeMillis()
                
                // 录音配置
                val audioSource = MediaRecorder.AudioSource.MIC
                val channelConfig = AudioFormat.CHANNEL_IN_MONO
                val audioFormat = AudioFormat.ENCODING_PCM_16BIT
                val numBytes = AudioRecord.getMinBufferSize(sampleRateInHz, channelConfig, audioFormat)
                audioRecord = AudioRecord(
                    audioSource,
                    sampleRateInHz,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    numBytes * 2
                )

                SimulateStreamingAsr.vad.reset()

                // 音频采集协程
                CoroutineScope(Dispatchers.IO).launch {
                    Log.i(TAG, "processing samples")
                    val interval = 0.1
                    val bufferSize = (interval * sampleRateInHz).toInt()
                    val buffer = ShortArray(bufferSize)

                    audioRecord?.let { it ->
                        it.startRecording()

                        while (isStarted) {
                            val ret = audioRecord?.read(buffer, 0, buffer.size)
                            ret?.let { n ->
                                val samples = FloatArray(n) { buffer[it] / 32768.0f }
                                samplesChannel.send(samples)
                            }
                        }
                        val samples = FloatArray(0)
                        samplesChannel.send(samples)
                    }
                }

                // VAD + ASR + 说话人识别 + 翻译处理协程
                CoroutineScope(Dispatchers.Default).launch {
                    var buffer = arrayListOf<Float>()
                    var offset = 0
                    val windowSize = 512
                    var isSpeechStarted = false
                    var speechStartTime = 0L  // 语音段开始时间
                    var startTime = System.currentTimeMillis()
                    var lastText = ""
                    var added = false
                    var currentIndex = -1

                    while (isStarted) {
                        for (s in samplesChannel) {
                            if (s.isEmpty()) {
                                break
                            }

                            buffer.addAll(s.toList())
                            
                            // VAD 处理
                            while (offset + windowSize < buffer.size) {
                                SimulateStreamingAsr.vad.acceptWaveform(
                                    buffer.subList(offset, offset + windowSize).toFloatArray()
                                )
                                offset += windowSize
                                
                                if (!isSpeechStarted && SimulateStreamingAsr.vad.isSpeechDetected()) {
                                    isSpeechStarted = true
                                    startTime = System.currentTimeMillis()
                                    // 🕐 记录语音段开始时间（相对于录音开始）
                                    speechStartTime = System.currentTimeMillis()
                                }
                            }

                            // 实时 ASR（中间结果）
                            val elapsed = System.currentTimeMillis() - startTime
                            if (isSpeechStarted && elapsed > 200) {
                                val stream = SimulateStreamingAsr.recognizer.createStream()
                                stream.acceptWaveform(
                                    buffer.subList(0, offset).toFloatArray(),
                                    sampleRateInHz
                                )
                                SimulateStreamingAsr.recognizer.decode(stream)
                                val result = SimulateStreamingAsr.recognizer.getResult(stream)
                                stream.release()

                                lastText = result.text

                                if (lastText.isNotBlank()) {
                                    // 🕐 计算时间戳
                                    val timestamp = formatTimestamp(speechStartTime - recordingStartTime.value)
                                    
                                    // 创建或更新中间结果
                                    val tempResult = SpeakerResult(
                                        timestamp = timestamp,
                                        speakerName = "...",
                                        originalText = lastText,
                                        translatedText = null,  // ✅ 不显示"翻译中"，保持 null
                                        isFinal = false
                                    )
                                    
                                    if (!added || resultList.isEmpty()) {
                                        resultList.add(tempResult)
                                        currentIndex = resultList.size - 1
                                        added = true
                                    } else {
                                        // ✅ 保留之前的翻译（如果有）
                                        val oldTranslation = if (currentIndex >= 0 && currentIndex < resultList.size) {
                                            resultList[currentIndex].translatedText
                                        } else null
                                        
                                        resultList[currentIndex] = tempResult.copy(
                                            translatedText = oldTranslation  // 保留旧译文
                                        )
                                    }

                                    coroutineScope.launch {
                                        lazyColumnListState.animateScrollToItem(resultList.size - 1)
                                    }
                                    
                                    // 🔥 实时翻译中间结果
                                    val now = System.currentTimeMillis()
                                    if (SimulateStreamingAsr.isTranslatorReady() && 
                                        (now - lastTranslationTime.value) > minTranslationInterval) {
                                        
                                        lastTranslationTime.value = now
                                        
                                        // 取消之前的翻译任务
                                        translationJobs[currentIndex]?.cancel()
                                        
                                        // 启动新的翻译任务
                                        val job = CoroutineScope(Dispatchers.IO).launch {
                                            try {
                                                // 检查缓存
                                                val cachedTranslation = translationCache[lastText]
                                                val translation = if (cachedTranslation != null) {
                                                    Log.d(TAG, "Using cached translation for: $lastText")
                                                    cachedTranslation
                                                } else {
                                                    Log.d(TAG, "Translating intermediate: $lastText")
                                                    val trans = SimulateStreamingAsr.translateText(lastText)
                                                    if (trans != null && trans.isNotEmpty()) {
                                                        translationCache[lastText] = trans
                                                        trans
                                                    } else null
                                                }
                                                
                                                // ✅ 直接更新翻译结果，不显示"翻译中"状态
                                                if (translation != null && currentIndex >= 0 && currentIndex < resultList.size) {
                                                    resultList[currentIndex] = resultList[currentIndex].copy(
                                                        translatedText = translation
                                                    )
                                                }
                                            } catch (e: CancellationException) {
                                                Log.d(TAG, "Translation cancelled")
                                            } catch (e: Exception) {
                                                Log.e(TAG, "Translation error: ${e.message}")
                                            }
                                        }
                                        translationJobs[currentIndex] = job
                                    }
                                }

                                startTime = System.currentTimeMillis()
                            }

                            // 处理完整的语音段（VAD 检测到结束）
                            while (!SimulateStreamingAsr.vad.empty()) {
                                try {
                                    val speechSegment = SimulateStreamingAsr.vad.front()
                                    val samples = speechSegment.samples
                                    
                                    Log.i(TAG, "Processing final speech segment, samples: ${samples.size}")
                                    
                                    // ASR 识别
                                    val stream = SimulateStreamingAsr.recognizer.createStream()
                                    stream.acceptWaveform(samples, sampleRateInHz)
                                    SimulateStreamingAsr.recognizer.decode(stream)
                                    val result = SimulateStreamingAsr.recognizer.getResult(stream)
                                    stream.release()
                                    
                                    Log.i(TAG, "Final ASR result: ${result.text}")

                                    // 🎤 说话人识别（限制最多15个）
                                    var speakerName = "Unknown"
                                    try {
                                        val embedding = SimulateStreamingAsr.extractEmbedding(
                                            samples, 
                                            sampleRateInHz
                                        )
                                        
                                        if (embedding != null) {
                                            speakerName = SimulateStreamingAsr.identifyOrRegisterSpeaker(
                                                embedding,
                                                maxSpeakers = 15  // ✅ 限制最多15个说话人
                                            )
                                        }
                                    } catch (e: Exception) {
                                        Log.e(TAG, "Speaker identification error: ${e.message}", e)
                                    }

                                    isSpeechStarted = false
                                    SimulateStreamingAsr.vad.pop()

                                    buffer = arrayListOf()
                                    offset = 0
                                    
                                    if (result.text.isNotBlank()) {
                                        // 🕐 计算时间戳
                                        val timestamp = formatTimestamp(speechStartTime - recordingStartTime.value)
                                        
                                        // ✅ 保留之前的翻译（如果有）
                                        val oldTranslation = if (added && currentIndex >= 0 && currentIndex < resultList.size) {
                                            resultList[currentIndex].translatedText
                                        } else null
                                        
                                        // 创建最终结果
                                        val finalResult = SpeakerResult(
                                            timestamp = timestamp,
                                            speakerName = speakerName,
                                            originalText = result.text,
                                            translatedText = oldTranslation,  // 保留旧译文
                                            isFinal = true
                                        )
                                        
                                        if (added && currentIndex >= 0 && currentIndex < resultList.size) {
                                            // 更新现有条目
                                            resultList[currentIndex] = finalResult
                                        } else {
                                            // 添加新条目
                                            resultList.add(finalResult)
                                            currentIndex = resultList.size - 1
                                        }

                                        coroutineScope.launch {
                                            lazyColumnListState.animateScrollToItem(resultList.size - 1)
                                        }
                                        
                                        // 🔥 翻译最终结果
                                        if (SimulateStreamingAsr.isTranslatorReady()) {
                                            // 取消之前的翻译任务
                                            translationJobs[currentIndex]?.cancel()
                                            
                                            val finalIndex = currentIndex
                                            val job = CoroutineScope(Dispatchers.IO).launch {
                                                try {
                                                    // 检查缓存
                                                    val cachedTranslation = translationCache[result.text]
                                                    val translation = if (cachedTranslation != null) {
                                                        Log.d(TAG, "Using cached translation for final: ${result.text}")
                                                        cachedTranslation
                                                    } else {
                                                        Log.d(TAG, "Translating final: ${result.text}")
                                                        val trans = SimulateStreamingAsr.translateText(result.text)
                                                        if (trans != null && trans.isNotEmpty()) {
                                                            translationCache[result.text] = trans
                                                            trans
                                                        } else null
                                                    }
                                                    
                                                    // ✅ 直接更新最终翻译
                                                    if (translation != null && finalIndex >= 0 && finalIndex < resultList.size) {
                                                        resultList[finalIndex] = resultList[finalIndex].copy(
                                                            translatedText = translation
                                                        )
                                                    }
                                                } catch (e: CancellationException) {
                                                    Log.d(TAG, "Final translation cancelled")
                                                } catch (e: Exception) {
                                                    Log.e(TAG, "Final translation error: ${e.message}")
                                                }
                                            }
                                            translationJobs[finalIndex] = job
                                        }
                                        
                                        added = false
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
            }
        } else {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
            
            // 取消所有翻译任务
            translationJobs.values.forEach { it.cancel() }
            translationJobs.clear()
        }
    }
    
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.TopCenter,
    ) {
        Column(modifier = Modifier) {
            HomeButtonRow(
                isStarted = isStarted,
                onRecordingButtonClick = onRecordingButtonClick,
                onCopyButtonClick = {
                    if (resultList.isNotEmpty()) {
                        val s = resultList.mapIndexed { i, result -> 
                            val translation = if (result.translatedText != null && result.translatedText.isNotEmpty()) {
                                "\n    → ${result.translatedText}"
                            } else ""
                            "${i + 1}: [${result.timestamp}] [${result.speakerName}] ${result.originalText}$translation"
                        }.joinToString(separator = "\n")
                        clipboardManager.setText(AnnotatedString(s))
                        Toast.makeText(context, "Copied to clipboard", Toast.LENGTH_SHORT).show()
                    } else {
                        Toast.makeText(context, "Nothing to copy", Toast.LENGTH_SHORT).show()
                    }
                },
                onClearButtonClick = {
                    resultList.clear()
                    translationCache.clear()
                    translationJobs.values.forEach { it.cancel() }
                    translationJobs.clear()
                }
            )

            if (resultList.size > 0) {
                LazyColumn(
                    modifier = Modifier
                        .fillMaxWidth()
                        .fillMaxHeight(),
                    contentPadding = PaddingValues(16.dp),
                    state = lazyColumnListState
                ) {
                    itemsIndexed(resultList) { index, result ->
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            // 原文行（带时间戳）
                            Row {
                                // 序号
                                Text(
                                    text = "${index + 1}: ",
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 14.sp
                                )
                                // 时间戳
                                Text(
                                    text = "[${result.timestamp}] ",
                                    color = Color(0xFF9E9E9E),
                                    fontSize = 13.sp,
                                    fontWeight = FontWeight.Normal
                                )
                                // 说话人
                                Text(
                                    text = "[${result.speakerName}] ",
                                    color = getSpeakerColor(result.speakerName),
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Bold
                                )
                                // 原文
                                Text(
                                    text = result.originalText,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontSize = 14.sp,
                                    fontWeight = if (result.isFinal) FontWeight.Normal else FontWeight.Light
                                )
                            }
                            
                            // 译文行（只有存在翻译时才显示）
                            if (result.translatedText != null && result.translatedText.isNotEmpty()) {
                                Row(modifier = Modifier.padding(start = 24.dp, top = 2.dp)) {
                                    Text(
                                        text = "→ ",
                                        color = Color(0xFF2196F3),
                                        fontSize = 14.sp
                                    )
                                    Text(
                                        text = result.translatedText,
                                        color = Color(0xFF2196F3),
                                        fontSize = 14.sp,
                                        fontWeight = if (result.isFinal) FontWeight.Normal else FontWeight.Light
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

// 🕐 格式化时间戳（毫秒 -> MM:SS）
fun formatTimestamp(milliseconds: Long): String {
    val totalSeconds = milliseconds / 1000
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return String.format("%02d:%02d", minutes, seconds)
}

@Composable
fun getSpeakerColor(speakerName: String): Color {
    val colors = listOf(
        Color(0xFF2196F3), // Blue
        Color(0xFF4CAF50), // Green
        Color(0xFFF44336), // Red
        Color(0xFFFF9800), // Orange
        Color(0xFF9C27B0), // Purple
        Color(0xFF00BCD4), // Cyan
        Color(0xFFFFEB3B), // Yellow
        Color(0xFF795548), // Brown
        Color(0xFF607D8B), // Blue Grey
        Color(0xFFE91E63), // Pink
        Color(0xFF009688), // Teal
        Color(0xFF8BC34A), // Light Green
        Color(0xFF3F51B5), // Indigo
        Color(0xFFFF5722), // Deep Orange
        Color(0xFF673AB7), // Deep Purple
    )
    
    return when {
        speakerName == "..." -> Color.Gray
        speakerName == "Unknown" -> Color.DarkGray
        speakerName == "S" -> Color(0xFF757575)  // ✅ 超过15个说话人用灰色
        speakerName.startsWith("Speaker ") -> {
            val num = speakerName.removePrefix("Speaker ").toIntOrNull() ?: 0
            if (num > 0 && num <= 15) {
                colors[(num - 1) % colors.size]
            } else {
                Color(0xFF757575)
            }
        }
        else -> colors[speakerName.hashCode().absoluteValue % colors.size]
    }
}

@SuppressLint("UnrememberedMutableState")
@Composable
private fun HomeButtonRow(
    modifier: Modifier = Modifier,
    isStarted: Boolean,
    onRecordingButtonClick: () -> Unit,
    onCopyButtonClick: () -> Unit,
    onClearButtonClick: () -> Unit,
) {
    Row(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
    ) {
        Button(onClick = onRecordingButtonClick) {
            Text(text = stringResource(if (isStarted) R.string.stop else R.string.start))
        }

        Spacer(modifier = Modifier.width(24.dp))

        Button(onClick = onCopyButtonClick) {
            Text(text = stringResource(id = R.string.copy))
        }

        Spacer(modifier = Modifier.width(24.dp))

        Button(onClick = onClearButtonClick) {
            Text(text = stringResource(id = R.string.clear))
        }
    }
}