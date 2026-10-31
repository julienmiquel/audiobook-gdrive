package com.julienmiquel.audiotheque.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.julienmiquel.audiotheque.core.db.AppDatabase
import com.julienmiquel.audiotheque.core.db.BookEntity
import com.julienmiquel.audiotheque.core.db.BookmarkEntity
import com.julienmiquel.audiotheque.core.db.ProgressEntity
import com.julienmiquel.audiotheque.core.model.BookStatus
import com.julienmiquel.audiotheque.core.model.DriveLibraryDefaults as D
import com.julienmiquel.audiotheque.core.model.SyncResult
import com.julienmiquel.audiotheque.core.model.SyncTrigger
import com.julienmiquel.audiotheque.core.model.TrackState
import com.julienmiquel.audiotheque.core.model.displayTitle
import com.julienmiquel.audiotheque.core.model.userEffectiveStatus
import com.julienmiquel.audiotheque.core.parse.Mp3Probe
import com.julienmiquel.audiotheque.core.parse.TextNormalizer
import com.julienmiquel.audiotheque.core.settings.ListenerProfile
import com.julienmiquel.audiotheque.core.settings.SettingsRepository
import com.julienmiquel.audiotheque.core.settings.StatusSyncMode
import com.julienmiquel.audiotheque.core.sync.Clock
import com.julienmiquel.audiotheque.sync.engine.AudioFileInfo
import com.julienmiquel.audiotheque.sync.engine.AudioFileInspector
import com.julienmiquel.audiotheque.sync.engine.LocalStorage
import com.julienmiquel.audiotheque.sync.engine.MetadataJson
import com.julienmiquel.audiotheque.sync.engine.NetworkStatus
import com.julienmiquel.audiotheque.sync.engine.ProgressJson
import com.julienmiquel.audiotheque.sync.engine.SyncEngine
import com.julienmiquel.audiotheque.sync.testing.FakeDriveService
import java.io.File
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
 * Tests unitaires de la synchronisation multi-utilisateurs et familiale sur Google Drive :
 * compatibilité ascendante ProgressJson v2 -> v3, fusion élément par élément (position + statut
 * personnel + signets avec pierres tombales), téléchargement sans déplacer les dossiers Drive des
 * autres membres de la famille, découverte automatique des profils distants, partage familial des
 * corrections de métadonnées (.audiotheque-meta.json) et protection hors ligne (« Garder sur ce téléphone »).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MultiUserSyncTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val fixtures = File("../testdata/audio")
    private lateinit var context: Context
    private lateinit var db1: AppDatabase
    private lateinit var db2: AppDatabase
    private lateinit var settings1: SettingsRepository
    private lateinit var settings2: SettingsRepository
    private lateinit var fake: FakeDriveService
    private lateinit var engine1: SyncEngine
    private lateinit var engine2: SyncEngine
    private lateinit var root1: File
    private lateinit var root2: File
    private var now = 1_850_000_000_000L
    private val clock = Clock { now }

    private val inspector = AudioFileInspector { file ->
        val bytes = file.readBytes()
        val info = Mp3Probe.parse(bytes.copyOf(minOf(bytes.size, Mp3Probe.MAX_TAG_BYTES)))
        AudioFileInfo(info.durationMs(bytes.size.toLong()), info.tags?.narrator(), info.tags?.picture)
    }

    private val network = object : NetworkStatus {
        override fun isConnected() = true
        override fun isUnmetered() = true
    }

    @Before
    fun setUp() = runBlocking<Unit> {
        check(File(fixtures, "civilisations_01.mp3").exists()) { "Fichiers de test absents : ${fixtures.absolutePath}" }
        context = ApplicationProvider.getApplicationContext()
        db1 = AppDatabase.inMemory(context)
        db2 = AppDatabase.inMemory(context)
        settings1 = SettingsRepository.forFile(File(tmp.newFolder(), "settings1.preferences_pb"))
        settings2 = SettingsRepository.forFile(File(tmp.newFolder(), "settings2.preferences_pb"))
        settings1.update { it.copy(accountEmail = FakeDriveService.ACCOUNT, onboardingDone = true) }
        settings2.update { it.copy(accountEmail = FakeDriveService.ACCOUNT, onboardingDone = true) }
        root1 = tmp.newFolder("Audiobooks1")
        root2 = tmp.newFolder("Audiobooks2")
        fake = FakeDriveService(content = { File(fixtures, it).readBytes() }, clock = clock)

        val storage1 = object : LocalStorage(context, settings1) {
            override fun sharedStorageGranted(): Boolean = false
            override suspend fun root(): File = root1
            override fun freeBytes(dir: File): Long = Long.MAX_VALUE
        }
        val storage2 = object : LocalStorage(context, settings2) {
            override fun sharedStorageGranted(): Boolean = false
            override suspend fun root(): File = root2
            override fun freeBytes(dir: File): Long = Long.MAX_VALUE
        }
        engine1 = SyncEngine(db1, settings1, { fake }, storage1, network, inspector, clock, "Pixel 9")
        engine2 = SyncEngine(db2, settings2, { fake }, storage2, network, inspector, clock, "Tablette Salon")
    }

    @After
    fun tearDown() {
        if (::db1.isInitialized) db1.close()
        if (::db2.isInitialized) db2.close()
    }

    private suspend fun bookIn(db: AppDatabase, name: String): BookEntity =
        db.bookDao().all().single { it.name == name }

    @Test
    fun `ProgressJson v2 legacy est relu sans perte et ré-encodé en v3`() = runBlocking<Unit> {
        engine1.sync(SyncTrigger.LAUNCH)
        val police = bookIn(db1, "Police - Hugo Boris")
        val legacyV2 = """
            {
              "bookId": "${police.driveId}",
              "trackIndex": 1,
              "positionMs": 33000,
              "speed": 1.15,
              "percent": 48.5,
              "updatedAt": ${now + 5_000L},
              "version": 2
            }
        """.trimIndent().toByteArray()
        fake.createFile(ProgressJson.fileName(FakeDriveService.ACCOUNT), police.driveId, legacyV2, ProgressJson.MIME)

        val adopted = engine1.pullProgress(police.driveId)
        assertThat(adopted).isNotNull()
        val progress = db1.progressDao().get(police.driveId, FakeDriveService.ACCOUNT)!!
        assertThat(progress.trackIndex).isEqualTo(1)
        assertThat(progress.positionMs).isEqualTo(33_000L)
        assertThat(progress.speed).isEqualTo(1.15f)
        assertThat(progress.personalStatus).isNull()
        assertThat(db1.bookmarkDao().forBook(police.driveId, FakeDriveService.ACCOUNT)).isEmpty()
    }

    @Test
    fun `fusion v3 multi-appareils - signets, suppressions et position plus récente convergent sans s'écraser`() = runBlocking<Unit> {
        engine1.sync(SyncTrigger.LAUNCH)
        engine2.sync(SyncTrigger.LAUNCH)
        val civ1 = bookIn(db1, "CIVILISATIONS - Laurent BINET")
        val tracks = db1.trackDao().forBook(civ1.driveId)

        // État initial partagé : un signet B0 sur les deux appareils.
        val sharedSyncId = "b0-shared-uuid"
        val t0 = now
        for (db in listOf(db1, db2)) {
            db.bookmarkDao().insert(
                BookmarkEntity(
                    bookId = civ1.driveId,
                    account = FakeDriveService.ACCOUNT,
                    syncId = sharedSyncId,
                    trackId = tracks[0].driveId,
                    trackIndex = 0,
                    positionMs = 5_000L,
                    note = "Ancien repère",
                    createdAt = t0,
                    updatedAt = t0,
                ),
            )
            db.progressDao().upsert(
                ProgressEntity(
                    bookId = civ1.driveId,
                    account = FakeDriveService.ACCOUNT,
                    trackId = tracks[0].driveId,
                    trackIndex = 0,
                    positionMs = 5_000L,
                    percent = 5f,
                    updatedAt = t0,
                    dirty = true,
                ),
            )
        }
        assertThat(engine1.pushProgress(civ1.driveId)).isTrue()

        // Appareil 1 supprime B0 (pierre tombale), ajoute B1 et change le statut personnel à t + 10s.
        now = t0 + 10_000L
        val b0Local1 = db1.bookmarkDao().forBook(civ1.driveId, FakeDriveService.ACCOUNT).single()
        db1.bookmarkDao().softDelete(b0Local1.id, now)
        db1.bookmarkDao().insert(
            BookmarkEntity(
                bookId = civ1.driveId,
                account = FakeDriveService.ACCOUNT,
                syncId = "b1-device1-uuid",
                trackId = tracks[2].driveId,
                trackIndex = 2,
                positionMs = 12_000L,
                note = "Passage Christophe Colomb",
                createdAt = now,
                updatedAt = now,
            ),
        )
        db1.progressDao().upsert(
            db1.progressDao().get(civ1.driveId, FakeDriveService.ACCOUNT)!!.copy(
                personalStatus = BookStatus.LATER,
                personalStatusChangedAt = now,
                dirty = true,
            ),
        )
        assertThat(engine1.pushProgress(civ1.driveId)).isTrue()

        // Appareil 2 (hors synchro entre-temps) écoute plus loin à t + 20s et ajoute son signet B2.
        now = t0 + 20_000L
        db2.bookmarkDao().insert(
            BookmarkEntity(
                bookId = civ1.driveId,
                account = FakeDriveService.ACCOUNT,
                syncId = "b2-device2-uuid",
                trackId = tracks[5].driveId,
                trackIndex = 5,
                positionMs = 28_000L,
                note = "Passage Atahualpa",
                createdAt = now,
                updatedAt = now,
            ),
        )
        db2.progressDao().upsert(
            db2.progressDao().get(civ1.driveId, FakeDriveService.ACCOUNT)!!.copy(
                trackId = tracks[5].driveId,
                trackIndex = 5,
                positionMs = 28_000L,
                percent = 58f,
                updatedAt = now,
                dirty = true,
            ),
        )

        // Synchro de progression sur Appareil 2 puis sur Appareil 1 : tout converge !
        engine2.syncProgress()
        engine1.syncProgress()

        for (db in listOf(db1, db2)) {
            val p = db.progressDao().get(civ1.driveId, FakeDriveService.ACCOUNT)!!
            assertThat(p.trackIndex).isEqualTo(5)
            assertThat(p.positionMs).isEqualTo(28_000L)
            assertThat(p.personalStatus).isEqualTo(BookStatus.LATER)
            val activeNotes = db.bookmarkDao().forBook(civ1.driveId, FakeDriveService.ACCOUNT).map { it.note }
            assertThat(activeNotes).containsExactly("Passage Christophe Colomb", "Passage Atahualpa")
        }
    }

    @Test
    fun `mode familial - Julie télécharge un livre de _Nouveaux sans le déplacer ni le supprimer au retour sur Julien`() = runBlocking<Unit> {
        settings1.update {
            it.copy(
                profiles = listOf(ListenerProfile("julie", "Julie"), ListenerProfile("clement", "Clément")),
                activeProfileKey = "julie",
                statusSyncMode = StatusSyncMode.PERSONAL_FAMILY,
            )
        }
        engine1.sync(SyncTrigger.LAUNCH)
        val police = bookIn(db1, "Police - Hugo Boris")
        assertThat(police.parentId).isEqualTo(D.NEW_ID)
        assertThat(db1.trackDao().forBook(police.driveId).all { it.state == TrackState.ABSENT }).isTrue()

        // Julie commence « Police » en mode familial : statut personnel IN_PROGRESS, aucun MOVE_BOOK.
        now += 5_000L
        db1.progressDao().upsert(
            ProgressEntity(
                bookId = police.driveId,
                account = "julie",
                personalStatus = BookStatus.IN_PROGRESS,
                personalStatusChangedAt = now,
                updatedAt = now,
                dirty = true,
            ),
        )
        val outcome = engine1.sync(SyncTrigger.STATUS_CHANGE)
        assertThat(outcome.result).isEqualTo(SyncResult.SUCCESS)

        // Sur Drive : le dossier « Police - Hugo Boris » est TOUJOURS dans _Nouveaux, avec le fichier de Julie.
        assertThat(fake.getFile(police.driveId).parents).containsExactly(D.NEW_ID)
        val children = fake.listChildren(police.driveId).map { it.name }
        assertThat(children).contains(ProgressJson.fileName("julie"))

        // Sur le téléphone : en mode streaming par défaut (N=0), les pistes 0 et 1 sont téléchargées pour Julie.
        val tracks = db1.trackDao().forBook(police.driveId)
        assertThat(tracks).hasSize(3)
        assertThat(tracks.take(2).all { it.state == TrackState.PRESENT && File(it.localPath!!).exists() }).isTrue()
        assertThat(tracks[2].state).isEqualTo(TrackState.ABSENT)

        // Clément pose aussi une progression sur un autre livre -> discoverRemoteProfiles détecte julie & clement.
        val yoga = bookIn(db1, "Yoga - Emmanuel Carrère")
        fake.createFile(
            ProgressJson.fileName("clement"),
            yoga.driveId,
            ProgressJson.encode(ProgressJson(bookId = yoga.driveId, updatedAt = now, account = "clement")),
            ProgressJson.MIME,
        )
        val discovered = engine1.discoverRemoteProfiles()
        assertThat(discovered).containsAtLeast("julie", "clement")

        // Retour sur le profil principal (Julien) sur le même appareil :
        // « Police » reste en statut NEW pour Julien, mais Cleaner NE supprime PAS les pistes de Julie !
        settings1.update { it.copy(activeProfileKey = null) }
        now += 30L * 24 * 3_600_000L // +30 jours
        engine1.sync(SyncTrigger.PERIODIC)

        val julienBook = db1.bookDao().get(police.driveId)!!
        val julienProgress = db1.progressDao().get(police.driveId, FakeDriveService.ACCOUNT)
        assertThat(julienBook.userEffectiveStatus(julienProgress?.personalStatus)).isEqualTo(BookStatus.NEW)
        val afterReturn = db1.trackDao().forBook(police.driveId)
        assertThat(afterReturn.take(2).all { it.state == TrackState.PRESENT && File(it.localPath!!).exists() }).isTrue()
        assertThat(afterReturn[2].state).isEqualTo(TrackState.ABSENT)
    }

    @Test
    fun `MetadataSync - les corrections de titre, auteur et voix sont partagées avec tous les appareils de la famille`() = runBlocking<Unit> {
        engine1.sync(SyncTrigger.LAUNCH)
        engine2.sync(SyncTrigger.LAUNCH)

        val dune1 = db1.bookDao().all().first { it.name == "DUNES__LES_HERETIQUES_DE_DUNE" }
        now += 10_000L
        db1.bookDao().upsert(
            dune1.copy(
                customTitle = "Les Hérétiques de Dune",
                customAuthor = "Frank Herbert",
                customNarrator = "Jean-Claude Drouot",
                titleConfirmed = true,
                normalizedKey = TextNormalizer.key("Les Hérétiques de Dune"),
                authorKey = TextNormalizer.key("Frank Herbert"),
                metadataUpdatedAt = now,
                metadataDirty = true,
            ),
        )

        assertThat(engine1.pushMetadata(dune1.driveId)).isTrue()
        assertThat(db1.bookDao().get(dune1.driveId)!!.metadataDirty).isFalse()
        assertThat(fake.listChildren(dune1.driveId).map { it.name }).contains(MetadataJson.FOLDER_FILE_NAME)
        // Le dossier Drive lui-même n'est pas renommé.
        assertThat(fake.getFile(dune1.driveId).name).isEqualTo("DUNES__LES_HERETIQUES_DE_DUNE")

        // Appareil 2 synchronise et adopte aussitôt les corrections familiales.
        engine2.syncProgress()
        val dune2 = db2.bookDao().get(dune1.driveId)!!
        assertThat(dune2.displayTitle).isEqualTo("Les Hérétiques de Dune")
        assertThat(dune2.customAuthor).isEqualTo("Frank Herbert")
        assertThat(dune2.customNarrator).isEqualTo("Jean-Claude Drouot")
        assertThat(dune2.titleConfirmed).isTrue()
    }

    @Test
    fun `pinnedOffline - télécharge un livre hors En écoute et le protège du nettoyage automatique`() = runBlocking<Unit> {
        engine1.sync(SyncTrigger.LAUNCH)
        val horla = bookIn(db1, "Le Horla - Guy de MAUPASSANT")
        assertThat(horla.status).isEqualTo(BookStatus.DONE)
        assertThat(db1.trackDao().forBook(horla.driveId).all { it.state == TrackState.ABSENT }).isTrue()

        // L'utilisateur active « Garder sur ce téléphone » sur un livre déjà dans _Ecouté.
        db1.bookDao().setPinnedOffline(horla.driveId, true)
        engine1.sync(SyncTrigger.STATUS_CHANGE)

        val downloaded = db1.trackDao().forBook(horla.driveId)
        assertThat(downloaded).hasSize(3)
        assertThat(downloaded.all { it.state == TrackState.PRESENT && File(it.localPath!!).exists() }).isTrue()

        // Même après 10 jours (bien au-delà des 24 h de rétention de _Ecouté), rien n'est nettoyé tant qu'il est épinglé.
        now += 10L * 24 * 3_600_000L
        engine1.cleanupNow()
        assertThat(db1.trackDao().forBook(horla.driveId).all { it.state == TrackState.PRESENT && File(it.localPath!!).exists() }).isTrue()

        // Désactivation de l'épinglage : le nettoyage reprend ses droits sur _Ecouté.
        db1.bookDao().setPinnedOffline(horla.driveId, false)
        engine1.cleanupNow()
        assertThat(db1.trackDao().forBook(horla.driveId).all { it.state == TrackState.ABSENT }).isTrue()
    }
}
