package com.nuvio.tv.core.dub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.exp

/**
 * JVM tests of the audio-first cascade in [DubSyncAnalyzer]: the music/effects audio decides, the
 * embedded subtitles are used only when the audio gave no answer.
 *
 * The windows are synthetic and deterministic (own LCG, no java.util.Random): per window 36 shared
 * "impacts" (heard in the original and, [musicLagFrames] later, in the dub) and speech in the dub that
 * follows the original's subtitle cues [speechLagFrames] later. Frames are 10 ms.
 */
class DubSyncAnalyzerTest {

    private class Lcg(seed: Long) {
        private var st = seed and 0x7fffffffL
        private fun nxt(): Long {
            st = (st * 1103515245L + 12345L) and 0x7fffffffL
            return st
        }
        fun f(): Double = (nxt() shr 8).toDouble() / (1 shl 23).toDouble()
        fun i(lo: Int, hi: Int): Int = lo + (f() * (hi - lo)).toInt()
    }

    private val maxLag = DubSyncAnalyzer.MAX_LAG
    private val n = 1500                // original window: 15 s
    private val m = n + 2 * maxLag      // dub window: 45 s

    private fun addEvent(arr: DoubleArray, idx: Int, amp: Double) {
        for (j in idx until minOf(idx + 60, arr.size)) if (j >= 0) arr[j] += amp * exp(-(j - idx) / 8.0)
    }

    private fun window(
        seed: Long,
        musicLagFrames: Int,
        speechLagFrames: Int,
        withAudio: Boolean = true,
        garbageAudio: Boolean = false
    ): Pair<ProbeCapture, ProbeCapture> {
        val r = Lcg(seed)
        val events = List(36) { val f = r.i(-maxLag, 3000); f to (0.2 + 0.8 * r.f()) }
        val cues = BooleanArray(m)
        var t = 0
        while (t < m) {
            val off = r.i(50, 250)
            val on = r.i(100, 300)
            t += off
            for (k in t until minOf(t + on, m)) cues[k] = true
            t += on
        }
        fun base(k: Int) = DoubleArray(k) { 1e-4 * (1 + 0.3 * r.f()) }
        val ob = arrayOfNulls<DoubleArray>(5)
        val db = arrayOfNulls<DoubleArray>(5)
        for (b in 0..4) { ob[b] = base(n); db[b] = base(m) }
        val scale = mapOf(ProbeCapture.BAND_LOW to 1.0, ProbeCapture.BAND_LOWMID to 0.15, ProbeCapture.BAND_HIGH to 0.8)
        for ((b, sc) in scale) {
            for ((f, a) in events) {
                if (f in 0 until n) addEvent(ob[b]!!, f, a * sc)
                val j = f + maxLag + musicLagFrames
                if (j in 0 until m) addEvent(db[b]!!, j, a * sc)
            }
        }
        for (c in 0 until m) {
            if (cues[c]) {
                val j = c + speechLagFrames
                if (j in 0 until m) { db[ProbeCapture.BAND_MID]!![j] += 0.5; db[ProbeCapture.BAND_LOWMID]!![j] += 0.3 }
            }
        }
        repeat(25) {
            val j = r.i(0, m)
            val a = 0.3 * r.f()
            addEvent(db[ProbeCapture.BAND_LOWMID]!!, j, a)
            addEvent(db[ProbeCapture.BAND_MID]!!, j, a)
        }

        var origBands: Array<FloatArray> = Array(5) { b -> FloatArray(n) { ob[b]!![it].toFloat() } }
        if (garbageAudio) {
            // Audio that has nothing to do with the dub: music/effects cannot find a shift.
            val g = Lcg(seed * 7 + 13)
            origBands = Array(5) {
                val arr = DoubleArray(n) { 1e-4 * (1 + 0.3 * g.f()) }
                repeat(12) { addEvent(arr, g.i(0, n), 0.2 + 0.8 * g.f()) }
                FloatArray(n) { k -> arr[k].toFloat() }
            }
        }
        val cueActivity = FloatArray(n) { if (cues[maxLag + it]) 1f else 0f }
        val orig = ProbeCapture(if (withAudio) origBands else null, cueActivity, n)
        val dub = ProbeCapture(Array(5) { b -> FloatArray(m) { db[b]!![it].toFloat() } }, null, m)
        return orig to dub
    }

