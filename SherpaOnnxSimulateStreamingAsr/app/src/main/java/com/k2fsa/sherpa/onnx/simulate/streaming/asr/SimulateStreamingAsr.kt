package com.k2fsa.sherpa.onnx.simulate.streaming.asr

import android.app.Application
import android.content.res.AssetManager
import android.util.Log
import com.k2fsa.sherpa.onnx.*
import com.k2fsa.sherpa.onnx.config.ModelConfig
import java.io.File

/**
 * 模型管理器（单一职责：管理模型生命周期）
 * 不再包含业务逻辑，只负责模型的初始化、使用和释放
 */
object SimulateStreamingAsr {
    private var _recognizer: OfflineRecognizer? = null
    val recognizer: OfflineRecognizer
        get() {
            return _recognizer!!
        }

    private var _vad: Vad? = null
    val vad: Vad
        get() {
            return _vad!!
        }

    // 说话人嵌入提取器
    private var _speakerExtractor: SpeakerEmbeddingExtractor? = null
    val speakerExtractor: SpeakerEmbeddingExtractor
        get() = _speakerExtractor!!

    // 说话人嵌入管理器
    private var _speakerManager: SpeakerEmbeddingManager? = null
    val speakerManager: SpeakerEmbeddingManager
        get() = _speakerManager!!

    // 翻译器
    private var _translator: HelsinkiONNXKV? = null
    val translator: HelsinkiONNXKV?
        get() = _translator

    // 说话人计数器（用于自动命名未知说话人）
    private var speakerCounter = 0

    // ========== 初始化方法 ==========
    
    fun initOfflineRecognizer(assetManager: AssetManager? = null, application: Application) {
        synchronized(this) {
            if (_recognizer != null) {
                return
            }
            Log.i(TAG, "Initializing sherpa-onnx offline recognizer")
            
            val asrModelType = ModelConfig.Selection.ASR_MODEL_TYPE
            Log.i(TAG, "Select model type $asrModelType for ASR")

            val config = OfflineRecognizerConfig(
                modelConfig = getOfflineModelConfig(type = asrModelType)!!,
            )

            if (config.modelConfig.numThreads == 1) {
                config.modelConfig.numThreads = ModelConfig.Runtime.ASR_NUM_THREADS
            }

            _recognizer = OfflineRecognizer(
                assetManager = assetManager,
                config = config,
            )

            Log.i(TAG, "sherpa-onnx offline recognizer initialized")
        }
    }

    // 初始化VAD
    fun initVad(assetManager: AssetManager? = null) {
        if (_vad != null) {
            return
        }
        val type = ModelConfig.Selection.VAD_MODEL_TYPE
        Log.i(TAG, "Select VAD model type $type")
        val config = getVadModelConfig(type)

        _vad = Vad(
            assetManager = assetManager,
            config = config!!,
        )
        Log.i(TAG, "sherpa-onnx vad initialized")
    }
    
    // 初始化说话人识别
    fun initSpeakerIdentification(assetManager: AssetManager? = null) {
        if (_speakerExtractor != null) {
            return
        }
        
        Log.i(TAG, "Initializing speaker embedding extractor")
        
        val config = SpeakerEmbeddingExtractorConfig(
            model = ModelConfig.Selection.SPEAKER_MODEL,
            numThreads = ModelConfig.Runtime.SPEAKER_NUM_THREADS,
            debug = true,
            provider = "cpu",
        )

        _speakerExtractor = SpeakerEmbeddingExtractor(
            assetManager = assetManager,
            config = config,
        )

        // 初始化说话人管理器，维度与提取器一致
        _speakerManager = SpeakerEmbeddingManager(_speakerExtractor!!.dim)
        
        Log.i(TAG, "Speaker identification initialized, embedding dim: ${_speakerExtractor!!.dim}")
    }

