package com.mochigalbi.catchpopped.stt

import java.io.File

/** Turns a recorded WAV file into text. Throws [TranscriptionException] on failure. */
interface Transcriber {
    suspend fun transcribe(wav: File): String
}
