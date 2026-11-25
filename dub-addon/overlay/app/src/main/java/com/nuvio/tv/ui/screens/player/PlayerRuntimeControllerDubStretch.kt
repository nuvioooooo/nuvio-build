@file:OptIn(androidx.media3.common.util.UnstableApi::class)

package com.nuvio.tv.ui.screens.player

import android.content.Context
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import com.nuvio.tv.domain.model.ExternalAudio

private const val STRETCH_LOG_TAG = "DubStretch"

/**
 * Plays [audio] slower or faster by [ratio] (length of the dub / length of the film): the stored
 * entry gets the ratio, the ratio is remembered for this video + dub, and the media source is
 * rebuilt at the current position. Returns the updated entry, or null when there is no player.
 */
internal fun PlayerRuntimeController.applyDubStretch(audio: ExternalAudio, ratio: Double): ExternalAudio? {
    val player = _exoPlayer ?: return null
    val updated = audio.copy(stretchRatio = ratio)
    streamExternalAudios = streamExternalAudios.map { if (it.url == audio.url) updated else it }
    DubStretchMemory.put(context, syncMemoryKey(audio), ratio)
    Log.i(STRETCH_LOG_TAG, "stretch ratio $ratio applied to [${audio.label}]")
    reselectDubAfterRebuild(updated)
    rebuildMediaSource(player.currentPosition, player.playWhenReady)
    return updated
}

/** A dub that was stretched before for this exact video + dub comes back stretched from the start. */
internal fun PlayerRuntimeController.withRememberedStretch(audio: ExternalAudio): ExternalAudio {
    val remembered = DubStretchMemory.get(context, syncMemoryKey(audio)) ?: return audio
    return audio.copy(stretchRatio = remembered)
}

/**
 * The track override normally survives the rebuild (same track group); this makes sure of it: once
 * the new track list is there and the dub is not selected, it is selected again.
 */
private fun PlayerRuntimeController.reselectDubAfterRebuild(audio: ExternalAudio) {
    val player = _exoPlayer ?: return
    val listener = object : Player.Listener {
        override fun onTracksChanged(tracks: Tracks) {
            val child = streamExternalAudios.indexOfFirst { it.url == audio.url } + 1
            if (child <= 0) {
                player.removeListener(this)
                return
            }
            val candidates = audioCandidates(tracks, child)
            if (candidates.isEmpty()) return // the new source is not ready yet
            player.removeListener(this)
            if (candidates.any { (group, index, _) -> group.isTrackSelected(index) }) return
            val pick = pickDubTrack(candidates, wantedLang(audio))
            player.trackSelectionParameters = player.trackSelectionParameters
                .buildUpon()
                .setOverrideForType(TrackSelectionOverride(pick.first.mediaTrackGroup, pick.second))
                .setTrackTypeDisabled(C.TRACK_TYPE_AUDIO, false)
                .build()
        }
    }
    player.addListener(listener)
}

/** Per video file + dub: by how much the dub had to be stretched (length of the dub / length of the film). */
internal object DubStretchMemory {
    private const val PREFS = "dub_stretch_memory"
    private const val MAX_ENTRIES = 300

    fun get(context: Context, key: String): Double? = runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(key, null)?.substringBefore(';')?.toDoubleOrNull()
    }.getOrNull()

    fun put(context: Context, key: String, ratio: Double) {
        runCatching {
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val edit = prefs.edit()
            edit.putString(key, "$ratio;${System.currentTimeMillis()}")
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
