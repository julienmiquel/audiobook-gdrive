package com.julienmiquel.audiotheque.library

import androidx.room.withTransaction
import com.julienmiquel.audiotheque.core.db.AppDatabase
import com.julienmiquel.audiotheque.core.db.BookEntity
import com.julienmiquel.audiotheque.core.db.BookmarkEntity
import com.julienmiquel.audiotheque.core.db.ListeningSessionEntity
import com.julienmiquel.audiotheque.core.db.ProgressEntity
import com.julienmiquel.audiotheque.core.parse.TextNormalizer
import com.julienmiquel.audiotheque.core.settings.SettingsRepository
import com.julienmiquel.audiotheque.core.sync.Clock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.math.abs

@Serializable
data class BookmarkBackupEntry(
    val trackId: String,
    val trackIndex: Int,
    val positionMs: Long,
    val note: String,
    val createdAt: Long,
)

@Serializable
data class SessionBackupEntry(
    val startedAt: Long,
    val endedAt: Long,
    val durationMs: Long,
)

@Serializable
data class BookBackupEntry(
    val driveId: String,
    val normalizedKey: String,
    val authorKey: String? = null,
    val name: String = "",
    val customTitle: String? = null,
    val customAuthor: String? = null,
    val customNarrator: String? = null,
    val titleConfirmed: Boolean = false,
    val hidden: Boolean = false,
    val speed: Float? = null,
    val audioPreset: String? = null,
    val trackIndex: Int? = null,
    val positionMs: Long? = null,
    val percent: Float? = null,
    val finished: Boolean? = null,
    val lastListenedAt: Long? = null,
    val progressUpdatedAt: Long? = null,
    val bookmarks: List<BookmarkBackupEntry> = emptyList(),
    val sessions: List<SessionBackupEntry> = emptyList(),
)

@Serializable
data class AudiothequeBackup(
    val version: Int = CURRENT_VERSION,
    val exportedAt: Long,
    val account: String,
    val books: List<BookBackupEntry>,
) {
    companion object {
        const val CURRENT_VERSION = 1
    }
}

data class ExportSummary(
    val json: String,
    val booksCount: Int,
    val bookmarksCount: Int,
    val sessionsCount: Int,
)

data class ImportSummary(
    val matchedBooks: Int,
    val restoredCorrections: Int,
    val restoredBookmarks: Int,
    val restoredPreferences: Int,
    val restoredSessions: Int,
    val unmatchedBooks: Int,
)

/**
 * Sauvegarde et restauration JSON locale non destructive : signets, corrections locales de
 * titre/auteur/voix, livres masqués, préférences audio par livre (vitesse, préréglage) et
 * sessions d'écoute enregistrées. Aucune écriture n'est déclenchée vers Google Drive.
 */
