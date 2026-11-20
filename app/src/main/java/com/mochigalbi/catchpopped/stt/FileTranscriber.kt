package com.mochigalbi.catchpopped.stt

import android.content.Context
import android.content.Intent
import android.media.AudioFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.annotation.RequiresApi
import com.mochigalbi.catchpopped.audio.Chunk
import com.mochigalbi.catchpopped.audio.Chunker
import com.mochigalbi.catchpopped.audio.WavData
import com.mochigalbi.catchpopped.audio.WavFile
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TranscriptionException(message: String) : Exception(message)

/**
 * Transcribes a recorded WAV file with the system speech recognizer, by handing it the
 * audio through a pipe instead of the microphone (Android 13+).
 *
 * The recognizer stops listening at a long pause, so the recording is first split at
 * pauses ([Chunker]) and every piece gets its own session on one shared recognizer.
 */
class FileTranscriber(context: Context, private val language: String = "ko-KR") : Transcriber {

    private val appContext = context.applicationContext

    override suspend fun transcribe(wav: File): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            throw TranscriptionException("파일 받아쓰기는 Android 13 이상에서만 됩니다.")
        }
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            throw TranscriptionException("이 기기에서 음성 인식 서비스를 찾을 수 없습니다.")
        }
        val audio = withContext(Dispatchers.IO) { WavFile.read(wav) }
        if (audio.samples.isEmpty()) throw TranscriptionException("녹음 파일이 비어 있습니다.")
        val chunks = withContext(Dispatchers.Default) {
            Chunker.split(audio.samples, audio.sampleRate)
        }
        if (chunks.isEmpty()) return ""

        // The on-device recognizer is tried first: the default one may need the network.
        val attempts = buildList {
            if (SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)) add(true)
            add(false)
        }
        val errors = mutableListOf<String>()
        for (onDevice in attempts) {
            try {
                return withContext(Dispatchers.Main.immediate) {
                    transcribeChunks(audio, chunks, onDevice)
                }
            } catch (e: TranscriptionException) {
                errors += (if (onDevice) "온디바이스 인식기: " else "기본 인식기: ") + e.message
            }
        }
        throw TranscriptionException(errors.joinToString("\n"))
    }

    /** Runs on the main thread. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun transcribeChunks(audio: WavData, chunks: List<Chunk>, onDevice: Boolean): String {
        val recognizer = SharedRecognizer(onDevice)
        try {
            val pieces = mutableListOf<String>()
            for ((index, chunk) in chunks.withIndex()) {
                try {
                    pieces += recognize(recognizer, audio, chunk)
                } catch (e: TranscriptionException) {
                    throw TranscriptionException("조각 ${index + 1}/${chunks.size}: ${e.message}")
                }
                delay(BETWEEN_CHUNKS_MS)
            }
            return pieces.filter { it.isNotEmpty() }.joinToString("\n")
        } finally {
            recognizer.reset()
        }
    }

    /** Recognizes one piece, retrying errors that usually pass after a short wait. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private suspend fun recognize(recognizer: SharedRecognizer, audio: WavData, chunk: Chunk): String {
        var lastCode = 0
        repeat(RETRIES) {
            try {
                return suspendCancellableCoroutine { continuation ->
                    Session(recognizer.get(), audio, chunk, continuation).start()
                }
            } catch (e: RetryableException) {
                lastCode = e.code
                // A disconnected or confused recognizer is replaced; a busy one is waited for.
                if (e.code != SpeechRecognizer.ERROR_RECOGNIZER_BUSY) recognizer.reset()
                delay(RETRY_WAIT_MS)
            }
        }
        throw TranscriptionException(describe(lastCode))
    }

    /** One recognizer reused for all pieces; recreated only after it fails. */
    private inner class SharedRecognizer(private val onDevice: Boolean) {
        private var current: SpeechRecognizer? = null

        fun get(): SpeechRecognizer = current ?: create().also { current = it }

        fun reset() {
            current?.destroy()
            current = null
        }

        private fun create(): SpeechRecognizer =
            if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
            else SpeechRecognizer.createSpeechRecognizer(appContext)
    }

    private class RetryableException(val code: Int) : Exception()

    private fun describe(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "네트워크 시간 초과 (코드 1)"
        SpeechRecognizer.ERROR_NETWORK -> "네트워크 오류 (코드 2)"
        SpeechRecognizer.ERROR_AUDIO -> "오디오 입력 오류 (코드 3)"
        SpeechRecognizer.ERROR_SERVER -> "서버 오류 (코드 4)"
        SpeechRecognizer.ERROR_CLIENT -> "클라이언트 오류 (코드 5)"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "인식기 사용 중 (코드 8)"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "권한 없음 (코드 9)"
        10 -> "요청이 너무 많음 (코드 10)"
        11 -> "인식 서비스 연결 끊김 (코드 11)"
        12 -> "지원하지 않는 언어 (코드 12)"
        13 -> "언어 데이터 없음 — 한국어 오프라인 음성 인식 데이터 설치 필요 (코드 13)"
        else -> "음성 인식 오류 (코드 $error)"
    }

    /** One recognition session for one piece of the recording. Runs on the main thread. */
    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private inner class Session(
        private val recognizer: SpeechRecognizer,
        private val audio: WavData,
        private val chunk: Chunk,
        private val continuation: CancellableContinuation<String>,
    ) : RecognitionListener {

        private val handler = Handler(Looper.getMainLooper())
        private val pipe = ParcelFileDescriptor.createPipe() // [0] read side, [1] write side
        private val text = StringBuilder()

        // Give up after twice the length of the piece plus a margin.
        private val timeoutMs = chunk.length * 1000L / audio.sampleRate * 2 + 15_000

        @Volatile
        private var feedDone = false
        private var finished = false

        private val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, audio.sampleRate)
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        }

        // Ends the session if the recognizer goes quiet after the whole piece was sent.
        private val idleCheck = Runnable { finish(null, stop = true) }

        fun start() {
            continuation.invokeOnCancellation { handler.post { finish(null, stop = true, cancelled = true) } }
            recognizer.setRecognitionListener(this)
            Thread({ feed() }, "FileTranscriberFeed").apply { isDaemon = true }.start()
            handler.postDelayed({ finish("음성 인식이 응답하지 않습니다.", stop = true) }, timeoutMs)
            recognizer.startListening(intent)
        }

        /** Writes the piece as little-endian PCM16 into the pipe, then closes it. */
        private fun feed() {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { out ->
                    val buffer = ByteBuffer.allocate(FEED_SAMPLES * 2).order(ByteOrder.LITTLE_ENDIAN)
                    var index = chunk.start
                    while (index < chunk.end) {
                        val count = minOf(FEED_SAMPLES, chunk.end - index)
                        buffer.clear()
                        for (i in index until index + count) buffer.putShort(audio.samples[i])
                        out.write(buffer.array(), 0, count * 2)
                        index += count
                    }
                }
            } catch (e: IOException) {
                // The read side was closed because the session already ended.
            } finally {
                feedDone = true
                handler.post { touch() }
            }
        }

        private fun touch() {
            if (finished) return
            handler.removeCallbacks(idleCheck)
            if (feedDone) handler.postDelayed(idleCheck, IDLE_AFTER_FEED_MS)
        }

        private fun append(bundle: Bundle?) {
            val piece = bundle
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                ?.trim()
            if (!piece.isNullOrEmpty()) {
                if (text.isNotEmpty()) text.append(' ')
                text.append(piece)
            }
            touch()
        }

        /**
         * [stop] cancels a session the recognizer has not ended by itself, so the shared
         * recognizer is free for the next piece.
         */
        private fun finish(
            error: String?,
            stop: Boolean = false,
            cancelled: Boolean = false,
            retryCode: Int? = null,
        ) {
            if (finished) return
            finished = true
            handler.removeCallbacksAndMessages(null)
            if (stop) runCatching { recognizer.cancel() }
            runCatching { pipe[0].close() }
            runCatching { pipe[1].close() }
            if (cancelled || !continuation.isActive) return

            val result = text.toString().trim()
            when {
                retryCode != null -> continuation.resumeWithException(RetryableException(retryCode))
                result.isEmpty() && error != null ->
                    continuation.resumeWithException(TranscriptionException(error))
                else -> continuation.resume(result)
            }
        }

        override fun onSegmentResults(segmentResults: Bundle) = append(segmentResults)
        override fun onEndOfSegmentedSession() = finish(null)

        override fun onResults(results: Bundle?) {
            append(results)
            finish(null)
        }

        override fun onPartialResults(partialResults: Bundle?) = touch()

        override fun onError(error: Int) {
            when (error) {
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> finish(null)
                SpeechRecognizer.ERROR_CLIENT,
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                ERROR_SERVER_DISCONNECTED -> finish(null, retryCode = error)
                else -> finish(describe(error))
            }
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private companion object {
        const val FEED_SAMPLES = 4096
        const val IDLE_AFTER_FEED_MS = 10_000L
        const val RETRIES = 3
        const val RETRY_WAIT_MS = 1_000L
        const val BETWEEN_CHUNKS_MS = 100L
        const val ERROR_SERVER_DISCONNECTED = 11
    }
}
