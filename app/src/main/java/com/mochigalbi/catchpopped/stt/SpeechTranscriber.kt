package com.mochigalbi.catchpopped.stt

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Continuous dictation on top of Android's SpeechRecognizer.
 *
 * The system recognizer ends a session whenever the speaker pauses, which drops whatever
 * is said next. This class restarts the session each time it ends and joins the pieces,
 * so the user can pause and keep talking.
 *
 * All methods must be called from the main thread.
 */
class SpeechTranscriber(context: Context, private val language: String = "ko-KR") {

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private var recognizer: SpeechRecognizer? = null
    private var active = false
    private var restartFailures = 0

    private val _committed = MutableStateFlow("")
    /** Finished utterances, one per line. */
    val committed: StateFlow<String> = _committed.asStateFlow()

    private val _partial = MutableStateFlow("")
    /** The utterance being recognized right now; may still change. */
    val partial: StateFlow<String> = _partial.asStateFlow()

    private val _listening = MutableStateFlow(false)
    val listening: StateFlow<Boolean> = _listening.asStateFlow()

    private val _level = MutableStateFlow(0f)
    /** Input level, 0..1. */
    val level: StateFlow<Float> = _level.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error.asStateFlow()

    fun start() {
        if (active) return
        active = true
        restartFailures = 0
        _error.value = null
        listen()
    }

    /** Stops listening and returns everything recognized so far. */
    fun stop(): String {
        active = false
        handler.removeCallbacksAndMessages(null)
        commitPartial()
        recognizer?.cancel()
        _listening.value = false
        _level.value = 0f
        return _committed.value
    }

    fun destroy() {
        active = false
        handler.removeCallbacksAndMessages(null)
        recognizer?.destroy()
        recognizer = null
    }

    private fun listen() {
        if (!active) return
        val current = recognizer ?: SpeechRecognizer.createSpeechRecognizer(appContext).also {
            it.setRecognitionListener(listener)
            recognizer = it
        }
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        current.startListening(intent)
    }

    private fun restart(delayMs: Long) {
        handler.postDelayed({ listen() }, delayMs)
    }

    private fun append(text: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val current = _committed.value
        _committed.value = if (current.isEmpty()) trimmed else current + "\n" + trimmed
    }

    private fun commitPartial() {
        append(_partial.value)
        _partial.value = ""
    }

    private fun fail(message: String) {
        commitPartial()
        active = false
        _listening.value = false
        _level.value = 0f
        _error.value = message
    }

    private fun firstResult(bundle: Bundle?): String =
        bundle?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _listening.value = true
        }

        override fun onBeginningOfSpeech() {}

        override fun onRmsChanged(rmsdB: Float) {
            // Reported range is roughly -2..10 dB.
            _level.value = ((rmsdB + 2f) / 12f).coerceIn(0f, 1f)
        }

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {}

        override fun onPartialResults(partialResults: Bundle?) {
            if (!active) return
            _partial.value = firstResult(partialResults)
        }

        override fun onResults(results: Bundle?) {
            if (!active) return
            restartFailures = 0
            _partial.value = ""
            append(firstResult(results))
            restart(100)
        }

        override fun onError(error: Int) {
            _listening.value = false
            if (!active) return
            when (error) {
                // Silence or nothing recognizable: normal while the user is thinking.
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> {
                    commitPartial()
                    restart(100)
                }
                // The previous session has not fully closed yet; retry a few times.
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY,
                SpeechRecognizer.ERROR_CLIENT -> {
                    restartFailures++
                    if (restartFailures > 5) {
                        fail("음성 인식을 다시 시작하지 못했습니다. (코드 $error)")
                    } else {
                        recognizer?.cancel()
                        restart(400)
                    }
                }
                else -> fail(describe(error))
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun describe(error: Int): String = when (error) {
        SpeechRecognizer.ERROR_NETWORK_TIMEOUT,
        SpeechRecognizer.ERROR_NETWORK -> "네트워크 문제로 음성 인식이 중단됐습니다."
        SpeechRecognizer.ERROR_AUDIO -> "마이크 입력을 받지 못했습니다."
        SpeechRecognizer.ERROR_SERVER -> "음성 인식 서버 오류입니다."
        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "마이크 권한이 없습니다."
        12, 13 -> "이 언어의 음성 인식을 사용할 수 없습니다."
        else -> "음성 인식 오류입니다. (코드 $error)"
    }
}
