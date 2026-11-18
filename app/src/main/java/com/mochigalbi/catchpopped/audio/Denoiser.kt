package com.mochigalbi.catchpopped.audio

import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * First-stage cleanup for speech recorded outdoors.
 *
 * 1. High-pass filter: removes low rumble (traffic, wind, handling noise) below the
 *    range of speech.
 * 2. Peak normalization: brings quiet recordings up to a consistent level.
 *
 * There is deliberately no noise gate: a gate cuts quiet speech, which is the failure
 * this stage is meant to reduce.
 */
object Denoiser {
    private val TARGET_PEAK = 0.9 * Short.MAX_VALUE
    private const val MAX_GAIN = 8.0

    fun process(input: ShortArray, sampleRate: Int, cutoffHz: Double = 100.0): ShortArray {
        val filtered = highPass(input, sampleRate, cutoffHz)

        var peak = 0.0
        for (value in filtered) {
            val magnitude = abs(value)
            if (magnitude > peak) peak = magnitude
        }
        // Limit the gain so a near-silent recording is not turned into loud hiss.
        val gain = if (peak < 1.0) 1.0 else min(TARGET_PEAK / peak, MAX_GAIN)

        return ShortArray(filtered.size) { index ->
            (filtered[index] * gain)
                .coerceIn(Short.MIN_VALUE.toDouble(), Short.MAX_VALUE.toDouble())
                .roundToInt()
                .toShort()
        }
    }

    /** Reads [source], cleans it and writes the result to [target]. */
    fun file(source: File, target: File) {
        val wav = WavFile.read(source)
        WavFile.write(target, wav.sampleRate, process(wav.samples, wav.sampleRate))
    }

    /** Second-order Butterworth high-pass (RBJ biquad). */
    private fun highPass(input: ShortArray, sampleRate: Int, cutoffHz: Double): DoubleArray {
        val omega = 2.0 * PI * cutoffHz / sampleRate
        val cosOmega = cos(omega)
        val alpha = sin(omega) / (2.0 * 0.7071)

        val a0 = 1.0 + alpha
        val b0 = (1.0 + cosOmega) / 2.0 / a0
        val b1 = -(1.0 + cosOmega) / a0
        val b2 = (1.0 + cosOmega) / 2.0 / a0
        val a1 = -2.0 * cosOmega / a0
        val a2 = (1.0 - alpha) / a0

        val output = DoubleArray(input.size)
        var x1 = 0.0
        var x2 = 0.0
        var y1 = 0.0
        var y2 = 0.0
        for (index in input.indices) {
            val x0 = input[index].toDouble()
            val y0 = b0 * x0 + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            output[index] = y0
            x2 = x1
            x1 = x0
            y2 = y1
            y1 = y0
        }
        return output
    }
}
