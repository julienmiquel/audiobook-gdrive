package com.julienmiquel.audiotheque.player.audio

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.julienmiquel.audiotheque.core.model.AudioPreset
import java.nio.ByteBuffer
import kotlin.math.roundToInt

/**
 * Processeur audio Media3 qui applique le préréglage courant au PCM 16 bits, avant le changement
 * de vitesse et la sortie. Toujours actif (sortie stéréo quel que soit le fichier) : le préréglage
 * peut ainsi changer pendant l'écoute sans reconfigurer la chaîne, avec un court fondu pour éviter
 * tout clic. Les filtres sont remis à zéro à chaque saut.
 */
@UnstableApi
class PresetAudioProcessor : BaseAudioProcessor() {

    /** Préréglage demandé (lu sur le fil audio à chaque tampon). */
    @Volatile
    var preset: AudioPreset = AudioPreset.NONE

    private var chain: PresetChain? = null
    private var left = FloatArray(0)
    private var right = FloatArray(0)
    private var fadeRemaining = 0
    private var fadeLength = 0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        // Plus de deux canaux : pas un livre audio, on laisse passer tel quel.
        if (inputAudioFormat.channelCount != 1 && inputAudioFormat.channelCount != 2) return AudioProcessor.AudioFormat.NOT_SET
        chain = null
        return AudioProcessor.AudioFormat(inputAudioFormat.sampleRate, 2, C.ENCODING_PCM_16BIT)
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val channels = inputAudioFormat.channelCount
        val frames = inputBuffer.remaining() / inputAudioFormat.bytesPerFrame
        val output = replaceOutputBuffer(frames * outputAudioFormat.bytesPerFrame)
        if (frames == 0) {
            output.flip()
            return
        }
        if (left.size < frames) {
            left = FloatArray(frames)
            right = FloatArray(frames)
        }
        for (i in 0 until frames) {
            val l = inputBuffer.short / SCALE
            left[i] = l
            right[i] = if (channels == 2) inputBuffer.short / SCALE else l
        }

        val wanted = preset
        val current = chain
        if (current == null || current.preset != wanted) {
            chain = if (wanted == AudioPreset.NONE) null else PresetChain(inputAudioFormat.sampleRate, wanted)
            if (current != null || wanted != AudioPreset.NONE) {
                fadeLength = inputAudioFormat.sampleRate * FADE_MS / 1000
                fadeRemaining = fadeLength
            }
        }
        chain?.process(left, right, frames)

        for (i in 0 until frames) {
            var l = left[i]
            var r = right[i]
            if (fadeRemaining > 0) {
                val gain = 1f - fadeRemaining.toFloat() / fadeLength
                l *= gain
                r *= gain
                fadeRemaining--
            }
            output.putShort(toPcm(l))
            output.putShort(toPcm(r))
        }
        output.flip()
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        chain?.reset()
        fadeRemaining = 0
    }

    override fun onReset() {
        chain = null
        left = FloatArray(0)
        right = FloatArray(0)
    }

    private companion object {
        const val SCALE = 32768f
        const val FADE_MS = 15

        fun toPcm(x: Float): Short = (x * SCALE).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
    }
}

/** ExoPlayer avec le processeur de préréglages en tête de la chaîne audio (avant la vitesse). */
@UnstableApi
class PresetRenderersFactory(context: Context, private val processor: PresetAudioProcessor) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioOutputPlaybackParams: Boolean): AudioSink =
        DefaultAudioSink.Builder(context)
            .setEnableFloatOutput(enableFloatOutput)
            .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
            .setAudioProcessorChain(DefaultAudioSink.DefaultAudioProcessorChain(processor))
            .build()
}
