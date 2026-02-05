package com.k2fsa.sherpa.onnx

import android.content.res.AssetManager
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Helsinki ONNX 翻译模块 - 多实例版本
 *
 * 支持同时创建多个翻译器实例，用于加载不同的翻译模型（如 en-zh 和 zh-en）
 */
class HelsinkiONNXKV {
    companion object {
        private const val TAG = "HelsinkiONNXKV"
        private var libraryLoadFailed = false

        init {
            try {
                System.loadLibrary("onnxruntime")
                Log.i(TAG, "ONNX Runtime library loaded successfully")

                Thread.sleep(100)

                System.loadLibrary("helsinki-onnx-jni")
                Log.i(TAG, "Helsinki native library loaded successfully")

            } catch (e: UnsatisfiedLinkError) {
                libraryLoadFailed = true
                Log.e(TAG, "Failed to load libraries", e)
            }
        }

        @JvmStatic
        external fun getApiVersionMulti(): String

        /**
         * 设置JNI日志级别
         * @param level 0=仅ERROR, 1=INFO+ERROR
         */
        @JvmStatic
        external fun setLogLevel(level: Int)
    }

    // 实例句柄（C++ 对象指针）
    private var nativeHandle: Long = 0
    private var isInitialized = false

    // Native 方法（实例方法）
    private external fun createInstance(): Long
    private external fun initInstance(
        handle: Long,
        encoderPath: String,
        decoderPath: String,
        decoderWithPastPath: String,
        sourceSpm: String,
        targetSpm: String,
        vocabTxt: String,
        verbose: Boolean
    ): Int

    private external fun translateWithInstance(handle: Long, text: String): String?
    private external fun isInstanceInitialized(handle: Long): Boolean
    private external fun destroyInstance(handle: Long)

