package com.k2fsa.sherpa.onnx.download

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 模型初始化工具
 * 在应用启动时检查模型，如果需要则下载
 */
object ModelInitializer {
    private const val TAG = "ModelInitializer"

    /**
     * 确保模型已准备就绪
     * 检查模型是否存在，不存在则下载；已存在则跳过
     * 只下载当前配置需要的模型（按需下载）
     *
     * @param context Android Context
     * @param onProgress 下载进度回调 (current, total, fileName)
     * @return true 表示模型已准备好，false 表示出错
     */
    suspend fun ensureModelsReady(
        context: Context,
        onProgress: (current: Int, total: Int, fileName: String) -> Unit = { _, _, _ -> }
    ): Boolean = withContext(Dispatchers.IO) {
        // 显示当前配置
        Log.i(TAG, "========================================")
        Log.i(TAG, "Model Configuration:")
        Log.i(TAG, "  ASR Type: ${com.k2fsa.sherpa.onnx.config.ModelConfig.Selection.ASR_MODEL_TYPE}")
        Log.i(TAG, "  VAD Type: ${com.k2fsa.sherpa.onnx.config.ModelConfig.Selection.VAD_MODEL_TYPE}")
        Log.i(TAG, "  Translation Mode: ${com.k2fsa.sherpa.onnx.config.ModelConfig.Selection.TRANSLATION_MODE}")

        if (com.k2fsa.sherpa.onnx.config.ModelConfig.Selection.TRANSLATION_MODE == "BIDIRECTIONAL") {
            Log.i(TAG, "  Translation Directions: " +
                    "${com.k2fsa.sherpa.onnx.config.ModelConfig.Selection.SOURCE_LANG1}-${com.k2fsa.sherpa.onnx.config.ModelConfig.Selection.TARGET_LANG1}, " +
                    "${com.k2fsa.sherpa.onnx.config.ModelConfig.Selection.SOURCE_LANG2}-${com.k2fsa.sherpa.onnx.config.ModelConfig.Selection.TARGET_LANG2}")
        }
        Log.i(TAG, "========================================")

        Log.i(TAG, "Checking required models...")

        return@withContext if (ModelDownloadManager.areAllModelsDownloaded(context)) {
            Log.i(TAG, "✓ All required models ready")
            true
        } else {
            val missingModels = ModelDownloadManager.getMissingModels(context)
            Log.i(TAG, "Missing ${missingModels.size} models, starting download...")
            missingModels.forEach { model ->
                Log.d(TAG, "  - ${model.relativePath} (${model.size / 1024 / 1024}MB)")
            }

            val success = ModelDownloadManager.downloadAllModels(context, onProgress)
            if (success) {
                Log.i(TAG, "✓ Models downloaded successfully")
            } else {
                Log.e(TAG, "✗ Model download failed")
            }
            success
        }
    }

    /**
     * 获取缺失模型信息
     */
    fun getMissingModelsInfo(context: Context): String {
        val missing = ModelDownloadManager.getMissingModels(context)
        if (missing.isEmpty()) return "All models present"

        val info = StringBuilder("Missing models:\n")
        missing.forEach { model ->
            info.append("  - ${model.name} (${model.size / 1024 / 1024}MB)\n")
        }
        return info.toString()
    }

    /**
     * 获取模型缓存信息
     */
    fun getModelsCacheInfo(context: Context): String {
        val sizeBytes = ModelDownloadManager.getModelsCacheSize(context)
        val sizeMB = sizeBytes / 1024 / 1024
        return "Models cache size: ${sizeMB}MB"
    }
}
