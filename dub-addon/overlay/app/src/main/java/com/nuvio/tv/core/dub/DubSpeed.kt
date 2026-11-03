package com.nuvio.tv.core.dub

import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Explains, in Turkish, why a dub cannot be matched with one delay when it runs at another speed
 * than the video (the classic case: a dub made from a 25 fps PAL version of a 23.976 fps film is
 * 4.1% shorter and drifts away more and more). Android-free so it can be unit tested on the JVM.
 *
 * The same number of frames lasts N / fps seconds, so length(dub) / length(video) = fpsVideo / fpsDub.
 */
internal object DubSpeed {

    private class Known(val fpsVideo: Double, val fpsDub: Double) {
        val ratio: Double get() = fpsVideo / fpsDub
    }

    /** The speed differences between PAL (25) and film (23.976 / 24) that show up in real releases. */
    private val KNOWN = listOf(
        Known(23.976, 25.0),
        Known(24.0, 25.0),
        Known(25.0, 23.976),
        Known(25.0, 24.0)
    )

    /** Lengths closer than this are "the same": a few seconds of extra silence do not mean another speed. */
    private const val SAME_BELOW = 0.003

    /** A length ratio this close (relative) to a known one is called that. */
    private const val MATCH_TOLERANCE = 0.004

    /**
     * A sentence for the toast when the two lengths differ clearly, null when they are alike. The
     * wording is careful: a long dub can also just have an extra part (ads, credits).
     */
    fun explain(dubMs: Long, videoMs: Long): String? {
        if (dubMs <= 0L || videoMs <= 0L) return null
        val ratio = dubMs.toDouble() / videoMs
        if (abs(ratio - 1.0) < SAME_BELOW) return null

        val shorterOrLonger = if (dubMs < videoMs) "kısa" else "uzun"
        val head = "dublaj videodan ${duration(abs(dubMs - videoMs))} $shorterOrLonger (%${percent(abs(ratio - 1.0) * 100)})"
        val known = KNOWN.minByOrNull { abs(ratio - it.ratio) }
            ?.takeIf { abs(ratio - it.ratio) / it.ratio < MATCH_TOLERANCE }
        return if (known != null) {
            "$head: video ${fps(known.fpsVideo)} fps, dublaj ${fps(known.fpsDub)} fps hızında olabilir; " +
                "böyle bir hız farkı tek bir gecikmeyle düzelmez"
        } else {
            "$head: farklı kurgu ya da fazladan giriş/çıkış bölümü olabilir"
        }
    }

    /** A hint for a measured slope (ms of lag per ms of film), empty when it matches nothing known. */
    fun slopeHint(slope: Double): String =
        if (abs(abs(slope) - 0.001) < 0.0004) " (24 ↔ 23.976 ya da 30 ↔ 29.97 fps farkına benziyor)" else ""

    private fun fps(x: Double): String =
        if (x == x.roundToInt().toDouble()) x.roundToInt().toString()
        else String.format(Locale.US, "%.3f", x)

    private fun percent(x: Double): String = String.format(Locale.US, "%.1f", x).replace('.', ',')

    private fun duration(ms: Long): String {
        val total = (ms / 1000.0).roundToInt()
        val m = total / 60
        val s = total % 60
        return if (m > 0) "$m dk $s sn" else "$s sn"
    }
}
