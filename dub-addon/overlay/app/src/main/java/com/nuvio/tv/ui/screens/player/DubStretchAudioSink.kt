@file:androidx.annotation.OptIn(UnstableApi::class)

package com.nuvio.tv.ui.screens.player

import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.audio.AudioOffloadSupport
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink
import com.nuvio.tv.core.dub.DubStretch
import com.nuvio.tv.core.dub.DubStretchTag
import java.nio.ByteBuffer
import kotlin.math.abs

private const val STRETCH_TAG = "DubStretch"

/** A time stamp this far from what the stretched audio predicts is a jump: start the stretch over. */
private const val JUMP_US = 200_000L

/**
 * Stretches the audio of a dub that was made at another speed by 1 / ratio, pitch kept (Media3's
 * Sonic), BEFORE it reaches the real sink. The real sink then only ever sees film-length audio, so
 * its clock, its time stamp checks and the video that follows that clock all stay correct.
 *
 * Only audio whose format carries a [DubStretchTag] (put there by [DubStretchMediaSource]) is
 * touched; every other audio goes straight through. The dub's time stamps are already film time
 * (the media source converted them): the first time stamp after a flush anchors the output, and
 * the stretched audio continues from there frame by frame.
 *
 * A dub in a compressed format (AC-3 ...) cannot be passed through while stretching: such a format
 * is reported as unsupported so the player decodes it to PCM. Only 16-bit PCM can be stretched.
 */
internal class DubStretchAudioSink(private val delegate: AudioSink) : ForwardingAudioSink(delegate) {

    /** Non-null while the current format is a dub that is being stretched. */
    private var sonic: SonicAudioProcessor? = null
    private var ratio = 1.0
    private var sampleRate = 0
    private var frameBytes = 0

    /** Film time stamp of the first output frame since the last flush (TIME_UNSET: not set yet). */
    private var anchorPtsUs = C.TIME_UNSET
    private var inFrames = 0L
    private var outFrames = 0L

    /** Stretched output that the real sink has not taken yet (it refuses buffers now and then). */
    private var pending: ByteBuffer? = null
    private var pendingPtsUs = 0L
    private var pendingFrames = 0L

    /** The buffer of the current handleBuffer call is already inside Sonic (the call is repeated). */
    private var inputQueued = false
    private var endQueued = false

    private fun resetState() {
        anchorPtsUs = C.TIME_UNSET
        inFrames = 0L
        outFrames = 0L
        pending = null
        pendingFrames = 0L
        inputQueued = false
        endQueued = false
    }

    private fun isTagged(format: Format): Boolean {
        val tag = format.customData as? DubStretchTag ?: return false
        return DubStretch.isOn(tag.ratio)
    }

    /** A tagged dub that is not raw PCM: it has to be decoded here, so passthrough/offload are refused. */
    private fun isTaggedBitstream(format: Format): Boolean =
        isTagged(format) && format.sampleMimeType != MimeTypes.AUDIO_RAW

    override fun supportsFormat(format: Format): Boolean =
        if (isTaggedBitstream(format)) false else super.supportsFormat(format)

    override fun getFormatSupport(format: Format): Int =
        if (isTaggedBitstream(format)) AudioSink.SINK_FORMAT_UNSUPPORTED else super.getFormatSupport(format)

    override fun getFormatOffloadSupport(format: Format): AudioOffloadSupport =
        if (isTagged(format)) AudioOffloadSupport.DEFAULT_UNSUPPORTED else super.getFormatOffloadSupport(format)

