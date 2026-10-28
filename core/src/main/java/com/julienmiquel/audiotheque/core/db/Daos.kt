package com.julienmiquel.audiotheque.core.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import com.julienmiquel.audiotheque.core.model.BookStatus
import com.julienmiquel.audiotheque.core.model.TrackState
import kotlinx.coroutines.flow.Flow

/** Ligne de la bibliothèque : le livre et ce qu'il faut pour l'afficher sans autre requête. */
data class BookListRow(
    @Embedded val book: BookEntity,
    val presentTracks: Int,
    val percent: Float?,
    val lastListenedAt: Long?,
    val finished: Boolean?,
    val duplicateCount: Int,
)

@Dao
interface BookDao {
    @Query("SELECT * FROM books WHERE driveId = :id")
    suspend fun get(id: String): BookEntity?

    @Query("SELECT * FROM books WHERE driveId = :id")
    fun observe(id: String): Flow<BookEntity?>

    @Query("SELECT * FROM books")
    suspend fun all(): List<BookEntity>

    @Query("SELECT * FROM books WHERE parentId = :parentId")
    suspend fun byParent(parentId: String): List<BookEntity>

    @Query("SELECT * FROM books WHERE COALESCE(pendingStatus, status) = :status")
    suspend fun byEffectiveStatus(status: BookStatus): List<BookEntity>

    @Query("SELECT * FROM books WHERE COALESCE(pendingStatus, status) = :status")
    fun observeByEffectiveStatus(status: BookStatus): Flow<List<BookEntity>>

    /** Autres exemplaires : même titre, auteurs identiques ou inconnus (cf. [BookEntity.authorKey]), hors suppressions en attente. */
    @Query(
        """
        SELECT * FROM books WHERE normalizedKey = :key AND driveId != :excludeId AND trashPending = 0
          AND (:authorKey IS NULL OR authorKey IS NULL OR authorKey = :authorKey)
        """,
    )
    suspend fun duplicatesOf(key: String, authorKey: String?, excludeId: String): List<BookEntity>

    @Upsert
    suspend fun upsert(book: BookEntity)

    @Upsert
    suspend fun upsertAll(books: List<BookEntity>)

    @Update
    suspend fun update(book: BookEntity)

    @Query("DELETE FROM books WHERE driveId = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM books")
    suspend fun deleteAll()

    @Query("SELECT COUNT(*) FROM books")
    suspend fun count(): Int

    // Mises à jour ciblées : le moteur ne touche qu'aux champs confirmés par Drive, les gestes
    // qu'aux champs « en attente » ; une synchro et un geste simultanés ne s'écrasent pas.

    @Query("UPDATE books SET parentId = :parentId, status = :status, statusChangedAt = :changedAt WHERE driveId = :id")
    suspend fun updateConfirmedLocation(id: String, parentId: String, status: BookStatus, changedAt: Long)

    @Query("UPDATE books SET pendingStatus = :status, pendingParentId = :parentId, pendingSince = :since WHERE driveId = :id")
    suspend fun setPending(id: String, status: BookStatus?, parentId: String?, since: Long?)

    @Query(
        """
        UPDATE books SET name = :name, parsedTitle = :title, parsedAuthor = :author, parsedNarrator = :narrator,
          normalizedKey = CASE WHEN customTitle IS NULL OR customTitle = '' THEN :key ELSE normalizedKey END,
          authorKey = CASE WHEN customAuthor IS NULL THEN :authorKey ELSE authorKey END
        WHERE driveId = :id
        """,
    )
    suspend fun updateDriveName(id: String, name: String, title: String, author: String?, narrator: String?, key: String, authorKey: String?)

    @Query("UPDATE books SET authorKey = :authorKey WHERE driveId = :id")
    suspend fun setAuthorKey(id: String, authorKey: String?)

    @Query("UPDATE books SET modifiedAt = :modifiedAt WHERE driveId = :id")
    suspend fun updateModifiedAt(id: String, modifiedAt: Long?)

