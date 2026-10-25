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
import com.julienmiquel.audiotheque.core.model.ProgressMath
import com.julienmiquel.audiotheque.core.model.SyncTrigger
import com.julienmiquel.audiotheque.core.model.displayAuthor
import com.julienmiquel.audiotheque.core.model.displayNarrator
import com.julienmiquel.audiotheque.core.model.displayTitle
import com.julienmiquel.audiotheque.core.model.effectiveStatus
import com.julienmiquel.audiotheque.core.model.effectiveStatusChangedAt
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
) {
    val id: String get() = book.driveId
    val isZip: Boolean get() = book.kind == BookKind.ZIP
    val isDownloaded: Boolean get() = trackCount > 0 && presentTracks >= trackCount
}

/** Lecture de la bibliothèque (base locale) : listes par statut, recherche, filtre « Lu par ». */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryRepository(private val db: AppDatabase, private val settings: SettingsRepository) {

    private val account: Flow<String> = settings.settings.map { it.accountKey }.distinctUntilChanged()

    fun observeLibrary(): Flow<List<LibraryItem>> =
        account.flatMapLatest { db.bookDao().observeLibrary(it) }.map { rows -> rows.map(::toItem) }

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
    suspend fun duplicatesOf(book: BookEntity): List<BookEntity> = db.bookDao().duplicatesOf(book.normalizedKey, book.driveId)

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
            return items.asSequence()
                .filter { showHidden || !it.book.hidden }
                .filter { if (q.isNotEmpty()) includeArchives || it.status != BookStatus.ARCHIVED else status == null || it.status == status }
                .filter { narrator == null || (it.narrator != null && TextNormalizer.key(it.narrator) == TextNormalizer.key(narrator)) }
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
            else -> compareBy { TextNormalizer.key(it.title) }
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

/** Résultat d'un geste : de quoi proposer « Annuler » pendant 10 s. */
data class MoveResult(val bookId: String, val title: String, val from: BookStatus, val to: BookStatus) {
    val changed: Boolean get() = from != to
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
        val result = moveTo(bookId, gesture.target) ?: return null
        val account = settings.current().accountKey
        val progressDao = db.progressDao()
        val progress = progressDao.get(bookId, account)
        val now = clock.now()
        when (gesture) {
            Gesture.METTRE_DE_COTE, Gesture.PLUS_TARD -> progress?.let {
                progressDao.upsert(it.copy(setAsideAtPercent = it.percent, updatedAt = now, dirty = true))
            }
            Gesture.MARQUER_ECOUTE -> progressDao.upsert(
                (progress ?: ProgressEntity(bookId = bookId, account = account, updatedAt = now))
                    .copy(finished = true, updatedAt = now, dirty = true),
            )
            Gesture.REECOUTER -> progressDao.upsert(
                ProgressEntity(
                    bookId = bookId,
                    account = account,
                    speed = progress?.speed ?: 1f,
                    lastListenedAt = progress?.lastListenedAt,
                    updatedAt = now,
                    dirty = true,
                ),
            )
            else -> Unit
        }
        return result
    }

    /** Déplacement vers n'importe quel statut (fiche livre, « Annuler »). */
    suspend fun moveTo(bookId: String, target: BookStatus, targetPairId: Long? = null): MoveResult? {
        val pairs = db.syncPairDao()
        val targetPair = targetPairId?.let { pairs.get(it) } ?: pairs.forStatus(target) ?: return null
        val result = db.withTransaction {
            val book = books.get(bookId) ?: return@withTransaction null
            val from = book.effectiveStatus
            val existing = ops.latestForBook(bookId)
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
                if (targetPair.driveFolderId == book.parentId) {
                    books.setPending(bookId, null, null, null)
                } else {
                    books.setPending(bookId, targetPair.status, targetPair.driveFolderId, now)
                }
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
            MoveResult(bookId, book.displayTitle, from, targetPair.status)
        } ?: return null
        if (result.changed) sync.requestSync(SyncTrigger.STATUS_CHANGE)
        return result
    }

    /** « Annuler » (bandeau affiché 10 s après un geste). */
    suspend fun undo(result: MoveResult): MoveResult? = moveTo(result.bookId, result.from)
}

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
        db.bookDao().updateCorrections(bookId, customTitle, customAuthor, customNarrator, true, TextNormalizer.key(t))
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
        )
    }

    suspend fun setHidden(bookId: String, hidden: Boolean) = db.bookDao().setHidden(bookId, hidden)
}

/** Durée totale et position globale d'un livre, pour l'affichage de l'avancement. */
fun bookTimeline(tracks: List<TrackEntity>, book: BookEntity?): List<Long> = ProgressMath.durations(tracks, book?.durationMs)
