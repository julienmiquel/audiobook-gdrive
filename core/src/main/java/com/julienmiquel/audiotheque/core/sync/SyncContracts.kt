package com.julienmiquel.audiotheque.core.sync

import com.julienmiquel.audiotheque.core.model.SyncTrigger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Point d'entrée vers le moteur de synchro pour les autres modules. Tout appel à Drive passe par
 * le moteur : la bibliothèque et le lecteur ne font que demander une synchro.
 */
interface SyncRequester {
    /** Synchro immédiate (au lancement, à chaque changement de statut…). */
    fun requestSync(trigger: SyncTrigger)
}

/** Position adoptée depuis Drive, enregistrée par un autre téléphone. */
data class RemoteProgress(
    val bookId: String,
    /** Nom de l'appareil qui l'a enregistrée (« Google Pixel 9 »), s'il est connu. */
    val device: String?,
    val percent: Float,
    val finished: Boolean,
)

/**
 * Progression échangée avec Drive en dehors des synchros complètes, pour commencer un livre sur
 * un téléphone et le continuer sur l'autre : envoi après une pause, réception juste avant une
 * lecture. Mis en œuvre par le moteur de synchro (seul accès à Drive), consommé par le lecteur.
 */
interface ProgressSyncRequester {
    /**
     * Avant la lecture d'un livre : relit sa position dans Drive, au plus [timeoutMs]. Retourne la
     * position adoptée si un autre appareil en a enregistré une plus récente, sinon null (position
     * locale à jour, hors ligne, délai dépassé).
     */
    suspend fun pullProgress(bookId: String, timeoutMs: Long): RemoteProgress?

    /** Après une pause, un déplacement ou en arrière-plan : envoie la progression locale sans attendre. */
    fun pushProgress(bookId: String?)
}

/** Horloge injectable pour les tests. */
fun interface Clock {
    fun now(): Long

    companion object {
        val SYSTEM = Clock { System.currentTimeMillis() }
    }
}

/**
 * Livre et piste chargés dans le lecteur : le nettoyage ne supprime jamais la piste en cours de
 * lecture, ni un livre hors _En écoute tant qu'il est ouvert dans le lecteur (aperçu, réécoute).
 */
object BookInUse {
    private val current = MutableStateFlow<String?>(null)
    private val currentTrack = MutableStateFlow<String?>(null)
    val bookId: StateFlow<String?> = current.asStateFlow()
    val trackId: StateFlow<String?> = currentTrack.asStateFlow()

    fun set(bookId: String?, trackId: String? = null) {
        current.value = bookId
        currentTrack.value = if (bookId != null) trackId else null
    }

    fun setTrack(trackId: String?) {
        if (current.value != null) currentTrack.value = trackId
    }

    fun isInUse(bookId: String): Boolean = current.value == bookId

    fun isTrackInUse(trackId: String): Boolean = currentTrack.value == trackId
}
