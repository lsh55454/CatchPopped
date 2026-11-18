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

class AppSettings private constructor(private val prefs: SharedPreferences) {

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
