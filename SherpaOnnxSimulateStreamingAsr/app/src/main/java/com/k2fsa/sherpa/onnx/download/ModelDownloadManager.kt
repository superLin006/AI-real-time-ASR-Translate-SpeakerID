package com.k2fsa.sherpa.onnx.download

import android.content.Context
import android.util.Log
import com.k2fsa.sherpa.onnx.config.ModelConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * 模型下载管理器
 * 负责从远程服务器下载模型文件到本地
 * 支持按需下载：根据配置的 ASR 类型和翻译方向只下载需要的模型
 */
object ModelDownloadManager {
    private const val TAG = "ModelDownloadManager"

    // 模型下载配置
    object Config {
        // 模型服务器地址（需要根据实际情况修改）
        const val MODEL_SERVER_URL = "http://your-model-server.com/models"

        // 本地模型存储目录
        fun getModelCacheDir(context: Context): File {
            val cacheDir = File(context.getExternalFilesDir(null), "models")
            if (!cacheDir.exists()) {
                cacheDir.mkdirs()
            }
            return cacheDir
        }

        // 模型列表及其哈希值（用于校验）
        data class ModelFile(
            val name: String,           // 文件名
            val relativePath: String,   // 相对路径（相对于 /models）
            val size: Long,            // 文件大小（字节）
            val md5: String = ""       // MD5 校验值（可选）
        )

        /**
         * 根据当前配置获取需要下载的模型列表（按需下载）
         */
        fun getRequiredModels(): List<ModelFile> {
            val models = mutableListOf<ModelFile>()

            // 1. ASR 模型（根据 ASR_MODEL_TYPE）
            when (ModelConfig.Selection.ASR_MODEL_TYPE) {
                100 -> { // SenseVoice RKNN
                    models.add(ModelFile(
                        "model-10-seconds.rknn",
                        "ASR/sense-voice-rknn/model-10-seconds.rknn",
                        495592525
                    ))
                    models.add(ModelFile(
                        "tokens.txt",
                        "ASR/sense-voice-rknn/tokens.txt",
                        315894
                    ))
                }
                // 可以在这里添加其他 ASR 模型类型
                else -> {
                    Log.w(TAG, "Unknown ASR model type: ${ModelConfig.Selection.ASR_MODEL_TYPE}")
                }
            }

            // 2. VAD 模型（根据 VAD_MODEL_TYPE）
            when (ModelConfig.Selection.VAD_MODEL_TYPE) {
                0 -> { // Silero VAD
                    models.add(ModelFile(
                        "silero_vad.onnx",
                        "VAD/silero_vad.onnx",
                        643854
                    ))
                }
                // 可以在这里添加其他 VAD 模型类型
                else -> {
                    Log.w(TAG, "Unknown VAD model type: ${ModelConfig.Selection.VAD_MODEL_TYPE}")
                }
            }

            // 3. Speaker 模型（始终需要）
            if (ModelConfig.Features.ENABLE_SPEAKER_ID) {
                models.add(ModelFile(
                    ModelConfig.Selection.SPEAKER_MODEL,
                    "Speaker/${ModelConfig.Selection.SPEAKER_MODEL}",
                    28281138
                ))
            }

            // 4. Translation 模型（根据翻译模式和方向）
            if (ModelConfig.Features.ENABLE_TRANSLATION) {
                when (ModelConfig.Selection.TRANSLATION_MODE) {
                    "BIDIRECTIONAL" -> {
                        // 双向翻译：根据配置的两个方向下载
                        val dir1 = "${ModelConfig.Selection.SOURCE_LANG1}-${ModelConfig.Selection.TARGET_LANG1}"
                        val dir2 = "${ModelConfig.Selection.SOURCE_LANG2}-${ModelConfig.Selection.TARGET_LANG2}"

                        models.addAll(getTranslationModelFiles(dir1))
                        models.addAll(getTranslationModelFiles(dir2))
                    }
                    "UNIDIRECTIONAL" -> {
                        // 单向翻译：只下载指定的一个方向
                        val dirName = ModelConfig.Selection.TRANSLATION_MODEL_DIR
                            .replace("helsinki-translation/", "")
                        models.addAll(getTranslationModelFiles(dirName))
                    }
                }
            }

            return models
        }