    @Query("UPDATE books SET sizeBytes = :sizeBytes WHERE driveId = :id")
    suspend fun updateSize(id: String, sizeBytes: Long)

    @Query(
        """
        UPDATE books SET sizeBytes = :sizeBytes, trackCount = :trackCount, durationMs = :durationMs,
          durationExact = :durationExact, tracksSyncedAt = :syncedAt
        WHERE driveId = :id
        """,
    )
    suspend fun updateTrackStats(id: String, sizeBytes: Long, trackCount: Int, durationMs: Long?, durationExact: Boolean, syncedAt: Long)

    @Query("UPDATE books SET durationMs = :durationMs, durationExact = :durationExact WHERE driveId = :id")
    suspend fun updateDuration(id: String, durationMs: Long?, durationExact: Boolean)

    @Query("UPDATE books SET probedAt = :at, tagNarrator = COALESCE(:narrator, tagNarrator) WHERE driveId = :id")
    suspend fun markProbed(id: String, at: Long, narrator: String?)

    @Query("UPDATE books SET tagNarrator = :narrator WHERE driveId = :id")
    suspend fun setTagNarrator(id: String, narrator: String?)

    @Query("UPDATE books SET coverPath = :path WHERE driveId = :id")
    suspend fun setCoverPath(id: String, path: String?)

    /** Pochette obtenue en ligne : enregistrée localement et, si demandé, à déposer dans le dossier Drive. */
    @Query("UPDATE books SET coverPath = :path, coverUploadPending = :uploadPending WHERE driveId = :id")
    suspend fun setCover(id: String, path: String?, uploadPending: Boolean)

    @Query("SELECT * FROM books WHERE coverUploadPending = 1")
    suspend fun withCoverToUpload(): List<BookEntity>

    @Query("UPDATE books SET coverUploadPending = :pending WHERE driveId = :id")
    suspend fun setCoverUploadPending(id: String, pending: Boolean)

    @Query("UPDATE books SET localDirName = :name WHERE driveId = :id")
    suspend fun setLocalDirName(id: String, name: String)

    @Query("SELECT COUNT(*) FROM books WHERE localDirName = :name AND driveId != :excludeId")
    suspend fun countLocalDirName(name: String, excludeId: String): Int

    @Query("UPDATE books SET tracksSyncedAt = NULL WHERE driveId IN (:ids)")
    suspend fun invalidateTracks(ids: List<String>)

    @Query("UPDATE books SET tracksSyncedAt = NULL WHERE parentId IN (:parentIds)")
    suspend fun invalidateTracksIn(parentIds: List<String>)

    // Titre ou auteur corrigés / confirmés : la recherche de pochette repart (elle cherche autre chose).
    @Query(
        """
        UPDATE books SET customTitle = :title, customAuthor = :author, customNarrator = :narrator,
          titleConfirmed = :confirmed, normalizedKey = :key, authorKey = :authorKey, enrichedAt = NULL
        WHERE driveId = :id
        """,
    )
    suspend fun updateCorrections(id: String, title: String?, author: String?, narrator: String?, confirmed: Boolean, key: String, authorKey: String?)

    @Query("UPDATE books SET hidden = :hidden WHERE driveId = :id")
    suspend fun setHidden(id: String, hidden: Boolean)

    @Query("UPDATE books SET trashPending = :pending WHERE driveId = :id")
    suspend fun setTrashPending(id: String, pending: Boolean)

    @Query("UPDATE books SET coverUrl = :coverUrl, summary = :summary, enrichedAt = :at WHERE driveId = :id")
    suspend fun setEnrichment(id: String, coverUrl: String?, summary: String?, at: Long)

    // Doublons : même titre, sauf si les deux auteurs sont connus et diffèrent (suppressions en attente exclues).

