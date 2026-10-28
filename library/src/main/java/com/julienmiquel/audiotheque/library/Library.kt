package com.julienmiquel.audiotheque.library

import androidx.room.withTransaction
import com.julienmiquel.audiotheque.core.db.AppDatabase
import com.julienmiquel.audiotheque.core.db.BookEntity
import com.julienmiquel.audiotheque.core.db.BookListRow
import com.julienmiquel.audiotheque.core.db.BookmarkEntity
import com.julienmiquel.audiotheque.core.db.PendingOperationEntity
import com.julienmiquel.audiotheque.core.db.ProgressEntity
import com.julienmiquel.audiotheque.core.db.SyncPairEntity
import com.julienmiquel.audiotheque.core.db.TrackEntity
import com.julienmiquel.audiotheque.core.model.BookKind
import com.julienmiquel.audiotheque.core.model.BookStatus
import com.julienmiquel.audiotheque.core.model.OperationType
import com.julienmiquel.audiotheque.core.model.ProgressMath
import com.julienmiquel.audiotheque.core.model.SyncTrigger
import com.julienmiquel.audiotheque.core.model.displayAuthor
import com.julienmiquel.audiotheque.core.model.displayNarrator
import com.julienmiquel.audiotheque.core.model.displayTitle
import com.julienmiquel.audiotheque.core.model.effectiveParentId
import com.julienmiquel.audiotheque.core.model.effectiveStatus
import com.julienmiquel.audiotheque.core.model.effectiveStatusChangedAt
import com.julienmiquel.audiotheque.core.model.expectedAuthorKey
import com.julienmiquel.audiotheque.core.parse.TextNormalizer
import com.julienmiquel.audiotheque.core.settings.SettingsRepository
import com.julienmiquel.audiotheque.core.sync.Clock
import com.julienmiquel.audiotheque.core.sync.SyncRequester
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/** Un livre tel qu'affiché dans la bibliothèque. */
data class LibraryItem(
    val book: BookEntity,
    /** Statut effectif (geste en attente compris). */
    val status: BookStatus,
    val title: String,
    val author: String?,
    val narrator: String?,
    val durationMs: Long?,
    val durationExact: Boolean,
    val sizeBytes: Long,
    val trackCount: Int,
    val presentTracks: Int,
    val percent: Float?,
    val lastListenedAt: Long?,
    val finished: Boolean,
    val isDuplicate: Boolean,
    /** Déplacement Drive en attente (hors ligne ou en cours). */
    val pendingSync: Boolean,
    /** Pochette : fichier local ou URL Google Books. */
    val cover: String?,
    /** Clé de tri alphabétique, calculée une fois. */
    val sortKey: String = TextNormalizer.key(title),
) {
    val id: String get() = book.driveId
    val isZip: Boolean get() = book.kind == BookKind.ZIP
    val isDownloaded: Boolean get() = trackCount > 0 && presentTracks >= trackCount
}

