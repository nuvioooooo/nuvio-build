@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.media3.common.C
import com.nuvio.tv.core.dub.DubAudioProbe
import com.nuvio.tv.core.dub.DubAutoSettings
import com.nuvio.tv.core.dub.DubSpeed
import com.nuvio.tv.core.dub.DubStretch
import com.nuvio.tv.core.dub.DubSyncAnalyzer
import com.nuvio.tv.core.dub.ProbeCapture
import com.nuvio.tv.domain.model.ExternalAudio
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs

private const val DUB_SYNC_TAG = "DubSync"

/**
 * Length of one stretch. Short on purpose: the cost is the download of the original, and many
 * short stretches spread over the film are worth more than a few long ones.
 */
private const val SYNC_WINDOW_MS = 15_000L

/** Never more stretches than this, however long it takes (each one is a separate chance). */
private const val SYNC_MAX_WINDOWS = 8

/**
 * Where the stretches are, as fractions of the duration, in the order they are used: any prefix of
 * this list is already spread over the film. Intro and end credits are avoided.
 */
private val SYNC_FRACTIONS = doubleArrayOf(0.30, 0.60, 0.45, 0.75, 0.20, 0.52, 0.68, 0.38)

private const val SYNC_MIN_START_MS = 60_000L
private const val SYNC_END_MARGIN_MS = 60_000L
private const val SYNC_MIN_DURATION_MS = 8 * 60_000L

/** The aim: finish within this. Not a hard stop: it only decides whether an extra refinement window is worth it. */
private const val SYNC_TARGET_MS = 60_000L

/** A window that takes longer than this is given up on (slow source, stalled download). */
private const val SYNC_WINDOW_TIMEOUT_MS = 30_000L

/**
 * The dub side of a window is read this much earlier and this much longer on each side than the
 * original's (the searched shift range, +-15 s): the dub is a small audio file, so the extra
 * 30 s are cheap, while the original (the heavy one) stays at [SYNC_WINDOW_MS].
 */
private val SYNC_DUB_MARGIN_MS: Long = DubSyncAnalyzer.MAX_LAG.toLong() * DubSyncAnalyzer.FRAME_MS

/** The dub window is 3x longer (30 s of extra audio), so it gets a little more time. */
private const val SYNC_DUB_TIMEOUT_MS = 40_000L

/** How long the first window may wait for the dub's length (a tiny header read, normally done long before). */
private const val SPEED_CHECK_WAIT_MS = 6_000L

/** A shift smaller than this is below what people notice; leave the delay alone. */
private const val SYNC_IGNORE_BELOW_MS = 40

/** Subtitle-only results are accurate to roughly +-150 ms; smaller corrections would be noise. */
private const val SYNC_IGNORE_BELOW_MS_COARSE = 100

/** Audio menu entry: measures the dub that is currently selected. */
internal fun PlayerRuntimeController.syncDubAudio() {
    val player = _exoPlayer
    if (player == null) {
        Toast.makeText(context, "Senkron sadece ExoPlayer ile çalışır", Toast.LENGTH_LONG).show()
        return
    }
    // The selected audio group belongs to MergingMediaSource child N (0 = the video's own audio).
    val selectedGroup = player.currentTracks.groups
        .firstOrNull { it.type == C.TRACK_TYPE_AUDIO && it.isSelected }
    val child = selectedGroup?.mediaTrackGroup?.id?.substringBefore(':')?.toIntOrNull() ?: 0
    val audio = streamExternalAudios.getOrNull(child - 1)
    if (child <= 0 || audio == null) {
        Toast.makeText(context, "Önce harici dublaj sesini seçin", Toast.LENGTH_LONG).show()
        return
    }
    startDubSync(audio, silent = false)
}

/**
 * Hook for [attachExternalAudios]. A dub with a fixed delay in the recipe is left alone. A result
 * that was measured before for this exact video file and this dub is applied right away.
 * Otherwise nothing happens unless the "Dublajı otomatik senkronla" switch in the audio menu is on
 * (off by default: measuring reads several short stretches of the original stream, tens of MB each
 * for a 4K remux). The menu entry itself always works.
 */
