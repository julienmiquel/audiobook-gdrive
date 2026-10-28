package com.julienmiquel.audiotheque.core.model

/**
 * Statut d'un livre : c'est le dossier Drive qui le contient (cf. spécification,
 * « Le statut d'un livre est le dossier Drive qui le contient »).
 */
enum class BookStatus {
    /** Racine /AudioBook : livres sans statut, à trier (« Non classés »). */
    UNSORTED,

    /** _Nouveaux : à écouter. */
    NEW,

    /** _En écoute : en cours, toutes les pistes téléchargées. */
    IN_PROGRESS,

    /** _A écouter plus tard : mis de côté, position gardée. */
    LATER,

    /** _Ecouté : terminé. */
    DONE,

    /** _archives : archivé, masqué par défaut. */
    ARCHIVED,

    /** Collection à part (_meditations, ou paire ajoutée par l'utilisateur). */
    COLLECTION,
}

/** Forme d'un livre dans Drive. */
enum class BookKind {
    /** Un dossier contenant des pistes mp3 (cas normal). */
    FOLDER,

    /** Une piste isolée hors dossier, traitée comme un livre d'une seule piste. */
    SINGLE_FILE,

    /** Livre livré en zip : affiché avec un badge, non lisible en v1. */
    ZIP,
}

/** État local d'une piste. */
enum class TrackState { ABSENT, DOWNLOADING, PRESENT }

/** Mode d'une paire de dossiers synchronisés. */
enum class PairMode {
    /** Réception de toutes les pistes (_En écoute). */
    FULL,

    /** Métadonnées à chaque synchro (titre, auteur, durée, pochette). */
    METADATA,

    /** Métadonnées seulement à l'ouverture du dossier dans l'app. */
    ON_OPEN,
}

/** Types d'événements de l'historique de synchro. */
enum class SyncEventType {
    /** Piste reçue (téléchargée). */
    RECEIVED,

    /** Suppression locale (nettoyage selon le statut). */
    LOCAL_DELETE,

    /** Déplacement de dossier dans Drive, à la suite d'un geste dans l'app. */
    DRIVE_MOVE,

    /** Statut mis à jour parce que le dossier a été déplacé dans Drive (Drive fait foi). */
    STATUS_FROM_DRIVE,

    /** Livre apparu ou disparu de la bibliothèque Drive. */
    LIBRARY_CHANGE,

    /** Position d'écoute reprise depuis Drive (enregistrée par un autre téléphone). */
    PROGRESS,

    /** Erreur. */
    ERROR,
}

enum class EventResult { OK, ERROR, CANCELLED }

enum class SyncResult { RUNNING, SUCCESS, PARTIAL, FAILED, CANCELLED }

/** Origine d'une synchro. */
enum class SyncTrigger {
    LAUNCH,
    PERIODIC,
    STATUS_CHANGE,
    MANUAL,
    FOLDER_OPEN,
    PLAYBACK,

    /** Retour du Wi-Fi : téléchargements reportés en mode « Wi-Fi uniquement ». */
    WIFI,
}

/** Opérations de la file (écritures Drive sur les dossiers de livres). */
enum class OperationType {
    /** Déplacer le dossier d'un livre (geste de statut). */
    MOVE_BOOK,

    /** Mettre le dossier d'un livre à la corbeille Drive (doublon supprimé par l'utilisateur ; restaurable 30 jours). */
    TRASH_BOOK,
}