/** Lecture de la bibliothèque (base locale) : listes par statut, recherche, filtre « Lu par ». */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryRepository(private val db: AppDatabase, private val settings: SettingsRepository) {

    private val account: Flow<String> = settings.settings.map { it.accountKey }.distinctUntilChanged()

    /** Bibliothèque affichée (suppressions en attente exclues). Les écritures sans effet visible (avancement d'un téléchargement) sont ignorées. */
    fun observeLibrary(): Flow<List<LibraryItem>> =
        account.flatMapLatest { db.bookDao().observeLibrary(it) }.distinctUntilChanged().map { rows -> rows.filter { !it.book.trashPending }.map(::toItem) }

    fun observeItem(bookId: String): Flow<LibraryItem?> =
        account.flatMapLatest { db.bookDao().observeRow(bookId, it) }.map { it?.let(::toItem) }

    fun observeTracks(bookId: String): Flow<List<TrackEntity>> = db.trackDao().observeForBook(bookId)

    fun observeProgress(bookId: String): Flow<ProgressEntity?> =
        account.flatMapLatest { db.progressDao().observe(bookId, it) }

    fun observeBookmarks(bookId: String): Flow<List<BookmarkEntity>> = db.bookmarkDao().observeForBook(bookId)

    fun observePairs(): Flow<List<SyncPairEntity>> = db.syncPairDao().observeAll()

    fun observePendingOperations(): Flow<Int> = db.pendingOperationDao().observeCount()

    suspend fun book(bookId: String): BookEntity? = db.bookDao().get(bookId)

    suspend fun tracks(bookId: String): List<TrackEntity> = db.trackDao().forBook(bookId)

    /** Autres exemplaires du même livre (doublons). */
    suspend fun duplicatesOf(book: BookEntity): List<BookEntity> = db.bookDao().duplicatesOf(book.normalizedKey, book.authorKey, book.driveId)

    suspend fun addBookmark(bookId: String, track: TrackEntity, positionMs: Long, note: String, clock: Clock = Clock.SYSTEM): Long =
        db.bookmarkDao().insert(
            BookmarkEntity(
                bookId = bookId,
                trackId = track.driveId,
                trackIndex = track.sortIndex,
                positionMs = positionMs,
                note = note.trim().take(MAX_NOTE_LENGTH),
                createdAt = clock.now(),
            ),
        )

    suspend fun updateBookmark(bookmark: BookmarkEntity) = db.bookmarkDao().update(bookmark.copy(note = bookmark.note.take(MAX_NOTE_LENGTH)))

    suspend fun deleteBookmark(id: Long) = db.bookmarkDao().delete(id)

    /** Vitesse mémorisée par livre, quand le livre n'est pas chargé dans le lecteur. */
    suspend fun setSpeed(bookId: String, speed: Float, clock: Clock = Clock.SYSTEM) {
        val account = settings.current().accountKey
        val now = clock.now()
        val dao = db.progressDao()
        val existing = dao.get(bookId, account)
        dao.upsert((existing ?: ProgressEntity(bookId = bookId, account = account, updatedAt = now)).copy(speed = speed, updatedAt = now, dirty = true))
    }

    private fun toItem(row: BookListRow): LibraryItem {
        val b = row.book
        return LibraryItem(
            book = b,
            status = b.effectiveStatus,
            title = b.displayTitle,
            author = b.displayAuthor,
            narrator = b.displayNarrator,
            durationMs = b.durationMs,
            durationExact = b.durationExact,
            sizeBytes = b.sizeBytes,
            trackCount = b.trackCount,
            presentTracks = row.presentTracks,
            percent = row.percent,
            lastListenedAt = row.lastListenedAt,
            finished = row.finished == true,
            isDuplicate = row.duplicateCount > 1,
            pendingSync = b.pendingStatus != null,
            cover = b.coverPath ?: b.coverUrl,
        )
    }

    companion object {
        const val MAX_NOTE_LENGTH = 280

        /** Voix présentes dans la bibliothèque, pour le filtre « Lu par ». */
        fun narrators(items: List<LibraryItem>): List<String> =
            items.mapNotNull { it.narrator }.distinctBy { TextNormalizer.key(it) }.sortedBy { TextNormalizer.key(it) }

        /**
         * Filtre et tri d'un onglet. La recherche porte sur toute la bibliothèque (titre, auteur,
         * voix, nom du dossier) quand [query] n'est pas vide.
         */
        fun filter(
            items: List<LibraryItem>,
            status: BookStatus?,
            query: String = "",
            narrator: String? = null,
            showHidden: Boolean = false,
            includeArchives: Boolean = true,
        ): List<LibraryItem> {
            val q = query.trim()
            val narratorKey = narrator?.let(TextNormalizer::key)
            return items.asSequence()
                .filter { showHidden || !it.book.hidden }
                .filter { if (q.isNotEmpty()) includeArchives || it.status != BookStatus.ARCHIVED else status == null || it.status == status }
                .filter { narratorKey == null || (it.narrator != null && TextNormalizer.key(it.narrator) == narratorKey) }
                .filter {
                    q.isEmpty() || TextNormalizer.matches(it.title, q) || TextNormalizer.matches(it.author, q) ||
                        TextNormalizer.matches(it.narrator, q) || TextNormalizer.matches(it.book.name, q)
                }
                .sortedWith(comparatorFor(if (q.isNotEmpty()) null else status))
                .toList()
        }

        private fun comparatorFor(status: BookStatus?): Comparator<LibraryItem> = when (status) {
            BookStatus.IN_PROGRESS -> compareByDescending<LibraryItem> { it.lastListenedAt ?: 0L }
                .thenByDescending { it.book.effectiveStatusChangedAt }
            BookStatus.NEW -> compareByDescending { it.book.addedAt }
            BookStatus.LATER, BookStatus.DONE -> compareByDescending { it.book.effectiveStatusChangedAt }
            else -> compareBy { it.sortKey }
        }
    }
}

