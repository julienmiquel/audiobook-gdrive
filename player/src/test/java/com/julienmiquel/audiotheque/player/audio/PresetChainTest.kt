package com.julienmiquel.audiotheque.player.audio

import com.google.common.truth.Truth.assertThat
import com.julienmiquel.audiotheque.core.model.AudioPreset
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Test

/** Préréglages audio : comportement mesurable des blocs de traitement (sans Android). */
class PresetChainTest {

    private val fs = 44_100

    private fun sine(frequency: Double, seconds: Double, amplitude: Double): FloatArray =
        FloatArray((fs * seconds).toInt()) { (amplitude * sin(2 * PI * frequency * it / fs)).toFloat() }

    private fun rmsDb(x: FloatArray, from: Int = 0, to: Int = x.size): Double {
        var sum = 0.0
        for (i in from until to) sum += x[i].toDouble() * x[i]
        return 10 * log10(sum / (to - from) + 1e-12)
    }

    private fun peak(x: FloatArray, from: Int = 0, to: Int = x.size): Float {
        var p = 0f
        for (i in from until to) p = maxOf(p, abs(x[i]))
        return p
    }

    private fun run(preset: AudioPreset, left: FloatArray, right: FloatArray = left.copyOf()): Pair<FloatArray, FloatArray> {
        val l = left.copyOf()
        val r = right.copyOf()
        PresetChain(fs, preset).process(l, r, l.size)
        return l to r
    }

    @Test
    fun `son d'origine - aucun traitement, échantillons identiques`() {
        val input = sine(440.0, 0.1, 0.5)
        val (l, r) = run(AudioPreset.NONE, input)
        assertThat(l).isEqualTo(input)
        assertThat(r).isEqualTo(input)
    }

    @Test
    fun `coupe-bas 80 Hz - le rumble à 40 Hz est atténué, la voix à 1 kHz préservée`() {
        val low = Biquad.highPass(fs, 80.0)
        val mid = Biquad.highPass(fs, 80.0)
        val rumble = sine(40.0, 0.5, 0.5).map { low.process(it) }.toFloatArray()
        val voice = sine(1_000.0, 0.5, 0.5).map { mid.process(it) }.toFloatArray()
        val tail = fs / 4
        assertThat(rmsDb(rumble, tail)).isLessThan(rmsDb(sine(40.0, 0.5, 0.5), tail) - 10)
        assertThat(abs(rmsDb(voice, tail) - rmsDb(sine(1_000.0, 0.5, 0.5), tail))).isLessThan(0.5)
    }

    @Test
    fun `présence 3,5 kHz - +3 dB au centre, rien à 300 Hz`() {
        val at = Biquad.peaking(fs, 3_500.0, 0.7, 3.0)
        val away = Biquad.peaking(fs, 3_500.0, 0.7, 3.0)
        val center = sine(3_500.0, 0.5, 0.3).map { at.process(it) }.toFloatArray()
        val bass = sine(300.0, 0.5, 0.3).map { away.process(it) }.toFloatArray()
        val tail = fs / 4
        assertThat(rmsDb(center, tail) - rmsDb(sine(3_500.0, 0.5, 0.3), tail)).isWithin(0.3).of(3.0)
        assertThat(abs(rmsDb(bass, tail) - rmsDb(sine(300.0, 0.5, 0.3), tail))).isLessThan(0.5)
    }

    @Test
    fun `compresseur 3 pour 1 - réduit au-dessus du seuil, transparent en dessous`() {
        val loud = Compressor(fs, thresholdDb = -20.0, ratio = 3.0, attackMs = 5.0, releaseMs = 50.0)
        val quiet = Compressor(fs, thresholdDb = -20.0, ratio = 3.0, attackMs = 5.0, releaseMs = 50.0)
        var gLoud = 1f
        repeat(fs) { gLoud = loud.gain(1.0f) }
        var gQuiet = 1f
        repeat(fs) { gQuiet = quiet.gain(0.01f) } // −40 dBFS
        // 0 dBFS, 20 dB au-dessus du seuil : sortie ≈ seuil + 20/3 → réduction ≈ 13,3 dB
        assertThat(20 * log10(gLoud.toDouble())).isWithin(0.5).of(-13.33)
        assertThat(gQuiet).isWithin(0.001f).of(1f)
    }

    @Test
    fun `normalisation - un signal faible est relevé vers la cible, sans dépasser +14 dB, le silence n'est pas amplifié`() {
        val agc = Agc(fs, targetDb = -20.0)
        var gain = 1f
        val weak = sine(440.0, 12.0, 0.01) // ≈ −43 dBFS RMS ; montée lente (2 s), 12 s pour converger
        for (x in weak) gain = agc.gain(x, x)
        assertThat(20 * log10(gain.toDouble())).isWithin(0.5).of(14.0)

        val moderate = Agc(fs, targetDb = -20.0)
        val input = sine(440.0, 12.0, 0.05) // ≈ −29 dBFS RMS
        for (x in input) gain = moderate.gain(x, x)
        assertThat(20 * log10(gain.toDouble())).isWithin(1.0).of(9.0)

        val silent = Agc(fs, targetDb = -20.0)
        var g = 1f
        for (x in sine(440.0, 3.0, 0.0001)) g = silent.gain(x, x) // −80 dBFS : sous la porte
        assertThat(g).isWithin(0.001f).of(1f)
    }