internal fun PlayerRuntimeController.maybeAutoSyncDub(audio: ExternalAudio) {
    if (audio.audioDelayMs != null) return
    val remembered = DubSyncMemory.get(context, syncMemoryKey(audio))
    if (remembered != null) {
        Log.i(DUB_SYNC_TAG, "remembered delay for this video and dub: ${remembered}ms")
        if (remembered != 0) applyMeasuredDubDelay(remembered, audio.lang)
        return
    }
    if (DubAutoSettings.isAutoSyncEnabled(context)) startDubSync(audio, silent = true)
}

/** Same video file (name and size, because releases differ) + same dub source = same shift. */
internal fun PlayerRuntimeController.syncMemoryKey(audio: ExternalAudio): String =
    listOf(
        currentVideoId ?: contentId.orEmpty(),
        currentFilename.orEmpty(),
        (currentVideoSize ?: 0L).toString(),
        audio.sourceId ?: audio.url.substringBefore('#').substringBefore('?')
    ).joinToString("|")

private fun windowStarts(durationMs: Long): List<Long> {
    val last = durationMs - SYNC_END_MARGIN_MS - SYNC_WINDOW_MS
    return SYNC_FRACTIONS.take(SYNC_MAX_WINDOWS).map {
        (durationMs * it).toLong().coerceIn(SYNC_MIN_START_MS, maxOf(SYNC_MIN_START_MS, last))
    }
}

private class SyncOutcome(
    val result: DubSyncAnalyzer.Result,
    val windowsUsed: Int,
    val elapsedMs: Long,
    /** Set when the first window already showed a known speed difference: the rest was skipped. */
    val earlyRatio: Double? = null,
    /** Length of the video file as the probes saw it (ms), -1 unknown. */
    val videoDurationMs: Long = -1L
)

/** Stretches the dub by [known] and measures what is left (a plain constant shift again). */
private fun PlayerRuntimeController.stretchAndResync(audio: ExternalAudio, known: Double, dubMs: Long?, videoMs: Long?) {
    Log.i(DUB_SYNC_TAG, "speed difference: dub=${dubMs}ms video=${videoMs}ms ratio=$known -> stretching the dub")
    val shorter = known < 1.0
    Toast.makeText(
        context,
        "Dublaj videodan %${DubStretch.percentText(known)} ${if (shorter) "kısa" else "uzun"}: " +
            "hız farkı bulundu. Dublaj ${if (shorter) "yavaşlatılıyor" else "hızlandırılıyor"}, " +
            "kalan kayma ölçülecek…",
        Toast.LENGTH_LONG
    ).show()
    val stretched = applyDubStretch(audio, known)
    if (stretched != null) {
        // This job is done; a new one measures the stretched dub.
        dubSyncJob = null
        startDubSync(stretched, silent = false, autoStretched = true)
    }
}