/** Gestes du cycle de vie d'un livre (6 statuts, 7 gestes) et autres déplacements. */
enum class Gesture(val label: String, val target: BookStatus) {
    CLASSER("Classer", BookStatus.NEW),
    COMMENCER("Commencer", BookStatus.IN_PROGRESS),
    PLUS_TARD("Plus tard", BookStatus.LATER),
    METTRE_DE_COTE("Mettre de côté", BookStatus.LATER),
    REPRENDRE("Reprendre", BookStatus.IN_PROGRESS),
    MARQUER_ECOUTE("Marquer écouté", BookStatus.DONE),
    ARCHIVER("Archiver", BookStatus.ARCHIVED),
    REECOUTER("Réécouter", BookStatus.IN_PROGRESS),
    ;

    companion object {
        /** Gestes proposés selon le statut (fiche livre, liste). */
        fun forStatus(status: BookStatus): List<Gesture> = when (status) {
            BookStatus.UNSORTED -> listOf(CLASSER, COMMENCER)
            BookStatus.NEW -> listOf(COMMENCER, PLUS_TARD)
            BookStatus.IN_PROGRESS -> listOf(METTRE_DE_COTE, MARQUER_ECOUTE)
            BookStatus.LATER -> listOf(REPRENDRE, MARQUER_ECOUTE)
            BookStatus.DONE -> listOf(REECOUTER, ARCHIVER)
            BookStatus.ARCHIVED -> listOf(REECOUTER)
            BookStatus.COLLECTION -> emptyList()
        }
    }
}

/**
 * Résultat d'un geste : de quoi proposer « Annuler » pendant 10 s, c'est-à-dire remettre le livre
 * dans son dossier de départ et rétablir la progression modifiée par le geste.
 */
data class MoveResult(
    val bookId: String,
    val title: String,
    val from: BookStatus,
    val to: BookStatus,
    /** Dossier Drive (effectif) avant le geste : « Annuler » y remet le livre. */
    val fromParentId: String,
    val toParentId: String,
    /** Geste à l'origine du déplacement (null : « Déplacer vers… » ou « Annuler »). */
    val gesture: Gesture? = null,
    /** Progression avant le geste, rétablie par « Annuler ». */
    val previousProgress: ProgressEntity? = null,
    /** Heure du geste : une position n'est rétablie que si rien ne l'a modifiée depuis. */
    val appliedAt: Long = 0,
) {
    val changed: Boolean get() = fromParentId != toParentId
}

/**
 * Gestes de statut : chaque geste s'inscrit dans la file d'opérations en base (déplacement du
 * dossier dans Drive), le statut affiché change aussitôt, et le moteur est sollicité. Fonctionne
 * hors ligne : le déplacement sera rejoué au retour du réseau.
 */
