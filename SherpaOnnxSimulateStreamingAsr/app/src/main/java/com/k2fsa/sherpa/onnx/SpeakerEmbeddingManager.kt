// app/src/main/java/com/k2fsa/sherpa/onnx/SpeakerEmbeddingManager.kt

package com.k2fsa.sherpa.onnx

class SpeakerEmbeddingManager(dim: Int) {
    private val ptr: Long = create(dim)

    fun add(name: String, embedding: FloatArray): Boolean {
        return add(ptr, name, embedding)
    }

    fun addList(name: String, embeddingList: Array<FloatArray>): Boolean {
        return addList(ptr, name, embeddingList)
    }

    fun remove(name: String): Boolean {
        return remove(ptr, name)
    }

    fun search(embedding: FloatArray, threshold: Float): String {
        return search(ptr, embedding, threshold)
    }

    fun verify(name: String, embedding: FloatArray, threshold: Float): Boolean {
        return verify(ptr, name, embedding, threshold)
    }

    fun contains(name: String): Boolean {
        return contains(ptr, name)
    }

    val numSpeakers: Int
        get() = numSpeakers(ptr)

    val allSpeakerNames: Array<String>
        get() = allSpeakerNames(ptr)

    fun release() {
        delete(ptr)
    }

    protected fun finalize() {
        delete(ptr)
    }

    private external fun create(dim: Int): Long
    private external fun delete(ptr: Long)
    private external fun add(ptr: Long, name: String, embedding: FloatArray): Boolean
    private external fun addList(ptr: Long, name: String, embeddingList: Array<FloatArray>): Boolean
    private external fun remove(ptr: Long, name: String): Boolean
    private external fun search(ptr: Long, embedding: FloatArray, threshold: Float): String
    private external fun verify(ptr: Long, name: String, embedding: FloatArray, threshold: Float): Boolean
    private external fun contains(ptr: Long, name: String): Boolean
    private external fun numSpeakers(ptr: Long): Int
    private external fun allSpeakerNames(ptr: Long): Array<String>

    companion object {
        init {
            System.loadLibrary("sherpa-onnx-jni")
        }
    }
}