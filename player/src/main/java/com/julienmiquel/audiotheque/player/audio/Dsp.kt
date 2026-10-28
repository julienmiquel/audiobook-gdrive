package com.julienmiquel.audiotheque.player.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/*
 * Briques de traitement du signal du lecteur, en virgule flottante, sans dépendance Android :
 * filtres, dynamique (compresseur, normalisation automatique, limiteur) et rendu binaural.
 * Chaque bloc traite un échantillon (ou une paire gauche/droite) à la fois ; l'état est remis à
 * zéro à chaque saut (flush).
 */

internal fun dbToLinear(db: Double): Double = 10.0.pow(db / 20.0)

internal fun linearToDb(x: Double): Double = 20.0 * log10(max(x, 1e-9))

/** Coefficient de lissage exponentiel pour une constante de temps en millisecondes. */
internal fun smoothing(sampleRate: Int, ms: Double): Double =
    if (ms <= 0.0) 0.0 else exp(-1.0 / (sampleRate * ms / 1000.0))

/** Filtre biquad (formules du « cookbook » RBJ), forme directe II transposée ; une instance par canal. */
class Biquad private constructor(
    private val b0: Double,
    private val b1: Double,
    private val b2: Double,
    private val a1: Double,
    private val a2: Double,
) {
    private var z1 = 0.0
    private var z2 = 0.0

    fun process(x: Float): Float {
        val y = b0 * x + z1
        z1 = b1 * x - a1 * y + z2
        z2 = b2 * x - a2 * y
        return y.toFloat()
    }

    fun reset() {
        z1 = 0.0
        z2 = 0.0
    }

    companion object {
        private fun normalized(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) =
            Biquad(b0 / a0, b1 / a0, b2 / a0, a1 / a0, a2 / a0)

        /** Coupe-bas du second ordre (−12 dB/octave) : rumble, pops. */
        fun highPass(sampleRate: Int, frequency: Double, q: Double = 0.7071): Biquad {
            val w0 = 2 * PI * frequency / sampleRate
            val alpha = sin(w0) / (2 * q)
            val c = cos(w0)
            return normalized((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha)
        }

        fun lowPass(sampleRate: Int, frequency: Double, q: Double = 0.7071): Biquad {
            val w0 = 2 * PI * frequency / sampleRate
            val alpha = sin(w0) / (2 * q)
            val c = cos(w0)
            return normalized((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + alpha, -2 * c, 1 - alpha)
        }

        /** Cloche (présence, creux) : [gainDb] à [frequency], largeur [q]. */
        fun peaking(sampleRate: Int, frequency: Double, q: Double, gainDb: Double): Biquad {
            val a = 10.0.pow(gainDb / 40.0)
            val w0 = 2 * PI * frequency / sampleRate
            val alpha = sin(w0) / (2 * q)
            val c = cos(w0)
            return normalized(1 + alpha * a, -2 * c, 1 - alpha * a, 1 + alpha / a, -2 * c, 1 - alpha / a)
        }

        fun highShelf(sampleRate: Int, frequency: Double, q: Double, gainDb: Double): Biquad {
            val a = 10.0.pow(gainDb / 40.0)
            val w0 = 2 * PI * frequency / sampleRate
            val alpha = sin(w0) / (2 * q)
            val c = cos(w0)
            val s = 2 * sqrt(a) * alpha
            return normalized(
                a * ((a + 1) + (a - 1) * c + s),
                -2 * a * ((a - 1) + (a + 1) * c),
                a * ((a + 1) + (a - 1) * c - s),
                (a + 1) - (a - 1) * c + s,
                2 * ((a - 1) - (a + 1) * c),
                (a + 1) - (a - 1) * c - s,
            )
        }
    }
}

/**
 * Compresseur à détection de crête, stéréo lié (même gain sur les deux canaux), genou doux,
 * lissage attaque/relâchement dans le domaine des décibels.
 */
class Compressor(
    sampleRate: Int,
    private val thresholdDb: Double,
    private val ratio: Double,
    attackMs: Double,
    releaseMs: Double,
    private val makeupDb: Double = 0.0,
    private val kneeDb: Double = 6.0,
) {
    private val attack = smoothing(sampleRate, attackMs)
    private val release = smoothing(sampleRate, releaseMs)

    /** Réduction de gain courante, en dB (≤ 0). */
    var reductionDb: Double = 0.0
        private set

    /** Gain linéaire à appliquer à la trame dont la crête est [peak] (valeur absolue, 0..1). */
    fun gain(peak: Float): Float {
        val over = linearToDb(peak.toDouble()) - thresholdDb
        val half = kneeDb / 2
        val target = when {
            over <= -half -> 0.0
            over >= half -> -(over - over / ratio)
            else -> -((1 - 1 / ratio) * (over + half) * (over + half) / (2 * kneeDb))
        }
        val coef = if (target < reductionDb) attack else release
        reductionDb = coef * reductionDb + (1 - coef) * target
        return dbToLinear(reductionDb + makeupDb).toFloat()
    }

    fun reset() {
        reductionDb = 0.0
    }
}

/**
 * Normalisation automatique du niveau : le niveau efficace (RMS, fenêtre glissante) est ramené
 * vers [targetDb], au plus +[maxGainDb] (le souffle n'est pas amplifié davantage), au moins
 * [minGainDb]. Baisse rapide quand c'est trop fort, montée lente quand c'est trop faible ; le
 * gain est figé sous [gateDb] (silences, blancs entre deux chapitres).
 */
class Agc(
    sampleRate: Int,
    private val targetDb: Double = -18.0,
    private val maxGainDb: Double = 14.0,
    private val minGainDb: Double = -12.0,
    windowMs: Double = 400.0,
    attackMs: Double = 150.0,
    releaseMs: Double = 2_000.0,
    private val gateDb: Double = -55.0,
) {
    private val window = smoothing(sampleRate, windowMs)
    private val attack = smoothing(sampleRate, attackMs)
    private val release = smoothing(sampleRate, releaseMs)
    private var meanSquare = 0.0

    /** Gain courant, en dB. */
    var gainDb: Double = 0.0
        private set

    fun gain(left: Float, right: Float): Float {
        val power = (left.toDouble() * left + right.toDouble() * right) / 2
        meanSquare = window * meanSquare + (1 - window) * power
        val levelDb = 10 * log10(meanSquare + 1e-12)
        if (levelDb > gateDb) {
            val desired = (targetDb - levelDb).coerceIn(minGainDb, maxGainDb)
            val coef = if (desired < gainDb) attack else release
            gainDb = coef * gainDb + (1 - coef) * desired
        }
        return dbToLinear(gainDb).toFloat()
    }

    fun reset() {
        meanSquare = 0.0
        gainDb = 0.0
    }
}

/** Limiteur de crête : attaque instantanée sous le plafond, relâchement lissé, écrêtage de sûreté. */
class Limiter(sampleRate: Int, ceilingDb: Double = -1.0, releaseMs: Double = 100.0) {
    private val ceiling = dbToLinear(ceilingDb).toFloat()
    private val release = smoothing(sampleRate, releaseMs)
    private var gain = 1.0

    /** Gain linéaire pour la trame de crête [peak]. */
    fun gain(peak: Float): Float {
        gain = if (peak * gain > ceiling) ceiling / peak.toDouble() else release * gain + (1 - release)
        return gain.toFloat()
    }

    fun clip(x: Float): Float = x.coerceIn(-ceiling, ceiling)

    fun reset() {
        gain = 1.0
    }
}

/** Ligne à retard à lecture fractionnaire (interpolation linéaire). */
internal class DelayLine(maxSamples: Int) {
    private val buffer = FloatArray(Integer.highestOneBit(maxSamples + 1) shl 1)
    private val mask = buffer.size - 1
    private var write = 0

    fun push(x: Float) {
        buffer[write] = x
        write = (write + 1) and mask
    }

    /** Échantillon écrit il y a [delay] échantillons (≥ 1), fractionnaire. */
    fun read(delay: Double): Float {
        val d = delay.coerceIn(1.0, (buffer.size - 2).toDouble())
        val whole = d.toInt()
        val frac = (d - whole).toFloat()
        val i0 = (write - whole + buffer.size) and mask
        val i1 = (i0 - 1 + buffer.size) and mask
        return buffer[i0] * (1 - frac) + buffer[i1] * frac
    }

    fun readInt(delay: Int): Float = buffer[(write - delay.coerceIn(1, buffer.size - 1) + buffer.size) and mask]

    fun reset() {
        buffer.fill(0f)
        write = 0
    }
}

/** Filtre en peigne à réinjection amortie (réverbération de Schroeder). */
internal class Comb(sampleRate: Int, delayMs: Double, rt60Seconds: Double, private val damping: Double) {
    private val delay = max(1, (sampleRate * delayMs / 1000).toInt())
    private val line = DelayLine(delay)
    private val feedback = 10.0.pow(-3.0 * delayMs / 1000 / rt60Seconds)
    private var lowPassState = 0.0

    fun process(x: Float): Float {
        val y = line.readInt(delay)
        lowPassState = (1 - damping) * y + damping * lowPassState
        line.push((x + feedback * lowPassState).toFloat())
        return y
    }

    fun reset() {
        line.reset()
        lowPassState = 0.0
    }
}

/** Filtre passe-tout de Schroeder (densification des échos). */
internal class AllPass(sampleRate: Int, delayMs: Double, private val g: Double = 0.7) {
    private val delay = max(1, (sampleRate * delayMs / 1000).toInt())
    private val line = DelayLine(delay)

    fun process(x: Float): Float {
        val delayed = line.readInt(delay)
        val v = x + g * delayed
        line.push(v.toFloat())
        return (delayed - g * v).toFloat()
    }

    fun reset() = line.reset()
}

/**
 * Rendu binaural d'une source mono, sans base de HRIR : modèle structurel de Brown & Duda
 * (délai interaural de Woodworth et ombre de la tête par oreille), premières réflexions d'une
 * pièce et réverbération de Schroeder discrète — l'équivalent embarqué du moteur binaural
 * (source statique à un azimut donné, bus de réverbération « petite pièce »).
 *
 * Azimut en degrés, 0 devant, positif à droite ; l'élévation n'a pas d'effet dans ce modèle.
 */
class Binaural(private val sampleRate: Int, azimuthDeg: Double = 0.0, room: Room = Room.SMALL) {

    enum class Room(val rt60: Double, val wetDb: Double, val earlyDb: Double) {
        /** Petite pièce : queue courte, réflexions proches. */
        SMALL(0.45, -18.0, -7.0),

        /** Salle de réunion : un peu plus d'espace. */
        MEETING(0.8, -16.0, -8.0),
    }

    private class Ear(sampleRate: Int, incidenceDeg: Double) {
        /** Délai (échantillons) et ombre de la tête pour un angle d'incidence par rapport à l'axe de l'oreille. */
        val delaySamples: Double
        private val b0: Double
        private val b1: Double
        private val a1: Double
        private var x1 = 0.0
        private var y1 = 0.0

        init {
            val radians = incidenceDeg * PI / 180
            val headRadiusOverC = HEAD_RADIUS_M / SPEED_OF_SOUND
            val delaySeconds = if (incidenceDeg < 90) headRadiusOverC * (1 - cos(radians)) else headRadiusOverC * (1 + radians - PI / 2)
            delaySamples = delaySeconds * sampleRate + 1
            val alpha = (1 + ALPHA_MIN / 2) + (1 - ALPHA_MIN / 2) * cos(incidenceDeg / THETA_MIN * PI)
            val beta = (SPEED_OF_SOUND / HEAD_RADIUS_M) / sampleRate
            b0 = (beta + alpha) / (beta + 1)
            b1 = (beta - alpha) / (beta + 1)
            a1 = (beta - 1) / (beta + 1)
        }

        fun shadow(x: Float): Float {
            val y = b0 * x + b1 * x1 - a1 * y1
            x1 = x.toDouble()
            y1 = y
            return y.toFloat()
        }

        fun reset() {
            x1 = 0.0
            y1 = 0.0
        }
    }

    private val left = Ear(sampleRate, incidence(azimuthDeg, -90.0))
    private val right = Ear(sampleRate, incidence(azimuthDeg, 90.0))
    private val direct = DelayLine((sampleRate * 0.002).toInt() + 4)

    private val earlyLevel = dbToLinear(room.earlyDb).toFloat()
    private val wetLevel = dbToLinear(room.wetDb).toFloat()
    private val early = DelayLine((sampleRate * 0.06).toInt() + 4)
    private val earlyTaps = EARLY_TAPS_MS.map { (ms, l, r) -> Triple(max(1, (sampleRate * ms / 1000).toInt()), l.toFloat(), r.toFloat()) }
    private val wallFilter = Biquad.lowPass(sampleRate, 4_000.0)
    private val preDelay = DelayLine((sampleRate * 0.015).toInt() + 4)
    private val preDelaySamples = max(1, (sampleRate * 0.010).toInt())
    private val combsLeft = COMB_LEFT_MS.map { Comb(sampleRate, it, room.rt60, 0.3) }
    private val combsRight = COMB_RIGHT_MS.map { Comb(sampleRate, it, room.rt60, 0.3) }
    private val allPassLeft = listOf(AllPass(sampleRate, 5.0), AllPass(sampleRate, 1.7))
    private val allPassRight = listOf(AllPass(sampleRate, 4.6), AllPass(sampleRate, 1.9))

    /** Produit la paire (gauche, droite) pour l'échantillon mono [x], dans [out] (taille 2). */
    fun process(x: Float, out: FloatArray) {
        direct.push(x)
        var l = left.shadow(direct.read(left.delaySamples))
        var r = right.shadow(direct.read(right.delaySamples))

        early.push(wallFilter.process(x))
        var el = 0f
        var er = 0f
        for ((delay, gl, gr) in earlyTaps) {
            val tap = early.readInt(delay)
            el += tap * gl
            er += tap * gr
        }
        l += el * earlyLevel
        r += er * earlyLevel

        preDelay.push(x)
        val send = preDelay.readInt(preDelaySamples)
        var wl = 0f
        var wr = 0f
        for (comb in combsLeft) wl += comb.process(send)
        for (comb in combsRight) wr += comb.process(send)
        wl *= 0.25f
        wr *= 0.25f
        for (ap in allPassLeft) wl = ap.process(wl)
        for (ap in allPassRight) wr = ap.process(wr)
        out[0] = l + wl * wetLevel
        out[1] = r + wr * wetLevel
    }

    fun reset() {
        left.reset()
        right.reset()
        direct.reset()
        early.reset()
        wallFilter.reset()
        preDelay.reset()
        combsLeft.forEach { it.reset() }
        combsRight.forEach { it.reset() }
        allPassLeft.forEach { it.reset() }
        allPassRight.forEach { it.reset() }
    }

    companion object {
        const val HEAD_RADIUS_M = 0.0875
        const val SPEED_OF_SOUND = 343.0
        private const val ALPHA_MIN = 0.1
        private const val THETA_MIN = 150.0

        /** Délai interaural maximal (source à 90°), en secondes : utile aux tests. */
        val MAX_ITD_SECONDS: Double = HEAD_RADIUS_M / SPEED_OF_SOUND * (1 + PI / 2)

        /** Angle d'incidence (0..180°) entre la direction de la source et l'axe d'une oreille. */
        fun incidence(azimuthDeg: Double, earAxisDeg: Double): Double {
            val d = ((azimuthDeg - earAxisDeg) % 360 + 540) % 360 - 180
            return abs(d)
        }

        /** Premières réflexions d'une petite pièce : retard (ms), gain gauche, gain droit. */
        private val EARLY_TAPS_MS = listOf(
            Triple(8.1, 0.50, 0.38),
            Triple(13.7, 0.34, 0.47),
            Triple(19.3, 0.40, 0.28),
            Triple(26.9, 0.24, 0.33),
            Triple(35.1, 0.27, 0.20),
            Triple(44.3, 0.17, 0.23),
        )
        private val COMB_LEFT_MS = listOf(29.7, 37.1, 41.1, 43.7)
        private val COMB_RIGHT_MS = listOf(30.3, 36.5, 41.9, 43.1)
    }
}
