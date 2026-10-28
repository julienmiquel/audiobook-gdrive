package com.julienmiquel.audiotheque.core.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.julienmiquel.audiotheque.core.model.BookKind
import com.julienmiquel.audiotheque.core.model.BookStatus
import com.julienmiquel.audiotheque.core.model.EventResult
import com.julienmiquel.audiotheque.core.model.OperationType
import com.julienmiquel.audiotheque.core.model.PairMode
import com.julienmiquel.audiotheque.core.model.SyncEventType
import com.julienmiquel.audiotheque.core.model.SyncResult
import com.julienmiquel.audiotheque.core.model.SyncTrigger
import com.julienmiquel.audiotheque.core.model.TrackState

/**
 * Livre = un dossier Drive (ou une piste isolée / un zip), identifié par l'ID Drive qui ne
 * change pas quand le dossier est déplacé : la position survit au changement de statut.
 *
 * [status] est le statut confirmé par Drive. Un geste fait dans l'app renseigne
 * [pendingStatus] tant que le déplacement Drive n'a pas abouti : l'interface affiche le statut
 * effectif ([pendingStatus] ?: [status]) et un déplacement en échec ne modifie pas l'état
 * confirmé.
 */
@Entity(
    tableName = "books",
    indices = [Index("status"), Index("parentId"), Index("normalizedKey")],
)
data class BookEntity(
    @PrimaryKey val driveId: String,
    /** Nom brut du dossier (ou du fichier) dans Drive. */
    val name: String,
    /** Dossier parent confirmé dans Drive. */
    val parentId: String,
    val status: BookStatus,
    /** Date du dernier changement de statut confirmé. */
    val statusChangedAt: Long,
    val pendingStatus: BookStatus? = null,
    val pendingParentId: String? = null,
    val pendingSince: Long? = null,
    val kind: BookKind = BookKind.FOLDER,
    /** Découpage automatique du nom de dossier. */
    val parsedTitle: String,
    val parsedAuthor: String? = null,
    val parsedNarrator: String? = null,
    /** Corrections saisies dans la fiche livre (le dossier Drive n'est jamais renommé). */
    val customTitle: String? = null,
    val customAuthor: String? = null,
    val customNarrator: String? = null,
    /** Voix lue dans les tags ID3. */
    val tagNarrator: String? = null,
    val titleConfirmed: Boolean = false,
    /** Clé normalisée du titre affiché, pour repérer les doublons. */
    val normalizedKey: String,
    /**
     * Clé normalisée de l'auteur affiché. Deux livres de même titre sont des doublons, sauf si
     * leurs auteurs sont connus tous les deux et diffèrent : un exemplaire dont le nom de dossier
     * ne donne pas l'auteur reste un doublon probable.
     */
    val authorKey: String? = null,
    val durationMs: Long? = null,
    val durationExact: Boolean = false,
    val sizeBytes: Long = 0,
    val trackCount: Int = 0,
    /** Pochette locale (image du dossier Drive, image intégrée aux tags, ou pochette téléchargée). */
    val coverPath: String? = null,
    /** Pochette Google Books (ou Open Library). */
    val coverUrl: String? = null,
    /** Pochette téléchargée en ligne : à déposer dans le dossier Drive du livre (« cover.jpg »). */
    @ColumnInfo(defaultValue = "0")
    val coverUploadPending: Boolean = false,
    val summary: String? = null,
    /** Date d'ajout (createdTime Drive). */
    val addedAt: Long,
    val modifiedAt: Long? = null,
    /** Dernier inventaire des pistes. */
    val tracksSyncedAt: Long? = null,
    /** Dernière estimation de durée à partir des premiers Ko de la première piste. */
    val probedAt: Long? = null,
    val enrichedAt: Long? = null,
    /** « Ce n'est pas un livre » : masqué de la bibliothèque. */
    val hidden: Boolean = false,
    /** Nom du dossier local attribué (unique, même en cas de doublon de nom). */
    val localDirName: String? = null,
    /** Suppression demandée (doublon) : masqué aussitôt, dossier mis à la corbeille Drive par la file d'opérations. */
    @ColumnInfo(defaultValue = "0")
    val trashPending: Boolean = false,
    /** Renommage Drive en attente dans la file d'opérations (« Titre - AUTEUR »). */
    val pendingName: String? = null,
)

