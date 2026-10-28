package com.julienmiquel.audiotheque.player.audio

import com.julienmiquel.audiotheque.core.model.AudioPreset
import kotlin.math.abs
import kotlin.math.max

/**
 * Chaîne de traitement d'un préréglage, pour une fréquence d'échantillonnage donnée. Entrée et
 * sortie stéréo en virgule flottante (−1..1), traitement en place.
 *
 * - **Volume constant** : compression 2,5:1 à −18 dBFS, normalisation vers −18 dBFS RMS, limiteur.
 * - **Voix renforcée** (chaîne vocale « Netophonix ») : coupe-bas 80 Hz, présence +3 dB à 3,5 kHz
 *   (Q 0,7), compression 3:1 à −20 dBFS (5 ms / 50 ms), normalisation vers −20 dBFS, limiteur −1 dB.
 * - **Niveau très bas** : compression 4:1 à −30 dBFS, normalisation vers −20 dBFS, limiteur, puis
 *   −12 dB en sortie.
 * - **Binaural** : la chaîne vocale, puis la source (mono) placée devant l'auditeur dans une
 *   petite pièce (modèle de tête, premières réflexions, réverbération), limiteur.
 */
class PresetChain(val sampleRate: Int, val preset: AudioPreset) {

    private val highPass: Array<Biquad>? = when (preset) {
        AudioPreset.VOICE_BOOST, AudioPreset.BINAURAL -> Array(2) { Biquad.highPass(sampleRate, 80.0) }
        else -> null
    }
    private val presence: Array<Biquad>? = when (preset) {
        AudioPreset.VOICE_BOOST, AudioPreset.BINAURAL -> Array(2) { Biquad.peaking(sampleRate, 3_500.0, 0.7, 3.0) }
        else -> null
    }
    private val compressor: Compressor? = when (preset) {
        AudioPreset.NORMALIZE -> Compressor(sampleRate, thresholdDb = -18.0, ratio = 2.5, attackMs = 10.0, releaseMs = 200.0)
        AudioPreset.VOICE_BOOST, AudioPreset.BINAURAL -> Compressor(sampleRate, thresholdDb = -20.0, ratio = 3.0, attackMs = 5.0, releaseMs = 50.0)
        AudioPreset.NIGHT -> Compressor(sampleRate, thresholdDb = -30.0, ratio = 4.0, attackMs = 5.0, releaseMs = 150.0)
        AudioPreset.NONE -> null
    }
    private val agc: Agc? = when (preset) {
        AudioPreset.NORMALIZE -> Agc(sampleRate, targetDb = -18.0)
        AudioPreset.VOICE_BOOST, AudioPreset.BINAURAL, AudioPreset.NIGHT -> Agc(sampleRate, targetDb = -20.0)
        AudioPreset.NONE -> null
    }
    private val binaural: Binaural? = if (preset == AudioPreset.BINAURAL) Binaural(sampleRate, azimuthDeg = 0.0, room = Binaural.Room.SMALL) else null
    private val limiter: Limiter? = if (preset == AudioPreset.NONE) null else Limiter(sampleRate, ceilingDb = -1.0)
    private val outputGain: Float = if (preset == AudioPreset.NIGHT) dbToLinear(-12.0).toFloat() else 1f
    private val pair = FloatArray(2)

    /** Traite [count] trames de [left] / [right] en place. */
    fun process(left: FloatArray, right: FloatArray, count: Int) {
        if (preset == AudioPreset.NONE) return
        for (i in 0 until count) {
            var l = left[i]
            var r = right[i]
            highPass?.let { l = it[0].process(l); r = it[1].process(r) }
            presence?.let { l = it[0].process(l); r = it[1].process(r) }
            compressor?.let { val g = it.gain(max(abs(l), abs(r))); l *= g; r *= g }
            agc?.let { val g = it.gain(l, r); l *= g; r *= g }
            binaural?.let {
                it.process((l + r) * 0.5f, pair)
                l = pair[0]
                r = pair[1]
            }
            limiter?.let {
                val g = it.gain(max(abs(l), abs(r)))
                l = it.clip(l * g)
                r = it.clip(r * g)
            }
            left[i] = l * outputGain
            right[i] = r * outputGain
        }
    }

    fun reset() {
        highPass?.forEach { it.reset() }
        presence?.forEach { it.reset() }
        compressor?.reset()
        agc?.reset()
        binaural?.reset()
        limiter?.reset()
    }
}
