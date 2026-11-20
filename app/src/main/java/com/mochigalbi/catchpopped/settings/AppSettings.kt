package com.mochigalbi.catchpopped.settings

import android.content.Context
import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class RecordMode {
    /** Text appears while speaking. No audio file, no denoising. */
    LIVE,

    /** Records a WAV file first, then denoises and transcribes it. */
    FILE,
}

/** Speech recognition engine for file recordings. */
enum class SttEngine { SYSTEM, WHISPER }

class AppSettings private constructor(private val prefs: SharedPreferences) {

    private val _sttEngine = MutableStateFlow(
        runCatching { SttEngine.valueOf(prefs.getString(KEY_STT_ENGINE, null) ?: "") }
            .getOrDefault(SttEngine.SYSTEM)
    )
    val sttEngine: StateFlow<SttEngine> = _sttEngine.asStateFlow()

    /**
     * File name of the selected LLM in [com.mochigalbi.catchpopped.models.ModelFiles].
     * When set, a document is written automatically after each transcription.
     */
    private val _llmModel = MutableStateFlow(prefs.getString(KEY_LLM_MODEL, null))
    val llmModel: StateFlow<String?> = _llmModel.asStateFlow()

    fun setLlmModel(name: String?) {
        prefs.edit().putString(KEY_LLM_MODEL, name).apply()
        _llmModel.value = name
    }

    /** File name of the selected model in [com.mochigalbi.catchpopped.models.ModelFiles]. */
    private val _whisperModel = MutableStateFlow(prefs.getString(KEY_WHISPER_MODEL, null))
    val whisperModel: StateFlow<String?> = _whisperModel.asStateFlow()

    /** "ko" or "auto". */
    private val _whisperLanguage = MutableStateFlow(prefs.getString(KEY_WHISPER_LANGUAGE, null) ?: "ko")
    val whisperLanguage: StateFlow<String> = _whisperLanguage.asStateFlow()

    fun setSttEngine(engine: SttEngine) {
        prefs.edit().putString(KEY_STT_ENGINE, engine.name).apply()
        _sttEngine.value = engine
    }

    fun setWhisperModel(name: String?) {
        prefs.edit().putString(KEY_WHISPER_MODEL, name).apply()
        _whisperModel.value = name
    }

    fun setWhisperLanguage(language: String) {
        prefs.edit().putString(KEY_WHISPER_LANGUAGE, language).apply()
        _whisperLanguage.value = language
    }

    private val _recordMode = MutableStateFlow(
        runCatching { RecordMode.valueOf(prefs.getString(KEY_RECORD_MODE, null) ?: "") }
            .getOrDefault(RecordMode.LIVE)
    )
    val recordMode: StateFlow<RecordMode> = _recordMode.asStateFlow()

    private val _denoise = MutableStateFlow(prefs.getBoolean(KEY_DENOISE, true))
    val denoise: StateFlow<Boolean> = _denoise.asStateFlow()

    fun setRecordMode(mode: RecordMode) {
        prefs.edit().putString(KEY_RECORD_MODE, mode.name).apply()
        _recordMode.value = mode
    }

    fun setDenoise(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_DENOISE, enabled).apply()
        _denoise.value = enabled
    }

    companion object {
        private const val KEY_RECORD_MODE = "record_mode"
        private const val KEY_DENOISE = "denoise"
        private const val KEY_STT_ENGINE = "stt_engine"
        private const val KEY_WHISPER_MODEL = "whisper_model"
        private const val KEY_WHISPER_LANGUAGE = "whisper_language"
        private const val KEY_LLM_MODEL = "llm_model"

        @Volatile
        private var instance: AppSettings? = null

        fun get(context: Context): AppSettings =
            instance ?: synchronized(this) {
                instance ?: AppSettings(
                    context.applicationContext.getSharedPreferences("settings", Context.MODE_PRIVATE)
                ).also { instance = it }
            }
    }
}
