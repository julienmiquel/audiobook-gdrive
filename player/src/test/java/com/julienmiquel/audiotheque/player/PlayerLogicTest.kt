package com.julienmiquel.audiotheque.player

import com.google.common.truth.Truth.assertThat
import com.julienmiquel.audiotheque.core.db.TrackEntity
import org.junit.After
import org.junit.Test

class PlayerLogicTest {

    @After
    fun tearDown() {
        PlayerRuntime.cancelTimer()
        PlayerRuntime.clearHistoryForBook("civ")
        PlayerRuntime.clearHistoryForBook("other")
    }

    @Test
    fun `identifiants média - livre, piste, chapitre intégré et position`() {
        val request = MediaIds.bookRequest("civ", "t9", 12_000)
        assertThat(MediaIds.parseBookRequest(request)).isEqualTo(MediaIds.BookRequest("civ", "t9", 12_000))
        assertThat(MediaIds.parseBookRequest(MediaIds.bookRequest("civ"))).isEqualTo(MediaIds.BookRequest("civ", null, null))
        assertThat(MediaIds.parseBookRequest(MediaIds.bookRequest("civ", null, 5_000))).isEqualTo(MediaIds.BookRequest("civ", null, 5_000))
        assertThat(MediaIds.parseTrack(MediaIds.track("civ", "t1"))).isEqualTo(MediaIds.TrackRef("civ", "t1", null))
        assertThat(MediaIds.parseTrack(MediaIds.track("civ", "m4b1", 3))).isEqualTo(MediaIds.TrackRef("civ", "m4b1", 3))
        assertThat(MediaIds.parseTrack("t:civ:m4b1:notanumber")).isNull()
        assertThat(MediaIds.parseTrack("b:civ")).isNull()
    }

    @Test
    fun `recul intelligent selon la durée de pause`() {
        assertThat(SmartRewind.computeMs(500L, smart = true)).isEqualTo(0L)
        assertThat(SmartRewind.computeMs(10_000L, smart = true)).isEqualTo(2_000L)
        assertThat(SmartRewind.computeMs(3 * 60_000L, smart = true)).isEqualTo(5_000L)
        assertThat(SmartRewind.computeMs(30 * 60_000L, smart = true)).isEqualTo(10_000L)
        assertThat(SmartRewind.computeMs(5 * 3_600_000L, smart = true)).isEqualTo(20_000L)
        assertThat(SmartRewind.computeMs(48 * 3_600_000L, smart = true)).isEqualTo(30_000L)
        assertThat(SmartRewind.computeMs(48 * 3_600_000L, smart = false)).isEqualTo(5_000L)
        assertThat(SmartRewind.computeMs(1_000L, smart = false)).isEqualTo(0L)
    }

