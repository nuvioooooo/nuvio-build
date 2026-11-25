@file:androidx.annotation.OptIn(UnstableApi::class)

package com.nuvio.tv.ui.screens.player

import androidx.media3.common.C
import androidx.media3.common.MimeTypes
import androidx.media3.common.StreamKey
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.exoplayer.FormatHolder
import androidx.media3.exoplayer.LoadingInfo
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.exoplayer.source.ForwardingTimeline
import androidx.media3.exoplayer.source.MediaPeriod
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.SampleStream
import androidx.media3.exoplayer.source.TrackGroupArray
import androidx.media3.exoplayer.source.WrappingMediaSource
import androidx.media3.exoplayer.trackselection.ExoTrackSelection
import androidx.media3.exoplayer.upstream.Allocator
import com.nuvio.tv.core.dub.DubStretch
import com.nuvio.tv.core.dub.DubStretchTag

/**
 * Wraps the media source of ONE dub that was made at another speed (25 fps PAL vs 23.976 fps film)
 * and presents it on the FILM's time line: dub time d = film time f * ratio. Time stamps, seek
 * positions, buffered positions and the duration are all converted, so seeking lands on the right
 * dub position and the merged duration is the film's. The audio itself is stretched afterwards by
 * [DubStretchAudioSink]; this class also tags the dub's audio format ([DubStretchTag]) so the
 * sink knows which audio that is.
 */
internal class DubStretchMediaSource(
    mediaSource: MediaSource,
    private val ratio: Double
) : WrappingMediaSource(mediaSource) {

    override fun createPeriod(
        id: MediaSource.MediaPeriodId,
        allocator: Allocator,
        startPositionUs: Long
    ): MediaPeriod {
        return DubStretchMediaPeriod(
            mediaPeriod = mediaSource.createPeriod(id, allocator, DubStretch.toDubUs(startPositionUs, ratio)),
            ratio = ratio
        )
    }

    override fun releasePeriod(mediaPeriod: MediaPeriod) {
        mediaSource.releasePeriod((mediaPeriod as DubStretchMediaPeriod).mediaPeriod)
    }

    override fun onChildSourceInfoRefreshed(timeline: Timeline) {
        refreshSourceInfo(DubStretchTimeline(timeline, ratio))
    }

    companion object {
        /** The source itself when [ratio] means "same speed", so a normal dub is not touched at all. */
        fun wrapIfNeeded(source: MediaSource, ratio: Double): MediaSource =
            if (DubStretch.isOn(ratio)) DubStretchMediaSource(source, ratio) else source
    }
}

private class DubStretchTimeline(timeline: Timeline, private val ratio: Double) : ForwardingTimeline(timeline) {

    private fun film(us: Long): Long = DubStretch.toFilmUs(us, ratio)

    override fun getWindow(windowIndex: Int, window: Window, defaultPositionProjectionUs: Long): Window {
        super.getWindow(windowIndex, window, defaultPositionProjectionUs)
        window.durationUs = film(window.durationUs)
        window.defaultPositionUs = film(window.defaultPositionUs)
        window.positionInFirstPeriodUs = film(window.positionInFirstPeriodUs)
        return window
    }

    override fun getPeriod(periodIndex: Int, period: Period, setIds: Boolean): Period {
        super.getPeriod(periodIndex, period, setIds)
        period.durationUs = film(period.durationUs)
        period.positionInWindowUs = film(period.positionInWindowUs)
        return period
    }
}

