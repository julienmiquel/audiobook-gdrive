package com.julienmiquel.audiotheque.player

import com.google.common.truth.Truth.assertThat
import com.julienmiquel.audiotheque.core.db.TrackEntity
import org.junit.After
import org.junit.Test

class PlayerLogicTest {

    @After
    fun tearDown() = PlayerRuntime.cancelTimer()

    @Test
    fun `identifiants média - livre, piste et position`() {
        val request = MediaIds.bookRequest("civ", "t9", 12_000)
        assertThat(MediaIds.parseBookRequest(request)).isEqualTo(MediaIds.BookRequest("civ", "t9", 12_000))
        assertThat(MediaIds.parseBookRequest(MediaIds.bookRequest("civ"))).isEqualTo(MediaIds.BookRequest("civ", null, null))
        assertThat(MediaIds.parseBookRequest(MediaIds.bookRequest("civ", null, 5_000))).isEqualTo(MediaIds.BookRequest("civ", null, 5_000))
        assertThat(MediaIds.parseTrack(MediaIds.track("civ", "t1"))).isEqualTo(MediaIds.TrackRef("civ", "t1"))
        assertThat(MediaIds.parseTrack("b:civ")).isNull()
    }

    @Test
    fun `chapitres groupés par partie, sinon une seule liste`() {
        fun t(i: Int, part: Int?) = TrackEntity(
            driveId = "t$i", bookId = "b", name = "$i.mp3", relativePath = "$i.mp3", sortIndex = i,
            part = part, chapter = i, title = "Chapitre $i", sizeBytes = 1, mimeType = "audio/mpeg",
        )
        val groups = Chapters.group(listOf(t(0, 1), t(1, 1), t(2, 2), t(3, 4)))
        assertThat(groups.map { it.label }).containsExactly("Partie 1", "Partie 2", "Partie 4").inOrder()
        assertThat(groups.first().tracks).hasSize(2)
        assertThat(Chapters.group(listOf(t(0, null), t(1, null))).single().label).isNull()
    }

    @Test
    fun `minuterie de sommeil - 15 à 60 min, fin du chapitre, prolongeable de 10 min`() {
        assertThat(PlayerRuntime.TIMER_CHOICES_MINUTES).containsExactly(15, 30, 45, 60).inOrder()
        PlayerRuntime.startTimer(15, now = 0)
        assertThat(PlayerRuntime.sleepTimer.value).isEqualTo(SleepTimerState.Countdown(15 * 60_000L, 15 * 60_000L))
        PlayerRuntime.extend(now = 60_000)
        assertThat((PlayerRuntime.sleepTimer.value as SleepTimerState.Countdown).endsAt).isEqualTo(25 * 60_000L)
        PlayerRuntime.startEndOfChapter()
        assertThat(PlayerRuntime.sleepTimer.value).isEqualTo(SleepTimerState.EndOfChapter)
        PlayerRuntime.cancelTimer()
        assertThat(PlayerRuntime.sleepTimer.value).isEqualTo(SleepTimerState.Off)
    }

    @Test
    fun `vitesse de 0,8 à 2,0 et reprise 5 s en arrière, sauvegarde toutes les 10 s`() {
        assertThat(PlaybackService.MIN_SPEED).isEqualTo(0.8f)
        assertThat(PlaybackService.MAX_SPEED).isEqualTo(2.0f)
        assertThat(PlaybackService.RESUME_REWIND_MS).isEqualTo(5_000)
        assertThat(PlaybackService.SAVE_INTERVAL_MS).isEqualTo(10_000)
    }
}
