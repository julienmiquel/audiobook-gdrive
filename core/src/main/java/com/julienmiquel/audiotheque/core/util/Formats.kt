package com.julienmiquel.audiotheque.core.util

import java.text.NumberFormat
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

/** Mise en forme française (interface en français). */
object Formats {
    private val locale: Locale = Locale.FRANCE
    private const val NBSP = '\u00A0'

    /** « 12 h 34 min », « 34 min », « 45 s » ; « ≈ » si la durée est estimée. */
    fun duration(ms: Long?, approximate: Boolean = false): String {
        if (ms == null || ms < 0) return "—"
        val totalSeconds = ms / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val text = when {
            hours > 0 && approximate -> "${hours}${NBSP}h${NBSP}${"%02d".format(minutes)}"
            hours > 0 -> "${hours}${NBSP}h${NBSP}${"%02d".format(minutes)}${NBSP}min"
            minutes > 0 -> "${minutes}${NBSP}min"
            else -> "${totalSeconds}${NBSP}s"
        }
        return if (approximate) "≈${NBSP}$text" else text
    }

    /** Horloge de lecture : « 1:02:03 » ou « 2:03 ». */
    fun clock(ms: Long): String {
        val totalSeconds = (ms.coerceAtLeast(0)) / 1000
        val h = totalSeconds / 3600
        val m = (totalSeconds % 3600) / 60
        val s = totalSeconds % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
    }

    /** « 636,3 Mo », « 1,1 Go », « 512 Ko » (unités décimales, comme Android). */
    fun size(bytes: Long?): String {
        if (bytes == null || bytes < 0) return "—"
        val nf = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1 }
        return when {
            bytes >= 1_000_000_000L -> "${nf.format(bytes / 1e9)}${NBSP}Go"
            bytes >= 1_000_000L -> "${nf.format(bytes / 1e6)}${NBSP}Mo"
            bytes >= 1_000L -> "${(bytes / 1e3).roundToLong()}${NBSP}Ko"
            else -> "$bytes${NBSP}o"
        }
    }

    /** Taille en Mo (historique) : « 6,9 » ; « 0,04 » pour les petits fichiers. */
    fun megabytes(bytes: Long): String =
        NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = if (bytes in 1 until 100_000) 2 else 1 }.format(bytes / 1e6)

    /** « 1,3× ». */
    fun speed(speed: Float): String =
        NumberFormat.getNumberInstance(locale).apply {
            minimumFractionDigits = 1
            maximumFractionDigits = 1
        }.format(speed) + "×"

    /** « 42 % ». */
    fun percent(percent: Float): String = "${percent.toInt().coerceIn(0, 100)}${NBSP}%"

    private val dateTimeFormatter = DateTimeFormatter.ofPattern("d MMM yyyy 'à' HH:mm", locale)
    private val dateFormatter = DateTimeFormatter.ofPattern("d MMM yyyy", locale)
    private val timeFormatter = DateTimeFormatter.ofPattern("HH:mm", locale)
    private val timeSecondsFormatter = DateTimeFormatter.ofPattern("HH:mm:ss", locale)
    private val dayFormatter = DateTimeFormatter.ofPattern("EEEE d MMMM", locale)

    fun dateTime(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        dateTimeFormatter.format(Instant.ofEpochMilli(epochMs).atZone(zone))

    fun date(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        dateFormatter.format(Instant.ofEpochMilli(epochMs).atZone(zone))

    fun time(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        timeFormatter.format(Instant.ofEpochMilli(epochMs).atZone(zone))

    fun timeWithSeconds(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
        timeSecondsFormatter.format(Instant.ofEpochMilli(epochMs).atZone(zone))

    /** « Aujourd'hui », « Hier » ou « lundi 20 octobre ». */
    fun dayLabel(epochMs: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
        val day = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        return when (day) {
            today -> "Aujourd'hui"
            today.minusDays(1) -> "Hier"
            else -> dayFormatter.format(day).replaceFirstChar { it.titlecase(locale) }
        }
    }

    /** « aujourd'hui à 15:12 », « demain à 03:12 » ou date complète. */
    fun upcoming(epochMs: Long, now: Long = System.currentTimeMillis(), zone: ZoneId = ZoneId.systemDefault()): String {
        val day: LocalDate = Instant.ofEpochMilli(epochMs).atZone(zone).toLocalDate()
        val today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
        val time = time(epochMs, zone)
        return when (day) {
            today -> "aujourd'hui à $time"
            today.plusDays(1) -> "demain à $time"
            else -> dateTime(epochMs, zone)
        }
    }

    /** Durée écoulée lisible : « 4 min 20 s », « 2 h 05 min ». */
    fun elapsed(ms: Long): String {
        val s = abs(ms) / 1000
        return when {
            s >= 3600 -> "${s / 3600}${NBSP}h${NBSP}${"%02d".format((s % 3600) / 60)}${NBSP}min"
            s >= 60 -> "${s / 60}${NBSP}min${NBSP}${"%02d".format(s % 60)}${NBSP}s"
            else -> "$s${NBSP}s"
        }
    }
}
