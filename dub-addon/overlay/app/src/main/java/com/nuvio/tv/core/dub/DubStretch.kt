package com.nuvio.tv.core.dub

import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToLong

/**
 * Rides on `Format.customData` of a dub's audio track (set by the stretching media source, read by
 * the stretching audio sink), so the sink knows which audio is the dub and by how much.
 *
 * [ratio] = length of the dub / length of the film. A PAL dub of a 23.976 fps film is 4.1% shorter:
 * 23.976 / 25 = 0.959. A data class on purpose: formats are compared by value.
 */
internal data class DubStretchTag(val ratio: Double)

/**
 * Android-free helpers for playing a dub made at another speed (25 fps PAL vs 23.976 fps film).
 *
 * Film time f and dub time d are tied by d = f * ratio. To keep the dub in step with the film,
 *  - the dub's time stamps and seek positions are mapped between the two (media source), and
 *  - the dub's audio is stretched by 1 / ratio with the pitch kept (audio sink).
 */
internal object DubStretch {

    /** Outside this range a ratio is a bug, not a speed change. */
    private const val MIN_RATIO = 0.9
    private const val MAX_RATIO = 1.1

    /**
     * Closer to 1 than this and nothing is stretched. It is also what Media3's Sonic needs: below
     * ~1% it switches itself off.
     */
    private const val MIN_DEVIATION = 0.02

    // Same values as androidx.media3.common.C.TIME_UNSET / TIME_END_OF_SOURCE (kept Android-free).
    private const val TIME_UNSET = Long.MIN_VALUE + 1
    private const val TIME_END_OF_SOURCE = Long.MIN_VALUE

    fun isOn(ratio: Double): Boolean =
        ratio.isFinite() && ratio in MIN_RATIO..MAX_RATIO && abs(ratio - 1.0) >= MIN_DEVIATION

    private fun special(us: Long) = us == TIME_UNSET || us == TIME_END_OF_SOURCE || us == Long.MAX_VALUE

    /** Dub time -> film time (microseconds). Special values (unset, end of source) pass through. */
    fun toFilmUs(dubUs: Long, ratio: Double): Long =
        if (special(dubUs)) dubUs else (dubUs / ratio).roundToLong()

    /** Film time -> dub time (microseconds). */
    fun toDubUs(filmUs: Long, ratio: Double): Long =
        if (special(filmUs)) filmUs else (filmUs * ratio).roundToLong()

    /** Where in the dub a stretch of the film that starts at [filmStartMs] starts. */
    fun dubStartMs(filmStartMs: Long, ratio: Double): Long =
        if (isOn(ratio)) (filmStartMs * ratio).roundToLong().coerceAtLeast(0L) else filmStartMs

    /** How much of the dub covers [filmLengthMs] of the film (a little extra so the end is not cut). */
    fun dubLengthMs(filmLengthMs: Long, ratio: Double): Long =
        if (isOn(ratio)) ceil(filmLengthMs * ratio).toLong() + 20L else filmLengthMs

    /**
     * [src] holds one value per frame in DUB time; returns [outFrames] values on the FILM time grid
     * (film frame k = dub frame k * ratio), linearly interpolated, the last value held at the end.
     */
    fun resample(src: FloatArray, ratio: Double, outFrames: Int): FloatArray {
        val out = FloatArray(outFrames)
        if (src.isEmpty()) return out
        for (k in 0 until outFrames) {
            val pos = k * ratio
            val i = floor(pos).toInt()
            out[k] = if (i >= src.size - 1) {
                src[src.size - 1]
            } else {
                val frac = (pos - i).toFloat()
                src[i] * (1f - frac) + src[i + 1] * frac
            }
        }
        return out
    }

    /** A capture of the dub read in its own time, put on the film's time grid. Unchanged when not stretched. */
    fun toFilmTime(capture: ProbeCapture, ratio: Double, filmFrames: Int): ProbeCapture {
        if (!isOn(ratio)) return capture
        val bands = capture.bands?.let { b -> Array(b.size) { resample(b[it], ratio, filmFrames) } }
        return ProbeCapture(bands, null, filmFrames, capture.sourceDurationMs)
    }

    /** "4,1" for a ratio of 0.959. */
    fun percentText(ratio: Double): String =
        String.format(Locale.US, "%.1f", abs(1.0 - ratio) * 100).replace('.', ',')
}
