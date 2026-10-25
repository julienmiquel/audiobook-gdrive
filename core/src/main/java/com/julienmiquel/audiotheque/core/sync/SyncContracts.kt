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

/** Horloge injectable pour les tests. */
fun interface Clock {
    fun now(): Long

    companion object {
        val SYSTEM = Clock { System.currentTimeMillis() }
    }
}

/**
 * Livre chargé dans le lecteur : le nettoyage ne supprime jamais ses pistes, même s'il n'est
 * plus dans _En écoute (aperçu, réécoute sans changement de statut).
 */
object BookInUse {
    private val current = MutableStateFlow<String?>(null)
    val bookId: StateFlow<String?> = current.asStateFlow()

    fun set(bookId: String?) {
        current.value = bookId
    }

    fun isInUse(bookId: String): Boolean = current.value == bookId
}
