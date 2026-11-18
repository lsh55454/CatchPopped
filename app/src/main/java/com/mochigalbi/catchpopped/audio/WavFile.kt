package com.mochigalbi.catchpopped.audio

import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavData(val sampleRate: Int, val samples: ShortArray)

/** Reads and writes the one WAV layout this app produces: 44-byte header, mono, 16-bit PCM. */
object WavFile {
    const val HEADER_SIZE = 44

    fun header(sampleRate: Int, dataBytes: Int): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put("RIFF".toByteArray(Charsets.US_ASCII))
        buffer.putInt(36 + dataBytes)
        buffer.put("WAVE".toByteArray(Charsets.US_ASCII))
        buffer.put("fmt ".toByteArray(Charsets.US_ASCII))
        buffer.putInt(16)                 // fmt chunk size
        buffer.putShort(1)                // PCM
        buffer.putShort(1)                // mono
        buffer.putInt(sampleRate)
        buffer.putInt(sampleRate * 2)     // bytes per second
        buffer.putShort(2)                // bytes per sample frame
        buffer.putShort(16)               // bits per sample
        buffer.put("data".toByteArray(Charsets.US_ASCII))
        buffer.putInt(dataBytes)
        return buffer.array()
    }

    fun sampleRate(file: File): Int {
        val head = ByteArray(HEADER_SIZE)
        file.inputStream().use { input ->
            if (input.read(head) < HEADER_SIZE) throw IOException("WAV 파일이 너무 짧습니다.")
        }
        return ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN).getInt(24)
    }

    fun read(file: File): WavData {
        val bytes = file.readBytes()
        if (bytes.size < HEADER_SIZE) throw IOException("WAV 파일이 너무 짧습니다.")
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        val sampleRate = buffer.getInt(24)
        val samples = ShortArray((bytes.size - HEADER_SIZE) / 2)
        buffer.position(HEADER_SIZE)
        buffer.asShortBuffer().get(samples)
        return WavData(sampleRate, samples)
    }

    fun write(file: File, sampleRate: Int, samples: ShortArray) {
        val dataBytes = samples.size * 2
        val buffer = ByteBuffer.allocate(HEADER_SIZE + dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        buffer.put(header(sampleRate, dataBytes))
        for (sample in samples) buffer.putShort(sample)
        file.parentFile?.mkdirs()
        file.writeBytes(buffer.array())
    }
}