    @Query(
        """
        SELECT b.*,
          (SELECT COUNT(*) FROM tracks t WHERE t.bookId = b.driveId AND t.state = 'PRESENT') AS presentTracks,
          p.percent AS percent,
          p.lastListenedAt AS lastListenedAt,
          p.finished AS finished,
          (SELECT COUNT(*) FROM books d WHERE d.normalizedKey = b.normalizedKey AND d.trashPending = 0
             AND (d.authorKey IS NULL OR b.authorKey IS NULL OR d.authorKey = b.authorKey)) AS duplicateCount
        FROM books b
        LEFT JOIN progress p ON p.bookId = b.driveId AND p.account = :account
        """,
    )
    fun observeLibrary(account: String): Flow<List<BookListRow>>

    @Query(
        """
        SELECT b.*,
          (SELECT COUNT(*) FROM tracks t WHERE t.bookId = b.driveId AND t.state = 'PRESENT') AS presentTracks,
          p.percent AS percent,
          p.lastListenedAt AS lastListenedAt,
          p.finished AS finished,
          (SELECT COUNT(*) FROM books d WHERE d.normalizedKey = b.normalizedKey AND d.trashPending = 0
             AND (d.authorKey IS NULL OR b.authorKey IS NULL OR d.authorKey = b.authorKey)) AS duplicateCount
        FROM books b
        LEFT JOIN progress p ON p.bookId = b.driveId AND p.account = :account
        WHERE b.driveId = :id
        """,
    )
    fun observeRow(id: String, account: String): Flow<BookListRow?>
}

@Dao
interface TrackDao {
    @Query("SELECT * FROM tracks WHERE bookId = :bookId ORDER BY sortIndex")
    suspend fun forBook(bookId: String): List<TrackEntity>

    @Query("SELECT * FROM tracks WHERE bookId = :bookId ORDER BY sortIndex")
    fun observeForBook(bookId: String): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE driveId = :id")
    suspend fun get(id: String): TrackEntity?

    @Upsert
    suspend fun upsertAll(tracks: List<TrackEntity>)

    @Update
    suspend fun update(track: TrackEntity)

    @Query("DELETE FROM tracks WHERE driveId = :id")
    suspend fun delete(id: String)

    @Query("DELETE FROM tracks WHERE bookId = :bookId AND driveId NOT IN (:keepIds)")
    suspend fun deleteMissing(bookId: String, keepIds: List<String>)

    @Query("UPDATE tracks SET state = :state, localPath = :localPath, downloadedBytes = :downloadedBytes WHERE driveId = :id")
    suspend fun setLocalState(id: String, state: TrackState, localPath: String?, downloadedBytes: Long)

    @Query("UPDATE tracks SET downloadedBytes = :downloadedBytes WHERE driveId = :id")
    suspend fun setDownloadedBytes(id: String, downloadedBytes: Long)

    @Query("UPDATE tracks SET durationMs = :durationMs, durationExact = 1 WHERE driveId = :id")
    suspend fun setExactDuration(id: String, durationMs: Long)

    /** Durée estimée (débit de la première piste) ; ne remplace jamais une durée exacte. */
    @Query("UPDATE tracks SET durationMs = :durationMs WHERE driveId = :id AND durationExact = 0")
    suspend fun updateEstimatedDuration(id: String, durationMs: Long)

    /** Pistes ayant une trace locale (fichier présent ou transfert entamé). */
    @Query("SELECT * FROM tracks WHERE state != 'ABSENT' OR localPath IS NOT NULL OR downloadedBytes > 0")
    suspend fun withLocalData(): List<TrackEntity>

    /** Pistes « en cours » laissées par une synchro interrompue. */
    @Query("UPDATE tracks SET state = 'ABSENT' WHERE state = 'DOWNLOADING'")
    suspend fun resetInterruptedDownloads()

    @Query("SELECT COALESCE(SUM(sizeBytes), 0) FROM tracks WHERE state = 'PRESENT'")
    fun observeLocalBytes(): Flow<Long>

    @Query("SELECT COUNT(*) FROM tracks WHERE bookId = :bookId AND state = 'PRESENT'")
    suspend fun presentCount(bookId: String): Int

    @Query("DELETE FROM tracks")
    suspend fun deleteAll()
}