    private fun copyAssetToFile(
        assetManager: AssetManager,
        assetPath: String,
        destFile: File
    ): Boolean {
        return try {
            Log.d(TAG, "Copying $assetPath to ${destFile.absolutePath}")

            assetManager.open(assetPath).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }

            val success = destFile.exists()
            if (success) {
                Log.d(TAG, "✓ Copied: ${destFile.length()} bytes")
            } else {
                Log.e(TAG, "✗ Copy failed")
            }
            success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to copy $assetPath: ${e.message}", e)
            false
        }
    }

    fun init(
        assetManager: AssetManager?,
        cacheDir: File,
        modelDir: String = "helsinki-translation/en-zh",
        verbose: Boolean = true,
        maxCacheSize: Long = 500 * 1024 * 1024,  // 默认500MB限制
        context: android.content.Context? = null  // 用于 download 模式
    ): Boolean {
        if (libraryLoadFailed) {
            Log.e(TAG, "Cannot initialize: library failed to load")
            return false
        }

        if (isInitialized) {
            Log.w(TAG, "Instance already initialized")
            return true
        }

        Log.i(TAG, "========================================")
        Log.i(TAG, "Initializing Helsinki Translator Instance")
        Log.i(TAG, "========================================")

        // 🔥 从下载目录加载（模型应该已通过 ModelInitializer 下载）
        if (context == null) {
            Log.e(TAG, "Context is required for model loading")
            return false
        }

        return initFromDownloadedPath(modelDir, context, cacheDir, verbose)
    }

    fun translateText(text: String): String? {
        if (libraryLoadFailed || !isInitialized || nativeHandle == 0L) {
            Log.w(TAG, "Translator not ready (lib=$libraryLoadFailed, init=$isInitialized, handle=$nativeHandle)")
            return null
        }

        if (text.isBlank()) return ""

        return try {
            Log.d(TAG, "Translating: $text")
            val result = translateWithInstance(nativeHandle, text)
            if (result != null) {
                Log.d(TAG, "Translation: $result")
            } else {
                Log.w(TAG, "Translation returned null")
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "Translation error", e)
            null
        }
    }

    fun translateSafe(text: String): String {
        return translateText(text) ?: ""
    }

    fun isReady(): Boolean {
        return !libraryLoadFailed && isInitialized && nativeHandle != 0L &&
                isInstanceInitialized(nativeHandle)
    }

    /**
     * 从 APK assets 加载模型（包含在安装包中）
     */
    private fun initFromDownloadedPath(
        modelDir: String,
        context: android.content.Context? = null,
        cacheDir: File? = null,
        verbose: Boolean
    ): Boolean {
        Log.i(TAG, "[Downloaded Model Mode]")
        Log.i(TAG, "Model directory: $modelDir")

        try {
            nativeHandle = createInstance()
            if (nativeHandle == 0L) {
                Log.e(TAG, "Failed to create native instance")
                return false
            }
            Log.i(TAG, "Native instance created: 0x${nativeHandle.toString(16)}")

            if (context == null) {
                Log.e(TAG, "Context is required for download mode")
                return false
            }

            // 将 helsinki-translation/zh-en 转换为 Translation/zh-en（规范化目录结构）
            val dirName = modelDir.replace("helsinki-translation/", "Translation/")

            val modelCacheDir = com.k2fsa.sherpa.onnx.download.ModelDownloadManager.Config.getModelCacheDir(context)
            val downloadedDir = File(modelCacheDir, dirName)

            if (!downloadedDir.exists()) {
                Log.e(TAG, "✗ Downloaded model directory not found: ${downloadedDir.absolutePath}")
                Log.e(TAG, "  Models need to be downloaded via ModelDownloadManager first")
                return false
            }

            Log.i(TAG, "Downloaded model directory: ${downloadedDir.absolutePath}")

            val fileMap = linkedMapOf(
                "encoder_model.onnx" to "encoder",
                "decoder_model.onnx" to "decoder",
                "decoder_with_past_model.onnx" to "decoder_with_past",
                "source.spm" to "source_spm",
                "target.spm" to "target_spm",
                "vocab.txt" to "vocab"
            )

            val filePaths = mutableMapOf<String, String>()

            Log.i(TAG, "Verifying model files...")
            for ((filename, key) in fileMap) {
                val file = File(downloadedDir, filename)

                if (!file.exists()) {
                    Log.e(TAG, "✗ File not found: ${file.absolutePath}")
                    return false
                }

                if (file.length() == 0L) {
                    Log.e(TAG, "✗ Invalid file (empty): ${file.absolutePath}")
                    return false
                }

                filePaths[key] = file.absolutePath
                Log.i(TAG, "  ✓ $key: ${file.name} (${file.length()} bytes)")
            }

            Log.i(TAG, "Calling native initialization...")
            val ret = initInstance(
                handle = nativeHandle,
                encoderPath = filePaths["encoder"]!!,
                decoderPath = filePaths["decoder"]!!,
                decoderWithPastPath = filePaths["decoder_with_past"]!!,
                sourceSpm = filePaths["source_spm"]!!,
                targetSpm = filePaths["target_spm"]!!,
                vocabTxt = filePaths["vocab"]!!,
                verbose = verbose
            )

            isInitialized = (ret == 0)

            Log.i(TAG, "========================================")
            if (isInitialized) {
                Log.i(TAG, "✓ Helsinki translator instance initialized successfully from downloaded path!")
                try {
                    Log.i(TAG, "API Version: ${getApiVersionMulti()}")
                } catch (e: Exception) {
                    Log.w(TAG, "Cannot get API version: ${e.message}")
                }
            } else {
                Log.e(TAG, "✗ Initialization failed with code: $ret")
            }
            Log.i(TAG, "========================================")

            return isInitialized

        } catch (e: Exception) {
            Log.e(TAG, "Exception during initialization from downloaded path", e)
            Log.e(TAG, "========================================")
            return false
        }
    }

    fun release() {
        if (libraryLoadFailed || nativeHandle == 0L) return

        try {
            destroyInstance(nativeHandle)
            nativeHandle = 0
            isInitialized = false
            Log.i(TAG, "Helsinki translator instance released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing", e)
        }
    }
}