    // 初始化翻译器
    fun initTranslator(
        assetManager: AssetManager? = null, 
        cacheDir: File, 
        modelDir: String = ModelConfig.Selection.TRANSLATION_MODEL_DIR,
        maxCacheSize: Long = ModelConfig.Cache.MAX_TRANSLATION_CACHE_SIZE
    ) {
        if (_translator != null && _translator!!.isReady()) {
            Log.w(TAG, "Translator already initialized")
            return
        }
        
        Log.i(TAG, "Initializing Helsinki translator (EN→ZH)")
        
        _translator = HelsinkiONNXKV
        
        val success = _translator!!.init(
            assetManager = assetManager,
            cacheDir = cacheDir,
            modelDir = modelDir,
            verbose = ModelConfig.Runtime.TRANSLATION_VERBOSE,
            maxCacheSize = maxCacheSize
        )
        
        if (success) {
            Log.i(TAG, "Helsinki translator initialized successfully")
        } else {
            Log.e(TAG, "Failed to initialize Helsinki translator")
            _translator = null
        }
    }

    // ========== 业务方法 ==========
    
    // 提取说话人嵌入
    fun extractEmbedding(samples: FloatArray, sampleRate: Int): FloatArray? {
        Log.i(TAG, "extractEmbedding: samples size = ${samples.size}")
        
        val stream = speakerExtractor.createStream()
        Log.i(TAG, "extractEmbedding: stream created")
        
        stream.acceptWaveform(samples, sampleRate)
        Log.i(TAG, "extractEmbedding: waveform accepted")
        
        stream.inputFinished()
        Log.i(TAG, "extractEmbedding: input finished")
        
        if (!speakerExtractor.isReady(stream)) {
            Log.w(TAG, "extractEmbedding: stream not ready")
            stream.release()
            return null
        }
        
        Log.i(TAG, "extractEmbedding: computing embedding")
        val embedding = speakerExtractor.compute(stream)
        Log.i(TAG, "extractEmbedding: embedding computed, size = ${embedding.size}")
        
        stream.release()
        return embedding
    }

    // 识别或注册说话人（支持最大说话人数量限制）
    fun identifyOrRegisterSpeaker(embedding: FloatArray, maxSpeakers: Int = 15): String {
        // 搜索已知说话人
        val speakerName = speakerManager.search(embedding, ModelConfig.Pipeline.SPEAKER_THRESHOLD)
        
        return if (speakerName.isNotEmpty()) {
            // 找到匹配的说话人
            speakerName
        } else {
            // 新说话人
            if (speakerCounter < maxSpeakers) {
                // 还没达到上限，注册新说话人
                speakerCounter++
                val newName = "Speaker $speakerCounter"
                speakerManager.add(newName, embedding)
                Log.i(TAG, "Registered new speaker: $newName ($speakerCounter/$maxSpeakers)")
                newName
            } else {
                // 已达到上限，统一标记为 "S"
                Log.i(TAG, "Max speakers ($maxSpeakers) reached, marking as 'S'")
                "S"
            }
        }
    }

    // 翻译文本（英文→中文）
    fun translateText(text: String): String? {
        if (_translator == null || !_translator!!.isReady()) {
            Log.w(TAG, "Translator not ready")
            return null
        }
        
        if (text.isBlank()) {
            return ""
        }
        
        Log.i(TAG, "Translating text: $text")
        val result = _translator!!.translateSafe(text)
        Log.i(TAG, "Translation result: $result")
        
        return result.ifBlank { null }
    }

    // 检查翻译器是否就绪
    fun isTranslatorReady(): Boolean {
        return _translator?.isReady() ?: false
    }

    // 手动注册说话人（可选，用于预先注册已知说话人）
    fun registerSpeaker(name: String, embedding: FloatArray): Boolean {
        return speakerManager.add(name, embedding)
    }

    // 获取所有已注册说话人
    fun getAllSpeakers(): Array<String> {
        return speakerManager.allSpeakerNames
    }

    // 重置说话人数据
    fun resetSpeakers() {
        _speakerManager?.release()
        _speakerManager = SpeakerEmbeddingManager(speakerExtractor.dim)
        speakerCounter = 0
    }
    
    // 释放所有资源
    fun releaseAll() {
        _translator?.release()
        _translator = null
        
        _speakerManager?.release()
        _speakerManager = null
        
        _speakerExtractor?.release()
        _speakerExtractor = null
        
        _vad?.release()
        _vad = null
        
        _recognizer?.release()
        _recognizer = null
        
        Log.i(TAG, "All resources released")
    }
}
