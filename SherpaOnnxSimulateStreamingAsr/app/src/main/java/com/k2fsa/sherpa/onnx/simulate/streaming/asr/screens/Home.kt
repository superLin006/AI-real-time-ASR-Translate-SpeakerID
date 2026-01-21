package com.k2fsa.sherpa.onnx.simulate.streaming.asr.screens

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
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
import com.k2fsa.sherpa.onnx.config.ModelConfig
import com.k2fsa.sherpa.onnx.pipeline.AudioRecorder
import com.k2fsa.sherpa.onnx.pipeline.SpeechPipeline
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.R
import com.k2fsa.sherpa.onnx.simulate.streaming.asr.TAG
import kotlinx.coroutines.*
import kotlin.math.absoluteValue

private var audioRecorder: AudioRecorder? = null
private var speechPipeline: SpeechPipeline? = null

// 带时间戳、说话人标签和翻译的结果数据类
data class SpeakerResult(
    val timestamp: String,
    val speakerName: String,
    val originalText: String,
    val translatedText: String?,
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
    
    // Pipeline 结果回调
    val onIntermediateResult: (com.k2fsa.sherpa.onnx.pipeline.PipelineResult) -> Unit = { result ->
        coroutineScope.launch(Dispatchers.Main) {
            val displayResult = SpeakerResult(
                timestamp = result.timestamp,
                speakerName = result.speakerName,
                originalText = result.originalText,
                translatedText = result.translatedText,
                isFinal = result.isFinal
            )
            
            // 🔥 与原代码逻辑完全一致
            if (resultList.isEmpty() || resultList.last().isFinal) {
                // 没有中间结果或上一个已经是最终结果，添加新的
                resultList.add(displayResult)
            } else {
                // 更新最后一个中间结果
                resultList[resultList.lastIndex] = displayResult
            }
            
            lazyColumnListState.animateScrollToItem(resultList.size - 1)
        }
    }
    
    val onFinalResult: (com.k2fsa.sherpa.onnx.pipeline.PipelineResult) -> Unit = { result ->
        coroutineScope.launch(Dispatchers.Main) {
            val displayResult = SpeakerResult(
                timestamp = result.timestamp,
                speakerName = result.speakerName,
                originalText = result.originalText,
                translatedText = result.translatedText,
                isFinal = result.isFinal
            )
            
            // 🔥 与原代码逻辑完全一致
            if (resultList.isEmpty() || resultList.last().isFinal) {
                // 直接添加（没有中间结果的情况）
                resultList.add(displayResult)
            } else {
                // 更新最后一个中间结果为最终结果
                resultList[resultList.lastIndex] = displayResult
            }
            
            lazyColumnListState.animateScrollToItem(resultList.size - 1)
        }
    }
    
    val onTranslationUpdate: (Int, String) -> Unit = { resultIndex, translation ->
        coroutineScope.launch(Dispatchers.Main) {
            // 🔥 与原代码逻辑完全一致：直接更新指定索引的翻译
            if (resultIndex >= 0 && resultIndex < resultList.size) {
                resultList[resultIndex] = resultList[resultIndex].copy(
                    translatedText = translation
                )
            }
        }
    }
    
    val onRecordingButtonClick: () -> Unit = {
        isStarted = !isStarted
        
        if (isStarted) {
            // 检查录音权限
            if (ActivityCompat.checkSelfPermission(activity, Manifest.permission.RECORD_AUDIO) 
                != PackageManager.PERMISSION_GRANTED) {
                Log.i(TAG, "Recording is not allowed")
                isStarted = false
            } else {
                // 初始化 Pipeline
                speechPipeline = SpeechPipeline(
                    onIntermediateResult = onIntermediateResult,
                    onFinalResult = onFinalResult,
                    onTranslationUpdate = onTranslationUpdate
                )
                speechPipeline?.start()
                
                // 启动音频录制
                audioRecorder = AudioRecorder(sampleRate = ModelConfig.Runtime.SAMPLE_RATE)
                val success = audioRecorder?.start { samples ->
                    speechPipeline?.feedAudio(samples)
                }
                
                if (success != true) {
                    Toast.makeText(context, "Failed to start recording", Toast.LENGTH_SHORT).show()
                    isStarted = false
                    speechPipeline?.stop()
                    speechPipeline = null
                }
            }
            
        } else {
            audioRecorder?.stop()
            audioRecorder = null
            speechPipeline?.stop()
            speechPipeline = null
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
        speakerName == "S" -> Color(0xFF757575)  // 超过15个说话人用灰色
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