@Dao
interface ProgressDao {
    @Query("SELECT * FROM progress WHERE bookId = :bookId AND account = :account")
    suspend fun get(bookId: String, account: String): ProgressEntity?

    @Query("SELECT * FROM progress WHERE bookId = :bookId AND account = :account")
    fun observe(bookId: String, account: String): Flow<ProgressEntity?>

    @Upsert
    suspend fun upsert(progress: ProgressEntity)

    @Query("SELECT * FROM progress WHERE account = :account AND dirty = 1")
    suspend fun dirty(account: String): List<ProgressEntity>

    @Query("SELECT * FROM progress WHERE account = :account")
    suspend fun allFor(account: String): List<ProgressEntity>

    @Query("SELECT * FROM progress WHERE account = :account AND lastListenedAt IS NOT NULL ORDER BY lastListenedAt DESC LIMIT 1")
    suspend fun lastListened(account: String): ProgressEntity?

    @Query("UPDATE progress SET dirty = 0, remoteFileId = :remoteFileId, remoteSyncedAt = :syncedAt WHERE bookId = :bookId AND account = :account AND updatedAt = :updatedAt")
    suspend fun markSynced(bookId: String, account: String, updatedAt: Long, remoteFileId: String, syncedAt: Long)

    /** Rattache la progression locale (avant connexion) au compte Google. */
    @Query(
        """
        UPDATE progress SET account = :to, dirty = 1
        WHERE account = :from AND bookId NOT IN (SELECT bookId FROM progress WHERE account = :to)
        """,
    )
    suspend fun migrateAccount(from: String, to: String)

    /** Déplacement vers _Ecouté abandonné : « terminé » est retiré si le livre n'était pas fini. */
    @Query("UPDATE progress SET finished = 0, dirty = 1, updatedAt = :now WHERE bookId = :bookId AND finished = 1 AND percent < 99")
    suspend fun clearFinishedUnlessAtEnd(bookId: String, now: Long)
}

@Dao
interface BookmarkDao {
    @Query("SELECT * FROM bookmarks WHERE bookId = :bookId ORDER BY trackIndex, positionMs")
    fun observeForBook(bookId: String): Flow<List<BookmarkEntity>>

    @Insert
    suspend fun insert(bookmark: BookmarkEntity): Long

    @Update
    suspend fun update(bookmark: BookmarkEntity)

    @Query("DELETE FROM bookmarks WHERE id = :id")
    suspend fun delete(id: Long)
}

@Dao
interface SyncEventDao {
    @Insert
    suspend fun insert(event: SyncEventEntity): Long

    @Query("SELECT * FROM sync_events ORDER BY timestamp DESC, id DESC LIMIT :limit")
    fun observeRecent(limit: Int = 1000): Flow<List<SyncEventEntity>>

    @Query("SELECT * FROM sync_events WHERE bookId = :bookId ORDER BY timestamp DESC, id DESC LIMIT :limit")
    fun observeForBook(bookId: String, limit: Int = 1000): Flow<List<SyncEventEntity>>

    @Query("SELECT * FROM sync_events WHERE runId = :runId ORDER BY timestamp DESC, id DESC")
    suspend fun forRun(runId: Long): List<SyncEventEntity>

    @Query("DELETE FROM sync_events WHERE timestamp < :before")
    suspend fun purgeOlderThan(before: Long): Int

    @Query("DELETE FROM sync_events")
    suspend fun deleteAll()

    /** Livres présents dans l'historique, pour le filtre par livre. */
    @Query("SELECT DISTINCT bookId, bookTitle FROM sync_events WHERE bookId IS NOT NULL ORDER BY bookTitle")
    fun observeBooksWithEvents(): Flow<List<EventBook>>
}

data class EventBook(val bookId: String, val bookTitle: String?)

@Dao
interface SyncRunDao {
    @Insert
    suspend fun insert(run: SyncRunEntity): Long

    @Update
    suspend fun update(run: SyncRunEntity)

    @Query("SELECT * FROM sync_runs WHERE id = :id")
    suspend fun get(id: Long): SyncRunEntity?

