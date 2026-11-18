package com.mochigalbi.catchpopped.recording

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import com.mochigalbi.catchpopped.audio.WavFile
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

/**
 * Records the microphone to a 16 kHz mono 16-bit WAV file.
 *
 * The caller must hold the RECORD_AUDIO permission before calling [start].
 * Recording runs only while the app is in the foreground; there is no service yet.
 */
class WavRecorder {

    private var audioRecord: AudioRecord? = null
    private var thread: Thread? = null

    @Volatile
    private var running = false

    private val _level = MutableStateFlow(0f)
    /** Input level, 0..1. */
    val level: StateFlow<Float> = _level.asStateFlow()

    /** Returns false if the microphone could not be opened. */
    @SuppressLint("MissingPermission")
    fun start(file: File): Boolean {
        if (running) return true

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) return false

        val record = try {
            AudioRecord(
                // Tuned for speech recognition: the platform applies little processing.
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                minBuffer * 4,
            )
        } catch (e: Exception) {
            return false
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            return false
        }

        try {
            record.startRecording()
        } catch (e: IllegalStateException) {
            record.release()
            return false
        }

        file.parentFile?.mkdirs()
        audioRecord = record
        running = true
        thread = Thread({ writeLoop(record, file) }, "WavRecorder").also { it.start() }
        return true
    }

    /** Stops recording and finishes the file. Safe to call more than once. */
    fun stop() {
        if (!running && audioRecord == null) return
        running = false
        val record = audioRecord
        try {
            record?.stop()
        } catch (e: IllegalStateException) {
            // Already stopped.
        }
        try {
            thread?.join(2000)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
        record?.release()
        audioRecord = null
        thread = null
        _level.value = 0f
    }

    private fun writeLoop(record: AudioRecord, file: File) {
        val samples = ShortArray(SAMPLE_RATE / 10) // 100 ms
        val bytes = ByteBuffer.allocate(samples.size * 2).order(ByteOrder.LITTLE_ENDIAN)
        var dataBytes = 0
        try {
            RandomAccessFile(file, "rw").use { out ->
                out.setLength(0)
                out.write(WavFile.header(SAMPLE_RATE, 0))
                while (running) {
                    val read = record.read(samples, 0, samples.size)
                    if (read < 0) break
                    if (read == 0) continue

                    var peak = 0
                    bytes.clear()
                    for (index in 0 until read) {
                        val sample = samples[index]
                        bytes.putShort(sample)
                        val magnitude = abs(sample.toInt())
                        if (magnitude > peak) peak = magnitude
                    }
                    out.write(bytes.array(), 0, read * 2)
                    dataBytes += read * 2
                    _level.value = peak / 32768f
                }
                // The real sizes are only known now.
                out.seek(0)
                out.write(WavFile.header(SAMPLE_RATE, dataBytes))
            }
        } catch (e: IOException) {
            // The file stays as far as it was written; the caller sees a short recording.
        }
    }

    companion object {
        const val SAMPLE_RATE = 16000
    }
}
