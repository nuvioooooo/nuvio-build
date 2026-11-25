package com.nuvio.tv.domain.model

import androidx.compose.runtime.Immutable

/**
 * A separate audio-only track (for example a Turkish dub) that is played together with the
 * main video stream. The player merges it with the video as an additional audio track.
 */
@Immutable
data class ExternalAudio(
    val url: String,
    val lang: String = "tr",
    val label: String? = null,
    val headers: Map<String, String>? = null,
    /** Fixed offset for this audio in ms (>0 = audio later). Applied through the stream-delay path. */
    val audioDelayMs: Int? = null,
    /** Which recipe/provider found this audio. Identifies a dub across searches (its URL changes). */
    val sourceId: String? = null,
    /**
     * Length of this dub / length of the film, when the dub was made at another speed (a PAL dub of
     * a 23.976 fps film is 4.1% shorter: 0.959). 1.0 = same speed, nothing is stretched. The player
     * then plays the dub slower or faster, pitch kept, so that it stays in step all through.
     */
    val stretchRatio: Double = 1.0
)
