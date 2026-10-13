package com.streamhit.app.ui.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer

/**
 * Custom AudioProcessor to dynamically adjust Audio/Video synchronization offset in real time.
 *
 * Positive offsetMs (> 0): Audio is delayed (use when audio leads video / sound is too early).
 * Negative offsetMs (< 0): Audio is advanced (use when video leads audio / sound is lagging).
 * Zero offsetMs (== 0): Baseline playback (direct pass-through).
 */
@UnstableApi
class AudioSyncAudioProcessor : BaseAudioProcessor() {

    @Volatile
    var currentOffsetMs: Long = 0L
        private set

    @Volatile
    private var targetOffsetMs: Long = 0L

    private var pendingSilenceBytes: Int = 0
    private var pendingDiscardBytes: Int = 0

    fun setAudioOffsetMs(offsetMs: Long) {
        val clamped = offsetMs.coerceIn(-5000L, 5000L)
        synchronized(this) {
            val deltaMs = clamped - targetOffsetMs
            targetOffsetMs = clamped
            currentOffsetMs = clamped

            if (deltaMs > 0) {
                // Need to delay audio further: inject silence
                val bytesToAdd = msToBytes(deltaMs)
                pendingSilenceBytes += bytesToAdd
            } else if (deltaMs < 0) {
                // Need to advance audio: discard samples
                val bytesToDrop = msToBytes(-deltaMs)
                pendingDiscardBytes += bytesToDrop
            }
        }
    }

    fun resetOffset() {
        setAudioOffsetMs(0L)
    }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // Support all standard PCM formats
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_FLOAT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_24BIT &&
            inputAudioFormat.encoding != C.ENCODING_PCM_32BIT
        ) {
            throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        }
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytesPerFrame = inputAudioFormat.bytesPerFrame
        if (bytesPerFrame <= 0) {
            val remaining = inputBuffer.remaining()
            val out = replaceOutputBuffer(remaining)
            out.put(inputBuffer)
            out.flip()
            return
        }

        var silenceToWrite = 0
        var discardToPerform = 0

        synchronized(this) {
            if (pendingDiscardBytes > 0) {
                val toDiscard = minOf(pendingDiscardBytes, inputBuffer.remaining())
                val alignedDiscard = toDiscard - (toDiscard % bytesPerFrame)
                if (alignedDiscard > 0) {
                    discardToPerform = alignedDiscard
                    pendingDiscardBytes -= alignedDiscard
                }
            }

            if (pendingSilenceBytes > 0) {
                // Write silence in chunks up to 8192 bytes
                val chunk = minOf(pendingSilenceBytes, 8192)
                val alignedSilence = chunk - (chunk % bytesPerFrame)
                if (alignedSilence > 0) {
                    silenceToWrite = alignedSilence
                    pendingSilenceBytes -= alignedSilence
                }
            }
        }

        if (discardToPerform > 0) {
            inputBuffer.position(inputBuffer.position() + discardToPerform)
        }

        val remainingInput = inputBuffer.remaining()
        val totalOutputBytes = silenceToWrite + remainingInput

        if (totalOutputBytes > 0) {
            val out = replaceOutputBuffer(totalOutputBytes)
            if (silenceToWrite > 0) {
                // Emit zero-filled PCM silence chunk
                val zeroBytes = ByteArray(silenceToWrite)
                out.put(zeroBytes)
            }
            if (remainingInput > 0) {
                out.put(inputBuffer)
            }
            out.flip()
        }
    }

    override fun onFlush() {
        synchronized(this) {
            pendingSilenceBytes = 0
            pendingDiscardBytes = 0
            // When seeking or flushing, re-apply the user's desired offset to newly decoded audio
            if (targetOffsetMs > 0) {
                pendingSilenceBytes = msToBytes(targetOffsetMs)
            } else if (targetOffsetMs < 0) {
                pendingDiscardBytes = msToBytes(-targetOffsetMs)
            }
        }
    }

    override fun onReset() {
        synchronized(this) {
            pendingSilenceBytes = 0
            pendingDiscardBytes = 0
            targetOffsetMs = 0L
            currentOffsetMs = 0L
        }
    }

    private fun msToBytes(ms: Long): Int {
        if (inputAudioFormat.sampleRate <= 0 || inputAudioFormat.bytesPerFrame <= 0) return 0
        val bytes = (ms * inputAudioFormat.sampleRate.toLong() * inputAudioFormat.bytesPerFrame.toLong()) / 1000L
        val remainder = bytes % inputAudioFormat.bytesPerFrame
        return (bytes - remainder).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
    }
}

@UnstableApi
class CustomRenderersFactory(
    context: android.content.Context,
    private val audioProcessor: AudioProcessor
) : androidx.media3.exoplayer.DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: android.content.Context,
        enableFloatOutput: Boolean,
        enableAudioTrackPlaybackParams: Boolean
    ): androidx.media3.exoplayer.audio.AudioSink {
        return androidx.media3.exoplayer.audio.DefaultAudioSink.Builder(context)
            .setAudioProcessors(arrayOf(audioProcessor))
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioTrackPlaybackParams(enableAudioTrackPlaybackParams)
            .build()
    }
}