class StatusGestures(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val sync: SyncRequester,
    private val clock: Clock = Clock.SYSTEM,
) {
    private val books = db.bookDao()
    private val ops = db.pendingOperationDao()

    suspend fun apply(bookId: String, gesture: Gesture): MoveResult? {
        val account = settings.current().accountKey
        val progressDao = db.progressDao()
        val previous = progressDao.get(bookId, account)
        val moved = moveTo(bookId, gesture.target) ?: return null
        val now = clock.now()
        when (gesture) {
            Gesture.METTRE_DE_COTE, Gesture.PLUS_TARD -> previous?.let {
                progressDao.upsert(it.copy(setAsideAtPercent = it.percent, updatedAt = now, dirty = true))
            }
            Gesture.MARQUER_ECOUTE -> progressDao.upsert(
                (previous ?: ProgressEntity(bookId = bookId, account = account, updatedAt = now))
                    .copy(finished = true, updatedAt = now, dirty = true),
            )
            // Réécouter repart du début d'un livre fini ; un livre pas fini garde sa position.
            Gesture.REECOUTER -> previous?.takeIf { it.finished || it.percent >= FINISHED_PERCENT }?.let {
                progressDao.upsert(
                    it.copy(
                        trackId = null,
                        trackIndex = 0,
                        positionMs = 0,
                        percent = 0f,
                        finished = false,
                        setAsideAtPercent = null,
                        updatedAt = now,
                        dirty = true,
                    ),
                )
            }
            else -> Unit
        }
        return moved.copy(gesture = gesture, previousProgress = previous, appliedAt = now)
    }

    /** Déplacement vers n'importe quel statut (fiche livre, « Annuler »). */
    suspend fun moveTo(bookId: String, target: BookStatus, targetPairId: Long? = null): MoveResult? {
        val pairs = db.syncPairDao()
        val targetPair = targetPairId?.let { pairs.get(it) } ?: pairs.forStatus(target) ?: return null
        val result = db.withTransaction {
            val book = books.get(bookId) ?: return@withTransaction null
            val from = book.effectiveStatus
            val fromParentId = book.effectiveParentId
            val existing = ops.latestForBook(bookId)
            // Suppression en file : plus de geste de statut sur ce livre.
            if (existing?.type == OperationType.TRASH_BOOK || book.trashPending) return@withTransaction null
            val now = clock.now()
            if (existing != null) {
                // Un déplacement est déjà en file : on change sa cible (ou on revient au départ).
                ops.update(
                    existing.copy(
                        toParentId = targetPair.driveFolderId,
                        toStatus = targetPair.status,
                        createdAt = now,
                        attempts = 0,
                        nextAttemptAt = 0,
                        lastError = null,
                    ),
                )
                // Retour au dossier confirmé : le statut reste « en attente » jusqu'à ce que le moteur
                // ait vérifié Drive (le déplacement aller est peut-être en cours) ; rien n'est nettoyé
                // d'ici là et l'ordre de la liste ne change pas.
                val back = targetPair.driveFolderId == book.parentId
                books.setPending(bookId, targetPair.status, targetPair.driveFolderId, if (back) book.statusChangedAt else now)
            } else if (targetPair.driveFolderId == book.parentId) {
                books.setPending(bookId, null, null, null)
            } else {
                ops.insert(
                    PendingOperationEntity(
                        bookId = bookId,
                        fromParentId = book.parentId,
                        toParentId = targetPair.driveFolderId,
                        fromStatus = book.status,
                        toStatus = targetPair.status,
                        createdAt = now,
                    ),
                )
                books.setPending(bookId, targetPair.status, targetPair.driveFolderId, now)
            }
            MoveResult(bookId, book.displayTitle, from, targetPair.status, fromParentId, targetPair.driveFolderId)
        } ?: return null
        if (result.changed) sync.requestSync(SyncTrigger.STATUS_CHANGE)
        return result
    }

    /**
     * « Annuler » (bandeau affiché 10 s après un geste) : le livre retourne dans son dossier de
     * départ (pas seulement le premier dossier du même statut) et la progression modifiée par le
     * geste est rétablie.
     */
    suspend fun undo(result: MoveResult): MoveResult? {
        val back = db.syncPairDao().forFolder(result.fromParentId)
        val undone = if (back != null) moveTo(result.bookId, back.status, back.id) else moveTo(result.bookId, result.from)
        restoreProgress(result)
        return undone
    }

    private suspend fun restoreProgress(result: MoveResult) {
        val gesture = result.gesture ?: return
        val dao = db.progressDao()
        val current = dao.get(result.bookId, settings.current().accountKey) ?: return
        val previous = result.previousProgress
        val restored = when (gesture) {
            Gesture.METTRE_DE_COTE, Gesture.PLUS_TARD -> current.copy(setAsideAtPercent = previous?.setAsideAtPercent)
            Gesture.MARQUER_ECOUTE -> current.copy(finished = previous?.finished ?: false)
            Gesture.REECOUTER -> {
                // Position remise seulement si rien ne l'a changée depuis (lecture lancée entre-temps).
                if (previous == null || current.updatedAt != result.appliedAt) return
                current.copy(
                    trackId = previous.trackId,
                    trackIndex = previous.trackIndex,
                    positionMs = previous.positionMs,
                    percent = previous.percent,
                    finished = previous.finished,
                    setAsideAtPercent = previous.setAsideAtPercent,
                )
            }
            else -> return
        }
        if (restored != current) dao.upsert(restored.copy(updatedAt = clock.now(), dirty = true))
    }

    // ------------------------------------------------------------------ doublons

    /**
     * Supprime un exemplaire en double : son dossier part à la **corbeille Drive** (restaurable
     * 30 jours depuis Drive, jamais de suppression définitive) par la file d'opérations, donc
     * aussi hors ligne. Le livre disparaît aussitôt de la bibliothèque ; ses fichiers locaux sont
     * supprimés quand Drive a confirmé. Refusé pour le livre chargé dans le lecteur.
     */
    suspend fun trash(bookId: String, isInUse: Boolean = false): TrashResult? {
        if (isInUse) return null
        return db.withTransaction {
            val book = books.get(bookId) ?: return@withTransaction null
            if (ops.latestForBook(bookId)?.type == OperationType.TRASH_BOOK) {
                return@withTransaction TrashResult(bookId, book.displayTitle, book.name)
            }
            // Un déplacement encore en file n'a plus de sens : la corbeille le remplace.
            ops.deleteForBook(bookId)
            ops.insert(
                PendingOperationEntity(
                    type = OperationType.TRASH_BOOK,
                    bookId = bookId,
                    fromParentId = book.parentId,
                    toParentId = book.parentId,
                    fromStatus = book.status,
                    toStatus = book.status,
                    createdAt = clock.now(),
                ),
            )
            books.setPending(bookId, null, null, null)
            books.setTrashPending(bookId, true)
            TrashResult(bookId, book.displayTitle, book.name)
        }?.also { sync.requestSync(SyncTrigger.STATUS_CHANGE) }
    }

    /**
     * « Annuler » après une suppression : possible tant que la file ne l'a pas exécutée. Retourne
     * faux si le dossier est déjà à la corbeille Drive (restaurable depuis Drive).
     */
    suspend fun undoTrash(bookId: String): Boolean = db.withTransaction {
        val book = books.get(bookId) ?: return@withTransaction false
        if (!book.trashPending) return@withTransaction true
        val op = ops.latestForBook(bookId)
        if (op != null && op.type == OperationType.TRASH_BOOK) ops.delete(op.id)
        books.setTrashPending(bookId, false)
        true
    }

    companion object {
        /** Au-delà, un livre est considéré comme fini (Réécouter repart du début). */
        const val FINISHED_PERCENT = 99f
    }
}

