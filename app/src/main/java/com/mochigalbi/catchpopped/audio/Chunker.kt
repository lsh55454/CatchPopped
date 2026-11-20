package com.mochigalbi.catchpopped.audio

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/** Sample range [start, end) of a recording. */
data class Chunk(val start: Int, val end: Int) {
    val length: Int get() = end - start
}

/**
 * Splits a recording into speech pieces so each one can be recognized on its own.
 *
 * - A pause of at least [MIN_PAUSE_MS] ends a piece. The middle of the pause is dropped,
 *   [PAD_MS] of it is kept on each side, so no word is cut in half.
 * - A piece longer than [MAX_CHUNK_MS] is cut at its quietest frame.
 * - Speech is any 30 ms frame louder than the noise floor (10th percentile) by
 *   [ABOVE_FLOOR_DB]. The values are first guesses to be tuned with recordings.
 */
object Chunker {
    private const val FRAME_MS = 30
    private const val MIN_PAUSE_MS = 800
    private const val PAD_MS = 200
    private const val MIN_SPEECH_MS = 200
    private const val MAX_CHUNK_MS = 20_000
    private const val MIN_SPLIT_MS = 5_000
    private const val ABOVE_FLOOR_DB = 6.0
    private const val MIN_SPEECH_DB = -50.0

    fun split(samples: ShortArray, sampleRate: Int): List<Chunk> {
        val frame = sampleRate * FRAME_MS / 1000
        val frames = samples.size / frame
        if (frames == 0) return emptyList()

        val db = DoubleArray(frames) { frameDb(samples, it * frame, frame) }
        val floor = db.sortedArray()[frames / 10]
        val threshold = max(floor + ABOVE_FLOOR_DB, MIN_SPEECH_DB)
        val speech = BooleanArray(frames) { db[it] > threshold }

        // Speech regions in frames, separated by long pauses.
        val minPause = MIN_PAUSE_MS / FRAME_MS
        val regions = mutableListOf<Chunk>()
        var regionStart = -1
        var lastSpeech = -1
        for (f in 0 until frames) {
            if (!speech[f]) continue
            if (regionStart < 0) {
                regionStart = f
            } else if (f - lastSpeech - 1 >= minPause) {
                regions += Chunk(regionStart, lastSpeech + 1)
                regionStart = f
            }
            lastSpeech = f
        }
        if (regionStart >= 0) regions += Chunk(regionStart, lastSpeech + 1)

        // Drop clicks, keep a little of the pause around each region.
        val minSpeech = MIN_SPEECH_MS / FRAME_MS
        val pad = PAD_MS / FRAME_MS
        val padded = regions
            .filter { r -> (r.start until r.end).count { speech[it] } >= minSpeech }
            .map { Chunk(max(0, it.start - pad), minOf(frames, it.end + pad)) }

        // Cut long regions at their quietest frame.
        val maxLen = MAX_CHUNK_MS / FRAME_MS
        val minLen = MIN_SPLIT_MS / FRAME_MS
        val pieces = mutableListOf<Chunk>()
        for (r in padded) {
            var start = r.start
            while (r.end - start > maxLen) {
                val cut = (start + minLen until start + maxLen).minBy { db[it] }
                pieces += Chunk(start, cut)
                start = cut
            }
            pieces += Chunk(start, r.end)
        }

        return pieces.map { p ->
            val end = if (p.end == frames) samples.size else p.end * frame
            Chunk(p.start * frame, end)
        }
    }

    /** Level of one frame in dB relative to full scale. */
    private fun frameDb(samples: ShortArray, from: Int, size: Int): Double {
        var sum = 0.0
        for (i in from until from + size) {
            val v = samples[i].toDouble()
            sum += v * v
        }
        val rms = sqrt(sum / size)
        return if (rms < 1.0) -100.0 else 20.0 * log10(rms / Short.MAX_VALUE)
    }
}
