package com.julienmiquel.audiotheque.library

import androidx.room.withTransaction
import com.julienmiquel.audiotheque.core.db.AppDatabase
import com.julienmiquel.audiotheque.core.db.BookEntity
import com.julienmiquel.audiotheque.core.db.BookListRow
import com.julienmiquel.audiotheque.core.db.BookmarkEntity
import com.julienmiquel.audiotheque.core.db.EmbeddedChapterEntity
import com.julienmiquel.audiotheque.core.db.ListeningSessionEntity
import com.julienmiquel.audiotheque.core.db.PendingOperationEntity
import com.julienmiquel.audiotheque.core.db.ProgressEntity
import com.julienmiquel.audiotheque.core.db.SyncPairEntity
import com.julienmiquel.audiotheque.core.db.TrackEntity
import com.julienmiquel.audiotheque.core.model.AudioPreset
import com.julienmiquel.audiotheque.core.model.BookKind
import com.julienmiquel.audiotheque.core.model.BookStatus
import com.julienmiquel.audiotheque.core.model.OperationType
import com.julienmiquel.audiotheque.core.model.ProgressMath
import com.julienmiquel.audiotheque.core.model.SyncTrigger
import com.julienmiquel.audiotheque.core.model.displayAuthor
import com.julienmiquel.audiotheque.core.model.displayNarrator
import com.julienmiquel.audiotheque.core.model.displayTitle
import com.julienmiquel.audiotheque.core.model.effectiveDriveName
import com.julienmiquel.audiotheque.core.model.effectiveParentId
import com.julienmiquel.audiotheque.core.model.effectiveStatus
import com.julienmiquel.audiotheque.core.model.effectiveStatusChangedAt
import com.julienmiquel.audiotheque.core.model.expectedAuthorKey
import com.julienmiquel.audiotheque.core.model.userEffectiveStatus
import com.julienmiquel.audiotheque.core.model.userEffectiveStatusChangedAt
import com.julienmiquel.audiotheque.core.parse.FolderNameParser
import com.julienmiquel.audiotheque.core.parse.SeriesInfo
import com.julienmiquel.audiotheque.core.parse.TextNormalizer
import com.julienmiquel.audiotheque.core.settings.LibrarySort
import com.julienmiquel.audiotheque.core.settings.SettingsRepository
import com.julienmiquel.audiotheque.core.settings.StatusSyncMode
import com.julienmiquel.audiotheque.core.sync.Clock
import com.julienmiquel.audiotheque.core.sync.SyncRequester
import java.util.UUID
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map

/** Un livre tel qu'affiché dans la bibliothèque. */
data class LibraryItem(
    val book: BookEntity,
    /** Statut effectif pour l'auditeur actif (geste en attente ou statut personnel compris). */
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
    /** Série et numéro de tome détectés automatiquement, le cas échéant. */
    val series: SeriesInfo? = FolderNameParser.extractSeries(title, book.effectiveDriveName),
    /** Statut du dossier Drive (hors statut personnel). */
    val driveStatus: BookStatus = book.effectiveStatus,
    /** Statut personnel de l'auditeur actif (mode familial), le cas échéant. */
    val personalStatus: BookStatus? = null,
    /** Heure du dernier changement de statut effectif (personnel ou Drive), pour le tri. */
    val statusChangedAt: Long = book.effectiveStatusChangedAt,
    /** Livre gardé intégralement hors ligne sur ce téléphone. */
    val pinnedOffline: Boolean = book.pinnedOffline,
) {
    val id: String get() = book.driveId
    val isZip: Boolean get() = book.kind == BookKind.ZIP
    val isDownloaded: Boolean get() = trackCount > 0 && presentTracks >= trackCount
}

