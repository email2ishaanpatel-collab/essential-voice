package com.ishaan.essentialvoice.whisper

/** Thin 1:1 mapping of app/src/main/cpp/jni.cpp. Nothing here allocates or blocks by itself. */
internal object WhisperLib {

    /**
     * ggml here is compiled for armv8.2-a with fp16 and dotprod, which is what
     * makes it fast enough to be worth having. Those instructions do not exist
     * on pre-2018 cores, and a phone without them does not fail gracefully — it
     * takes SIGILL somewhere inside a matrix multiply. So the capability is
     * checked before the library is ever loaded, and a phone that cannot run it
     * is told so instead of crashing.
     */
    val isSupported: Boolean by lazy {
        runCatching {
            val flags = java.io.File("/proc/cpuinfo").readLines()
                .firstOrNull { it.startsWith("Features") }
                ?.substringAfter(':')
                ?.split(' ')
                ?.map { it.trim() }
                ?: return@runCatching false
            "asimdhp" in flags && "asimddp" in flags
        }.getOrDefault(false)
    }

    private val loaded: Boolean by lazy {
        if (!isSupported) false
        else runCatching { System.loadLibrary("essentialwhisper") }.isSuccess
    }

    /** Must be true before any native call below. */
    fun ensureLoaded(): Boolean = loaded

    @JvmStatic external fun nativeInit(modelPath: String, useGpu: Boolean): Long
    @JvmStatic external fun nativeFree(ptr: Long)
    @JvmStatic external fun nativeAbort(on: Boolean)
    @JvmStatic external fun nativeTranscribe(
        ptr: Long,
        audio: FloatArray,
        nThreads: Int,
        language: String,
        translate: Boolean,
        beamSize: Int,
        bestOf: Int,
        noSpeechThold: Float,
        initialPrompt: String,
        audioCtx: Int,
        singleSegment: Boolean,
    ): String
    @JvmStatic external fun nativeTrimHeap()
    @JvmStatic external fun nativeSystemInfo(): String
}
