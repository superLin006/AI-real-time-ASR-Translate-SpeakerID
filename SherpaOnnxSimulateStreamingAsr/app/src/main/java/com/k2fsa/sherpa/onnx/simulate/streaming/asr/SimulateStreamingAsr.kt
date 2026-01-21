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
            return _vad ?: throw IllegalStateException("VAD is not initialized. Please call initVad() first.")
        }

    // 说话人嵌入提取器
    private var _speakerExtractor: SpeakerEmbeddingExtractor? = null
    val speakerExtractor: SpeakerEmbeddingExtractor
        get() = _speakerExtractor ?: throw IllegalStateException("SpeakerExtractor is not initialized. Please call initSpeakerIdentification() first.")

    // 说话人嵌入管理器
    private var _speakerManager: SpeakerEmbeddingManager? = null
    val speakerManager: SpeakerEmbeddingManager
        get() = _speakerManager ?: throw IllegalStateException("SpeakerManager is not initialized. Please call initSpeakerIdentification() first.")

    // 翻译器（支持双向和单向模式）
    private var _translator1: HelsinkiONNXKV? = null  // 翻译器1（双向模式：方向1；单向模式：唯一翻译器）
    private var _translator2: HelsinkiONNXKV? = null  // 翻译器2（双向模式：方向2；单向模式：null）

    // 翻译方向映射（源语言 → 翻译器）
    private val translatorMap = mutableMapOf<String, HelsinkiONNXKV>()

    val translator: HelsinkiONNXKV?
        get() = _translator1  // 向后兼容

    // 说话人计数器（用于自动命名未知说话人）
    private var speakerCounter = 0

    // ========== 初始化方法 ==========

    /**
     * 初始化离线识别器
     * @param assetManager Android AssetManager，用于从 assets 加载模型
     * @param application Application 实例
     * @param externalModelBasePath 外部模型基础路径（用于 MTK 等需要文件系统路径的模型）
     */
    fun initOfflineRecognizer(
        assetManager: AssetManager? = null,
        application: Application,
        externalModelBasePath: String? = null
    ) {
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

            // MTK 模型需要使用文件系统路径，不能使用 assets
            val useAssetManager = if (asrModelType == 1000 && externalModelBasePath != null) {
                // MTK 模式：使用外部路径，将模型路径改为绝对路径
                val modelDir = "sense-voice-mtk"
                config.modelConfig.senseVoice.model = "$externalModelBasePath/$modelDir/sensevoice-10s.dla"
                config.modelConfig.tokens = "$externalModelBasePath/$modelDir/tokens.txt"
                Log.i(TAG, "MTK model path: ${config.modelConfig.senseVoice.model}")
                Log.i(TAG, "MTK tokens path: ${config.modelConfig.tokens}")
                null // 不使用 assetManager
            } else {
                assetManager
            }

            _recognizer = OfflineRecognizer(
                assetManager = useAssetManager,
                config = config,
            )

            Log.i(TAG, "sherpa-onnx offline recognizer initialized")
        }
    }

    // 初始化VAD
    fun initVad(assetManager: AssetManager? = null) {
        if (_vad != null) {
            Log.i(TAG, "VAD already initialized, skipping")
            return
        }

        try {
            val type = ModelConfig.Selection.VAD_MODEL_TYPE
            Log.i(TAG, "Select VAD model type $type")
            val config = getVadModelConfig(type)

            if (config == null) {
                Log.e(TAG, "Failed to get VAD model config for type $type")
                return
            }

            Log.i(TAG, "Creating VAD object...")
            _vad = Vad(
                assetManager = assetManager,
                config = config,
            )
            Log.i(TAG, "sherpa-onnx vad initialized successfully ✓")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to initialize VAD", e)
            _vad = null
        }
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

    // 初始化翻译器（支持双向和单向模式）
    fun initTranslator(
        assetManager: AssetManager? = null,
        cacheDir: File,
        maxCacheSize: Long = ModelConfig.Cache.MAX_TRANSLATION_CACHE_SIZE
    ) {
        if (_translator1 != null || _translator2 != null) {
            Log.w(TAG, "Translators already initialized")
            return
        }

        translatorMap.clear()

        val mode = ModelConfig.Selection.TRANSLATION_MODE
        Log.i(TAG, "========================================")
        Log.i(TAG, "Initializing Helsinki Translators (Mode: $mode)")
        Log.i(TAG, "========================================")

        when (mode) {
            "BIDIRECTIONAL" -> {
                // 双向翻译模式：加载两个翻译器
                val sourceLang1 = ModelConfig.Selection.SOURCE_LANG1
                val targetLang1 = ModelConfig.Selection.TARGET_LANG1
                val sourceLang2 = ModelConfig.Selection.SOURCE_LANG2
                val targetLang2 = ModelConfig.Selection.TARGET_LANG2

                Log.i(TAG, "Loading translator: $sourceLang1→$targetLang1")
                _translator1 = HelsinkiONNXKV()
                val success1 = _translator1!!.init(
                    assetManager = assetManager,
                    cacheDir = cacheDir,
                    modelDir = "helsinki-translation/$sourceLang1-$targetLang1",
                    verbose = ModelConfig.Runtime.TRANSLATION_VERBOSE,
                    maxCacheSize = maxCacheSize
                )

                if (success1) {
                    Log.i(TAG, "✓ $sourceLang1→$targetLang1 translator loaded")
                    translatorMap[sourceLang1] = _translator1!!
                    // 支持相关语言变体
                    if (sourceLang1 == "zh") {
                        translatorMap["yue"] = _translator1!!  // 粤语也使用中文翻译器
                    }
                } else {
                    Log.e(TAG, "✗ Failed to load $sourceLang1→$targetLang1 translator")
                    _translator1 = null
                }

                Log.i(TAG, "Loading translator: $sourceLang2→$targetLang2")
                _translator2 = HelsinkiONNXKV()
                val success2 = _translator2!!.init(
                    assetManager = assetManager,
                    cacheDir = cacheDir,
                    modelDir = "helsinki-translation/$sourceLang2-$targetLang2",
                    verbose = ModelConfig.Runtime.TRANSLATION_VERBOSE,
                    maxCacheSize = maxCacheSize
                )

                if (success2) {
                    Log.i(TAG, "✓ $sourceLang2→$targetLang2 translator loaded")
                    translatorMap[sourceLang2] = _translator2!!
                    // 支持相关语言变体
                    if (sourceLang2 == "zh") {
                        translatorMap["yue"] = _translator2!!
                    }
                } else {
                    Log.e(TAG, "✗ Failed to load $sourceLang2→$targetLang2 translator")
                    _translator2 = null
                }

                if (success1 && success2) {
                    Log.i(TAG, "✓ Both translators initialized successfully")
                } else if (success1 || success2) {
                    Log.w(TAG, "⚠ Only one translator initialized")
                } else {
                    Log.e(TAG, "✗ All translators failed to initialize")
                }
            }

            "UNIDIRECTIONAL" -> {
                // 单向翻译模式：只加载一个翻译器，不检查语言
                val modelDir = ModelConfig.Selection.TRANSLATION_MODEL_DIR

                Log.i(TAG, "Loading single translator (unidirectional)")
                Log.i(TAG, "Model directory: $modelDir")

                _translator1 = HelsinkiONNXKV()
                val success = _translator1!!.init(
                    assetManager = assetManager,
                    cacheDir = cacheDir,
                    modelDir = modelDir,
                    verbose = ModelConfig.Runtime.TRANSLATION_VERBOSE,
                    maxCacheSize = maxCacheSize
                )

                if (success) {
                    Log.i(TAG, "✓ Translator loaded (unidirectional mode)")
                    // 通配符：任何语言都使用这个翻译器
                    translatorMap["*"] = _translator1!!
                } else {
                    Log.e(TAG, "✗ Failed to load translator")
                    _translator1 = null
                }
            }

            else -> {
                Log.e(TAG, "Unknown translation mode: $mode")
            }
        }

        Log.i(TAG, "Translation map: ${translatorMap.keys}")
        Log.i(TAG, "========================================")
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

    // 翻译文本（根据配置模式自动选择翻译器）
    fun translateText(text: String, sourceLang: String = "auto"): String? {
        if (text.isBlank()) {
            return ""
        }

        // 根据源语言从映射表选择翻译器
        val translator = translatorMap[sourceLang]
            ?: translatorMap["*"]  // 通配符（单向模式 auto）
            ?: run {
                Log.w(TAG, "No translator found for language '$sourceLang'")
                return null
            }

        if (!translator.isReady()) {
            Log.w(TAG, "Translator for language '$sourceLang' not ready")
            return null
        }

        Log.i(TAG, "Translating [$sourceLang]: $text")
        val result = translator.translateSafe(text)
        Log.i(TAG, "Translation result: $result")

        return result.ifBlank { null }
    }

    // 获取源语言对应的目标语言（仅用于UI显示）
    fun getTargetLanguage(sourceLang: String): String? {
        val mode = ModelConfig.Selection.TRANSLATION_MODE
        return when (mode) {
            "BIDIRECTIONAL" -> {
                // 双向模式：根据源语言返回对应的目标语言
                when (sourceLang) {
                    ModelConfig.Selection.SOURCE_LANG1 -> ModelConfig.Selection.TARGET_LANG1
                    ModelConfig.Selection.SOURCE_LANG2 -> ModelConfig.Selection.TARGET_LANG2
                    "yue" -> {
                        // 粤语：检查是否与配置的源语言相关
                        if (ModelConfig.Selection.SOURCE_LANG1 == "zh" || ModelConfig.Selection.SOURCE_LANG2 == "zh") {
                            if (ModelConfig.Selection.SOURCE_LANG1 == "zh") ModelConfig.Selection.TARGET_LANG1
                            else ModelConfig.Selection.TARGET_LANG2
                        } else null
                    }
                    else -> null
                }
            }
            "UNIDIRECTIONAL" -> {
                // 单向模式：总是返回"unknown"（因为不关心源语言）
                "unknown"
            }
            else -> null
        }
    }

    // 检查翻译器是否就绪（至少一个可用即可）
    fun isTranslatorReady(): Boolean {
        return (_translator1?.isReady() == true) || (_translator2?.isReady() == true)
    }

    // 检查 VAD 是否就绪
    fun isVadReady(): Boolean {
        return _vad != null
    }

    // 检查识别器是否就绪
    fun isRecognizerReady(): Boolean {
        return _recognizer != null
    }

    // 检查说话人识别是否就绪
    fun isSpeakerIdReady(): Boolean {
        return _speakerExtractor != null && _speakerManager != null
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
        _translator1?.release()
        _translator1 = null

        _translator2?.release()
        _translator2 = null

        translatorMap.clear()

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
