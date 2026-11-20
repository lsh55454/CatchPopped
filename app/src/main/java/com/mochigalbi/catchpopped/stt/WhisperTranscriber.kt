package com.mochigalbi.catchpopped.stt

import com.mochigalbi.catchpopped.audio.Chunk
import com.mochigalbi.catchpopped.audio.Chunker
import com.mochigalbi.catchpopped.audio.WavFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

/** JNI bridge to whisper.cpp (app/src/main/cpp). */
internal object WhisperNative {
    /** False until the native library is part of the build (needs the NDK). */
    val loaded: Boolean by lazy {
        runCatching { System.loadLibrary("catchpopped_whisper") }.isSuccess
    }

    /** Returns a context handle, or 0 if the model could not be loaded. */
    external fun init(modelPath: String): Long

    /** Returns UTF-8 text, or null if whisper failed. [language] is "ko", "en" or "auto". */
    external fun transcribe(context: Long, samples: FloatArray, language: String, threads: Int): ByteArray?

    external fun free(context: Long)
}

/**
 * Transcribes a WAV file on the phone with whisper.cpp.
 *
 * The recording is split at pauses first ([Chunker]) so long silences, where whisper
 * tends to invent text, are left out; the speech pieces are then packed into windows
 * that fit whisper's 30 s input.
 */
class WhisperTranscriber(
    private val model: File,
    private val language: String = "ko",
    private val threads: Int = 4,
) : Transcriber {

    override suspend fun transcribe(wav: File): String = withContext(Dispatchers.Default) {
        if (!WhisperNative.loaded) {
            throw TranscriptionException("Whisper 엔진이 이 빌드에 없습니다. (NDK 빌드 필요)")
        }
        if (!model.isFile) throw TranscriptionException("Whisper 모델 파일이 없습니다: ${model.name}")

        val audio = WavFile.read(wav)
        if (audio.sampleRate != SAMPLE_RATE) {
            throw TranscriptionException("Whisper는 16kHz 녹음만 받습니다. (${audio.sampleRate}Hz)")
        }
        val chunks = Chunker.split(audio.samples, audio.sampleRate)
        if (chunks.isEmpty()) return@withContext ""

        val handle = WhisperNative.init(model.absolutePath)
        if (handle == 0L) throw TranscriptionException("Whisper 모델을 불러오지 못했습니다: ${model.name}")
        try {
            val pieces = mutableListOf<String>()
            for (window in windows(chunks)) {
                ensureActive()
                val bytes = WhisperNative.transcribe(handle, join(audio.samples, window), language, threads)
                    ?: throw TranscriptionException("Whisper 처리 중 오류가 났습니다.")
                pieces += String(bytes, Charsets.UTF_8).trim()
            }
            pieces.filter { it.isNotEmpty() }.joinToString("\n")
        } finally {
            WhisperNative.free(handle)
        }
    }

    /**
     * Whisper always computes a full 30 s window, however short the input. Consecutive
     * pieces are therefore packed into windows of up to [MAX_WINDOW_SAMPLES].
     */
    private fun windows(chunks: List<Chunk>): List<List<Chunk>> {
        val windows = mutableListOf<MutableList<Chunk>>()
        var size = 0
        for (chunk in chunks) {
            val current = windows.lastOrNull()
            if (current == null || size + GAP_SAMPLES + chunk.length > MAX_WINDOW_SAMPLES) {
                windows += mutableListOf(chunk)
                size = chunk.length
            } else {
                current += chunk
                size += GAP_SAMPLES + chunk.length
            }
        }
        return windows
    }

    /** Joins pieces with a short silence between them, as float samples in [-1, 1]. */
    private fun join(samples: ShortArray, window: List<Chunk>): FloatArray {
        val length = window.sumOf { it.length } + GAP_SAMPLES * (window.size - 1)
        // whisper skips input shorter than 1 s, so short input is padded with silence.
        val output = FloatArray(maxOf(length, MIN_SAMPLES))
        var position = 0
        for ((index, chunk) in window.withIndex()) {
            if (index > 0) position += GAP_SAMPLES
            for (i in chunk.start until chunk.end) output[position++] = samples[i] / 32768f
        }
        return output
    }

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val MIN_SAMPLES = SAMPLE_RATE * 11 / 10
        const val GAP_SAMPLES = SAMPLE_RATE * 3 / 10
        const val MAX_WINDOW_SAMPLES = SAMPLE_RATE * 28
    }
}