/** Suppression d'un doublon demandée : de quoi afficher « Annuler » pendant 10 s. */
data class TrashResult(val bookId: String, val title: String, val folderName: String)

/** Corrections de titre, d'auteur et de voix, stockées par l'app (le dossier n'est pas renommé). */
class BookEditor(private val db: AppDatabase) {

    suspend fun save(bookId: String, title: String, author: String, narrator: String) {
        val book = db.bookDao().get(bookId) ?: return
        val t = title.trim().ifEmpty { book.parsedTitle }
        val a = author.trim()
        val n = narrator.trim()
        val customTitle = t.takeIf { it != book.parsedTitle }
        val customAuthor = when {
            a == (book.parsedAuthor ?: "") -> null
            else -> a
        }
        val automaticNarrator = book.tagNarrator ?: book.parsedNarrator ?: ""
        val customNarrator = if (n == automaticNarrator) null else n
        val shownAuthor = customAuthor ?: book.parsedAuthor
        db.bookDao().updateCorrections(bookId, customTitle, customAuthor, customNarrator, true, TextNormalizer.key(t), TextNormalizer.keyOrNull(shownAuthor))
    }

    /** Accepte le découpage proposé tel quel. */
    suspend fun confirm(bookId: String) {
        val book = db.bookDao().get(bookId) ?: return
        db.bookDao().updateCorrections(
            bookId,
            book.customTitle,
            book.customAuthor,
            book.customNarrator,
            true,
            TextNormalizer.key(book.displayTitle),
            book.expectedAuthorKey,
        )
    }

    suspend fun setHidden(bookId: String, hidden: Boolean) = db.bookDao().setHidden(bookId, hidden)
}

/** Durée totale et position globale d'un livre, pour l'affichage de l'avancement. */
fun bookTimeline(tracks: List<TrackEntity>, book: BookEntity?): List<Long> = ProgressMath.durations(tracks, book?.durationMs)
