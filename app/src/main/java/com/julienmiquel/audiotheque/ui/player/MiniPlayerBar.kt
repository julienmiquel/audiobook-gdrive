package com.julienmiquel.audiotheque.ui.player

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.julienmiquel.audiotheque.core.db.TrackEntity
import com.julienmiquel.audiotheque.core.model.ProgressMath
import com.julienmiquel.audiotheque.core.model.chapterLabel
import com.julienmiquel.audiotheque.core.util.Formats
import com.julienmiquel.audiotheque.library.LibraryItem
import com.julienmiquel.audiotheque.library.bookTimeline
import com.julienmiquel.audiotheque.player.PlayerUiState
import com.julienmiquel.audiotheque.ui.common.BookCover

/**
 * Mini-lecteur persistant affiché juste au-dessus de la barre d'onglets sur Bibliothèque,
 * Fiche livre, Synchro, Statistiques et Réglages dès qu'un livre est en cours ou prêt à reprendre.
 */
@Composable
fun MiniPlayerBar(
    item: LibraryItem,
    tracks: List<TrackEntity>,
    playerState: PlayerUiState,
    skipSeconds: Int,
    onOpenPlayer: () -> Unit,
    onTogglePlay: () -> Unit,
    onSeekBack: () -> Unit,
    modifier: Modifier = Modifier,
    waitingForDownload: Boolean = false,
) {
    val isLoadedBook = playerState.bookId == item.id
    val livePercent = remember(isLoadedBook, tracks, item.book.durationMs, playerState.trackId, playerState.trackPositionMs, item.percent) {
        if (isLoadedBook && tracks.isNotEmpty() && playerState.trackId != null) {
            val durations = bookTimeline(tracks, item.book)
            val currentIdx = tracks.firstOrNull { it.driveId == playerState.trackId }?.sortIndex ?: 0
            ProgressMath.percent(durations, currentIdx, playerState.trackPositionMs)
        } else {
            item.percent ?: 0f
        }
    }

    val currentChapterText = remember(isLoadedBook, tracks, playerState.trackId, playerState.chapterTitle) {
        if (!isLoadedBook) {
            null
        } else {
            playerState.chapterTitle?.takeIf { it.isNotBlank() }
                ?: tracks.firstOrNull { it.driveId == playerState.trackId }?.chapterLabel
        }
    }

    val subtitle = buildList {
        if (waitingForDownload) {
            add("Téléchargement…")
        } else if (currentChapterText != null) {
            add(currentChapterText)
        } else if (!item.author.isNullOrBlank()) {
            add(item.author)
        }
        if (livePercent > 0f) {
            add(Formats.percent(livePercent))
        } else if (!isLoadedBook && !waitingForDownload) {
            add(if (item.presentTracks > 0) "Prêt à écouter" else "À télécharger")
        }
    }.joinToString(" · ")

    Surface(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClickLabel = "Ouvrir le lecteur", onClick = onOpenPlayer),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        tonalElevation = 4.dp,
    ) {
        Column(Modifier.fillMaxWidth()) {
            LinearProgressIndicator(
                progress = { (livePercent / 100f).coerceIn(0f, 1f) },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BookCover(
                    cover = item.cover,
                    title = item.title,
                    size = 44.dp,
                    cornerRadius = 8.dp,
                )
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle.isNotEmpty()) {
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (isLoadedBook) {
                    IconButton(
                        onClick = onSeekBack,
                        modifier = Modifier.semantics {
                            contentDescription = "Reculer de $skipSeconds secondes"
                        },
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Replay,
                            contentDescription = null,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }
                val playing = isLoadedBook && playerState.isPlaying
                FilledTonalIconButton(
                    onClick = onTogglePlay,
                    modifier = Modifier.size(40.dp),
                ) {
                    if (waitingForDownload) {
                        CircularProgressIndicator(
                            modifier = Modifier
                                .size(20.dp)
                                .semantics { contentDescription = "Annuler le lancement automatique" },
                            strokeWidth = 2.dp,
                        )
                    } else {
                        Icon(
                            imageVector = if (playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                            contentDescription = if (playing) "Mettre en pause" else "Lire ${item.title}",
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        }
    }
}
