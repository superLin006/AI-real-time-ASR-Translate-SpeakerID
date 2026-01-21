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
        maxCacheSize: Long = 500 * 1024 * 1024  // 默认500MB限制
    ): Boolean {
        if (libraryLoadFailed) {
            Log.e(TAG, "Cannot initialize: library failed to load")
            return false
        }

        if (isInitialized) {
            Log.w(TAG, "Instance already initialized")
            return true
        }

        if (assetManager == null) {
            Log.e(TAG, "AssetManager is null")
            return false
        }

        Log.i(TAG, "========================================")
        Log.i(TAG, "Initializing Helsinki Translator Instance")
        Log.i(TAG, "========================================")
        Log.i(TAG, "Assets path: $modelDir")
        Log.i(TAG, "Cache dir: ${cacheDir.absolutePath}")

        try {
            // 创建 C++ 实例
            nativeHandle = createInstance()
            if (nativeHandle == 0L) {
                Log.e(TAG, "Failed to create native instance")
                return false
            }
            Log.i(TAG, "Native instance created: 0x${nativeHandle.toString(16)}")

            // 准备模型文件
            val helsinkiCacheRoot = File(cacheDir, "helsinki-models")
            val tempDir = File(helsinkiCacheRoot, modelDir.replace("/", "_"))

            if (!tempDir.exists()) {
                if (tempDir.mkdirs()) {
                    Log.i(TAG, "Created temp directory: ${tempDir.absolutePath}")
                } else {
                    Log.e(TAG, "Failed to create temp directory")
                    return false
                }
            }

            // 更新访问时间（用于LRU）
            try {
                File(tempDir, ".last_access").writeText(System.currentTimeMillis().toString())
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update access time: ${e.message}")
            }

            // LRU缓存清理逻辑（同原版）
            try {
                if (helsinkiCacheRoot.exists()) {
                    val allDirs = helsinkiCacheRoot.listFiles()?.filter {
                        it.isDirectory && !it.name.startsWith(".")
                    } ?: emptyList()

                    val totalSize = allDirs.sumOf { dir ->
                        dir.walkTopDown()
                            .filter { it.isFile && !it.name.startsWith(".") }
                            .map { it.length() }
                            .sum()
                    }

                    Log.d(TAG, "Current cache size: ${totalSize / 1024 / 1024}MB (limit: ${maxCacheSize / 1024 / 1024}MB)")

                    if (totalSize > maxCacheSize) {
                        Log.w(TAG, "Cache size exceeds limit, starting LRU cleanup...")

                        val currentDirName = modelDir.replace("/", "_")

                        val sortedDirs = allDirs.sortedBy { dir ->
                            val accessFile = File(dir, ".last_access")
                            if (accessFile.exists()) {
                                try {
                                    accessFile.readText().toLongOrNull() ?: 0L
                                } catch (e: Exception) {
                                    0L
                                }
                            } else {
                                0L
                            }
                        }

                        var currentSize = totalSize
                        for (dir in sortedDirs) {
                            if (dir.name == currentDirName) {
                                continue
                            }

                            val dirSize = dir.walkTopDown()
                                .filter { it.isFile }
                                .map { it.length() }
                                .sum()

                            Log.i(TAG, "Removing old cache: ${dir.name} (${dirSize / 1024 / 1024}MB)")
                            dir.deleteRecursively()
                            currentSize -= dirSize

                            if (currentSize <= maxCacheSize * 0.8) {
                                Log.i(TAG, "Cache cleanup completed, new size: ${currentSize / 1024 / 1024}MB")
                                break
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during cache cleanup: ${e.message}", e)
            }

            val fileMap = linkedMapOf(
                "encoder_model.onnx" to "encoder",
                "decoder_model.onnx" to "decoder",
                "decoder_with_past_model.onnx" to "decoder_with_past",
                "source.spm" to "source_spm",
                "target.spm" to "target_spm",
                "vocab.txt" to "vocab"
            )

            val filePaths = mutableMapOf<String, String>()

            Log.i(TAG, "Preparing model files...")

            for ((filename, key) in fileMap) {
                val assetPath = "$modelDir/$filename"
                val destFile = File(tempDir, filename)

                var needCopy = !destFile.exists()

                if (!needCopy) {
                    try {
                        val assetFd = assetManager.openFd(assetPath)
                        val assetSize = assetFd.length
                        assetFd.close()
                        val cachedSize = destFile.length()

                        if (assetSize != cachedSize) {
                            Log.w(TAG, "  $filename: size mismatch (cached=$cachedSize, asset=$assetSize)")
                            needCopy = true
                        } else {
                            Log.d(TAG, "  $filename: using cached ($cachedSize bytes)")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "  $filename: cannot verify, will recopy")
                        needCopy = true
                    }
                }

                if (needCopy) {
                    Log.i(TAG, "  Copying: $filename")
                    if (!copyAssetToFile(assetManager, assetPath, destFile)) {
                        Log.e(TAG, "✗ Failed to copy $filename")
                        return false
                    }
                }

                if (!destFile.exists() || destFile.length() == 0L) {
                    Log.e(TAG, "✗ Invalid file: ${destFile.absolutePath}")
                    return false
                }

                filePaths[key] = destFile.absolutePath
            }

            Log.i(TAG, "Model files ready:")
            filePaths.forEach { (key, path) ->
                val file = File(path)
                Log.i(TAG, "  $key: ${file.name} (${file.length()} bytes)")
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
                Log.i(TAG, "✓ Helsinki translator instance initialized successfully!")
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
            Log.e(TAG, "Exception during initialization", e)
            Log.e(TAG, "========================================")
            return false
        }
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