    private val seeds = longArrayOf(11, 23, 37, 41, 59)

    private fun windows(music: Int, speech: Int, withAudio: Boolean = true, garbage: Boolean = false) =
        seeds.map { window(it, music, speech, withAudio, garbage) }

    private fun ok(r: DubSyncAnalyzer.Result): DubSyncAnalyzer.Result.Ok {
        assertTrue("expected Ok but was $r", r is DubSyncAnalyzer.Result.Ok)
        return r as DubSyncAnalyzer.Result.Ok
    }

    @Test
    fun conclusiveAudioWinsOverDisagreeingSubtitles() {
        // Music/effects say +500 ms, the subtitles (speech) say +2000 ms. Before the cascade this was
        // thrown away as a contradiction; now the audio is used and the subtitles are not consulted.
        val w = windows(music = 50, speech = 200)
        val r = ok(DubSyncAnalyzer.analyze(w))
        assertEquals(DubSyncAnalyzer.Method.MUSIC_EFFECTS, r.method)
        assertTrue(r.precise)
        assertTrue("lag ${r.lagMs}", abs(r.lagMs - 500) <= 30)

        // Sanity: the subtitles really disagree (audio stage skipped -> they give ~2000 ms).
        val s = ok(DubSyncAnalyzer.analyze(w, skipAudio = true))
        assertEquals(DubSyncAnalyzer.Method.SUBTITLES, s.method)
        assertTrue("subtitle lag ${s.lagMs}", abs(s.lagMs - 2000) <= 150)
    }

    @Test
    fun agreeingMethodsStillGiveTheAudioValue() {
        val r = ok(DubSyncAnalyzer.analyze(windows(music = -120, speech = -120)))
        assertEquals(DubSyncAnalyzer.Method.MUSIC_EFFECTS, r.method)
        assertTrue("lag ${r.lagMs}", abs(r.lagMs + 1200) <= 30)
    }

    @Test
    fun subtitlesAreUsedWhenOriginalAudioIsNotDecodable() {
        val r = ok(DubSyncAnalyzer.analyze(windows(music = 50, speech = 200, withAudio = false)))
        assertEquals(DubSyncAnalyzer.Method.SUBTITLES, r.method)
        assertFalse(r.precise)
        assertTrue("lag ${r.lagMs}", abs(r.lagMs - 2000) <= 150)
    }

    @Test
    fun subtitlesWaitUntilTheAudioHasFailed() {
        val w = windows(music = 50, speech = 200, garbage = true)
        // The controller's loop asks without fallback while more windows are coming: audio only.
        val audioOnly = DubSyncAnalyzer.analyze(w, subtitleFallback = false)
        assertTrue("expected Unreliable but was $audioOnly", audioOnly is DubSyncAnalyzer.Result.Unreliable)
        // Once the audio is out of chances, the same windows are enough for the subtitles.
        val r = ok(DubSyncAnalyzer.analyze(w))
        assertEquals(DubSyncAnalyzer.Method.SUBTITLES, r.method)
        assertTrue("lag ${r.lagMs}", abs(r.lagMs - 2000) <= 150)
    }

    @Test
    fun nothingReadableIsUnreliable() {
        val empty = ProbeCapture(null, null, 0)
        val w = List(DubSyncAnalyzer.MIN_WINDOWS) { empty to empty }
        assertTrue(DubSyncAnalyzer.analyze(w) is DubSyncAnalyzer.Result.Unreliable)
        assertTrue(DubSyncAnalyzer.analyze(w.take(1)) is DubSyncAnalyzer.Result.Unreliable)
    }
}
