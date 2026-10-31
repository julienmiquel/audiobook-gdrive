package com.julienmiquel.audiotheque

import android.app.SearchManager
import android.content.Intent
import android.os.Bundle
import android.provider.MediaStore
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.julienmiquel.audiotheque.core.model.BookStatus
import com.julienmiquel.audiotheque.core.model.SyncTrigger
import com.julienmiquel.audiotheque.library.LibraryItem
import com.julienmiquel.audiotheque.player.AutoCatalog
import com.julienmiquel.audiotheque.player.PlaybackService
import com.julienmiquel.audiotheque.ui.AudiothequeRoot
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val container: AppContainer get() = (application as AudiothequeApp).container

    /** Notification de lecture ou recherche vocale : ouvrir l'onglet Lecteur. */
    private val openPlayer = Channel<Unit>(Channel.CONFLATED)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) {
            handleIntent(intent)
            // Synchro immédiate au lancement.
            lifecycleScope.launch {
                if (container.settings.current().isConnected) container.requestSync(SyncTrigger.LAUNCH)
            }
        }
        setContent { AudiothequeRoot(container, openPlayer.receiveAsFlow()) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.getBooleanExtra(PlaybackService.EXTRA_OPEN_PLAYER, false)) {
            openPlayer.trySend(Unit)
        }
        if (intent.action == MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH) {
            val query = intent.getStringExtra(SearchManager.QUERY).orEmpty().trim()
            openPlayer.trySend(Unit)
            lifecycleScope.launch {
                val targetId = resolveVoiceSearchBookId(query)
                if (targetId != null) {
                    container.playOrDownloadBook(targetId)
                } else {
                    container.message("Aucun livre trouvé pour « $query »")
                }
            }
        }
    }

    private suspend fun resolveVoiceSearchBookId(query: String): String? {
        val items = container.library.observeLibrary().first().filter { it.trackCount > 0 }
        if (items.isEmpty()) return null
        if (query.isBlank() || AutoCatalog.isGenericRequest(query)) {
            val s = container.settings.current()
            val candidates = listOfNotNull(
                s.lastPlayedBookId,
                container.database.progressDao().lastListened(s.accountKey)?.bookId,
            )
            candidates.firstOrNull { id -> items.any { it.id == id } }?.let { return it }
            return items.firstOrNull { it.status == BookStatus.IN_PROGRESS }?.id ?: items.first().id
        }
        return items
            .map { it to AutoCatalog.searchScore(query, it.title, it.author, it.narrator) }
            .filter { (_, score) -> score > 0 }
            .sortedWith(
                compareByDescending<Pair<LibraryItem, Int>> { it.second }
                    .thenByDescending { it.first.presentTracks > 0 }
                    .thenByDescending { it.first.lastListenedAt ?: 0L },
            )
            .firstOrNull()
            ?.first
            ?.id
    }

    override fun onStart() {
        super.onStart()
        container.player.connect()
        // Positions enregistrées sur l'autre téléphone relues, les nôtres envoyées.
        container.onForeground()
    }

    override fun onStop() {
        super.onStop()
        container.player.release()
    }
}