        /**
         * 获取指定翻译方向的模型文件列表
         * @param direction 翻译方向，如 "zh-en" 或 "en-zh"
         */
        private fun getTranslationModelFiles(direction: String): List<ModelFile> {
            // 根据不同方向的实际文件大小
            return when (direction) {
                "zh-en" -> listOf(
                    ModelFile("encoder_model.onnx", "Translation/$direction/encoder_model.onnx", 52940751),
                    ModelFile("decoder_model.onnx", "Translation/$direction/decoder_model.onnx", 93536305),
                    ModelFile("decoder_with_past_model.onnx", "Translation/$direction/decoder_with_past_model.onnx", 90255384),
                    ModelFile("source.spm", "Translation/$direction/source.spm", 804677),
                    ModelFile("target.spm", "Translation/$direction/target.spm", 806530),
                    ModelFile("vocab.txt", "Translation/$direction/vocab.txt", 1005785)
                )
                "en-zh" -> listOf(
                    ModelFile("encoder_model.onnx", "Translation/$direction/encoder_model.onnx", 52940751),
                    ModelFile("decoder_model.onnx", "Translation/$direction/decoder_model.onnx", 93536305),
                    ModelFile("decoder_with_past_model.onnx", "Translation/$direction/decoder_with_past_model.onnx", 90255384),
                    ModelFile("source.spm", "Translation/$direction/source.spm", 806435),
                    ModelFile("target.spm", "Translation/$direction/target.spm", 804600),
                    ModelFile("vocab.txt", "Translation/$direction/vocab.txt", 1005662)
                )
                else -> {
                    // 对于其他方向，使用平均值（用户自己添加模型时需要修改）
                    Log.w(TAG, "Unknown translation direction: $direction, using approximate sizes")
                    listOf(
                        ModelFile("encoder_model.onnx", "Translation/$direction/encoder_model.onnx", 52940751),
                        ModelFile("decoder_model.onnx", "Translation/$direction/decoder_model.onnx", 93536305),
                        ModelFile("decoder_with_past_model.onnx", "Translation/$direction/decoder_with_past_model.onnx", 90255384),
                        ModelFile("source.spm", "Translation/$direction/source.spm", 805000),
                        ModelFile("target.spm", "Translation/$direction/target.spm", 806000),
                        ModelFile("vocab.txt", "Translation/$direction/vocab.txt", 1006000)
                    )
                }
            }
        }
    }

    private val okHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .build()

    /**
     * 检查所有需要的模型是否已下载完成
     */
    fun areAllModelsDownloaded(context: Context): Boolean {
        val cacheDir = Config.getModelCacheDir(context)
        val requiredModels = Config.getRequiredModels()

        Log.d(TAG, "Checking ${requiredModels.size} required models...")
        Log.d(TAG, "Model cache directory: ${cacheDir.absolutePath}")
        Log.d(TAG, "Cache directory exists: ${cacheDir.exists()}")
        Log.d(TAG, "Cache directory can read: ${cacheDir.canRead()}")

        return requiredModels.all { model ->
            val file = File(cacheDir, model.relativePath)
            Log.d(TAG, "Checking file: ${file.absolutePath}")
            val fileExists = file.exists()
            val canRead = file.canRead()
            val actualSize = if (fileExists) file.length() else 0L
            val sizeMatches = actualSize == model.size
            val exists = fileExists && sizeMatches

            if (!exists) {
                if (!fileExists) {
                    Log.d(TAG, "Missing: ${model.relativePath} (canRead: $canRead)")
                } else {
                    Log.d(TAG, "Size mismatch: ${model.relativePath} (expected: ${model.size}, actual: $actualSize, canRead: $canRead)")
                }
            }
            exists
        }
    }

