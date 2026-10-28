package com.julienmiquel.audiotheque.library

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.julienmiquel.audiotheque.core.db.AppDatabase
import com.julienmiquel.audiotheque.core.db.BookEntity
import com.julienmiquel.audiotheque.core.db.BookmarkEntity
import com.julienmiquel.audiotheque.core.db.ListeningSessionEntity
import com.julienmiquel.audiotheque.core.db.ProgressEntity
import com.julienmiquel.audiotheque.core.model.Accounts
import com.julienmiquel.audiotheque.core.model.AudioPreset
import com.julienmiquel.audiotheque.core.model.BookKind
import com.julienmiquel.audiotheque.core.model.BookStatus
import com.julienmiquel.audiotheque.core.parse.TextNormalizer
import com.julienmiquel.audiotheque.core.settings.SettingsRepository
import com.julienmiquel.audiotheque.core.sync.Clock
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class BackupManagerTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    private lateinit var sourceDb: AppDatabase
    private lateinit var targetDb: AppDatabase
    private lateinit var sourceSettings: SettingsRepository
    private lateinit var targetSettings: SettingsRepository

    @Before
    fun setUp() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        sourceDb = AppDatabase.inMemory(context)
        targetDb = AppDatabase.inMemory(context)
        sourceSettings = SettingsRepository.forFile(File(tempFolder.newFolder(), "source.preferences_pb"))
        targetSettings = SettingsRepository.forFile(File(tempFolder.newFolder(), "target.preferences_pb"))
    }

    @After
    fun tearDown() {
        sourceDb.close()
        targetDb.close()
    }

    @Test
    fun `exporte et restaure signets, corrections, préférences audio et sessions sans doublons ni écriture Drive`() = runTest {
        val bookSource = BookEntity(
            driveId = "drive-old-id",
            parentId = "p1",
            status = BookStatus.IN_PROGRESS,
            kind = BookKind.FOLDER,
            name = "CIVILISATIONS - Laurent BINET",
            parsedTitle = "Civilisations",
            parsedAuthor = "Laurent Binet",
            customTitle = "Civilisations (Édition intégrale)",
            customAuthor = "Laurent Binet",
            customNarrator = "Denis Podalydès",
            titleConfirmed = true,
            normalizedKey = TextNormalizer.key("Civilisations (Édition intégrale)"),
            authorKey = TextNormalizer.key("Laurent Binet"),
            localDirName = "civilisations",
            addedAt = 1_000L,
            statusChangedAt = 1_000L,
        )
        sourceDb.bookDao().upsert(bookSource)
        sourceDb.progressDao().upsert(
            ProgressEntity(
                bookId = bookSource.driveId,
                account = Accounts.LOCAL,
                trackIndex = 3,
                positionMs = 45_000L,
                percent = 37.5f,
                speed = 1.25f,
                audioPreset = AudioPreset.VOICE_BOOST.name,
                lastListenedAt = 5_000L,
                updatedAt = 5_000L,
            ),
        )
        sourceDb.bookmarkDao().insert(
            BookmarkEntity(
                bookId = bookSource.driveId,
                trackId = "t3",
                trackIndex = 3,
                positionMs = 45_000L,
                note = "Passage clé sur Atahualpa",
                createdAt = 5_000L,
            ),
        )
        sourceDb.listeningSessionDao().insert(
            ListeningSessionEntity(
                bookId = bookSource.driveId,
                account = Accounts.LOCAL,
                startedAt = 10_000L,
                endedAt = 610_000L,
                durationMs = 600_000L,
            ),
        )

        val exporter = BackupManager(sourceDb, sourceSettings, Clock { 20_000L })
        val exported = exporter.exportBackup()
        assertThat(exported.booksCount).isEqualTo(1)
        assertThat(exported.bookmarksCount).isEqualTo(1)
        assertThat(exported.sessionsCount).isEqualTo(1)

        // Sur le nouveau téléphone, le dossier a même été réimporté avec un autre driveId :
        // le rapprochement par clé normalisée retrouve quand même le livre.
        val bookTarget = BookEntity(
            driveId = "drive-new-id",
            parentId = "p2",
            status = BookStatus.IN_PROGRESS,
            kind = BookKind.FOLDER,
            name = "Civilisations (Édition intégrale) - Laurent BINET",
            parsedTitle = "Civilisations (Édition intégrale)",
            parsedAuthor = "Laurent Binet",
            normalizedKey = TextNormalizer.key("Civilisations (Édition intégrale)"),
            authorKey = TextNormalizer.key("Laurent Binet"),
            localDirName = "civilisations",
            addedAt = 2_000L,
            statusChangedAt = 2_000L,
        )
        targetDb.bookDao().upsert(bookTarget)

        val importer = BackupManager(targetDb, targetSettings, Clock { 30_000L })
        val summary1 = importer.importBackup(exported.json)
        assertThat(summary1.matchedBooks).isEqualTo(1)
        assertThat(summary1.restoredCorrections).isEqualTo(1)
        assertThat(summary1.restoredBookmarks).isEqualTo(1)
        assertThat(summary1.restoredPreferences).isEqualTo(1)
        assertThat(summary1.restoredSessions).isEqualTo(1)

        val restoredBook = targetDb.bookDao().get("drive-new-id")!!
        assertThat(restoredBook.customNarrator).isEqualTo("Denis Podalydès")
        assertThat(restoredBook.titleConfirmed).isTrue()

        val restoredProgress = targetDb.progressDao().get("drive-new-id", Accounts.LOCAL)!!
        assertThat(restoredProgress.speed).isEqualTo(1.25f)
        assertThat(restoredProgress.audioPreset).isEqualTo(AudioPreset.VOICE_BOOST.name)
        assertThat(restoredProgress.trackIndex).isEqualTo(3)

        val restoredBookmarks = targetDb.bookmarkDao().forBook("drive-new-id")
        assertThat(restoredBookmarks).hasSize(1)
        assertThat(restoredBookmarks.single().note).isEqualTo("Passage clé sur Atahualpa")

        // Aucun déplacement ou renommage Drive n'est déclenché silencieusement.
        assertThat(targetDb.pendingOperationDao().all()).isEmpty()

        // Une seconde importation du même fichier est idempotente (aucun doublon créé).
        val summary2 = importer.importBackup(exported.json)
        assertThat(summary2.restoredBookmarks).isEqualTo(0)
        assertThat(summary2.restoredSessions).isEqualTo(0)
        assertThat(targetDb.bookmarkDao().forBook("drive-new-id")).hasSize(1)
        assertThat(targetDb.listeningSessionDao().forAccount(Accounts.LOCAL)).hasSize(1)
    }
}
