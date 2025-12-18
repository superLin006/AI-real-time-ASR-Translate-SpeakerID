// app/src/main/java/com/k2fsa/sherpa/onnx/OnlineStream.kt

package com.k2fsa.sherpa.onnx

class OnlineStream(internal var ptr: Long) {
    
    fun acceptWaveform(samples: FloatArray, sampleRate: Int) {
        acceptWaveform(ptr, samples, sampleRate)
    }

    fun inputFinished() {
        inputFinished(ptr)
    }

    fun release() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0
        }
    }

    protected fun finalize() {
        if (ptr != 0L) {
            delete(ptr)
            ptr = 0
        }
    }

    private external fun acceptWaveform(ptr: Long, samples: FloatArray, sampleRate: Int)
    private external fun inputFinished(ptr: Long)
    private external fun delete(ptr: Long)

    companion object {
        init {
            System.loadLibrary("sherpa-onnx-jni")
        }
    }
}