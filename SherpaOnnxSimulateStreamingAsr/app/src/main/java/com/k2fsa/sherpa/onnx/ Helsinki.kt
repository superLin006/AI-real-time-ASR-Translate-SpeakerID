package com.k2fsa.sherpa.onnx

import android.content.res.AssetManager
import android.util.Log
import java.io.File
import java.io.FileOutputStream

/**
 * Helsinki ONNX 翻译模块（英文→中文）
 * 最终工作版本
 */
object HelsinkiONNXKV {
    private const val TAG = "HelsinkiONNXKV"
    
    private var isInitialized = false
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
    private external fun initSession(
        encoderPath: String,
        decoderPath: String,
        decoderWithPastPath: String,
        sourceSpm: String,
        targetSpm: String,
        vocabTxt: String,
        verbose: Boolean
    ): Int
    
    @JvmStatic
    private external fun translate(text: String): String?
    
    @JvmStatic
    private external fun releaseSession(): Int
    
    @JvmStatic
    external fun setVerboseMode(verbose: Boolean)
    
    @JvmStatic
    external fun getApiVersion(): String
    
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
        maxCacheSize: Long = 500 * 1024 * 1024  // ✅ 新增：默认500MB限制
    ): Boolean {
        if (libraryLoadFailed) {
            Log.e(TAG, "Cannot initialize: library failed to load")
            return false
        }
        
        if (isInitialized) {
            Log.w(TAG, "Already initialized")
            return true
        }
        
        if (assetManager == null) {
            Log.e(TAG, "AssetManager is null")
            return false
        }
        
        Log.i(TAG, "========================================")
        Log.i(TAG, "Initializing Helsinki Translator")
        Log.i(TAG, "========================================")
        Log.i(TAG, "Assets path: $modelDir")
        Log.i(TAG, "Cache dir: ${cacheDir.absolutePath}")
        
        try {
            // ✅ 修改：使用分层缓存目录
            val helsinkiCacheRoot = File(cacheDir, "helsinki-models")
            val tempDir = File(helsinkiCacheRoot, modelDir.replace("/", "_"))  // en-zh -> en_zh
            
            if (!tempDir.exists()) {
                if (tempDir.mkdirs()) {
                    Log.i(TAG, "Created temp directory: ${tempDir.absolutePath}")
                } else {
                    Log.e(TAG, "Failed to create temp directory")
                    return false
                }
            }
            
            // ✅ 新增：更新访问时间（用于LRU）
            try {
                File(tempDir, ".last_access").writeText(System.currentTimeMillis().toString())
            } catch (e: Exception) {
                Log.w(TAG, "Failed to update access time: ${e.message}")
            }
            
            // ✅ 新增：LRU缓存清理逻辑
            try {
                if (helsinkiCacheRoot.exists()) {
                    val allDirs = helsinkiCacheRoot.listFiles()?.filter { 
                        it.isDirectory && !it.name.startsWith(".")  // 排除隐藏目录
                    } ?: emptyList()
                    
                    // 计算总缓存大小
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
                        
                        // 按最后访问时间排序
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
                        
                        // 删除最旧的缓存
                        var currentSize = totalSize
                        for (dir in sortedDirs) {
                            if (dir.name == currentDirName) {
                                // 不删除当前模型
                                continue
                            }
                            
                            val dirSize = dir.walkTopDown()
                                .filter { it.isFile }
                                .map { it.length() }
                                .sum()
                            
                            Log.i(TAG, "Removing old cache: ${dir.name} (${dirSize / 1024 / 1024}MB)")
                            dir.deleteRecursively()
                            currentSize -= dirSize
                            
                            // 达到限制就停止
                            if (currentSize <= maxCacheSize * 0.8) {  // 保留20%余量
                                Log.i(TAG, "Cache cleanup completed, new size: ${currentSize / 1024 / 1024}MB")
                                break
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error during cache cleanup: ${e.message}", e)
                // 清理失败不影响继续运行
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
                val destFile = File(tempDir, filename)  // ✅ 使用新的分层目录
                
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
            
            val ret = initSession(
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
                Log.i(TAG, "✓ Helsinki translator initialized successfully!")
                try {
                    Log.i(TAG, "API Version: ${getApiVersion()}")
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
        if (libraryLoadFailed || !isInitialized) {
            Log.w(TAG, "Translator not ready (lib=$libraryLoadFailed, init=$isInitialized)")
            return null
        }
        
        if (text.isBlank()) return ""
        
        return try {
            Log.d(TAG, "Translating: $text")
            val result = translate(text)
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
        // 简化：只检查标志，不调用 native 方法
        return !libraryLoadFailed && isInitialized
    }
    
    fun release() {
        if (libraryLoadFailed || !isInitialized) return
        
        try {
            releaseSession()
            isInitialized = false
            Log.i(TAG, "Helsinki translator released")
        } catch (e: Exception) {
            Log.e(TAG, "Error releasing", e)
        }
    }
}