class BackupManager(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val clock: Clock = Clock.SYSTEM,
) {
    private val jsonFormat = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = false
    }

    suspend fun exportBackup(): ExportSummary {
        val account = settings.current().accountKey
        val now = clock.now()
        val books = db.bookDao().all().filter { !it.trashPending }
        val progressByBook = db.progressDao().allFor(account).associateBy { it.bookId }
        val bookmarksByBook = db.bookmarkDao().all().groupBy { it.bookId }
        val sessionsByBook = db.listeningSessionDao().forAccount(account).groupBy { it.bookId }

        val knownBookIds = books.mapTo(HashSet()) { it.driveId }
        val entries = ArrayList<BookBackupEntry>(books.size)
        var totalBookmarks = 0
        var totalSessions = 0

        for (book in books) {
            val progress = progressByBook[book.driveId]
            val bookmarks = bookmarksByBook[book.driveId].orEmpty()
            val sessions = sessionsByBook[book.driveId].orEmpty()

            val hasCorrections = book.customTitle != null ||
                book.customAuthor != null ||
                book.customNarrator != null ||
                book.titleConfirmed ||
                book.hidden
            val hasProgressOrPrefs = progress != null && (
                progress.speed != 1.0f ||
                    progress.audioPreset != null ||
                    progress.positionMs > 0L ||
                    progress.trackIndex > 0 ||
                    progress.finished
                )

            if (!hasCorrections && !hasProgressOrPrefs && bookmarks.isEmpty() && sessions.isEmpty()) {
                continue
            }

            totalBookmarks += bookmarks.size
            totalSessions += sessions.size

            entries += BookBackupEntry(
                driveId = book.driveId,
                normalizedKey = book.normalizedKey,
                authorKey = book.authorKey,
                name = book.name,
                customTitle = book.customTitle,
                customAuthor = book.customAuthor,
                customNarrator = book.customNarrator,
                titleConfirmed = book.titleConfirmed,
                hidden = book.hidden,
                speed = progress?.speed?.takeIf { it != 1.0f },
                audioPreset = progress?.audioPreset,
                trackIndex = progress?.trackIndex,
                positionMs = progress?.positionMs,
                percent = progress?.percent,
                finished = progress?.finished,
                lastListenedAt = progress?.lastListenedAt,
                progressUpdatedAt = progress?.updatedAt,
                bookmarks = bookmarks.map { bm ->
                    BookmarkBackupEntry(
                        trackId = bm.trackId,
                        trackIndex = bm.trackIndex,
                        positionMs = bm.positionMs,
                        note = bm.note,
                        createdAt = bm.createdAt,
                    )
                },
                sessions = sessions.map { s ->
                    SessionBackupEntry(
                        startedAt = s.startedAt,
                        endedAt = s.endedAt,
                        durationMs = s.durationMs,
                    )
                },
            )
        }

        // Sessions rattachées à un livre absent du catalogue actuel (ex. archivé hors paires).
        for ((orphanBookId, orphanSessions) in sessionsByBook) {
            if (orphanBookId in knownBookIds || orphanSessions.isEmpty()) continue
            totalSessions += orphanSessions.size
            entries += BookBackupEntry(
                driveId = orphanBookId,
                normalizedKey = orphanBookId,
                sessions = orphanSessions.map { s ->
                    SessionBackupEntry(
                        startedAt = s.startedAt,
                        endedAt = s.endedAt,
                        durationMs = s.durationMs,
                    )
                },
            )
        }

        val payload = AudiothequeBackup(
            version = AudiothequeBackup.CURRENT_VERSION,
            exportedAt = now,
            account = account,
            books = entries,
        )
        return ExportSummary(
            json = jsonFormat.encodeToString(payload),
            booksCount = entries.size,
            bookmarksCount = totalBookmarks,
            sessionsCount = totalSessions,
        )
    }

    suspend fun importBackup(rawJson: String): ImportSummary {
        val backup = jsonFormat.decodeFromString<AudiothequeBackup>(rawJson)
        val account = settings.current().accountKey
        val now = clock.now()

        var matchedBooks = 0
        var unmatchedBooks = 0
        var restoredCorrections = 0
        var restoredBookmarks = 0
        var restoredPreferences = 0
        var restoredSessions = 0

        db.withTransaction {
            val allBooks = db.bookDao().all().filter { !it.trashPending }
            val booksById = allBooks.associateBy { it.driveId }
            val booksByKey = allBooks.groupBy { it.normalizedKey }
            val existingSessions = db.listeningSessionDao().forAccount(account)
            val sessionKeys = existingSessions
                .mapTo(HashSet()) { "${it.bookId}|${it.startedAt}" }

            for (entry in backup.books) {
                val targetBook = resolveBook(entry, booksById, booksByKey)
                val targetBookId = targetBook?.driveId ?: entry.driveId

                if (targetBook != null) {
                    matchedBooks++
                    if (restoreBookMetadata(targetBook, entry)) {
                        restoredCorrections++
                    }
                    if (restoreProgressAndPrefs(targetBook.driveId, account, entry, now)) {
                        restoredPreferences++
                    }
                    restoredBookmarks += restoreBookmarks(targetBook.driveId, entry.bookmarks)
                } else {
                    unmatchedBooks++
                }

                for (s in entry.sessions) {
                    if (s.durationMs <= 0L) continue
                    val dedupKey = "$targetBookId|${s.startedAt}"
                    if (sessionKeys.add(dedupKey)) {
                        db.listeningSessionDao().insert(
                            ListeningSessionEntity(
                                bookId = targetBookId,
                                account = account,
                                startedAt = s.startedAt,
                                endedAt = s.endedAt.coerceAtLeast(s.startedAt),
                                durationMs = s.durationMs,
                            ),
                        )
                        restoredSessions++
                    }
                }
            }
        }

        return ImportSummary(
            matchedBooks = matchedBooks,
            restoredCorrections = restoredCorrections,
            restoredBookmarks = restoredBookmarks,
            restoredPreferences = restoredPreferences,
            restoredSessions = restoredSessions,
            unmatchedBooks = unmatchedBooks,
        )
    }

    private fun resolveBook(
        entry: BookBackupEntry,
        booksById: Map<String, BookEntity>,
        booksByKey: Map<String, List<BookEntity>>,
    ): BookEntity? {
        booksById[entry.driveId]?.let { return it }
        val candidates = booksByKey[entry.normalizedKey].orEmpty()
        if (candidates.isEmpty()) return null
        if (entry.authorKey != null) {
            candidates.firstOrNull { it.authorKey == entry.authorKey }?.let { return it }
        }
        return candidates.first()
    }

    private suspend fun restoreBookMetadata(book: BookEntity, entry: BookBackupEntry): Boolean {
        var changed = false
        val newTitle = book.customTitle ?: entry.customTitle
        val newAuthor = book.customAuthor ?: entry.customAuthor
        val newNarrator = book.customNarrator ?: entry.customNarrator
        val newConfirmed = book.titleConfirmed || entry.titleConfirmed

        if (newTitle != book.customTitle ||
            newAuthor != book.customAuthor ||
            newNarrator != book.customNarrator ||
            newConfirmed != book.titleConfirmed
        ) {
            val effectiveTitle = newTitle?.takeIf { it.isNotBlank() } ?: book.parsedTitle
            val effectiveAuthor = (newAuthor ?: book.parsedAuthor)?.takeIf { it.isNotBlank() }
            db.bookDao().updateCorrections(
                id = book.driveId,
                title = newTitle,
                author = newAuthor,
                narrator = newNarrator,
                confirmed = newConfirmed,
                key = TextNormalizer.key(effectiveTitle),
                authorKey = TextNormalizer.keyOrNull(effectiveAuthor),
            )
            changed = true
        }

        if (!book.hidden && entry.hidden) {
            db.bookDao().setHidden(book.driveId, true)
            changed = true
        }
        return changed
    }

    private suspend fun restoreProgressAndPrefs(
        bookId: String,
        account: String,
        entry: BookBackupEntry,
        now: Long,
    ): Boolean {
        val hasAnyProgressData = entry.speed != null ||
            entry.audioPreset != null ||
            entry.positionMs != null ||
            entry.trackIndex != null ||
            entry.finished == true
        if (!hasAnyProgressData) return false

        val dao = db.progressDao()
        val existing = dao.get(bookId, account)
        val backupUpdatedAt = entry.progressUpdatedAt ?: now

        if (existing == null) {
            dao.upsert(
                ProgressEntity(
                    bookId = bookId,
                    account = account,
                    trackIndex = entry.trackIndex ?: 0,
                    positionMs = entry.positionMs ?: 0L,
                    percent = entry.percent ?: 0f,
                    speed = entry.speed ?: 1.0f,
                    audioPreset = entry.audioPreset,
                    finished = entry.finished ?: false,
                    lastListenedAt = entry.lastListenedAt,
                    updatedAt = backupUpdatedAt,
                    dirty = true,
                ),
            )
            return true
        }

        val updatedSpeed = if (existing.speed == 1.0f && entry.speed != null) entry.speed else existing.speed
        val updatedPreset = existing.audioPreset ?: entry.audioPreset
        val backupIsNewer = entry.progressUpdatedAt != null && entry.progressUpdatedAt > existing.updatedAt

        val merged = if (backupIsNewer) {
            existing.copy(
                trackIndex = entry.trackIndex ?: existing.trackIndex,
                positionMs = entry.positionMs ?: existing.positionMs,
                percent = entry.percent ?: existing.percent,
                speed = entry.speed ?: updatedSpeed,
                audioPreset = entry.audioPreset ?: updatedPreset,
                finished = entry.finished ?: existing.finished,
                lastListenedAt = entry.lastListenedAt ?: existing.lastListenedAt,
                updatedAt = entry.progressUpdatedAt,
                dirty = true,
            )
        } else {
            existing.copy(
                speed = updatedSpeed,
                audioPreset = updatedPreset,
                updatedAt = if (updatedSpeed != existing.speed || updatedPreset != existing.audioPreset) now else existing.updatedAt,
                dirty = existing.dirty || updatedSpeed != existing.speed || updatedPreset != existing.audioPreset,
            )
        }

        if (merged != existing) {
            dao.upsert(merged)
            return true
        }
        return false
    }

    private suspend fun restoreBookmarks(
        bookId: String,
        incoming: List<BookmarkBackupEntry>,
    ): Int {
        if (incoming.isEmpty()) return 0
        val existing = db.bookmarkDao().forBook(bookId)
        val tracksByIndex = db.trackDao().forBook(bookId).associateBy { it.sortIndex }
        var added = 0
        for (bm in incoming) {
            val duplicate = existing.any { cur ->
                cur.trackIndex == bm.trackIndex &&
                    abs(cur.positionMs - bm.positionMs) <= 1_000L &&
                    cur.note.trim() == bm.note.trim()
            }
            if (!duplicate) {
                val resolvedTrackId = tracksByIndex[bm.trackIndex]?.driveId ?: bm.trackId
                db.bookmarkDao().insert(
                    BookmarkEntity(
                        bookId = bookId,
                        trackId = resolvedTrackId,
                        trackIndex = bm.trackIndex,
                        positionMs = bm.positionMs,
                        note = bm.note.trim().take(LibraryRepository.MAX_NOTE_LENGTH),
                        createdAt = bm.createdAt,
                    ),
                )
                added++
            }
        }
        return added
    }
}