    /**
     * 获取缺失的模型文件列表（只检查需要的模型）
     */
    fun getMissingModels(context: Context): List<Config.ModelFile> {
        val cacheDir = Config.getModelCacheDir(context)
        val requiredModels = Config.getRequiredModels()

        return requiredModels.filter { model ->
            val file = File(cacheDir, model.relativePath)
            !file.exists() || file.length() != model.size
        }
    }

    /**
     * 下载单个模型文件
     */
    suspend fun downloadModelFile(
        context: Context,
        model: Config.ModelFile,
        onProgress: (downloaded: Long, total: Long) -> Unit = { _, _ -> }
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            val url = "${Config.MODEL_SERVER_URL}/${model.relativePath}"
            Log.i(TAG, "Downloading: $url")

            val request = Request.Builder()
                .url(url)
                .build()

            val response = okHttpClient.newCall(request).execute()
            if (!response.isSuccessful) {
                Log.e(TAG, "Download failed for ${model.name}: ${response.code}")
                return@withContext false
            }

            val body = response.body ?: run {
                Log.e(TAG, "Empty response body for ${model.name}")
                return@withContext false
            }

            val totalSize = body.contentLength()
            val cacheDir = Config.getModelCacheDir(context)
            val targetFile = File(cacheDir, model.relativePath)

            // 创建父目录
            targetFile.parentFile?.mkdirs()

            // 分块下载，报告进度
            var downloadedSize = 0L
            FileOutputStream(targetFile).use { output ->
                body.byteStream().use { input ->
                    val buffer = ByteArray(8192)
                    var read: Int
                    while (input.read(buffer).also { read = it } != -1) {
                        output.write(buffer, 0, read)
                        downloadedSize += read
                        onProgress(downloadedSize, totalSize)
                    }
                }
            }

            Log.i(TAG, "Downloaded successfully: ${model.name}")
            return@withContext true

        } catch (e: Exception) {
            Log.e(TAG, "Download error for ${model.name}: ${e.message}", e)
            return@withContext false
        }
    }

    /**
     * 批量下载所有模型
     */
    suspend fun downloadAllModels(
        context: Context,
        onProgress: (current: Int, total: Int, fileName: String) -> Unit = { _, _, _ -> }
    ): Boolean = withContext(Dispatchers.IO) {
        val missingModels = getMissingModels(context)

        if (missingModels.isEmpty()) {
            Log.i(TAG, "All models already downloaded")
            return@withContext true
        }

        Log.i(TAG, "Found ${missingModels.size} missing models, starting download...")

        for ((index, model) in missingModels.withIndex()) {
            onProgress(index + 1, missingModels.size, model.name)

            val success = downloadModelFile(context, model) { downloaded, total ->
                Log.d(TAG, "${model.name}: $downloaded / $total bytes")
            }

            if (!success) {
                Log.e(TAG, "Failed to download ${model.name}, stopping...")
                return@withContext false
            }
        }

        Log.i(TAG, "All models downloaded successfully!")
        return@withContext true
    }

    /**
     * 删除所有已下载的模型（用于清理缓存）
     */
    fun clearAllModels(context: Context) {
        try {
            val cacheDir = Config.getModelCacheDir(context)
            cacheDir.deleteRecursively()
            Log.i(TAG, "Models cleared")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to clear models: ${e.message}", e)
        }
    }

    /**
     * 获取模型缓存大小（字节）
     */
    fun getModelsCacheSize(context: Context): Long {
        val cacheDir = Config.getModelCacheDir(context)
        return if (cacheDir.exists()) {
            cacheDir.walkTopDown().map { it.length() }.sum()
        } else {
            0L
        }
    }
}