/** Lecture de la bibliothèque (base locale) : listes par statut, recherche, filtre « Lu par ». */
@OptIn(ExperimentalCoroutinesApi::class)
class LibraryRepository(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val onProgressDirty: (String) -> Unit = {},
) {

    private val account: Flow<String> = settings.settings.map { it.accountKey }.distinctUntilChanged()

    /** Bibliothèque affichée (suppressions en attente exclues). Les écritures sans effet visible (avancement d'un téléchargement) sont ignorées. */
    fun observeLibrary(): Flow<List<LibraryItem>> =
        account.flatMapLatest { db.bookDao().observeLibrary(it) }.distinctUntilChanged().map { rows -> rows.filter { !it.book.trashPending }.map(::toItem) }

    fun observeItem(bookId: String): Flow<LibraryItem?> =
        account.flatMapLatest { db.bookDao().observeRow(bookId, it) }.map { it?.let(::toItem) }

    fun observeTracks(bookId: String): Flow<List<TrackEntity>> = db.trackDao().observeForBook(bookId)

    fun observeEmbeddedChapters(bookId: String): Flow<List<EmbeddedChapterEntity>> = db.embeddedChapterDao().observeForBook(bookId)

    fun observeProgress(bookId: String): Flow<ProgressEntity?> =
        account.flatMapLatest { db.progressDao().observe(bookId, it) }

    fun observeBookmarks(bookId: String): Flow<List<BookmarkEntity>> =
        account.flatMapLatest { db.bookmarkDao().observeForBook(bookId, it) }

    fun observeListeningSessions(): Flow<List<ListeningSessionEntity>> =
        account.flatMapLatest { db.listeningSessionDao().observeForAccount(it) }

    fun observePairs(): Flow<List<SyncPairEntity>> = db.syncPairDao().observeAll()

    fun observePendingOperations(): Flow<Int> = db.pendingOperationDao().observeCount()

    suspend fun book(bookId: String): BookEntity? = db.bookDao().get(bookId)

    suspend fun tracks(bookId: String): List<TrackEntity> = db.trackDao().forBook(bookId)

    suspend fun embeddedChapters(bookId: String): List<EmbeddedChapterEntity> = db.embeddedChapterDao().forBook(bookId)

    /** Autres exemplaires du même livre (doublons). */
    suspend fun duplicatesOf(book: BookEntity): List<BookEntity> = db.bookDao().duplicatesOf(book.normalizedKey, book.authorKey, book.driveId)

    private suspend fun markProgressDirtyForBook(bookId: String) {
        val accountKey = settings.current().accountKey
        val progressDao = db.progressDao()
        val existing = progressDao.get(bookId, accountKey)
        if (existing != null) {
            if (!existing.dirty) progressDao.upsert(existing.copy(dirty = true))
        } else {
            // updatedAt = 0L pour qu'un ajout de signet sans écoute n'écrase jamais une position distante.
            progressDao.upsert(ProgressEntity(bookId = bookId, account = accountKey, updatedAt = 0L, dirty = true))
        }
        onProgressDirty(bookId)
    }

    suspend fun addBookmark(bookId: String, track: TrackEntity, positionMs: Long, note: String, clock: Clock = Clock.SYSTEM): Long {
        val accountKey = settings.current().accountKey
        val now = clock.now()
        val id = db.bookmarkDao().insert(
            BookmarkEntity(
                bookId = bookId,
                account = accountKey,
                syncId = UUID.randomUUID().toString(),
                trackId = track.driveId,
                trackIndex = track.sortIndex,
                positionMs = positionMs,
                note = note.trim().take(MAX_NOTE_LENGTH),
                createdAt = now,
                updatedAt = now,
                deleted = false,
            ),
        )
        markProgressDirtyForBook(bookId)
        return id
    }

    suspend fun updateBookmark(bookmark: BookmarkEntity, clock: Clock = Clock.SYSTEM) {
        val now = clock.now()
        val syncId = bookmark.syncId.ifBlank { UUID.randomUUID().toString() }
        db.bookmarkDao().update(
            bookmark.copy(
                syncId = syncId,
                note = bookmark.note.take(MAX_NOTE_LENGTH),
                updatedAt = now,
            ),
        )
        markProgressDirtyForBook(bookmark.bookId)
    }

    suspend fun deleteBookmark(id: Long, clock: Clock = Clock.SYSTEM) {
        val existing = db.bookmarkDao().get(id) ?: return
        val now = clock.now()
        if (existing.syncId.isBlank()) {
            db.bookmarkDao().update(existing.copy(syncId = UUID.randomUUID().toString(), updatedAt = now, deleted = true))
        } else {
            db.bookmarkDao().softDelete(id, now)
        }
        markProgressDirtyForBook(existing.bookId)
    }

    /** Garde toutes les pistes du livre hors ligne sur ce téléphone (sans changer son statut ni son dossier Drive). */
    suspend fun setPinnedOffline(bookId: String, pinned: Boolean, sync: SyncRequester? = null) {
        db.bookDao().setPinnedOffline(bookId, pinned)
        sync?.requestSync(SyncTrigger.STATUS_CHANGE)
    }

    /** Vitesse mémorisée par livre, quand le livre n'est pas chargé dans le lecteur. */
    suspend fun setSpeed(bookId: String, speed: Float, clock: Clock = Clock.SYSTEM) {
        val account = settings.current().accountKey
        val now = clock.now()
        val dao = db.progressDao()
        val existing = dao.get(bookId, account)
        dao.upsert((existing ?: ProgressEntity(bookId = bookId, account = account, updatedAt = now)).copy(speed = speed, updatedAt = now, dirty = true))
        onProgressDirty(bookId)
    }

    /** Préréglage audio propre au livre (null = revient au préréglage commun de l'app). */
    suspend fun setBookAudioPreset(bookId: String, preset: AudioPreset?, clock: Clock = Clock.SYSTEM) {
        val account = settings.current().accountKey
        val now = clock.now()
        val dao = db.progressDao()
        val existing = dao.get(bookId, account)
        val newValue = preset?.name
        if (existing?.audioPreset == newValue) return
        dao.upsert(
            (existing ?: ProgressEntity(bookId = bookId, account = account, updatedAt = now))
                .copy(audioPreset = newValue, updatedAt = now, dirty = true),
        )
        onProgressDirty(bookId)
    }

    private fun toItem(row: BookListRow): LibraryItem {
        val b = row.book
        return LibraryItem(
            book = b,
            status = b.userEffectiveStatus(row.personalStatus),
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
            pendingSync = b.pendingStatus != null || b.pendingName != null,
            cover = b.coverPath ?: b.coverUrl,
            driveStatus = b.effectiveStatus,
            personalStatus = row.personalStatus,
            statusChangedAt = b.userEffectiveStatusChangedAt(row.personalStatus, row.personalStatusChangedAt),
            pinnedOffline = b.pinnedOffline,
        )
    }

    companion object {
        const val MAX_NOTE_LENGTH = 280

        /** Voix présentes dans la bibliothèque, pour le filtre « Lu par ». */
        fun narrators(items: List<LibraryItem>): List<String> =
            items.mapNotNull { it.narrator }.distinctBy { TextNormalizer.key(it) }.sortedBy { TextNormalizer.key(it) }

        /**
         * Filtre et tri d'un onglet. La recherche porte sur toute la bibliothèque (titre, auteur,
         * voix, série, nom du dossier) quand [query] n'est pas vide.
         */
        fun filter(
            items: List<LibraryItem>,
            status: BookStatus?,
            query: String = "",
            narrator: String? = null,
            showHidden: Boolean = false,
            includeArchives: Boolean = true,
            offlineOnly: Boolean = false,
            sort: LibrarySort = LibrarySort.DEFAULT,
        ): List<LibraryItem> {
            val q = query.trim()
            val narratorKey = narrator?.let(TextNormalizer::key)
            return items.asSequence()
                .filter { showHidden || !it.book.hidden }
                .filter { !offlineOnly || it.presentTracks > 0 }
                .filter { if (q.isNotEmpty()) includeArchives || it.status != BookStatus.ARCHIVED else status == null || it.status == status }
                .filter { narratorKey == null || (it.narrator != null && TextNormalizer.key(it.narrator) == narratorKey) }
                .filter {
                    q.isEmpty() || TextNormalizer.matches(it.title, q) || TextNormalizer.matches(it.author, q) ||
                        TextNormalizer.matches(it.narrator, q) || TextNormalizer.matches(it.series?.name, q) ||
                        TextNormalizer.matches(it.book.name, q)
                }
                .sortedWith(comparatorFor(if (q.isNotEmpty()) null else status, sort))
                .toList()
        }

        private fun comparatorFor(status: BookStatus?, sort: LibrarySort): Comparator<LibraryItem> = when (sort) {
            LibrarySort.DEFAULT -> when (status) {
                BookStatus.IN_PROGRESS -> compareByDescending<LibraryItem> { it.lastListenedAt ?: 0L }
                    .thenByDescending { it.statusChangedAt }
                BookStatus.NEW -> compareByDescending { it.book.addedAt }
                BookStatus.LATER, BookStatus.DONE -> compareByDescending { it.statusChangedAt }
                else -> compareBy<LibraryItem> { it.series?.let { s -> TextNormalizer.key(s.name) } ?: it.sortKey }
                    .thenBy { it.series?.volume ?: Int.MAX_VALUE }
                    .thenBy { it.sortKey }
            }
            LibrarySort.RECENTLY_LISTENED -> compareByDescending<LibraryItem> { it.lastListenedAt ?: 0L }
                .thenByDescending { it.statusChangedAt }
                .thenBy { it.sortKey }
            LibrarySort.ADDED_DESC -> compareByDescending<LibraryItem> { it.book.addedAt }
                .thenBy { it.sortKey }
            LibrarySort.TITLE_ASC -> compareBy<LibraryItem> { it.series?.let { s -> TextNormalizer.key(s.name) } ?: it.sortKey }
                .thenBy { it.series?.volume ?: Int.MAX_VALUE }
                .thenBy { it.sortKey }
            LibrarySort.AUTHOR_ASC -> compareBy<LibraryItem> { TextNormalizer.keyOrNull(it.author) ?: "\uFFFF" }
                .thenBy { it.series?.let { s -> TextNormalizer.key(s.name) } ?: it.sortKey }
                .thenBy { it.series?.volume ?: Int.MAX_VALUE }
                .thenBy { it.sortKey }
            LibrarySort.DURATION_ASC -> compareBy<LibraryItem> { it.durationMs ?: Long.MAX_VALUE }
                .thenBy { it.sortKey }
            LibrarySort.DURATION_DESC -> compareByDescending<LibraryItem> { it.durationMs ?: -1L }
                .thenBy { it.sortKey }
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
 * dans son état ou dossier de départ et rétablir la progression modifiée par le geste.
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
    /** Vrai si seul le statut personnel de l'auditeur a changé (mode familial, aucun déplacement Drive). */
    val personalOnly: Boolean = false,
) {
    val changed: Boolean get() = if (personalOnly) from != to else fromParentId != toParentId
}

/**
 * Gestes de statut :
 * - En mode [StatusSyncMode.SHARED_DRIVE], chaque geste s'inscrit dans la file d'opérations en base
 *   (déplacement du dossier dans Drive).
 * - En mode [StatusSyncMode.PERSONAL_FAMILY], le geste met uniquement à jour le statut personnel de
 *   l'auditeur actif dans [ProgressEntity] (synchronisé via `.audiotheque-progress-<profil>.json`),
 *   sans déplacer les dossiers Drive des autres membres de la famille.
 */
class StatusGestures(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val sync: SyncRequester,
    private val clock: Clock = Clock.SYSTEM,
    private val onProgressDirty: (String) -> Unit = {},
) {
    private val books = db.bookDao()
    private val ops = db.pendingOperationDao()

    suspend fun apply(bookId: String, gesture: Gesture): MoveResult? {
        val account = settings.current().accountKey
        val progressDao = db.progressDao()
        val previous = progressDao.get(bookId, account)
        val moved = moveTo(bookId, gesture.target) ?: return null
        val now = clock.now()
        val base = progressDao.get(bookId, account)
        var progressTouched = moved.personalOnly
        when (gesture) {
            Gesture.METTRE_DE_COTE, Gesture.PLUS_TARD -> base?.let {
                progressDao.upsert(it.copy(setAsideAtPercent = it.percent, updatedAt = now, dirty = true))
                progressTouched = true
            }
            Gesture.MARQUER_ECOUTE -> {
                progressDao.upsert(
                    (base ?: ProgressEntity(bookId = bookId, account = account, updatedAt = now))
                        .copy(finished = true, updatedAt = now, dirty = true),
                )
                progressTouched = true
            }
            // Réécouter repart du début d'un livre fini ; un livre pas fini garde sa position.
            Gesture.REECOUTER -> base?.takeIf { it.finished || it.percent >= FINISHED_PERCENT }?.let {
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
                progressTouched = true
            }
            else -> Unit
        }
        if (progressTouched) onProgressDirty(bookId)
        return moved.copy(gesture = gesture, previousProgress = previous, appliedAt = now)
    }

    /**
     * Changement de statut selon le mode actif :
     * - si [targetPairId] est fourni, déplace physiquement le dossier dans Drive ([moveDriveFolder]) ;
     * - sinon, suit [StatusSyncMode.PERSONAL_FAMILY] ou [StatusSyncMode.SHARED_DRIVE].
     */
    suspend fun moveTo(bookId: String, target: BookStatus, targetPairId: Long? = null): MoveResult? {
        val s = settings.current()
        if (targetPairId == null && s.statusSyncMode == StatusSyncMode.PERSONAL_FAMILY) {
            return movePersonal(bookId, target, s.accountKey)
        }
        return moveDriveFolderInternal(bookId, target, targetPairId, clearPersonalStatus = s.statusSyncMode == StatusSyncMode.SHARED_DRIVE)
    }

    /**
     * Déplacement explicite du dossier dans Google Drive (menu « Déplacer le dossier dans Drive… »),
     * même lorsque le mode familial est activé.
     */
    suspend fun moveDriveFolder(bookId: String, targetPairId: Long): MoveResult? {
        val s = settings.current()
        val pair = db.syncPairDao().get(targetPairId) ?: return null
        return moveDriveFolderInternal(
            bookId = bookId,
            target = pair.status,
            targetPairId = targetPairId,
            clearPersonalStatus = s.statusSyncMode == StatusSyncMode.SHARED_DRIVE,
        )
    }

    private suspend fun movePersonal(bookId: String, target: BookStatus, account: String): MoveResult? {
        val progressDao = db.progressDao()
        val result = db.withTransaction {
            val book = books.get(bookId) ?: return@withTransaction null
            if (book.trashPending || ops.latestOfType(bookId, OperationType.TRASH_BOOK) != null) return@withTransaction null
            val existing = progressDao.get(bookId, account)
            val from = book.userEffectiveStatus(existing?.personalStatus)
            val now = maxOf(clock.now(), (existing?.personalStatusChangedAt ?: 0L) + 1L)
            val updated = (existing ?: ProgressEntity(bookId = bookId, account = account, updatedAt = 0L)).copy(
                personalStatus = target,
                personalStatusChangedAt = now,
                dirty = true,
            )
            progressDao.upsert(updated)
            MoveResult(
                bookId = bookId,
                title = book.displayTitle,
                from = from,
                to = target,
                fromParentId = book.effectiveParentId,
                toParentId = book.effectiveParentId,
                previousProgress = existing,
                appliedAt = now,
                personalOnly = true,
            )
        } ?: return null
        onProgressDirty(bookId)
        if (result.changed) sync.requestSync(SyncTrigger.STATUS_CHANGE)
        return result
    }

    private suspend fun moveDriveFolderInternal(
        bookId: String,
        target: BookStatus,
        targetPairId: Long?,
        clearPersonalStatus: Boolean,
    ): MoveResult? {
        val pairs = db.syncPairDao()
        val targetPair = targetPairId?.let { pairs.get(it) } ?: pairs.forStatus(target) ?: return null
        val account = settings.current().accountKey
        var personalCleared = false
        val result = db.withTransaction {
            val book = books.get(bookId) ?: return@withTransaction null
            val existingProgress = db.progressDao().get(bookId, account)
            val from = if (clearPersonalStatus) book.userEffectiveStatus(existingProgress?.personalStatus) else book.effectiveStatus
            val fromParentId = book.effectiveParentId
            // Suppression en file : plus de geste de statut sur ce livre.
            if (book.trashPending || ops.latestOfType(bookId, OperationType.TRASH_BOOK) != null) return@withTransaction null
            val existing = ops.latestOfType(bookId, OperationType.MOVE_BOOK)
            val now = clock.now()
            if (clearPersonalStatus && existingProgress?.personalStatus != null) {
                val statusNow = maxOf(now, (existingProgress.personalStatusChangedAt ?: 0L) + 1L)
                db.progressDao().upsert(existingProgress.copy(personalStatus = null, personalStatusChangedAt = statusNow, dirty = true))
                personalCleared = true
            }
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
            MoveResult(bookId, book.displayTitle, from, targetPair.status, fromParentId, targetPair.driveFolderId, previousProgress = existingProgress, appliedAt = now)
        } ?: return null
        if (personalCleared) onProgressDirty(bookId)
        if (result.changed || personalCleared) sync.requestSync(SyncTrigger.STATUS_CHANGE)
        return result
    }

    /**
     * « Annuler » (bandeau affiché 10 s après un geste) : le livre retourne dans son état ou son
     * dossier de départ et la progression modifiée par le geste est rétablie.
     */
    suspend fun undo(result: MoveResult): MoveResult? {
        if (result.personalOnly) {
            return undoPersonal(result)
        }
        val back = db.syncPairDao().forFolder(result.fromParentId)
        val undone = if (back != null) {
            moveDriveFolderInternal(result.bookId, back.status, back.id, clearPersonalStatus = false)
        } else {
            moveDriveFolderInternal(result.bookId, result.from, null, clearPersonalStatus = false)
        }
        restoreProgress(result)
        return undone
    }

    private suspend fun undoPersonal(result: MoveResult): MoveResult? {
        val account = settings.current().accountKey
        val progressDao = db.progressDao()
        val undone = db.withTransaction {
            val book = books.get(result.bookId) ?: return@withTransaction null
            val current = progressDao.get(result.bookId, account) ?: return@withTransaction null
            val previous = result.previousProgress
            val statusNow = maxOf(clock.now(), (current.personalStatusChangedAt ?: 0L) + 1L)
            var restored = current.copy(
                personalStatus = previous?.personalStatus,
                personalStatusChangedAt = statusNow,
                dirty = true,
            )
            when (result.gesture) {
                Gesture.METTRE_DE_COTE, Gesture.PLUS_TARD -> {
                    restored = restored.copy(setAsideAtPercent = previous?.setAsideAtPercent, updatedAt = clock.now())
                }
                Gesture.MARQUER_ECOUTE -> {
                    restored = restored.copy(finished = previous?.finished ?: false, updatedAt = clock.now())
                }
                Gesture.REECOUTER -> {
                    if (previous != null && current.updatedAt == result.appliedAt) {
                        restored = restored.copy(
                            trackId = previous.trackId,
                            trackIndex = previous.trackIndex,
                            positionMs = previous.positionMs,
                            percent = previous.percent,
                            finished = previous.finished,
                            setAsideAtPercent = previous.setAsideAtPercent,
                            updatedAt = clock.now(),
                        )
                    }
                }
                else -> Unit
            }
            progressDao.upsert(restored)
            val toStatus = book.userEffectiveStatus(restored.personalStatus)
            MoveResult(
                bookId = result.bookId,
                title = book.displayTitle,
                from = result.to,
                to = toStatus,
                fromParentId = book.effectiveParentId,
                toParentId = book.effectiveParentId,
                personalOnly = true,
            )
        } ?: return null
        onProgressDirty(result.bookId)
        sync.requestSync(SyncTrigger.STATUS_CHANGE)
        return undone
    }

    private suspend fun restoreProgress(result: MoveResult) {
        val account = settings.current().accountKey
        val dao = db.progressDao()
        val current = dao.get(result.bookId, account) ?: return
        val previous = result.previousProgress
        var restored = if (previous?.personalStatus != null && current.personalStatus == null) {
            val statusNow = maxOf(clock.now(), (current.personalStatusChangedAt ?: 0L) + 1L)
            current.copy(personalStatus = previous.personalStatus, personalStatusChangedAt = statusNow)
        } else {
            current
        }
        val gesture = result.gesture
        if (gesture != null) {
            restored = when (gesture) {
                Gesture.METTRE_DE_COTE, Gesture.PLUS_TARD -> restored.copy(setAsideAtPercent = previous?.setAsideAtPercent)
                Gesture.MARQUER_ECOUTE -> restored.copy(finished = previous?.finished ?: false)
                Gesture.REECOUTER -> {
                    // Position remise seulement si rien ne l'a changée depuis (lecture lancée entre-temps).
                    if (previous == null || current.updatedAt != result.appliedAt) restored
                    else restored.copy(
                        trackId = previous.trackId,
                        trackIndex = previous.trackIndex,
                        positionMs = previous.positionMs,
                        percent = previous.percent,
                        finished = previous.finished,
                        setAsideAtPercent = previous.setAsideAtPercent,
                    )
                }
                else -> restored
            }
        }
        if (restored != current) {
            dao.upsert(restored.copy(updatedAt = clock.now(), dirty = true))
            onProgressDirty(result.bookId)
        }
    }

    // ------------------------------------------------------------------ renommage normalisé Drive

    /**
     * Renomme le dossier (ou le fichier) Drive du livre via la file d'opérations hors ligne,
     * avec possibilité d'annuler pendant 10 s.
     */
    suspend fun rename(bookId: String, newName: String): RenameResult? {
        val cleaned = FolderNameParser.sanitizeDriveName(newName)
        if (cleaned.isEmpty()) return null
        val result = db.withTransaction {
            val book = books.get(bookId) ?: return@withTransaction null
            if (book.trashPending || ops.latestOfType(bookId, OperationType.TRASH_BOOK) != null) return@withTransaction null
            val previousEffective = book.effectiveDriveName
            val existing = ops.latestOfType(bookId, OperationType.RENAME_BOOK)
            val now = clock.now()
            if (existing != null) {
                ops.update(
                    existing.copy(
                        targetName = cleaned,
                        createdAt = now,
                        attempts = 0,
                        nextAttemptAt = 0,
                        lastError = null,
                    ),
                )
                books.setPendingName(bookId, cleaned)
            } else if (cleaned == book.name) {
                books.setPendingName(bookId, null)
            } else {
                ops.insert(
                    PendingOperationEntity(
                        type = OperationType.RENAME_BOOK,
                        bookId = bookId,
                        fromParentId = book.parentId,
                        toParentId = book.parentId,
                        fromStatus = book.status,
                        toStatus = book.status,
                        createdAt = now,
                        targetName = cleaned,
                    ),
                )
                books.setPendingName(bookId, cleaned)
            }
            RenameResult(bookId = bookId, title = book.displayTitle, oldName = previousEffective, newName = cleaned)
        } ?: return null
        if (result.changed) sync.requestSync(SyncTrigger.STATUS_CHANGE)
        return result
    }

    suspend fun undoRename(result: RenameResult): RenameResult? = rename(result.bookId, result.oldName)

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
            books.setPendingName(bookId, null)
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

/** Renommage normalisé demandé : de quoi proposer « Annuler » pendant 10 s. */
data class RenameResult(
    val bookId: String,
    val title: String,
    val oldName: String,
    val newName: String,
) {
    val changed: Boolean get() = oldName != newName
}

/** Suppression d'un doublon demandée : de quoi afficher « Annuler » pendant 10 s. */
data class TrashResult(val bookId: String, val title: String, val folderName: String)

/** Corrections de titre, d'auteur et de voix, partagées avec la famille via `.audiotheque-meta.json`. */
class BookEditor(
    private val db: AppDatabase,
    private val clock: Clock = Clock.SYSTEM,
    private val onMetadataDirty: (String) -> Unit = {},
) {

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
        db.bookDao().updateCorrections(
            id = bookId,
            title = customTitle,
            author = customAuthor,
            narrator = customNarrator,
            confirmed = true,
            key = TextNormalizer.key(t),
            authorKey = TextNormalizer.keyOrNull(shownAuthor),
            updatedAt = clock.now(),
        )
        onMetadataDirty(bookId)
    }

    /** Accepte le découpage proposé tel quel. */
    suspend fun confirm(bookId: String) {
        val book = db.bookDao().get(bookId) ?: return
        db.bookDao().updateCorrections(
            id = bookId,
            title = book.customTitle,
            author = book.customAuthor,
            narrator = book.customNarrator,
            confirmed = true,
            key = TextNormalizer.key(book.displayTitle),
            authorKey = book.expectedAuthorKey,
            updatedAt = clock.now(),
        )
        onMetadataDirty(bookId)
    }

    suspend fun setHidden(bookId: String, hidden: Boolean) {
        db.bookDao().setHidden(bookId, hidden, updatedAt = clock.now())
        onMetadataDirty(bookId)
    }
}

/** Durée totale et position globale d'un livre, pour l'affichage de l'avancement. */
fun bookTimeline(tracks: List<TrackEntity>, book: BookEntity?): List<Long> = ProgressMath.durations(tracks, book?.durationMs)