    @Test
    fun `limiteur - jamais au-dessus du plafond, même sur un signal deux fois trop fort`() {
        val limiter = Limiter(fs, ceilingDb = -1.0)
        val ceiling = 10.0.pow(-1.0 / 20).toFloat()
        var worst = 0f
        for (x in sine(200.0, 0.5, 2.0)) {
            val y = limiter.clip(x * limiter.gain(abs(x)))
            worst = maxOf(worst, abs(y))
        }
        assertThat(worst).isAtMost(ceiling + 1e-4f)
    }

    @Test
    fun `volume constant et voix renforcée - niveau de sortie régulier, crêtes sous −1 dB`() {
        val quietThenLoud = sine(300.0, 4.0, 0.02) + sine(300.0, 4.0, 0.6)
        for (preset in listOf(AudioPreset.NORMALIZE, AudioPreset.VOICE_BOOST)) {
            val (l, _) = run(preset, quietThenLoud)
            val n = fs * 4
            val quietOut = rmsDb(l, n - fs, n)
            val loudOut = rmsDb(l, 2 * n - fs, 2 * n)
            // 30 dB d'écart en entrée, bien moins de 12 dB en sortie.
            assertThat(abs(quietOut - loudOut)).isLessThan(12.0)
            assertThat(peak(l)).isAtMost(10.0.pow(-1.0 / 20).toFloat() + 1e-3f)
        }
    }

    @Test
    fun `niveau très bas - sortie nettement plus basse que le volume constant (−12 dB, cible −20 au lieu de −18)`() {
        val input = sine(300.0, 4.0, 0.3)
        val (normal, _) = run(AudioPreset.NORMALIZE, input)
        val (night, _) = run(AudioPreset.NIGHT, input)
        val n = input.size
        val difference = rmsDb(normal, n - fs, n) - rmsDb(night, n - fs, n)
        assertThat(difference).isWithin(3.0).of(14.0)
    }

    @Test
    fun `binaural - source à droite, l'oreille droite entend avant et plus fort dans l'aigu`() {
        val binaural = Binaural(fs, azimuthDeg = 70.0)
        val out = FloatArray(2)
        val input = sine(3_000.0, 0.3, 0.3)
        val l = FloatArray(input.size)
        val r = FloatArray(input.size)
        for (i in input.indices) {
            binaural.process(input[i], out)
            l[i] = out[0]
            r[i] = out[1]
        }
        val tail = fs / 10
        assertThat(rmsDb(r, tail) - rmsDb(l, tail)).isGreaterThan(3.0)

        // Délai interaural, mesuré sur un bruit large bande (une sinusoïde est ambiguë à la période
        // près) : le canal gauche est en retard de 0,55 ms environ (≤ 0,66 ms), jamais en avance.
        val noiseEngine = Binaural(fs, azimuthDeg = 70.0)
        val random = java.util.Random(42)
        val noise = FloatArray(fs / 2) { (random.nextGaussian() * 0.1).toFloat() }
        val nl = FloatArray(noise.size)
        val nr = FloatArray(noise.size)
        for (i in noise.indices) {
            noiseEngine.process(noise[i], out)
            nl[i] = out[0]
            nr[i] = out[1]
        }
        val maxLag = (Binaural.MAX_ITD_SECONDS * fs).toInt() + 2
        var bestLag = 0
        var best = Double.NEGATIVE_INFINITY
        for (lag in -maxLag..maxLag) {
            var c = 0.0
            for (i in maxLag until noise.size - maxLag) c += nr[i].toDouble() * nl[i + lag]
            if (c > best) {
                best = c
                bestLag = lag
            }
        }
        val expectedLag = (0.00055 * fs).toInt()
        assertThat(bestLag).isAtLeast(expectedLag - 3)
        assertThat(bestLag).isAtMost(expectedLag + 3)
    }

    @Test
    fun `binaural - source devant, les deux oreilles reçoivent la même chose et le mono devient stéréo`() {
        val mono = sine(500.0, 0.5, 0.3)
        val (l, r) = run(AudioPreset.BINAURAL, mono, mono.copyOf())
        val tail = fs / 4
        assertThat(abs(rmsDb(l, tail) - rmsDb(r, tail))).isLessThan(1.0)
        assertThat(peak(l)).isGreaterThan(0.05f)
        assertThat(peak(l)).isAtMost(10.0.pow(-1.0 / 20).toFloat() + 1e-3f)
        assertThat(Binaural.incidence(0.0, 90.0)).isWithin(1e-9).of(90.0)
        assertThat(Binaural.incidence(90.0, 90.0)).isWithin(1e-9).of(0.0)
        assertThat(Binaural.incidence(90.0, -90.0)).isWithin(1e-9).of(180.0)
        assertThat(Binaural.MAX_ITD_SECONDS).isWithin(1e-5).of(0.000656)
    }

    @Test
    fun `remise à zéro - après un saut, aucun résidu du passé`() {
        val chain = PresetChain(fs, AudioPreset.BINAURAL)
        val loud = sine(300.0, 1.0, 0.8)
        chain.process(loud.copyOf(), loud.copyOf(), loud.size)
        chain.reset()
        val silence = FloatArray(fs)
        val l = silence.copyOf()
        val r = silence.copyOf()
        chain.process(l, r, l.size)
        assertThat(peak(l)).isEqualTo(0f)
        assertThat(sqrt(l.sumOf { it.toDouble() * it })).isEqualTo(0.0)
    }

    private fun Double.pow(e: Double) = Math.pow(this, e)
}
