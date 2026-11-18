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
import com.mochigalbi.catchpopped.audio.WavFile
import kotlinx.coroutines.CancellableContinuation
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class TranscriptionException(message: String) : Exception(message)

/**
 * Transcribes a recorded WAV file with the system speech recognizer, by handing it the
 * audio through a pipe instead of the microphone (Android 13+).
 *
 * EXPERIMENTAL. Whether the recognition service on a given phone accepts audio this way
 * has not been verified. If it does not, [transcribe] fails with a [TranscriptionException]
 * and the fallback is an on-device engine such as whisper.cpp.
 *
 * Call from the main thread.
 */
class FileTranscriber(context: Context, private val language: String = "ko-KR") {

    private val appContext = context.applicationContext

    suspend fun transcribe(wav: File): String {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            throw TranscriptionException("파일 받아쓰기는 Android 13 이상에서만 됩니다.")
        }
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            throw TranscriptionException("이 기기에서 음성 인식 서비스를 찾을 수 없습니다.")
        }
        val dataBytes = wav.length() - WavFile.HEADER_SIZE
        if (dataBytes <= 0) throw TranscriptionException("녹음 파일이 비어 있습니다.")
        val sampleRate = WavFile.sampleRate(wav)

        // The on-device recognizer is tried first: the default one may need the network
        // and may not accept audio from a file.
        val attempts = buildList {
            if (SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)) add(true)
            add(false)
        }
        val errors = mutableListOf<String>()
        for (onDevice in attempts) {
            try {
                return suspendCancellableCoroutine { continuation ->
                    Session(wav, sampleRate, dataBytes, onDevice, continuation).start()
                }
            } catch (e: TranscriptionException) {
                errors += (if (onDevice) "온디바이스 인식기: " else "기본 인식기: ") + e.message
            }
        }
        throw TranscriptionException(errors.joinToString("\n"))
    }

    private fun describe(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "네트워크 시간 초과 (코드 1)"
        SpeechRecognizer.ERROR_NETWORK -> "네트워크 오류 (코드 2)"
        SpeechRecognizer.ERROR_AUDIO -> "오디오 입력 오류 (코드 3)"
        SpeechRecognizer.ERROR_SERVER -> "서버 오류 (코드 4)"
        SpeechRecognizer.ERROR_CLIENT -> "클라이언트 오류 (코드 5)"
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "권한 없음 (코드 9)"
        SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "인식기 사용 중 (코드 8)"
        12 -> "지원하지 않는 언어 (코드 12)"
        13 -> "언어 데이터 없음 — 한국어 오프라인 음성 인식 데이터 설치 필요 (코드 13)"
        else -> "음성 인식 오류 (코드 $error)"
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private inner class Session(
        private val wav: File,
        private val sampleRate: Int,
        dataBytes: Long,
        onDevice: Boolean,
        private val continuation: CancellableContinuation<String>,
    ) : RecognitionListener {

        private val handler = Handler(Looper.getMainLooper())
        private val pipe = ParcelFileDescriptor.createPipe() // [0] read side, [1] write side
        private val recognizer =
            if (onDevice) SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
            else SpeechRecognizer.createSpeechRecognizer(appContext)
        private val text = StringBuilder()

        // Give up after twice the length of the recording plus a margin.
        private val timeoutMs = dataBytes * 1000 / (sampleRate * 2L) * 2 + 30_000

        @Volatile
        private var feedDone = false
        private var finished = false
        private var restarts = 0

        private val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE, pipe[0])
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_CHANNEL_COUNT, 1)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_ENCODING, AudioFormat.ENCODING_PCM_16BIT)
            putExtra(RecognizerIntent.EXTRA_AUDIO_SOURCE_SAMPLING_RATE, sampleRate)
            // Ask for one result per utterance instead of stopping at the first pause.
            putExtra(RecognizerIntent.EXTRA_SEGMENTED_SESSION, RecognizerIntent.EXTRA_AUDIO_SOURCE)
        }

        // Ends the session if the recognizer goes quiet after all audio has been sent.
        private val idleCheck = Runnable { finish(null) }

        fun start() {
            continuation.invokeOnCancellation { handler.post { finish(null, cancelled = true) } }
            recognizer.setRecognitionListener(this)
            Thread({ feed() }, "FileTranscriberFeed").apply { isDaemon = true }.start()
            handler.postDelayed({ finish("음성 인식이 응답하지 않습니다.") }, timeoutMs)
            recognizer.startListening(intent)
        }

        /** Copies the PCM samples (without the WAV header) into the pipe. */
        private fun feed() {
            try {
                ParcelFileDescriptor.AutoCloseOutputStream(pipe[1]).use { out ->
                    wav.inputStream().use { input ->
                        input.skip(WavFile.HEADER_SIZE.toLong())
                        input.copyTo(out)
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
                if (text.isNotEmpty()) text.append('\n')
                text.append(piece)
            }
            touch()
        }

        /**
         * Services that ignore the segmented-session request end after one utterance.
         * While audio is still being sent, start another session on the same pipe.
         */
        private fun continueOrFinish(error: String?, mayRestart: Boolean) {
            if (finished) return
            if (mayRestart && !feedDone && restarts < MAX_RESTARTS) {
                restarts++
                handler.postDelayed({ if (!finished) recognizer.startListening(intent) }, 100)
                return
            }
            finish(error)
        }

        private fun finish(error: String?, cancelled: Boolean = false) {
            if (finished) return
            finished = true
            handler.removeCallbacksAndMessages(null)
            recognizer.destroy()
            runCatching { pipe[0].close() }
            runCatching { pipe[1].close() }
            if (cancelled || !continuation.isActive) return

            val result = text.toString().trim()
            if (result.isEmpty() && error != null) {
                continuation.resumeWithException(TranscriptionException(error))
            } else {
                continuation.resume(result)
            }
        }

        override fun onSegmentResults(segmentResults: Bundle) {
            append(segmentResults)
        }

        override fun onEndOfSegmentedSession() {
            finish(null)
        }

        override fun onResults(results: Bundle?) {
            append(results)
            continueOrFinish(error = null, mayRestart = true)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            touch()
        }

        override fun onError(error: Int) {
            val silence = error == SpeechRecognizer.ERROR_NO_MATCH ||
                error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            continueOrFinish(
                error = if (silence) null else describe(error),
                mayRestart = silence,
            )
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private companion object {
        const val MAX_RESTARTS = 200
        const val IDLE_AFTER_FEED_MS = 20_000L
    }
}