private class DubStretchMediaPeriod(
    val mediaPeriod: MediaPeriod,
    private val ratio: Double
) : MediaPeriod, MediaPeriod.Callback {

    private var callback: MediaPeriod.Callback? = null

    private fun film(us: Long): Long = DubStretch.toFilmUs(us, ratio)
    private fun dub(us: Long): Long = DubStretch.toDubUs(us, ratio)

    /**
     * MergingMediaPeriod insists that every child returns EXACTLY the position it was asked for
     * (seekToUs / selectTracks), else it throws IllegalStateException. dub() and film() are each
     * rounded, so film(dub(x)) can be off by 1 us. When the child simply did what it was asked,
     * the asked position is returned untouched; only a real adjustment is converted.
     */
    private fun backToFilm(askedFilmUs: Long, childResultUs: Long): Long =
        if (childResultUs == dub(askedFilmUs)) askedFilmUs else film(childResultUs)

    override fun prepare(callback: MediaPeriod.Callback, positionUs: Long) {
        this.callback = callback
        mediaPeriod.prepare(this, dub(positionUs))
    }

    override fun maybeThrowPrepareError() = mediaPeriod.maybeThrowPrepareError()

    override fun getTrackGroups(): TrackGroupArray = mediaPeriod.getTrackGroups()

    override fun getStreamKeys(trackSelections: List<ExoTrackSelection>): List<StreamKey> =
        mediaPeriod.getStreamKeys(trackSelections)

    override fun selectTracks(
        selections: Array<ExoTrackSelection?>,
        mayRetainStreamFlags: BooleanArray,
        streams: Array<SampleStream?>,
        streamResetFlags: BooleanArray,
        positionUs: Long
    ): Long {
        val childStreams = arrayOfNulls<SampleStream>(streams.size)
        for (index in streams.indices) {
            childStreams[index] = (streams[index] as? DubStretchSampleStream)?.child ?: streams[index]
        }
        val selectedPositionUs = mediaPeriod.selectTracks(
            selections,
            mayRetainStreamFlags,
            childStreams,
            streamResetFlags,
            dub(positionUs)
        )
        for (index in streams.indices) {
            val childStream = childStreams[index]
            val current = streams[index] as? DubStretchSampleStream
            streams[index] = when {
                childStream == null -> null
                current?.child === childStream -> current
                else -> DubStretchSampleStream(childStream, ratio)
            }
        }
        return backToFilm(positionUs, selectedPositionUs)
    }

    override fun discardBuffer(positionUs: Long, toKeyframe: Boolean) =
        mediaPeriod.discardBuffer(dub(positionUs), toKeyframe)

    override fun reevaluateBuffer(positionUs: Long) = mediaPeriod.reevaluateBuffer(dub(positionUs))

    override fun readDiscontinuity(): Long = film(mediaPeriod.readDiscontinuity())

    override fun getBufferedPositionUs(): Long = film(mediaPeriod.getBufferedPositionUs())

    override fun seekToUs(positionUs: Long): Long = backToFilm(positionUs, mediaPeriod.seekToUs(dub(positionUs)))

    override fun getAdjustedSeekPositionUs(positionUs: Long, seekParameters: SeekParameters): Long =
        film(mediaPeriod.getAdjustedSeekPositionUs(dub(positionUs), seekParameters))

    override fun getNextLoadPositionUs(): Long = film(mediaPeriod.getNextLoadPositionUs())

    override fun continueLoading(loadingInfo: LoadingInfo): Boolean =
        mediaPeriod.continueLoading(
            loadingInfo.buildUpon().setPlaybackPositionUs(dub(loadingInfo.playbackPositionUs)).build()
        )

    override fun isLoading(): Boolean = mediaPeriod.isLoading()

    override fun onPrepared(mediaPeriod: MediaPeriod) {
        callback?.onPrepared(this)
    }

    override fun onContinueLoadingRequested(source: MediaPeriod) {
        callback?.onContinueLoadingRequested(this)
    }
}

private class DubStretchSampleStream(
    val child: SampleStream,
    private val ratio: Double
) : SampleStream {

    override fun isReady(): Boolean = child.isReady()

    override fun maybeThrowError() = child.maybeThrowError()

    override fun readData(formatHolder: FormatHolder, buffer: DecoderInputBuffer, readFlags: Int): Int {
        val result = child.readData(formatHolder, buffer, readFlags)
        if (result == C.RESULT_BUFFER_READ) {
            if (!buffer.isEndOfStream() && buffer.timeUs != C.TIME_UNSET) {
                buffer.timeUs = DubStretch.toFilmUs(buffer.timeUs, ratio)
            }
        } else if (result == C.RESULT_FORMAT_READ) {
            val format = formatHolder.format
            if (format != null && MimeTypes.isAudio(format.sampleMimeType) && format.customData !is DubStretchTag) {
                formatHolder.format = format.buildUpon().setCustomData(DubStretchTag(ratio)).build()
                android.util.Log.i("DubStretch", "dub format tagged: mime=${format.sampleMimeType} rate=${format.sampleRate} ch=${format.channelCount} ratio=$ratio")
            }
        }
        return result
    }

    override fun skipData(positionUs: Long): Int = child.skipData(DubStretch.toDubUs(positionUs, ratio))
}
