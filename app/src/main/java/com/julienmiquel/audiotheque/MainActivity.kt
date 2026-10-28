package com.julienmiquel.audiotheque

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.lifecycleScope
import com.julienmiquel.audiotheque.core.model.SyncTrigger
import com.julienmiquel.audiotheque.player.PlaybackService
import com.julienmiquel.audiotheque.ui.AudiothequeRoot
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val container: AppContainer get() = (application as AudiothequeApp).container

    /** Notification de lecture touchée : ouvrir l'onglet Lecteur. */
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
        if (intent?.getBooleanExtra(PlaybackService.EXTRA_OPEN_PLAYER, false) == true) openPlayer.trySend(Unit)
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