    override fun configure(inputFormat: Format, specifiedBufferSize: Int, outputChannels: IntArray?) {
        sonic = null
        resetState()
        val tag = inputFormat.customData as? DubStretchTag
        if (tag == null && inputFormat.customData != null) {
            Log.d(STRETCH_TAG, "sink configure: foreign customData ${inputFormat.customData?.javaClass?.simpleName}")
        }
        if (tag != null && DubStretch.isOn(tag.ratio)) {
            if (inputFormat.sampleMimeType == MimeTypes.AUDIO_RAW && inputFormat.pcmEncoding == C.ENCODING_PCM_16BIT) {
                try {
                    val processor = SonicAudioProcessor()
                    // speed = ratio: a dub 4.1% shorter (0.959) is played 4.1% slower.
                    processor.setSpeed(tag.ratio.toFloat())
                    processor.configure(
                        AudioProcessor.AudioFormat(inputFormat.sampleRate, inputFormat.channelCount, C.ENCODING_PCM_16BIT)
                    )
                    if (processor.isActive()) {
                        processor.flush()
                        sonic = processor
                        ratio = tag.ratio
                        sampleRate = inputFormat.sampleRate
                        frameBytes = inputFormat.channelCount * 2
                        Log.i(STRETCH_TAG, "stretching dub: ratio=${tag.ratio} rate=$sampleRate ch=${inputFormat.channelCount}")
                    }
                } catch (e: AudioProcessor.UnhandledAudioFormatException) {
                    Log.w(STRETCH_TAG, "cannot stretch this format: $e")
                }
            } else {
                Log.w(STRETCH_TAG, "dub is not 16-bit PCM (${inputFormat.sampleMimeType}, ${inputFormat.pcmEncoding}): not stretched")
            }
        }
        super.configure(inputFormat, specifiedBufferSize, outputChannels)
    }

    override fun handleBuffer(buffer: ByteBuffer, presentationTimeUs: Long, encodedAccessUnitCount: Int): Boolean {
        val processor = sonic ?: return super.handleBuffer(buffer, presentationTimeUs, encodedAccessUnitCount)

        if (!inputQueued) {
            if (!buffer.hasRemaining()) return true
            if (anchorPtsUs == C.TIME_UNSET) {
                anchorPtsUs = presentationTimeUs
            } else {
                // Where the next input should start if time stamps simply continue.
                val expectedUs = anchorPtsUs + (inFrames * 1_000_000L / sampleRate / ratio).toLong()
                if (abs(presentationTimeUs - expectedUs) > JUMP_US) {
                    Log.i(STRETCH_TAG, "time stamp jump of ${presentationTimeUs - expectedUs}us: restarting the stretch")
                    processor.flush()
                    resetState()
                    anchorPtsUs = presentationTimeUs
                }
            }
            val frames = buffer.remaining() / frameBytes
            processor.queueInput(buffer) // takes everything
            inFrames += frames
            inputQueued = true
        }
        if (!drain(processor)) return false
        inputQueued = false
        return true
    }

    /** Hands everything Sonic has ready to the real sink. false = the sink wants us to try again later. */
    private fun drain(processor: SonicAudioProcessor): Boolean {
        while (true) {
            val held = pending
            if (held != null) {
                if (!delegate.handleBuffer(held, pendingPtsUs, 1)) return false
                outFrames += pendingFrames
                pending = null
            }
            val out = processor.getOutput()
            if (!out.hasRemaining()) return true
            pendingFrames = (out.remaining() / frameBytes).toLong()
            pendingPtsUs = anchorPtsUs + outFrames * 1_000_000L / sampleRate
            pending = out
        }
    }

    override fun playToEndOfStream() {
        val processor = sonic
        if (processor == null) {
            super.playToEndOfStream()
            return
        }
        if (!endQueued) {
            processor.queueEndOfStream()
            endQueued = true
        }
        if (!drain(processor)) return
        super.playToEndOfStream()
    }

    override fun isEnded(): Boolean {
        val processor = sonic ?: return super.isEnded()
        return endQueued && pending == null && processor.isEnded() && super.isEnded()
    }

    override fun hasPendingData(): Boolean = pending?.hasRemaining() == true || super.hasPendingData()

    override fun handleDiscontinuity() {
        sonic?.let {
            it.flush()
            resetState()
        }
        super.handleDiscontinuity()
    }

    override fun flush() {
        resetState()
        sonic?.flush()
        super.flush()
    }

    override fun reset() {
        resetState()
        sonic?.reset()
        sonic = null
        super.reset()
    }
}
