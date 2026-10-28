package com.julienmiquel.audiotheque.ui.common

import com.julienmiquel.audiotheque.core.db.SyncPairEntity
import com.julienmiquel.audiotheque.core.model.BookStatus
import com.julienmiquel.audiotheque.core.model.DriveLibraryDefaults
import com.julienmiquel.audiotheque.core.model.EventResult
import com.julienmiquel.audiotheque.core.model.PairMode
import com.julienmiquel.audiotheque.core.model.SyncEventType
import com.julienmiquel.audiotheque.core.model.SyncResult
import com.julienmiquel.audiotheque.core.model.SyncTrigger
import java.util.Locale

/** Libellés français de l'interface. */
object Labels {

    /** Onglets de la bibliothèque. */
    fun statusTab(status: BookStatus): String = when (status) {
        BookStatus.IN_PROGRESS -> "En écoute"
        BookStatus.NEW -> "Nouveaux"
        BookStatus.LATER -> "Plus tard"
        BookStatus.DONE -> "Écouté"
        BookStatus.ARCHIVED -> "Archives"
        BookStatus.UNSORTED -> "Non classés"
        BookStatus.COLLECTION -> "Collections"
    }

    /** Statut d'un livre (fiche, résultats de recherche). */
    fun status(status: BookStatus): String = when (status) {
        BookStatus.IN_PROGRESS -> "En écoute"
        BookStatus.NEW -> "Nouveau"
        BookStatus.LATER -> "À écouter plus tard"
        BookStatus.DONE -> "Écouté"
        BookStatus.ARCHIVED -> "Archivé"
        BookStatus.UNSORTED -> "Non classé"
        BookStatus.COLLECTION -> "Collection"
    }

    fun mode(mode: PairMode): String = when (mode) {
        PairMode.FULL -> "Toutes les pistes"
        PairMode.METADATA -> "Métadonnées"
        PairMode.ON_OPEN -> "Métadonnées à l'ouverture"
    }

    fun modeDescription(mode: PairMode): String = when (mode) {
        PairMode.FULL -> "Toutes les pistes descendent sur le téléphone, à chaque synchro"
        PairMode.METADATA -> "Titre, auteur, durée et pochette, à chaque synchro"
        PairMode.ON_OPEN -> "Titre, auteur, durée et pochette, à l'ouverture du dossier dans l'app"
    }

    fun trigger(trigger: SyncTrigger): String = when (trigger) {
        SyncTrigger.LAUNCH -> "au lancement"
        SyncTrigger.PERIODIC -> "planifiée"
        SyncTrigger.STATUS_CHANGE -> "changement de statut"
        SyncTrigger.MANUAL -> "manuelle"
        SyncTrigger.FOLDER_OPEN -> "ouverture d'un dossier"
        SyncTrigger.PLAYBACK -> "lecture"
        SyncTrigger.WIFI -> "retour du Wi-Fi"
    }

    fun result(result: SyncResult): String = when (result) {
        SyncResult.RUNNING -> "Synchro en cours"
        SyncResult.SUCCESS -> "Terminée"
        SyncResult.PARTIAL -> "Terminée avec des erreurs"
        SyncResult.FAILED -> "Échec"
        SyncResult.CANCELLED -> "Annulée"
    }

    fun eventType(type: SyncEventType): String = when (type) {
        SyncEventType.RECEIVED -> "Réception"
        SyncEventType.LOCAL_DELETE -> "Suppression locale"
        SyncEventType.DRIVE_MOVE -> "Déplacement Drive"
        SyncEventType.STATUS_FROM_DRIVE -> "Statut mis à jour depuis Drive"
        SyncEventType.LIBRARY_CHANGE -> "Bibliothèque"
        SyncEventType.PROGRESS -> "Position reprise"
        SyncEventType.ERROR -> "Erreur"
    }

    fun eventResult(result: EventResult): String = when (result) {
        EventResult.OK -> "OK"
        EventResult.ERROR -> "Erreur"
        EventResult.CANCELLED -> "Annulé"
    }

    /**
     * Nom affiché d'une paire : « _A écouter plus tard » → « À écouter plus tard » (capitales
     * accentuées), « _meditations » → « Méditations ». Le nom du dossier Drive ne change pas.
     */
    fun pairTitle(pair: SyncPairEntity): String = when {
        pair.status == BookStatus.UNSORTED -> "Non classés (racine)"
        else -> when (pair.driveFolderId) {
            DriveLibraryDefaults.IN_PROGRESS_ID -> "En écoute"
            DriveLibraryDefaults.NEW_ID -> "Nouveaux"
            DriveLibraryDefaults.LATER_ID -> "À écouter plus tard"
            DriveLibraryDefaults.DONE_ID -> "Écouté"
            DriveLibraryDefaults.ARCHIVES_ID -> "Archives"
            DriveLibraryDefaults.MEDITATIONS_ID -> "Méditations"
            else -> pair.name.trimStart('_').replaceFirstChar { it.titlecase(Locale.FRENCH) }
        }
    }

    /** Destination d'un déplacement (« Déplacer vers… »). */
    fun destination(pair: SyncPairEntity): String = when (pair.status) {
        BookStatus.UNSORTED -> "Non classés (racine ${pair.drivePath})"
        else -> pair.name
    }

    fun skip(seconds: Int): String = "$seconds\u00A0s"

    fun hours(hours: Int): String = when {
        hours == 0 -> "Immédiatement"
        hours % 24 == 0 && hours >= 48 -> "${hours / 24}\u00A0jours"
        hours == 24 -> "24\u00A0h"
        else -> "$hours\u00A0h"
    }

    fun days(days: Int): String = if (days == 1) "1\u00A0jour" else "$days\u00A0jours"

    fun interval(hours: Int): String = if (hours == 1) "Toutes les heures" else "Toutes les $hours\u00A0h"

    fun tracks(count: Int): String = if (count <= 1) "$count piste" else "$count pistes"

    fun books(count: Int): String = if (count <= 1) "$count livre" else "$count livres"

    fun files(count: Int): String = if (count <= 1) "$count fichier" else "$count fichiers"
}
