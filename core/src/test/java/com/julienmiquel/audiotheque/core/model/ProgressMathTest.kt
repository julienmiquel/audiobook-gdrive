package com.julienmiquel.audiotheque.core.model

import com.google.common.truth.Truth.assertThat
import com.julienmiquel.audiotheque.core.db.TrackEntity
import com.julienmiquel.audiotheque.core.util.Formats
import org.junit.Test

class ProgressMathTest {

    private fun track(i: Int, duration: Long?, size: Long = 1_000_000) = TrackEntity(
        driveId = "t$i",
        bookId = "b",
        name = "$i.mp3",
        relativePath = "$i.mp3",
        sortIndex = i,
        title = "Piste $i",
        durationMs = duration,
        sizeBytes = size,
        mimeType = "audio/mpeg",
    )

    @Test
    fun `durées manquantes estimées au prorata de la taille`() {
        val tracks = listOf(track(0, 60_000, 1_000_000), track(1, null, 2_000_000))
        assertThat(ProgressMath.durations(tracks)).containsExactly(60_000L, 120_000L).inOrder()
    }

    @Test
    fun `position globale, pourcentage et temps restant à la vitesse en cours`() {
        val d = listOf(60_000L, 60_000L, 120_000L)
        assertThat(ProgressMath.globalPosition(d, 1, 30_000)).isEqualTo(90_000)
        assertThat(ProgressMath.percent(d, 1, 30_000)).isWithin(0.01f).of(37.5f)
        // 150 s restantes à 1,5× → 100 s.
        assertThat(ProgressMath.remainingMs(d, 1, 30_000, 1.5f)).isEqualTo(100_000)
    }

    @Test
    fun `barre de progression sur le livre entier - position globale vers piste`() {
        val d = listOf(60_000L, 60_000L, 120_000L)
        assertThat(ProgressMath.locate(d, 0)).isEqualTo(0 to 0L)
        assertThat(ProgressMath.locate(d, 90_000)).isEqualTo(1 to 30_000L)
        assertThat(ProgressMath.locate(d, 500_000)).isEqualTo(2 to 120_000L)
    }

    @Test
    fun `mise en forme française`() {
        assertThat(Formats.duration(45_240_000)).isEqualTo("12\u00A0h\u00A034\u00A0min")
        assertThat(Formats.duration(45_240_000, approximate = true)).isEqualTo("≈\u00A012\u00A0h\u00A034")
        assertThat(Formats.size(636_300_000)).isEqualTo("636,3\u00A0Mo")
        assertThat(Formats.speed(1.3f)).isEqualTo("1,3×")
        assertThat(Formats.percent(42.7f)).isEqualTo("42\u00A0%")
        assertThat(Formats.clock(3_723_000)).isEqualTo("1:02:03")
    }
}
