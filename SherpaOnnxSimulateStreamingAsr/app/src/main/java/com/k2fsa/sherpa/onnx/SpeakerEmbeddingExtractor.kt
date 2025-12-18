// app/src/main/java/com/k2fsa/sherpa/onnx/SpeakerEmbeddingExtractor.kt

package com.k2fsa.sherpa.onnx

import android.content.res.AssetManager

data class SpeakerEmbeddingExtractorConfig(
    val model: String = "",
    val numThreads: Int = 1,
    val debug: Boolean = false,
    val provider: String = "cpu",
)

class SpeakerEmbeddingExtractor(
    assetManager: AssetManager? = null,
    config: SpeakerEmbeddingExtractorConfig,
) {
    private val ptr: Long

    init {
        ptr = if (assetManager != null) {
            newFromAsset(assetManager, config)
        } else {
            newFromFile(config)
        }
    }

    fun createStream(): OnlineStream {
        val p = createStream(ptr)
        return OnlineStream(p)
    }

    fun isReady(stream: OnlineStream): Boolean {
        return isReady(ptr, stream.ptr)
    }

    fun compute(stream: OnlineStream): FloatArray {
        return compute(ptr, stream.ptr)
    }

    val dim: Int
        get() = dim(ptr)

    fun release() {
        delete(ptr)
    }

    protected fun finalize() {
        delete(ptr)
    }

    private external fun newFromAsset(
        assetManager: AssetManager,
        config: SpeakerEmbeddingExtractorConfig,
    ): Long

    private external fun newFromFile(
        config: SpeakerEmbeddingExtractorConfig,
    ): Long

    private external fun delete(ptr: Long)
    private external fun createStream(ptr: Long): Long
    private external fun isReady(ptr: Long, streamPtr: Long): Boolean
    private external fun compute(ptr: Long, streamPtr: Long): FloatArray
    private external fun dim(ptr: Long): Int

    companion object {
        init {
            System.loadLibrary("sherpa-onnx-jni")
        }
    }
}