private fun PlayerRuntimeController.startDubSync(audio: ExternalAudio, silent: Boolean, autoStretched: Boolean = false) {
    val player = _exoPlayer ?: return
    if (dubSyncJob?.isActive == true) {
        if (!silent) Toast.makeText(context, "Senkron ölçümü zaten çalışıyor…", Toast.LENGTH_SHORT).show()
        return
    }
    val durationMs = player.duration
    if (durationMs == C.TIME_UNSET || durationMs < SYNC_MIN_DURATION_MS) {
        if (!silent) Toast.makeText(context, "Otomatik senkron için içerik çok kısa ya da süre bilinmiyor", Toast.LENGTH_LONG).show()
        return
    }
    // Everything the probes need is captured now: the user may change stream while this runs.
    val streamUrl = currentStreamUrl
    val headers = currentHeaders
    val filename = currentFilename
    val responseHeaders = currentStreamResponseHeaders
    val mimeType = currentStreamMimeType
    val memoryKey = syncMemoryKey(audio)
    // A dub that is being stretched is read in its own time; its captures are put back on the film's time grid.
    val stretch = audio.stretchRatio
    if (!silent) Toast.makeText(context, "Dublaj senkronu ölçülüyor… (yaklaşık 1 dk)", Toast.LENGTH_LONG).show()

    dubSyncJob = scope.launch {
        // A manual run on a dub that is not stretched yet: its length (a tiny header read, in parallel
        // with the first window) is compared with the video's length the first window reports. A known
        // speed difference (PAL 25 fps vs 23.976 fps) is then found after ONE window, instead of after
        // all windows have failed. An automatic run never stretches anything on a guess.
        val speedCheck = !silent && !DubStretch.isOn(audio.stretchRatio)
        val dubLengthJob: Deferred<Long?>? = if (speedCheck) {
            async {
                mediaSourceFactory.createExternalAudioSource(context, audio)
                    ?.let { DubAudioProbe.durationMs(context, it, timeoutMs = 15_000L) }
            }
        } else null
        var earlyRatio: Double? = null
        var videoDurationMs = -1L
        val starts = windowStarts(durationMs)
        val trace: (String) -> Unit = { Log.i(DUB_SYNC_TAG, it) }
        val startedAt = SystemClock.elapsedRealtime()

        val outcome = coroutineScope {
            // The dub is a small audio file: its next window is read while the heavy original one is running.
            val dubJobs = HashMap<Int, Deferred<ProbeCapture?>>()
            fun dubWindow(i: Int): Deferred<ProbeCapture?> = dubJobs.getOrPut(i) {
                async {
                    val source = mediaSourceFactory.createExternalAudioSource(context, audio)
                    if (source == null) null
                    else {
                        val filmLengthMs = SYNC_WINDOW_MS + 2 * SYNC_DUB_MARGIN_MS
                        DubAudioProbe.capture(
                            context,
                            source,
                            DubStretch.dubStartMs(starts[i] - SYNC_DUB_MARGIN_MS, stretch),
                            DubStretch.dubLengthMs(filmLengthMs, stretch),
                            timeoutMs = SYNC_DUB_TIMEOUT_MS
                        )?.let { DubStretch.toFilmTime(it, stretch, (filmLengthMs / DubSyncAnalyzer.FRAME_MS).toInt()) }
                    }
                }
            }

            val windows = ArrayList<Pair<ProbeCapture, ProbeCapture>>()
            // Film position (centre of the stretch) of each entry of [windows]: a drift needs it.
            val positions = ArrayList<Long>()
            var failed = 0
            var best: DubSyncAnalyzer.Result.Ok? = null
            var last: DubSyncAnalyzer.Result = DubSyncAnalyzer.Result.Unreliable("yeterli ses penceresi yok")

            for (i in starts.indices) {
                // New MediaSource per probe; no cache key so nothing lands in the media cache. No
                // subtitleConfigurations are passed on purpose: only subtitles inside the media count.
                val original = async {
                    DubAudioProbe.capture(
                        context = context,
                        source = mediaSourceFactory.createMediaSource(
                            context = context,
                            url = streamUrl,
                            headers = headers,
                            filename = filename,
                            responseHeaders = responseHeaders,
                            mimeTypeOverride = mimeType,
                            cacheKey = null,
                            externalAudios = emptyList(),
                            useDiskCache = false
                        ),
                        startMs = starts[i],
                        lengthMs = SYNC_WINDOW_MS,
                        withCues = true,
                        timeoutMs = SYNC_WINDOW_TIMEOUT_MS
                    )
                }
                // The dub is small: its window is read while the heavy original one runs. On the first
                // window of a speed check, its length (already being read) is waited for first, while
                // the original downloads, so a known speed difference costs no dub window at all.
                var dubLen: Long? = null
                if (speedCheck && i == 0 && dubLengthJob != null) {
                    dubLen = withTimeoutOrNull(SPEED_CHECK_WAIT_MS) { dubLengthJob.await() }
                }
                dubWindow(i)
                if (i + 1 < starts.size) dubWindow(i + 1)
                val o = original.await()
                if (o != null && o.sourceDurationMs > 0L) videoDurationMs = o.sourceDurationMs
                if (speedCheck && i == 0 && dubLen != null && o != null && o.sourceDurationMs > 0L) {
                    val known = DubSpeed.knownRatio(dubMs = dubLen, videoMs = o.sourceDurationMs)
                    if (known != null) {
                        earlyRatio = known
                        break
                    }
                }
                val d = dubWindow(i).await()
                dubJobs.remove(i)

                if (o != null && d != null) {
                    windows.add(o to d)
                    positions.add(starts[i] + SYNC_WINDOW_MS / 2)
                } else {
                    failed++
                }
                Log.i(
                    DUB_SYNC_TAG,
                    "window ${i + 1}/${starts.size} at ${starts[i]}ms: ${if (o != null && d != null) "ok" else "failed"} " +
                        "origAudio=${o?.bands != null} embeddedSubtitle=${o?.cueActivity != null} dub=${d != null} " +
                        "elapsed=${SystemClock.elapsedRealtime() - startedAt}ms"
                )

                // Nothing readable at all: no point in trying further positions.
                if (windows.isEmpty() && failed >= 2) break

                if (windows.size >= DubSyncAnalyzer.MIN_WINDOWS) {
                    last = withContext(Dispatchers.Default) { DubSyncAnalyzer.analyze(windows, trace, positions.toList()) }
                    val ok = last as? DubSyncAnalyzer.Result.Ok
                    if (ok != null) {
                        best = ok
                        val elapsed = SystemClock.elapsedRealtime() - startedAt
                        val perWindow = elapsed / (i + 1)
                        // A coarse subtitle-only result is worth one more window while there is time and
                        // the original's audio is readable (it can refine it to tens of ms).
                        val canRefine = !ok.precise && DubSyncAnalyzer.hasOriginalAudio(windows) &&
                            elapsed + perWindow <= SYNC_TARGET_MS
                        if (!canRefine) break
                    }
                }
            }
            // The prefetched dub window that is not needed any more.
            coroutineContext[Job]?.children?.forEach { it.cancel() }

            SyncOutcome(
                result = best ?: last,
                windowsUsed = windows.size,
                elapsedMs = SystemClock.elapsedRealtime() - startedAt,
                earlyRatio = earlyRatio,
                videoDurationMs = videoDurationMs
            )
        }
        Log.i(DUB_SYNC_TAG, "done: windows=${outcome.windowsUsed} elapsed=${outcome.elapsedMs}ms result=${outcome.result}")

        // The user may have switched stream or dub while we were measuring.
        if (currentStreamUrl != streamUrl || streamExternalAudios.none { it.url == audio.url }) return@launch

        val early = outcome.earlyRatio
        if (early != null) {
            stretchAndResync(audio, early, dubLengthJob?.await(), outcome.videoDurationMs)
            return@launch
        }

        when (val r = outcome.result) {
            is DubSyncAnalyzer.Result.Ok -> {
                val ignoreBelow = if (r.precise) SYNC_IGNORE_BELOW_MS else SYNC_IGNORE_BELOW_MS_COARSE
                if (abs(r.lagMs) < ignoreBelow) {
                    DubSyncMemory.put(context, memoryKey, 0)
                    Toast.makeText(context, "Dublaj zaten senkronda (${r.lagMs} ms)", Toast.LENGTH_LONG).show()
                } else {
                    // Dub plays later than the original -> the audio has to come earlier: negative delay.
                    applyMeasuredDubDelay(-r.lagMs, audio.lang)
                    DubSyncMemory.put(context, memoryKey, -r.lagMs)
                    val dir = if (r.lagMs > 0) "geç" else "erken"
                    val note = if (r.precise) "" else " (yaklaşık; gerekirse elle ince ayar yapın)"
                    Toast.makeText(
                        context,
                        "Dublaj ${abs(r.lagMs)} ms $dir kalıyordu, düzeltildi$note [${r.method.label}, ${outcome.elapsedMs / 1000} sn]",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            is DubSyncAnalyzer.Result.Drift -> {
                // Not remembered: the memory holds ONE delay per video and dub, and this one changes.
                val perMinuteMs = Math.round(r.slope * 60_000).toInt()
                val lagNow = r.lagAtMs(player.currentPosition)
                Log.i(DUB_SYNC_TAG, "drift: slope=${r.slope} offset=${r.offsetMs}ms lagNow=${lagNow}ms inliers=${r.inliers}/${r.windows}")
                // Only when asked for: a silent automatic run must not change anything on a guess.
                if (!silent) {
                    if (abs(lagNow) >= SYNC_IGNORE_BELOW_MS_COARSE) applyMeasuredDubDelay(-lagNow, audio.lang)
                    val dir = if (r.slope < 0) "öne kayıyor (dublaj hızlı)" else "geride kalıyor (dublaj yavaş)"
                    Toast.makeText(
                        context,
                        "Kayma sabit değil: dublaj dakikada ~${abs(perMinuteMs)} ms $dir${DubSpeed.slopeHint(r.slope)}. " +
                            "Şu anki konuma göre ayarlandı (${-lagNow} ms); ilerledikçe yeniden senkronlayın [${r.method.label}]",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
            is DubSyncAnalyzer.Result.Unreliable -> {
                val reason = if (outcome.windowsUsed == 0) "kaynak okunamadı" else r.reason
                Log.i(DUB_SYNC_TAG, "unreliable: $reason")
                if (autoStretched) {
                    // The stretch did not make the dub measurable: do not leave a guess playing.
                    applyDubStretch(audio, 1.0)
                    if (!silent) Toast.makeText(context, "Uzatma sonuç vermedi, geri alındı ($reason). Elle ayarlayın.", Toast.LENGTH_LONG).show()
                    return@launch
                }
                // Not remembered: a failed measurement says nothing about the shift.
                if (!silent) {
                    // A dub at another speed (25 fps vs 23.976 fps) drifts by minutes: far beyond what the
                    // windows can see. The two lengths tell. Only now, so a good result costs nothing extra.
                    val lengths = coroutineScope {
                        val videoLength = async {
                            outcome.videoDurationMs.takeIf { it > 0L } ?: DubAudioProbe.durationMs(
                                context,
                                mediaSourceFactory.createMediaSource(
                                    context = context,
                                    url = streamUrl,
                                    headers = headers,
                                    filename = filename,
                                    responseHeaders = responseHeaders,
                                    mimeTypeOverride = mimeType,
                                    cacheKey = null,
                                    externalAudios = emptyList(),
                                    useDiskCache = false
                                )
                            )
                        }
                        val dubLength = async {
                            dubLengthJob?.await()
                                ?: mediaSourceFactory.createExternalAudioSource(context, audio)
                                    ?.let { DubAudioProbe.durationMs(context, it) }
                        }
                        videoLength.await() to dubLength.await()
                    }
                    val video = lengths.first
                    val dub = lengths.second

                    // The lengths differ by exactly a known speed change (PAL 25 fps vs film 23.976 fps):
                    // play the dub slower (or faster) by that factor, pitch kept, and measure what is left,
                    // which is a plain constant shift again. Not when it is stretched already.
                    val known = if (video != null && dub != null && !DubStretch.isOn(audio.stretchRatio)) {
                        DubSpeed.knownRatio(dubMs = dub, videoMs = video)
                    } else {
                        null
                    }
                    if (known != null) {
                        stretchAndResync(audio, known, dub, video)
                        return@launch
                    }
                    val speedNote = if (video != null && dub != null) DubSpeed.explain(dubMs = dub, videoMs = video) else null
                    val suffix = speedNote?.let { " — $it" }.orEmpty()
                    Toast.makeText(context, "Otomatik senkron güvenilir değil, elle ayarlayın ($reason)$suffix", Toast.LENGTH_LONG).show()
                }
            }
        }
    }
}

/** Sets (not adds to) the stream delay, so measuring twice never stacks. */
private fun PlayerRuntimeController.applyMeasuredDubDelay(delayMs: Int, lang: String) {
    audioDelayParts = audioDelayParts.withStream(delayMs, lang)
    syncStreamDelayWithSelectedTracks(forceApply = true)
    Log.i(DUB_SYNC_TAG, "Measured dub delay applied: ${delayMs}ms (total ${audioDelayParts.totalMs}ms)")
}

/**
 * Remembers the delay that was measured (or confirmed as 0) per video file + dub, on this device.
 * Newest 300 entries are kept.
 */
internal object DubSyncMemory {
    private const val PREFS = "dub_sync_memory"
    private const val MAX_ENTRIES = 300

    fun get(context: Context, key: String): Int? = runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key, null)?.substringBefore(';')?.toIntOrNull()
    }.getOrNull()

    fun put(context: Context, key: String, delayMs: Int) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val edit = prefs.edit()
            edit.putString(key, "$delayMs;${System.currentTimeMillis()}")
            val all = prefs.all
            if (all.size >= MAX_ENTRIES) {
                all.entries
                    .sortedBy { (it.value as? String)?.substringAfter(';')?.toLongOrNull() ?: 0L }
                    .take(all.size - MAX_ENTRIES + 1)
                    .forEach { edit.remove(it.key) }
            }
            edit.apply()
        }
    }
}