/** Piste = un fichier audio du dossier du livre. */
@Entity(
    tableName = "tracks",
    foreignKeys = [
        ForeignKey(
            entity = BookEntity::class,
            parentColumns = ["driveId"],
            childColumns = ["bookId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("bookId"), Index(value = ["bookId", "state"])],
)
data class TrackEntity(
    @PrimaryKey val driveId: String,
    val bookId: String,
    val name: String,
    /** Chemin relatif dans le dossier du livre (sous-dossiers « CD1/… » éventuels). */
    val relativePath: String,
    /** Ordre de lecture, de 0 à n-1. */
    val sortIndex: Int,
    /** Numéro d'ordre global lu dans le nom (« 091 »). */
    val number: Int? = null,
    val part: Int? = null,
    val chapter: Int? = null,
    val title: String,
    val durationMs: Long? = null,
    val durationExact: Boolean = false,
    val sizeBytes: Long,
    val md5: String? = null,
    val mimeType: String,
    val localPath: String? = null,
    val state: TrackState = TrackState.ABSENT,
    val downloadedBytes: Long = 0,
    val modifiedAt: Long? = null,
)

/**
 * Progression d'un utilisateur sur un livre. Indexée par l'ID du dossier Drive et par compte :
 * la bibliothèque est partagée, chaque compte a sa propre position.
 */
@Entity(tableName = "progress", primaryKeys = ["bookId", "account"])
data class ProgressEntity(
    val bookId: String,
    val account: String,
    val trackId: String? = null,
    val trackIndex: Int = 0,
    val positionMs: Long = 0,
    val speed: Float = 1f,
    val percent: Float = 0f,
    val lastListenedAt: Long? = null,
    val finished: Boolean = false,
    val setAsideAtPercent: Float? = null,
    val updatedAt: Long,
    /** À écrire dans le fichier de progression du dossier Drive du livre. */
    val dirty: Boolean = true,
    /** Fichier de progression dans Drive (dossier du livre), une fois connu. */
    val remoteFileId: String? = null,
    /** modifiedTime Drive du fichier tel que lu ou écrit la dernière fois. */
    val remoteSyncedAt: Long? = null,
    /** Préréglage audio propre à ce livre (null = suit le réglage commun de l'app). */
    val audioPreset: String? = null,
)

/**
 * Chapitre embarqué dans une piste unique (fichier .m4b / .m4a chapitré ou tags ID3 CHAP).
 * Supprimé automatiquement si la piste disparaît.
 */
@Entity(
    tableName = "embedded_chapters",
    primaryKeys = ["bookId", "trackId", "chapterIndex"],
    foreignKeys = [
        ForeignKey(
            entity = TrackEntity::class,
            parentColumns = ["driveId"],
            childColumns = ["trackId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("bookId"), Index("trackId")],
)
data class EmbeddedChapterEntity(
    val bookId: String,
    val trackId: String,
    val chapterIndex: Int,
    val title: String,
    val startMs: Long,
    val endMs: Long,
)

@Entity(tableName = "bookmarks", indices = [Index("bookId")])
data class BookmarkEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val bookId: String,
    val trackId: String,
    val trackIndex: Int,
    val positionMs: Long,
    val note: String,
    val createdAt: Long,
)

/** Événement de l'historique (conservé 30 jours). */
@Entity(tableName = "sync_events", indices = [Index("timestamp"), Index("bookId")])
data class SyncEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long,
    val type: SyncEventType,
    val bookId: String? = null,
    val bookTitle: String? = null,
    val path: String,
    val sizeBytes: Long? = null,
    val result: EventResult = EventResult.OK,
    val message: String? = null,
    val runId: Long? = null,
)

/** Une exécution du moteur de synchro (écran Synchro › Suivi). */
@Entity(tableName = "sync_runs", indices = [Index("startedAt")])
data class SyncRunEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val trigger: SyncTrigger,
    val startedAt: Long,
    val endedAt: Long? = null,
    val result: SyncResult = SyncResult.RUNNING,
    val filesReceived: Int = 0,
    val bytesReceived: Long = 0,
    val filesDeleted: Int = 0,
    val bytesDeleted: Long = 0,
    val moves: Int = 0,
    val statusUpdates: Int = 0,
    val errors: Int = 0,
    val fullInventory: Boolean = false,
    val message: String? = null,
)

/** File d'opérations : écritures Drive (déplacement, renommage ou corbeille) à rejouer hors ligne. */
@Entity(tableName = "pending_ops", indices = [Index("bookId")])
data class PendingOperationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val type: OperationType = OperationType.MOVE_BOOK,
    val bookId: String,
    val fromParentId: String,
    val toParentId: String,
    val fromStatus: BookStatus,
    val toStatus: BookStatus,
    val createdAt: Long,
    val attempts: Int = 0,
    val nextAttemptAt: Long = 0,
    val lastError: String? = null,
    val targetName: String? = null,
)

/** Paire de dossiers synchronisés : une par statut par défaut, sans limite. */
@Entity(tableName = "sync_pairs", indices = [Index(value = ["driveFolderId"], unique = true)])
data class SyncPairEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val status: BookStatus,
    val name: String,
    val driveFolderId: String,
    val drivePath: String,
    val mode: PairMode,
    val enabled: Boolean = true,
    val position: Int,
    val lastInventoryAt: Long? = null,
)