    @Query("SELECT * FROM sync_runs ORDER BY startedAt DESC, id DESC LIMIT 1")
    fun observeLast(): Flow<SyncRunEntity?>

    @Query("SELECT * FROM sync_runs WHERE result != 'RUNNING' ORDER BY startedAt DESC, id DESC LIMIT 1")
    fun observeLastFinished(): Flow<SyncRunEntity?>

    /** Une synchro tuée (processus arrêté) reste « en cours » : on la clôt au démarrage suivant. */
    @Query("UPDATE sync_runs SET result = 'CANCELLED', endedAt = :now WHERE result = 'RUNNING'")
    suspend fun closeDangling(now: Long)

    @Query("DELETE FROM sync_runs WHERE startedAt < :before")
    suspend fun purgeOlderThan(before: Long)

    @Query("DELETE FROM sync_runs")
    suspend fun deleteAll()
}

@Dao
interface PendingOperationDao {
    @Insert
    suspend fun insert(op: PendingOperationEntity): Long

    @Update
    suspend fun update(op: PendingOperationEntity)

    @Query("DELETE FROM pending_ops WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("SELECT * FROM pending_ops WHERE id = :id")
    suspend fun get(id: Long): PendingOperationEntity?

    @Query("SELECT * FROM pending_ops ORDER BY createdAt, id")
    suspend fun all(): List<PendingOperationEntity>

    @Query("SELECT * FROM pending_ops WHERE nextAttemptAt <= :now ORDER BY createdAt, id")
    suspend fun due(now: Long): List<PendingOperationEntity>

    @Query("SELECT * FROM pending_ops WHERE bookId = :bookId ORDER BY createdAt DESC, id DESC LIMIT 1")
    suspend fun latestForBook(bookId: String): PendingOperationEntity?

    @Query("DELETE FROM pending_ops WHERE bookId = :bookId")
    suspend fun deleteForBook(bookId: String)

    /** Supprime l'opération seulement si sa cible n'a pas changé entre-temps. */
    @Query("DELETE FROM pending_ops WHERE id = :id AND toParentId = :toParentId")
    suspend fun deleteIfTarget(id: Long, toParentId: String): Int

    @Query("SELECT COUNT(*) FROM pending_ops")
    fun observeCount(): Flow<Int>

    @Query("SELECT MIN(nextAttemptAt) FROM pending_ops")
    suspend fun nextAttemptAt(): Long?

    @Query("DELETE FROM pending_ops")
    suspend fun deleteAll()
}

@Dao
interface SyncPairDao {
    @Query("SELECT * FROM sync_pairs ORDER BY position, id")
    fun observeAll(): Flow<List<SyncPairEntity>>

    @Query("SELECT * FROM sync_pairs ORDER BY position, id")
    suspend fun all(): List<SyncPairEntity>

    @Query("SELECT * FROM sync_pairs WHERE id = :id")
    suspend fun get(id: Long): SyncPairEntity?

    @Query("SELECT * FROM sync_pairs WHERE status = :status ORDER BY position, id LIMIT 1")
    suspend fun forStatus(status: BookStatus): SyncPairEntity?

    @Query("SELECT * FROM sync_pairs WHERE driveFolderId = :folderId")
    suspend fun forFolder(folderId: String): SyncPairEntity?

    @Insert
    suspend fun insertAll(pairs: List<SyncPairEntity>)

    @Insert
    suspend fun insert(pair: SyncPairEntity): Long

    @Update
    suspend fun update(pair: SyncPairEntity)

    @Query("DELETE FROM sync_pairs WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("DELETE FROM sync_pairs")
    suspend fun deleteAll()

    @Query("UPDATE sync_pairs SET lastInventoryAt = :at WHERE id = :id")
    suspend fun setLastInventory(id: Long, at: Long)

    @Transaction
    suspend fun replaceAll(pairs: List<SyncPairEntity>) {
        all().forEach { delete(it.id) }
        insertAll(pairs)
    }
}
