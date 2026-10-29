package com.julienmiquel.audiotheque.library

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.julienmiquel.audiotheque.core.db.AppDatabase
import com.julienmiquel.audiotheque.core.db.BookEntity
import com.julienmiquel.audiotheque.core.db.ProgressEntity
import com.julienmiquel.audiotheque.core.db.TrackEntity
import com.julienmiquel.audiotheque.core.model.BookStatus
import com.julienmiquel.audiotheque.core.model.DriveLibraryDefaults
import com.julienmiquel.audiotheque.core.model.OperationType
import com.julienmiquel.audiotheque.core.model.SyncTrigger
import com.julienmiquel.audiotheque.core.parse.TextNormalizer
import com.julienmiquel.audiotheque.core.settings.ListenerProfile
import com.julienmiquel.audiotheque.core.settings.SettingsRepository
import com.julienmiquel.audiotheque.core.settings.StatusSyncMode
import com.julienmiquel.audiotheque.core.sync.Clock
import com.julienmiquel.audiotheque.core.sync.SyncRequester
import java.io.File
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Tests unitaires du mode familial, des profils d'auditeurs, des signets isolés par profil,
 * du déplacement explicite sur Drive et de la sauvegarde v2 multi-profils.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FamilyModeGesturesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository
    private val requests = mutableListOf<SyncTrigger>()
    private val dirtyProgressBooks = mutableListOf<String>()
    private val dirtyMetadataBooks = mutableListOf<String>()
    private val sync = object : SyncRequester {
        override fun requestSync(trigger: SyncTrigger) {
            requests += trigger
        }
    }
    private var now = 2_000_000L
    private val clock = Clock { now }
    private lateinit var repo: LibraryRepository
    private lateinit var gestures: StatusGestures
    private lateinit var editor: BookEditor

    @Before
    fun setUp() = runBlocking<Unit> {
        db = AppDatabase.inMemory(ApplicationProvider.getApplicationContext())
        settings = SettingsRepository.forFile(tmp.newFile("settings.preferences_pb").also { it.delete() })
        settings.update {
            it.copy(
                accountEmail = "famille@example.com",
                accountName = "Julien",
                onboardingDone = true,
                profiles = listOf(ListenerProfile(key = "julie", displayName = "Julie")),
                activeProfileKey = "julie",
                statusSyncMode = StatusSyncMode.PERSONAL_FAMILY,
            )
        }
        db.syncPairDao().insertAll(DriveLibraryDefaults.defaultPairs())
        repo = LibraryRepository(db, settings, onProgressDirty = { dirtyProgressBooks += it })
        gestures = StatusGestures(db, settings, sync, clock, onProgressDirty = { dirtyProgressBooks += it })
        editor = BookEditor(db, clock, onMetadataDirty = { dirtyMetadataBooks += it })
    }

    @After
    fun tearDown() = db.close()

    private suspend fun insertBook(id: String, name: String, parent: String, status: BookStatus): BookEntity {
        val b = BookEntity(
            driveId = id,
            name = name,
            parentId = parent,
            status = status,
            statusChangedAt = 1_000L,
            parsedTitle = name,
            normalizedKey = TextNormalizer.key(name),
            addedAt = 1_000L,
        )
        db.bookDao().upsert(b)
        return b
    }

    private suspend fun insertTrack(bookId: String, id: String, index: Int): TrackEntity {
        val name = "%02d - Chapitre %d.mp3".format(index + 1, index + 1)
        val t = TrackEntity(
            driveId = id,
            bookId = bookId,
            name = name,
            relativePath = name,
            title = "Chapitre ${index + 1}",
            mimeType = "audio/mpeg",
            sizeBytes = 64_000L,
            sortIndex = index,
        )
        db.trackDao().upsertAll(listOf(t))
        return t
    }

    @Test
    fun `mode familial - Commencer met à jour le statut personnel sans déplacer le dossier Drive`() = runBlocking<Unit> {
        insertBook("police", "Police", DriveLibraryDefaults.NEW_ID, BookStatus.NEW)

        val moved = gestures.apply("police", Gesture.COMMENCER)!!

        assertThat(moved.personalOnly).isTrue()
        assertThat(moved.from).isEqualTo(BookStatus.NEW)
        assertThat(moved.to).isEqualTo(BookStatus.IN_PROGRESS)
        // Aucun déplacement de dossier Drive en file d'opérations !
        assertThat(db.pendingOperationDao().all()).isEmpty()
        val book = db.bookDao().get("police")!!
        assertThat(book.parentId).isEqualTo(DriveLibraryDefaults.NEW_ID)
        assertThat(book.status).isEqualTo(BookStatus.NEW)
        assertThat(book.pendingStatus).isNull()
        // Progression de Julie marquée dirty avec son statut personnel, synchro déclenchée pour télécharger les pistes.
        val julieProgress = db.progressDao().get("police", "julie")!!
        assertThat(julieProgress.personalStatus).isEqualTo(BookStatus.IN_PROGRESS)
        assertThat(julieProgress.dirty).isTrue()
        assertThat(dirtyProgressBooks).contains("police")
        assertThat(requests).containsExactly(SyncTrigger.STATUS_CHANGE)
    }

    @Test
    fun `isolation des profils - statuts, positions et signets indépendants entre Julien et Julie`() = runBlocking<Unit> {
        insertBook("sapiens", "Sapiens", DriveLibraryDefaults.NEW_ID, BookStatus.NEW)
        val t0 = insertTrack("sapiens", "t0", 0)
        val t1 = insertTrack("sapiens", "t1", 1)

        // Julie commence le livre, avance à 42 % et pose un signet.
        gestures.apply("sapiens", Gesture.COMMENCER)
        now += 5_000L
        db.progressDao().upsert(
            db.progressDao().get("sapiens", "julie")!!.copy(
                trackId = t1.driveId,
                trackIndex = 1,
                positionMs = 30_000L,
                percent = 42f,
                updatedAt = now,
            ),
        )
        val julieBookmarkId = repo.addBookmark("sapiens", t1, 30_000L, "Note de Julie", clock)

        val julieView = repo.observeLibrary().first().single()
        assertThat(julieView.status).isEqualTo(BookStatus.IN_PROGRESS)
        assertThat(julieView.driveStatus).isEqualTo(BookStatus.NEW)
        assertThat(julieView.personalStatus).isEqualTo(BookStatus.IN_PROGRESS)
        assertThat(julieView.percent).isEqualTo(42f)
        assertThat(repo.observeBookmarks("sapiens").first().map { it.note }).containsExactly("Note de Julie")

        // Bascule sur le compte principal (Julien : activeProfileKey = null -> famille@example.com).
        settings.update { it.copy(activeProfileKey = null) }

        val julienView = repo.observeLibrary().first().single()
        assertThat(julienView.status).isEqualTo(BookStatus.NEW)
        assertThat(julienView.personalStatus).isNull()
        assertThat(julienView.percent).isNull()
        assertThat(repo.observeBookmarks("sapiens").first()).isEmpty()

        // Julien ajoute son propre signet et classe le livre en « À écouter plus tard ».
        now += 5_000L
        repo.addBookmark("sapiens", t0, 12_000L, "Note de Julien", clock)
        gestures.apply("sapiens", Gesture.PLUS_TARD)

        assertThat(repo.observeLibrary().first().single().status).isEqualTo(BookStatus.LATER)
        assertThat(repo.observeBookmarks("sapiens").first().map { it.note }).containsExactly("Note de Julien")

        // Retour sur Julie : son statut IN_PROGRESS et son signet sont intacts ; suppression par pierre tombale.
        settings.update { it.copy(activeProfileKey = "julie") }
        assertThat(repo.observeLibrary().first().single().status).isEqualTo(BookStatus.IN_PROGRESS)
        now += 5_000L
        repo.deleteBookmark(julieBookmarkId, clock)
        assertThat(repo.observeBookmarks("sapiens").first()).isEmpty()
        val tombstone = db.bookmarkDao().allIncludingDeleted("sapiens", "julie").single()
        assertThat(tombstone.deleted).isTrue()
        assertThat(tombstone.syncId).isNotEmpty()
    }

    @Test
    fun `mode familial - Annuler rétablit le statut personnel et la progression précédente`() = runBlocking<Unit> {
        insertBook("civ", "Civilisations", DriveLibraryDefaults.NEW_ID, BookStatus.NEW)
        db.progressDao().upsert(
            ProgressEntity(
                bookId = "civ",
                account = "julie",
                trackIndex = 2,
                positionMs = 18_000L,
                percent = 35f,
                personalStatus = BookStatus.IN_PROGRESS,
                personalStatusChangedAt = 1_500_000L,
                updatedAt = 1_500_000L,
            ),
        )

        val marked = gestures.apply("civ", Gesture.MARQUER_ECOUTE)!!
        assertThat(marked.personalOnly).isTrue()
        assertThat(repo.observeLibrary().first().single().status).isEqualTo(BookStatus.DONE)
        assertThat(db.progressDao().get("civ", "julie")!!.finished).isTrue()

        now += 2_000L
        gestures.undo(marked)

        val restored = db.progressDao().get("civ", "julie")!!
        assertThat(restored.personalStatus).isEqualTo(BookStatus.IN_PROGRESS)
        assertThat(restored.finished).isFalse()
        assertThat(restored.positionMs).isEqualTo(18_000L)
        assertThat(repo.observeLibrary().first().single().status).isEqualTo(BookStatus.IN_PROGRESS)
        assertThat(db.pendingOperationDao().all()).isEmpty()
    }

    @Test
    fun `moveDriveFolder - déplace explicitement le dossier dans Drive même en mode familial`() = runBlocking<Unit> {
        insertBook("yoga", "Yoga", DriveLibraryDefaults.NEW_ID, BookStatus.NEW)
        val archivesPair = db.syncPairDao().forStatus(BookStatus.ARCHIVED)!!

        val moved = gestures.moveDriveFolder("yoga", archivesPair.id)!!

        assertThat(moved.personalOnly).isFalse()
        assertThat(moved.to).isEqualTo(BookStatus.ARCHIVED)
        val op = db.pendingOperationDao().all().single()
        assertThat(op.type).isEqualTo(OperationType.MOVE_BOOK)
        assertThat(op.fromParentId).isEqualTo(DriveLibraryDefaults.NEW_ID)
        assertThat(op.toParentId).isEqualTo(DriveLibraryDefaults.ARCHIVES_ID)
    }

    @Test
    fun `BookEditor et épinglage hors ligne marquent les métadonnées dirty et pinnedOffline`() = runBlocking<Unit> {
        insertBook("dune", "DUNES__LES_HERETIQUES_DE_DUNE", DriveLibraryDefaults.DONE_ID, BookStatus.DONE)

        editor.save("dune", "Les Hérétiques de Dune", "Frank Herbert", "Jean-Claude Drouot")
        repo.setPinnedOffline("dune", pinned = true, sync = sync)

        val updated = db.bookDao().get("dune")!!
        assertThat(updated.metadataDirty).isTrue()
        assertThat(updated.metadataUpdatedAt).isEqualTo(now)
        assertThat(updated.pinnedOffline).isTrue()
        assertThat(dirtyMetadataBooks).containsExactly("dune")
        assertThat(requests).containsExactly(SyncTrigger.STATUS_CHANGE)
        assertThat(repo.observeLibrary().first().single().pinnedOffline).isTrue()
    }

    @Test
    fun `sauvegarde v2 - exporte et restaure statuts personnels, signets multi-profils et épinglage`() = runBlocking<Unit> {
        val b = insertBook("civ", "CIVILISATIONS - Laurent BINET", DriveLibraryDefaults.NEW_ID, BookStatus.NEW)
        val t = insertTrack("civ", "t0", 0)
        repo.setPinnedOffline(b.driveId, pinned = true)
        gestures.apply("civ", Gesture.COMMENCER)
        repo.addBookmark("civ", t, 24_000L, "Repère de Julie", clock)
        val exportedJulie = BackupManager(db, settings, clock).exportBackup()
        assertThat(exportedJulie.bookmarksCount).isEqualTo(1)

        // Second auditeur sur le même livre : exportation dédiée à son profil actif.
        settings.update { it.copy(activeProfileKey = null) }
        gestures.apply("civ", Gesture.PLUS_TARD)
        repo.addBookmark("civ", t, 9_000L, "Repère de Julien", clock)
        val exportedJulien = BackupManager(db, settings, clock).exportBackup()
        assertThat(exportedJulien.bookmarksCount).isEqualTo(1)

        val targetDb = AppDatabase.inMemory(ApplicationProvider.getApplicationContext())
        val targetSettings = SettingsRepository.forFile(File(tmp.newFolder(), "target.preferences_pb"))
        try {
            targetSettings.update {
                it.copy(
                    accountEmail = "famille@example.com",
                    profiles = listOf(ListenerProfile("julie", "Julie")),
                    activeProfileKey = "julie",
                    statusSyncMode = StatusSyncMode.PERSONAL_FAMILY,
                )
            }
            targetDb.bookDao().upsert(
                BookEntity(
                    driveId = "civ-restored",
                    name = "CIVILISATIONS - Laurent BINET",
                    parentId = DriveLibraryDefaults.NEW_ID,
                    status = BookStatus.NEW,
                    statusChangedAt = 1_000L,
                    parsedTitle = "CIVILISATIONS - Laurent BINET",
                    normalizedKey = TextNormalizer.key("CIVILISATIONS - Laurent BINET"),
                    addedAt = 1_000L,
                ),
            )
            val importer = BackupManager(targetDb, targetSettings, Clock { now + 10_000L })
            val summaryJulie = importer.importBackup(exportedJulie.json)
            assertThat(summaryJulie.matchedBooks).isEqualTo(1)
            assertThat(summaryJulie.restoredBookmarks).isEqualTo(1)
            assertThat(summaryJulie.restoredPreferences).isEqualTo(1)

            targetSettings.update { it.copy(activeProfileKey = null) }
            val summaryJulien = importer.importBackup(exportedJulien.json)
            assertThat(summaryJulien.restoredBookmarks).isEqualTo(1)
            assertThat(summaryJulien.restoredPreferences).isEqualTo(1)

            assertThat(targetDb.bookDao().get("civ-restored")!!.pinnedOffline).isTrue()
            assertThat(targetDb.progressDao().get("civ-restored", "julie")!!.personalStatus).isEqualTo(BookStatus.IN_PROGRESS)
            assertThat(targetDb.progressDao().get("civ-restored", "famille@example.com")!!.personalStatus).isEqualTo(BookStatus.LATER)
            assertThat(targetDb.bookmarkDao().forBook("civ-restored", "julie").single().note).isEqualTo("Repère de Julie")
            assertThat(targetDb.bookmarkDao().forBook("civ-restored", "famille@example.com").single().note).isEqualTo("Repère de Julien")
        } finally {
            targetDb.close()
        }
    }
}