    @Test
    fun `historique de sauts - dédoublonnage proche et nettoyage par livre`() {
        PlayerRuntime.recordJump("civ", "t1", "Chapitre 1", 40_000L, "Avant saut", now = 1_000L)
        // Moins de 5 s d'écart sur la même piste : ignoré.
        PlayerRuntime.recordJump("civ", "t1", "Chapitre 1", 42_000L, "Avant saut", now = 2_000L)
        PlayerRuntime.recordJump("civ", "t2", "Chapitre 2", 120_000L, "Minuterie", now = 3_000L)
        PlayerRuntime.recordJump("other", "x1", "Piste 1", 10_000L, "Avant saut", now = 4_000L)

        val civEntries = PlayerRuntime.seekHistory.value.filter { it.bookId == "civ" }
        assertThat(civEntries).hasSize(2)
        assertThat(civEntries.first().trackId).isEqualTo("t2")
        assertThat(civEntries.last().positionMs).isEqualTo(40_000L)

        PlayerRuntime.clearHistoryForBook("civ")
        assertThat(PlayerRuntime.seekHistory.value.map { it.bookId }).containsExactly("other")
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
    fun `vignette système - numéro de piste ou chapitre, jamais le nom de fichier`() {
        fun t(i: Int, part: Int?, chapter: Int?) = TrackEntity(
            driveId = "t$i", bookId = "b", name = "9791035411947 00$i.mp3", relativePath = "$i.mp3", sortIndex = i,
            part = part, chapter = chapter, title = "9791035411947 00$i mp3", sizeBytes = 1, mimeType = "audio/mpeg",
        )
        assertThat(Chapters.label(t(3, null, null), total = 51)).isEqualTo("Piste 4 / 51")
        assertThat(Chapters.label(t(3, null, null), total = 0)).isEqualTo("Piste 4")
        assertThat(Chapters.label(t(3, null, 12), total = 51)).isEqualTo("Chapitre 12")
        assertThat(Chapters.label(t(3, 2, 4), total = 51)).isEqualTo("Partie 2 · chapitre 4")
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

    @Test
    fun `résolution de la piste cible - ne retombe jamais sur la piste 0 quand la piste de reprise est absente`() {
        fun track(i: Int) = TrackEntity(
            driveId = "t$i",
            bookId = "civ",
            name = "0$i.mp3",
            relativePath = "0$i.mp3",
            sortIndex = i,
            title = "Chapitre $i",
            sizeBytes = 1_000L,
            mimeType = "audio/mpeg",
        )
        val allTracks = (0..9).map(::track)
        val now = 1_000_000L

        // 1. Reprise streaming / inter-appareils sur t8 alors que seules t0 et t1 sont locales :
        //    retourne MissingTrack(t8) avec la position exacte, sans retomber sur t0.
        val progressOnT8 = com.julienmiquel.audiotheque.core.db.ProgressEntity(
            bookId = "civ",
            account = "a@b.fr",
            trackId = "t8",
            trackIndex = 8,
            positionMs = 42_000L,
            lastListenedAt = now - 60_000L,
            updatedAt = now - 60_000L,
        )
        val missing = PlaybackTargetResolver.resolve(
            tracks = allTracks,
            presentTrackIds = setOf("t0", "t1"),
            progress = progressOnT8,
            smartRewind = true,
            nowMs = now,
        )
        assertThat(missing).isEqualTo(StartResolution.MissingTrack(allTracks[8], 42_000L))

        // 2. Même reprise une fois t8 présente : Playable avec recul intelligent (1 min -> 5 s).
        val playable = PlaybackTargetResolver.resolve(
            tracks = allTracks,
            presentTrackIds = setOf("t7", "t8", "t9"),
            progress = progressOnT8,
            smartRewind = true,
            nowMs = now,
        )
        assertThat(playable).isEqualTo(StartResolution.Playable(allTracks[8], 37_000L))

        // 3. Saut explicite vers une piste absente : MissingTrack sans recul.
        val explicitMissing = PlaybackTargetResolver.resolve(
            tracks = allTracks,
            presentTrackIds = setOf("t0", "t1"),
            progress = progressOnT8,
            explicitTrackId = "t6",
            explicitPositionMs = 15_000L,
            smartRewind = true,
            nowMs = now,
        )
        assertThat(explicitMissing).isEqualTo(StartResolution.MissingTrack(allTracks[6], 15_000L))

        // 4. Livre terminé (finished = true) dont les pistes ont été nettoyées : repart au début (t0, 0 ms).
        val finishedProgress = progressOnT8.copy(trackId = "t9", trackIndex = 9, percent = 100f, finished = true)
        val restartMissing = PlaybackTargetResolver.resolve(
            tracks = allTracks,
            presentTrackIds = emptySet(),
            progress = finishedProgress,
            smartRewind = true,
            nowMs = now,
        )
        assertThat(restartMissing).isEqualTo(StartResolution.MissingTrack(allTracks[0], 0L))

        // 5. Progression indexée sans driveId ou avec piste remplacée sur Drive : retrouvée par trackIndex.
        val indexOnlyProgress = progressOnT8.copy(trackId = null, trackIndex = 5, positionMs = 20_000L)
        val fallbackByIndex = PlaybackTargetResolver.resolve(
            tracks = allTracks,
            presentTrackIds = setOf("t0"),
            progress = indexOnlyProgress,
            smartRewind = false,
            nowMs = now,
        )
        assertThat(fallbackByIndex).isEqualTo(StartResolution.MissingTrack(allTracks[5], 20_000L))

        val replacedIdProgress = progressOnT8.copy(trackId = "ancien-id-drive", trackIndex = 5, positionMs = 20_000L)
        assertThat(
            PlaybackTargetResolver.resolve(
                tracks = allTracks,
                presentTrackIds = setOf("t0"),
                progress = replacedIdProgress,
                smartRewind = false,
                nowMs = now,
            ),
        ).isEqualTo(StartResolution.MissingTrack(allTracks[5], 0L))

        // 6. Aucune piste inventoriée.
        assertThat(
            PlaybackTargetResolver.resolve(
                tracks = emptyList(),
                presentTrackIds = emptySet(),
                progress = null,
                smartRewind = true,
                nowMs = now,
            ),
        ).isEqualTo(StartResolution.NoTracks)
    }
}
