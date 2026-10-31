package com.julienmiquel.audiotheque.core.model

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CleanupPolicyTest {

    @Test
    fun `fenêtre streaming - piste N-1, N et N+1 bornées au nombre total de pistes`() {
        assertThat(CleanupPolicy.streamingWindow(currentTrackIndex = 0, totalTracks = 10)).isEqualTo(0..1)
        assertThat(CleanupPolicy.streamingWindow(currentTrackIndex = 4, totalTracks = 10)).isEqualTo(3..5)
        assertThat(CleanupPolicy.streamingWindow(currentTrackIndex = 9, totalTracks = 10)).isEqualTo(8..9)
        assertThat(CleanupPolicy.streamingWindow(currentTrackIndex = 0, totalTracks = 1)).isEqualTo(0..0)
        assertThat(CleanupPolicy.streamingWindow(currentTrackIndex = 0, totalTracks = 0).isEmpty()).isTrue()
        assertThat(CleanupPolicy.streamingWindow(currentTrackIndex = -5, totalTracks = 4)).isEqualTo(0..1)
        assertThat(CleanupPolicy.streamingWindow(currentTrackIndex = 99, totalTracks = 4)).isEqualTo(2..3)
    }

    @Test
    fun `ordre de téléchargement en mode streaming - priorité N, puis N+1, puis N-1`() {
        assertThat(
            CleanupPolicy.orderedDownloadIndices(
                totalTracks = 10,
                currentTrackIndex = 0,
                finished = false,
                streamingMode = true,
            ),
        ).containsExactly(0, 1).inOrder()

        assertThat(
            CleanupPolicy.orderedDownloadIndices(
                totalTracks = 10,
                currentTrackIndex = 4,
                finished = false,
                streamingMode = true,
            ),
        ).containsExactly(4, 5, 3).inOrder()

        assertThat(
            CleanupPolicy.orderedDownloadIndices(
                totalTracks = 10,
                currentTrackIndex = 9,
                finished = false,
                streamingMode = true,
            ),
        ).containsExactly(9, 8).inOrder()

        assertThat(
            CleanupPolicy.orderedDownloadIndices(
                totalTracks = 10,
                currentTrackIndex = 9,
                finished = true,
                streamingMode = true,
            ),
        ).isEmpty()
    }

    @Test
    fun `ordre de téléchargement hors mode streaming - seules les pistes non écoutées descendent`() {
        assertThat(
            CleanupPolicy.orderedDownloadIndices(
                totalTracks = 6,
                currentTrackIndex = 0,
                finished = false,
                streamingMode = false,
            ),
        ).containsExactly(0, 1, 2, 3, 4, 5).inOrder()

        assertThat(
            CleanupPolicy.orderedDownloadIndices(
                totalTracks = 6,
                currentTrackIndex = 3,
                finished = false,
                streamingMode = false,
            ),
        ).containsExactly(3, 4, 5).inOrder()

        assertThat(
            CleanupPolicy.orderedDownloadIndices(
                totalTracks = 6,
                currentTrackIndex = 5,
                finished = true,
                streamingMode = false,
            ),
        ).isEmpty()
    }

    @Test
    fun `ordre de téléchargement d'un livre épinglé - toutes les pistes en partant de N`() {
        assertThat(
            CleanupPolicy.orderedDownloadIndices(
                totalTracks = 5,
                currentTrackIndex = 2,
                finished = true,
                streamingMode = true,
                keepAllTracks = true,
            ),
        ).containsExactly(2, 3, 4, 1, 0).inOrder()
    }

    @Test
    fun `conservation sur disque - mode streaming contre suppression des pistes écoutées`() {
        // Mode streaming : seules N-1, N et N+1 sont gardées.
        val keptStreaming = (0 until 8).filter { idx ->
            CleanupPolicy.shouldKeepInProgressTrack(
                trackSortIndex = idx,
                totalTracks = 8,
                currentTrackIndex = 3,
                finished = false,
                streamingMode = true,
            )
        }
        assertThat(keptStreaming).containsExactly(2, 3, 4).inOrder()

        // Hors mode streaming : toute piste écoutée (< N) est supprimée, et tout est supprimé une fois terminé.
        val keptStandard = (0 until 8).filter { idx ->
            CleanupPolicy.shouldKeepInProgressTrack(
                trackSortIndex = idx,
                totalTracks = 8,
                currentTrackIndex = 3,
                finished = false,
                streamingMode = false,
            )
        }
        assertThat(keptStandard).containsExactly(3, 4, 5, 6, 7).inOrder()

        val keptFinishedStandard = (0 until 8).filter { idx ->
            CleanupPolicy.shouldKeepInProgressTrack(
                trackSortIndex = idx,
                totalTracks = 8,
                currentTrackIndex = 7,
                finished = true,
                streamingMode = false,
            )
        }
        assertThat(keptFinishedStandard).isEmpty()

        // Livre épinglé (« Garder sur ce téléphone ») : tout est conservé quel que soit le mode.
        val keptPinned = (0 until 8).filter { idx ->
            CleanupPolicy.shouldKeepInProgressTrack(
                trackSortIndex = idx,
                totalTracks = 8,
                currentTrackIndex = 7,
                finished = true,
                streamingMode = false,
                keepAllTracks = true,
            )
        }
        assertThat(keptPinned).containsExactly(0, 1, 2, 3, 4, 5, 6, 7).inOrder()
    }
}
