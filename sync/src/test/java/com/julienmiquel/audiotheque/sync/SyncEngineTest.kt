package com.julienmiquel.audiotheque.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.julienmiquel.audiotheque.core.db.AppDatabase
import com.julienmiquel.audiotheque.core.db.BookEntity
import com.julienmiquel.audiotheque.core.db.PendingOperationEntity
import com.julienmiquel.audiotheque.core.db.ProgressEntity
import com.julienmiquel.audiotheque.core.model.BookKind
import com.julienmiquel.audiotheque.core.model.BookStatus
import com.julienmiquel.audiotheque.core.model.CleanupPolicy
import com.julienmiquel.audiotheque.core.model.DriveLibraryDefaults as D
import com.julienmiquel.audiotheque.core.model.OperationType
import com.julienmiquel.audiotheque.core.model.SyncEventType
import com.julienmiquel.audiotheque.core.model.SyncResult
import com.julienmiquel.audiotheque.core.model.SyncTrigger
import com.julienmiquel.audiotheque.core.model.TrackState
import com.julienmiquel.audiotheque.core.parse.Mp3Probe
import com.julienmiquel.audiotheque.core.settings.SettingsRepository
import com.julienmiquel.audiotheque.core.sync.Clock
import com.julienmiquel.audiotheque.sync.testing.LibraryFixture
import com.julienmiquel.audiotheque.sync.testing.FakeDriveService
import com.julienmiquel.audiotheque.sync.drive.DriveContent
import com.julienmiquel.audiotheque.sync.drive.DriveFile
import com.julienmiquel.audiotheque.sync.drive.DriveService
import com.julienmiquel.audiotheque.sync.engine.AudioFileInfo
import com.julienmiquel.audiotheque.sync.engine.AudioFileInspector
import com.julienmiquel.audiotheque.sync.engine.LocalStorage
import com.julienmiquel.audiotheque.sync.engine.NetworkStatus
import com.julienmiquel.audiotheque.sync.engine.ProgressJson
import com.julienmiquel.audiotheque.sync.engine.SyncEngine
import com.julienmiquel.audiotheque.sync.engine.md5Hex
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Collections
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
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
 * Tests unitaires du moteur de synchro, contre un double de test de Drive (en mémoire, peuplé
 * avec l'inventaire de la spécification) et les mp3 de `testdata/audio` : inventaire,
 * téléchargements, gestes, Drive fait foi, hors ligne, refus définitif, nettoyage, espace,
 * reprise de transfert et progression dans le dossier du livre. L'app, elle, n'utilise que l'API Google Drive.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val fixtures = File("../testdata/audio")
    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository
    private lateinit var fake: FakeDriveService
    private lateinit var drive: RecordingDrive
    private lateinit var engine: SyncEngine
    private lateinit var root: File
    private var now = 1_800_000_000_000L
    private var free = Long.MAX_VALUE
    private var unmetered = true
    private var inUse: String? = null
    private var trackInUse: String? = null
    private val clock = Clock { now }

    /** Enregistre les plages demandées (reprise de transfert par en-tête Range). */
    class RecordingDrive(private val inner: FakeDriveService) : DriveService by inner {
        val opened: MutableList<Pair<String, Long>> = Collections.synchronizedList(mutableListOf())

        /** Exécuté pendant l'appel de déplacement (geste fait pendant qu'il est en cours). */
        var beforeMove: (suspend (String) -> Unit)? = null

        override suspend fun openContent(fileId: String, fromByte: Long, toByteInclusive: Long?): DriveContent {
            opened += fileId to fromByte
            return inner.openContent(fileId, fromByte, toByteInclusive)
        }

        override suspend fun move(fileId: String, addParent: String, removeParents: List<String>): DriveFile {
            beforeMove?.let { hook ->
                beforeMove = null
                hook(fileId)
            }
            return inner.move(fileId, addParent, removeParents)
        }
    }

    private val inspector = AudioFileInspector { file ->
        val bytes = file.readBytes()
        val info = Mp3Probe.parse(bytes.copyOf(minOf(bytes.size, Mp3Probe.MAX_TAG_BYTES)))
        AudioFileInfo(info.durationMs(bytes.size.toLong()), info.tags?.narrator(), info.tags?.picture)
    }

    @Before
    fun setUp() = runBlocking<Unit> {
        check(File(fixtures, "civilisations_01.mp3").exists()) { "Fichiers de test absents : ${fixtures.absolutePath}" }
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = AppDatabase.inMemory(context)
        settings = SettingsRepository.forFile(File(tmp.root, "settings.preferences_pb"))
        settings.update { it.copy(accountEmail = FakeDriveService.ACCOUNT, onboardingDone = true) }
        root = tmp.newFolder("Audiobooks")
        val storage = object : LocalStorage(context, settings) {
            override fun sharedStorageGranted(): Boolean = false
            override suspend fun root(): File = root
            override fun freeBytes(dir: File): Long = free
        }
        fake = FakeDriveService(content = { File(fixtures, it).readBytes() }, clock = clock)
        drive = RecordingDrive(fake)
        val network = object : NetworkStatus {
            override fun isConnected() = true
            override fun isUnmetered() = unmetered
        }
        engine = SyncEngine(
            db,
            settings,
            { drive },
            storage,
            network,
            inspector,
            clock,
            "test",
            isBookInUse = { it == inUse },
            isTrackInUse = { it == trackInUse },
        )
    }

    @After
    fun tearDown() {
        if (::db.isInitialized) db.close()
    }

    private suspend fun book(name: String): BookEntity = db.bookDao().all().single { it.name == name }

    /** Ce que fait un geste de statut (module bibliothèque) : opération en file + statut en attente. */
    private suspend fun queueMove(book: BookEntity, status: BookStatus, folder: String) {
        db.pendingOperationDao().insert(
            PendingOperationEntity(
                bookId = book.driveId,
                fromParentId = book.parentId,
                toParentId = folder,
                fromStatus = book.status,
                toStatus = status,
                createdAt = now,
            ),
        )
        db.bookDao().setPending(book.driveId, status, folder, now)
    }

    private suspend fun events() = db.syncEventDao().observeRecent().first()

    @Test
    fun `première synchro - inventaire complet, mode streaming par défaut sur En écoute`() = runBlocking<Unit> {
        val outcome = engine.sync(SyncTrigger.LAUNCH)
        assertThat(outcome.result).isEqualTo(SyncResult.SUCCESS)

        val all = db.bookDao().all()
        assertThat(all).hasSize(54)
        assertThat(all.count { it.status == BookStatus.NEW }).isEqualTo(16)
        assertThat(all.count { it.status == BookStatus.UNSORTED }).isEqualTo(11)
        assertThat(all.count { it.kind == BookKind.ZIP }).isEqualTo(3)
        assertThat(all.count { it.kind == BookKind.SINGLE_FILE }).isEqualTo(1)

        val civ = book(LibraryFixture.civilisations.name)
        assertThat(civ.status).isEqualTo(BookStatus.IN_PROGRESS)
        val tracks = db.trackDao().forBook(civ.driveId)
        assertThat(tracks).hasSize(10)
        // Mode streaming par défaut (N=0) : seules les pistes 0 et 1 (N et N+1) sont téléchargées.
        assertThat(tracks.take(2).map { it.state }.toSet()).containsExactly(TrackState.PRESENT)
        assertThat(tracks.drop(2).map { it.state }.toSet()).containsExactly(TrackState.ABSENT)
        // Intégrité : plus de « .part », taille et checksum de Drive sur les pistes téléchargées.
        tracks.take(2).forEach { t ->
            val f = File(t.localPath!!)
            assertThat(f.exists()).isTrue()
            assertThat(File(f.path + ".part").exists()).isFalse()
            assertThat(f.length()).isEqualTo(t.sizeBytes)
            assertThat(f.md5Hex()).isEqualTo(t.md5)
        }
        // [Stockage interne]/Audiobooks/<nom du livre>/
        assertThat(File(tracks.first().localPath!!).parentFile!!.name).isEqualTo("CIVILISATIONS - Laurent BINET")
        assertThat(tracks.map { it.part }).containsExactly(1, 1, 2, 2, 3, 3, 3, 4, 4, 4).inOrder()
        val fresh = db.bookDao().get(civ.driveId)!!
        assertThat(fresh.durationMs).isNotNull()
        assertThat(fresh.durationExact).isFalse()
        assertThat(fresh.tagNarrator).isEqualTo("Audrey (voix de synthèse)")
        assertThat(fresh.coverPath).isNotNull()

        // _Nouveaux : métadonnées seulement (pistes listées, durée estimée, pochette du dossier).
        val police = book("Police - Hugo Boris")
        assertThat(db.trackDao().presentCount(police.driveId)).isEqualTo(0)
        assertThat(police.trackCount).isEqualTo(3)
        assertThat(police.durationMs).isNotNull()
        assertThat(police.durationExact).isFalse()
        assertThat(police.coverPath).isNotNull()

        // _archives : métadonnées à l'ouverture seulement.
        val archived = all.first { it.status == BookStatus.ARCHIVED && it.kind == BookKind.FOLDER }
        assertThat(archived.tracksSyncedAt).isNull()

        assertThat(events().count { it.type == SyncEventType.RECEIVED }).isEqualTo(2)
        val run = db.syncRunDao().observeLastFinished().first()!!
        assertThat(run.filesReceived).isEqualTo(2)
        assertThat(run.fullInventory).isTrue()
    }

    @Test
    fun `Marquer écouté - dossier déplacé dans Drive, pistes supprimées 24 h après`() = runBlocking<Unit> {
        engine.sync(SyncTrigger.LAUNCH)
        val civ = book(LibraryFixture.civilisations.name)

        queueMove(civ, BookStatus.DONE, D.DONE_ID)
        engine.sync(SyncTrigger.STATUS_CHANGE)

        assertThat(fake.getFile(civ.driveId).parents).containsExactly(D.DONE_ID)
        val moved = db.bookDao().get(civ.driveId)!!
        assertThat(moved.status).isEqualTo(BookStatus.DONE)
        assertThat(moved.pendingStatus).isNull()
        assertThat(db.pendingOperationDao().all()).isEmpty()
        assertThat(events().any { it.type == SyncEventType.DRIVE_MOVE && it.bookId == civ.driveId }).isTrue()
        // Pistes déjà présentes gardées 24 h dans _Ecouté.
        assertThat(db.trackDao().presentCount(civ.driveId)).isEqualTo(2)

        now += 25 * 3_600_000L
        engine.sync(SyncTrigger.PERIODIC)
        assertThat(db.trackDao().presentCount(civ.driveId)).isEqualTo(0)
        assertThat(db.trackDao().forBook(civ.driveId).none { it.localPath?.let(::File)?.exists() == true }).isTrue()
        assertThat(events().count { it.type == SyncEventType.LOCAL_DELETE }).isEqualTo(2)
    }

    @Test
    fun `Commencer - la fenêtre streaming descend en commençant à la position d'écoute`() = runBlocking<Unit> {
        engine.sync(SyncTrigger.LAUNCH)
        val police = book("Police - Hugo Boris")
        db.progressDao().upsert(ProgressEntity(bookId = police.driveId, account = FakeDriveService.ACCOUNT, trackIndex = 2, updatedAt = now))
        drive.opened.clear()

        queueMove(police, BookStatus.IN_PROGRESS, D.IN_PROGRESS_ID)
        engine.sync(SyncTrigger.STATUS_CHANGE)

        // Piste N=2 (dernière des 3 pistes) -> fenêtre N-1..N = pistes 1 et 2, avec priorité sur N=2.
        assertThat(db.trackDao().presentCount(police.driveId)).isEqualTo(2)
        val order = db.trackDao().forBook(police.driveId).associate { it.driveId to it.sortIndex }
        val firstDownloaded = drive.opened.first { it.first in order.keys && it.second == 0L }.first
        assertThat(order[firstDownloaded]).isEqualTo(2)
    }

    @Test
    fun `Drive fait foi - un dossier déplacé depuis le web change de statut`() = runBlocking<Unit> {
        engine.sync(SyncTrigger.LAUNCH)
        val guerriers = book(LibraryFixture.UNFINISHED_BOOK)
        assertThat(guerriers.status).isEqualTo(BookStatus.DONE)

        fake.externalMove(guerriers.driveId, D.LATER_ID)
        engine.sync(SyncTrigger.PERIODIC)

        assertThat(db.bookDao().get(guerriers.driveId)!!.status).isEqualTo(BookStatus.LATER)
        assertThat(events().any { it.type == SyncEventType.STATUS_FROM_DRIVE && it.bookId == guerriers.driveId }).isTrue()
    }

    @Test
    fun `hors ligne - le geste reste en file et l'état confirmé ne bouge pas, puis il est rejoué`() = runBlocking<Unit> {
        engine.sync(SyncTrigger.LAUNCH)
        val police = book("Police - Hugo Boris")
        queueMove(police, BookStatus.IN_PROGRESS, D.IN_PROGRESS_ID)

        fake.offline = true
        val failed = engine.sync(SyncTrigger.STATUS_CHANGE)
        assertThat(failed.result).isEqualTo(SyncResult.FAILED)
        val waiting = db.bookDao().get(police.driveId)!!
        assertThat(waiting.status).isEqualTo(BookStatus.NEW)
        assertThat(waiting.pendingStatus).isEqualTo(BookStatus.IN_PROGRESS)
        val op = db.pendingOperationDao().all().single()
        assertThat(op.attempts).isEqualTo(1)
        assertThat(op.nextAttemptAt).isGreaterThan(now)
        assertThat(failed.retryAtMs).isEqualTo(op.nextAttemptAt)

        fake.offline = false
        now += 31_000
        engine.sync(SyncTrigger.STATUS_CHANGE)
        assertThat(fake.getFile(police.driveId).parents).containsExactly(D.IN_PROGRESS_ID)
        assertThat(db.bookDao().get(police.driveId)!!.status).isEqualTo(BookStatus.IN_PROGRESS)
        assertThat(db.pendingOperationDao().all()).isEmpty()
    }

    @Test
    fun `refus définitif de Drive - le geste est abandonné, le livre reste dans son statut`() = runBlocking<Unit> {
        engine.sync(SyncTrigger.LAUNCH)
        val police = book("Police - Hugo Boris")
        fake.forbiddenMoves += police.driveId
        queueMove(police, BookStatus.IN_PROGRESS, D.IN_PROGRESS_ID)

        engine.sync(SyncTrigger.STATUS_CHANGE)

        val b = db.bookDao().get(police.driveId)!!
        assertThat(b.status).isEqualTo(BookStatus.NEW)
        assertThat(b.pendingStatus).isNull()
        assertThat(db.pendingOperationDao().all()).isEmpty()
        assertThat(fake.getFile(police.driveId).parents).containsExactly(D.NEW_ID)
        assertThat(events().any { it.type == SyncEventType.ERROR && it.message.orEmpty().contains("refusé") }).isTrue()
    }

    @Test
    fun `espace insuffisant - le transfert ne démarre pas`() = runBlocking<Unit> {
        free = 100_000_000L // < taille du livre + 500 Mo
        val outcome = engine.sync(SyncTrigger.LAUNCH)
        assertThat(outcome.result).isEqualTo(SyncResult.PARTIAL)
        val civ = book(LibraryFixture.civilisations.name)
        assertThat(db.trackDao().presentCount(civ.driveId)).isEqualTo(0)
        assertThat(events().any { it.type == SyncEventType.ERROR && it.message.orEmpty().startsWith("Espace insuffisant") }).isTrue()
    }

    @Test
    fun `reprise après coupure - le fichier part est complété par une requête Range`() = runBlocking<Unit> {
        free = 1L
        engine.sync(SyncTrigger.LAUNCH) // inventaire seulement : pas de place
        val civ = book(LibraryFixture.civilisations.name)
        val first = db.trackDao().forBook(civ.driveId).first()
        val bytes = File(fixtures, "civilisations_01.mp3").readBytes()
        val dir = File(root, "CIVILISATIONS - Laurent BINET").apply { mkdirs() }
        File(dir, first.relativePath + ".part").writeBytes(bytes.copyOf(10_000))
        free = Long.MAX_VALUE

        engine.sync(SyncTrigger.MANUAL)

        val done = db.trackDao().get(first.driveId)!!
        assertThat(done.state).isEqualTo(TrackState.PRESENT)
        assertThat(File(done.localPath!!).readBytes()).isEqualTo(bytes)
        assertThat(drive.opened).contains(first.driveId to 10_000L)
    }

    @Test
    fun `progression par utilisateur dans le dossier Drive du livre`() = runBlocking<Unit> {
        engine.sync(SyncTrigger.LAUNCH)
        // Progression déjà écrite par l'autre téléphone dans le dossier du livre, importée : livre
        // rangé à tort dans _Ecouté, pas fini.
        val guerriers = book(LibraryFixture.UNFINISHED_BOOK)
        val imported = db.progressDao().get(guerriers.driveId, FakeDriveService.ACCOUNT)!!
        assertThat(imported.percent).isEqualTo(46f)
        assertThat(imported.finished).isFalse()
        assertThat(imported.dirty).isFalse()
        assertThat(events().any { it.type == SyncEventType.PROGRESS && it.bookId == guerriers.driveId && it.message!!.contains(FakeDriveService.OTHER_DEVICE) }).isTrue()

        val civ = book(LibraryFixture.civilisations.name)
        db.progressDao().upsert(ProgressEntity(bookId = civ.driveId, account = FakeDriveService.ACCOUNT, trackIndex = 4, positionMs = 12_000, speed = 1.3f, percent = 42f, updatedAt = now))
        engine.sync(SyncTrigger.MANUAL)

        // Un fichier par compte, dans le dossier du livre, lisible dans Drive.
        val file = fake.findChildren(civ.driveId, ProgressJson.fileName(FakeDriveService.ACCOUNT)).single()
        val json = ProgressJson.decode(fake.bytesOf(file.id))
        assertThat(json.trackIndex).isEqualTo(4)
        assertThat(json.positionMs).isEqualTo(12_000)
        assertThat(json.speed).isEqualTo(1.3f)
        assertThat(json.device).isEqualTo("test")
        assertThat(json.title).isEqualTo("Civilisations")
        assertThat(db.progressDao().get(civ.driveId, FakeDriveService.ACCOUNT)!!.dirty).isFalse()
        // Jamais de pistes relistées à cause de ce fichier : il n'est pas une piste.
        assertThat(db.trackDao().forBook(civ.driveId).map { it.name }).doesNotContain(ProgressJson.fileName(FakeDriveService.ACCOUNT))
    }

    @Test
    fun `deux téléphones - la position la plus récente l'emporte, dans les deux sens`() = runBlocking<Unit> {
        engine.sync(SyncTrigger.LAUNCH)
        val civ = book(LibraryFixture.civilisations.name)
        val tracks = db.trackDao().forBook(civ.driveId)

        // Ce téléphone a écouté jusqu'à la piste 2 il y a une heure, sans réseau depuis.
        val localAt = now - 3_600_000
        db.progressDao().upsert(ProgressEntity(bookId = civ.driveId, account = FakeDriveService.ACCOUNT, trackId = tracks[2].driveId, trackIndex = 2, positionMs = 30_000, percent = 25f, updatedAt = localAt))
        // L'autre téléphone a continué jusqu'à la piste 5 il y a dix minutes.
        val remoteAt = now - 600_000
        fake.writeProgressFromOtherDevice(
            civ.driveId,
            ProgressJson(bookId = civ.driveId, trackId = tracks[5].driveId, trackIndex = 5, positionMs = 5_000, percent = 55f, updatedAt = remoteAt, device = FakeDriveService.OTHER_DEVICE),
            at = remoteAt,
        )

        engine.sync(SyncTrigger.MANUAL)

        // Notre position, plus ancienne, n'a pas écrasé la sienne : elle est remplacée.
        val merged = db.progressDao().get(civ.driveId, FakeDriveService.ACCOUNT)!!
        assertThat(merged.trackIndex).isEqualTo(5)
        assertThat(merged.positionMs).isEqualTo(5_000)
        assertThat(merged.dirty).isFalse()
        val remote = ProgressJson.decode(fake.bytesOf(fake.findChildren(civ.driveId, ProgressJson.fileName(FakeDriveService.ACCOUNT)).single().id))
        assertThat(remote.trackIndex).isEqualTo(5)
        assertThat(remote.device).isEqualTo(FakeDriveService.OTHER_DEVICE)

        // Puis on écoute ici, plus tard : notre position devient la plus récente et part dans Drive.
        now += 60_000
        db.progressDao().upsert(merged.copy(trackIndex = 6, trackId = tracks[6].driveId, positionMs = 1_000, percent = 62f, updatedAt = now, dirty = true))
        engine.sync(SyncTrigger.MANUAL)
        val pushed = ProgressJson.decode(fake.bytesOf(fake.findChildren(civ.driveId, ProgressJson.fileName(FakeDriveService.ACCOUNT)).single().id))
        assertThat(pushed.trackIndex).isEqualTo(6)
        assertThat(pushed.device).isEqualTo("test")
        // Toujours un seul fichier par compte dans le dossier.
        assertThat(fake.findChildren(civ.driveId, ProgressJson.fileName(FakeDriveService.ACCOUNT))).hasSize(1)
    }

    @Test
    fun `lecteur - position relue dans Drive avant la lecture, envoyée après la pause`() = runBlocking<Unit> {
        engine.sync(SyncTrigger.LAUNCH)
        val civ = book(LibraryFixture.civilisations.name)
        val tracks = db.trackDao().forBook(civ.driveId)
        db.progressDao().upsert(ProgressEntity(bookId = civ.driveId, account = FakeDriveService.ACCOUNT, trackId = tracks[1].driveId, trackIndex = 1, positionMs = 0, percent = 10f, updatedAt = now - 7_200_000, dirty = false))

        // Pause sur l'autre téléphone il y a cinq minutes.
        fake.writeProgressFromOtherDevice(
            civ.driveId,
            ProgressJson(bookId = civ.driveId, trackId = tracks[3].driveId, trackIndex = 3, positionMs = 42_000, percent = 38f, updatedAt = now - 300_000, device = FakeDriveService.OTHER_DEVICE),
            at = now - 300_000,
        )

        // Bouton ▶ ici : la position de l'autre téléphone est adoptée avant de démarrer.
        val restored = engine.pullProgress(civ.driveId)
        assertThat(restored).isNotNull()
        assertThat(restored!!.device).isEqualTo(FakeDriveService.OTHER_DEVICE)
        assertThat(restored.percent).isEqualTo(38f)
        val local = db.progressDao().get(civ.driveId, FakeDriveService.ACCOUNT)!!
        assertThat(local.trackIndex).isEqualTo(3)
        assertThat(local.positionMs).isEqualTo(42_000)
        // Relecture immédiate : rien de nouveau, et pas de seconde requête dans les 30 s.
        assertThat(engine.pullProgress(civ.driveId)).isNull()

        // Pause ici, un peu plus tard : envoi léger, sans synchro complète.
        now += 120_000
        db.progressDao().upsert(local.copy(trackId = tracks[3].driveId, positionMs = 90_000, percent = 40f, updatedAt = now, dirty = true))
        assertThat(engine.pushProgress(civ.driveId)).isTrue()
        val remote = ProgressJson.decode(fake.bytesOf(fake.findChildren(civ.driveId, ProgressJson.fileName(FakeDriveService.ACCOUNT)).single().id))
        assertThat(remote.positionMs).isEqualTo(90_000)
        assertThat(remote.device).isEqualTo("test")
        assertThat(db.progressDao().get(civ.driveId, FakeDriveService.ACCOUNT)!!.dirty).isFalse()

        // Hors ligne : l'envoi échoue sans bruit, la position reste à envoyer.
        now += 60_000
        db.progressDao().upsert(local.copy(positionMs = 100_000, updatedAt = now, dirty = true))
        fake.offline = true
        assertThat(engine.pushProgress(civ.driveId)).isFalse()
        assertThat(db.progressDao().get(civ.driveId, FakeDriveService.ACCOUNT)!!.dirty).isTrue()
    }

    @Test
    fun `pochette téléchargée - déposée une seule fois dans le dossier Drive du livre`() = runBlocking<Unit> {
        engine.sync(SyncTrigger.LAUNCH)
        // « Solo » n'a pas d'image dans son dossier Drive ; « Police » en a une.
        val solo = book("Solo - William Boyd")
        val police = book("Police - Hugo Boris")
        val jpeg = File(fixtures, "cover_police.jpg").readBytes()
        val local = tmp.newFile("solo.jpg").apply { writeBytes(jpeg) }
        db.bookDao().setCover(solo.driveId, local.path, uploadPending = true)
        db.bookDao().setCover(police.driveId, db.bookDao().get(police.driveId)!!.coverPath, uploadPending = true)

        assertThat(engine.uploadPendingCovers()).isEqualTo(1)

        val uploaded = fake.listChildren(solo.driveId).filter { it.isImage }
        assertThat(uploaded.map { it.name }).containsExactly("cover.jpg")
        assertThat(fake.bytesOf(uploaded.single().id)).isEqualTo(jpeg)
        // Le dossier de « Police » avait déjà sa pochette : rien d'ajouté, drapeau levé.
        assertThat(fake.listChildren(police.driveId).count { it.isImage }).isEqualTo(1)
        assertThat(db.bookDao().withCoverToUpload()).isEmpty()
        // Second passage : rien à faire, pas de doublon.
        assertThat(engine.uploadPendingCovers()).isEqualTo(0)
        assertThat(fake.listChildren(solo.driveId).count { it.isImage }).isEqualTo(1)
        assertThat(events().any { it.type == SyncEventType.LIBRARY_CHANGE && it.bookId == solo.driveId && it.message!!.contains("Pochette") }).isTrue()
    }

    @Test
    fun `dossier à l'ouverture - métadonnées des archives lues à la demande`() = runBlocking<Unit> {
        engine.sync(SyncTrigger.LAUNCH)
        val pair = db.syncPairDao().forStatus(BookStatus.ARCHIVED)!!
        engine.refreshPair(pair.id)
        val archived = db.bookDao().byParent(D.ARCHIVES_ID).filter { it.kind == BookKind.FOLDER }
        assertThat(archived).isNotEmpty()
        archived.forEach { assertThat(it.trackCount).isEqualTo(3) }
        assertThat(db.syncPairDao().get(pair.id)!!.lastInventoryAt).isNotNull()
    }

    @Test
    fun `nettoyage - rien n'est supprimé avant que Drive confirme le déplacement, puis 10 min de grâce`() = runBlocking<Unit> {
        engine.sync(SyncTrigger.LAUNCH)
        val civ = book(LibraryFixture.civilisations.name)
        assertThat(db.trackDao().presentCount(civ.driveId)).isEqualTo(2)

        // Archiver, mais Drive refuse passagèrement le déplacement : rien ne bouge sur le téléphone.
        fake.busyMoves += civ.driveId
        queueMove(civ, BookStatus.ARCHIVED, D.ARCHIVES_ID)
        val busy = engine.sync(SyncTrigger.STATUS_CHANGE)
        assertThat(db.bookDao().get(civ.driveId)!!.status).isEqualTo(BookStatus.IN_PROGRESS)
        assertThat(db.trackDao().presentCount(civ.driveId)).isEqualTo(2)
        assertThat(busy.retryAtMs).isAtLeast(now + SyncEngine.MIN_RETRY_DELAY_MS)

        // Déplacement fait : les pistes restent pendant le délai de grâce…
        fake.busyMoves.clear()
        now += 31_000
        engine.sync(SyncTrigger.STATUS_CHANGE)
        assertThat(db.bookDao().get(civ.driveId)!!.status).isEqualTo(BookStatus.ARCHIVED)
        assertThat(db.trackDao().presentCount(civ.driveId)).isEqualTo(2)

        // … puis sont supprimées.
        now += CleanupPolicy.GRACE_MS
        engine.sync(SyncTrigger.PERIODIC)
        assertThat(db.trackDao().presentCount(civ.driveId)).isEqualTo(0)
    }

    @Test
    fun `Drive fait foi - un geste en file ne défait pas un déplacement fait entre-temps dans Drive`() = runBlocking<Unit> {
        engine.sync(SyncTrigger.LAUNCH)
        val police = book("Police - Hugo Boris")
        queueMove(police, BookStatus.IN_PROGRESS, D.IN_PROGRESS_ID) // geste fait hors ligne
        fake.externalMove(police.driveId, D.DONE_ID) // déplacé depuis le web entre-temps

        engine.sync(SyncTrigger.STATUS_CHANGE)

        assertThat(fake.getFile(police.driveId).parents).containsExactly(D.DONE_ID)
        val b = db.bookDao().get(police.driveId)!!
        assertThat(b.status).isEqualTo(BookStatus.DONE)
        assertThat(b.pendingStatus).isNull()
        assertThat(db.pendingOperationDao().all()).isEmpty()
        assertThat(events().any { it.type == SyncEventType.ERROR && it.message.orEmpty().contains("Drive fait foi") }).isTrue()
    }

    @Test
    fun `Annuler pendant le déplacement - le livre revient et le retour est journalisé`() = runBlocking<Unit> {
        engine.sync(SyncTrigger.LAUNCH)
        val police = book("Police - Hugo Boris")
        queueMove(police, BookStatus.IN_PROGRESS, D.IN_PROGRESS_ID)
        // « Annuler » pendant l'appel à Drive : la cible de l'opération redevient _Nouveaux.
        drive.beforeMove = { id ->
            val op = db.pendingOperationDao().latestForBook(id)!!
            db.pendingOperationDao().update(op.copy(toParentId = D.NEW_ID, toStatus = BookStatus.NEW, createdAt = now))
            db.bookDao().setPending(id, BookStatus.NEW, D.NEW_ID, police.statusChangedAt)
        }

        engine.sync(SyncTrigger.STATUS_CHANGE)

        assertThat(fake.getFile(police.driveId).parents).containsExactly(D.NEW_ID)
        val b = db.bookDao().get(police.driveId)!!
        assertThat(b.status).isEqualTo(BookStatus.NEW)
        assertThat(b.pendingStatus).isNull()
        assertThat(db.pendingOperationDao().all()).isEmpty()
        // Aller puis retour, tous deux dans l'historique ; jamais téléchargé entre-temps.
        assertThat(events().count { it.type == SyncEventType.DRIVE_MOVE && it.bookId == police.driveId }).isEqualTo(2)
        assertThat(db.trackDao().presentCount(police.driveId)).isEqualTo(0)
    }

    @Test
    fun `autorisation expirée - le geste est gardé, sans nouvel essai automatique en boucle`() = runBlocking<Unit> {
        engine.sync(SyncTrigger.LAUNCH)
        val police = book("Police - Hugo Boris")
        queueMove(police, BookStatus.IN_PROGRESS, D.IN_PROGRESS_ID)
        fake.authExpired = true

        val outcome = engine.sync(SyncTrigger.STATUS_CHANGE)

        assertThat(outcome.result).isEqualTo(SyncResult.FAILED)
        assertThat(outcome.needsAuth).isTrue()
        assertThat(outcome.retryAtMs).isNull()
        assertThat(db.bookDao().get(police.driveId)!!.pendingStatus).isEqualTo(BookStatus.IN_PROGRESS)
        // Bandeau « Renouveler » dans l'app.
        assertThat(settings.currentSyncState().authRequired).isTrue()

        // Autorisation renouvelée : la synchro suivante déplace le dossier et retire le bandeau.
        fake.authExpired = false
        val renewed = engine.sync(SyncTrigger.MANUAL)

        assertThat(renewed.result).isEqualTo(SyncResult.SUCCESS)
        assertThat(settings.currentSyncState().authRequired).isFalse()
        assertThat(db.bookDao().get(police.driveId)!!.pendingStatus).isNull()
        assertThat(db.bookDao().get(police.driveId)!!.status).isEqualTo(BookStatus.IN_PROGRESS)
    }

    @Test
    fun `progression - deux fichiers pour un même livre, le plus récent l'emporte`() = runBlocking<Unit> {
        engine.sync(SyncTrigger.LAUNCH)
        val police = book("Police - Hugo Boris")
        fun json(position: Long, updatedAt: Long) = ProgressJson.encode(
            ProgressJson(bookId = police.driveId, trackIndex = 1, positionMs = position, percent = 40f, updatedAt = updatedAt, device = "autre"),
        )
        // Créés au même moment par deux téléphones dans le dossier du livre : le plus récent d'abord, l'ancien ensuite.
        val name = ProgressJson.fileName(FakeDriveService.ACCOUNT)
        val t0 = now
        now = t0 + 60_000
        fake.createFile(name, police.driveId, json(77_000, now), ProgressJson.MIME)
        now = t0
        fake.createFile(name, police.driveId, json(11_000, now), ProgressJson.MIME)
        now = t0 + 120_000

        engine.sync(SyncTrigger.MANUAL)

        val p = db.progressDao().get(police.driveId, FakeDriveService.ACCOUNT)!!
        assertThat(p.positionMs).isEqualTo(77_000)
        assertThat(p.dirty).isFalse()
    }

    @Test
    fun `paire retirée - le dossier _meditations ne devient pas un livre`() = runBlocking<Unit> {
        engine.sync(SyncTrigger.LAUNCH)
        val meditations = db.syncPairDao().all().single { it.driveFolderId == D.MEDITATIONS_ID }
        engine.removePair(meditations.id)

        engine.sync(SyncTrigger.MANUAL, forceFull = true)

        assertThat(db.bookDao().all().none { it.driveId == D.MEDITATIONS_ID || it.name.startsWith("_") }).isTrue()
    }

    @Test
    fun `Wi-Fi uniquement sur réseau mesuré - transferts reportés, repris au retour du Wi-Fi`() = runBlocking<Unit> {
        unmetered = false
        val metered = engine.sync(SyncTrigger.LAUNCH)
        val civ = book(LibraryFixture.civilisations.name)
        assertThat(db.trackDao().forBook(civ.driveId)).hasSize(10) // pistes listées quand même
        assertThat(db.trackDao().presentCount(civ.driveId)).isEqualTo(0)
        assertThat(metered.waitingForWifi).isTrue()
        // Ni échantillon audio ni pochette sur réseau mesuré.
        assertThat(book("Police - Hugo Boris").probedAt).isNull()
        assertThat(book("Police - Hugo Boris").coverPath).isNull()

        unmetered = true
        val wifi = engine.sync(SyncTrigger.WIFI)
        assertThat(wifi.waitingForWifi).isFalse()
        assertThat(db.trackDao().presentCount(civ.driveId)).isEqualTo(2)
        assertThat(book("Police - Hugo Boris").durationMs).isNotNull()
        assertThat(book("Police - Hugo Boris").coverPath).isNotNull()
    }

    @Test
    fun `livre en cours de lecture - retiré de la bibliothèque seulement après l'écoute`() = runBlocking<Unit> {
        engine.sync(SyncTrigger.LAUNCH)
        val civ = book(LibraryFixture.civilisations.name)
        inUse = civ.driveId
        fake.externalMove(civ.driveId, "dossier-hors-bibliotheque")

        engine.sync(SyncTrigger.PERIODIC)
        assertThat(db.bookDao().get(civ.driveId)).isNotNull()
        assertThat(db.trackDao().presentCount(civ.driveId)).isEqualTo(2)

        inUse = null
        engine.sync(SyncTrigger.PERIODIC)
        assertThat(db.bookDao().get(civ.driveId)).isNull()
        assertThat(root.walkTopDown().none { it.isFile && it.extension == "mp3" }).isTrue()
    }

    // ------------------------------------------------------------------ doublons

    /** Ce que fait « Supprimer ce doublon » (module bibliothèque) : opération corbeille en file + livre masqué. */
    private suspend fun queueTrash(book: BookEntity) {
        db.pendingOperationDao().insert(
            PendingOperationEntity(
                type = OperationType.TRASH_BOOK,
                bookId = book.driveId,
                fromParentId = book.parentId,
                toParentId = book.parentId,
                fromStatus = book.status,
                toStatus = book.status,
                createdAt = now,
            ),
        )
        db.bookDao().setTrashPending(book.driveId, true)
    }

    @Test
    fun `doublon supprimé - dossier à la corbeille Drive, fichiers locaux effacés, l'autre exemplaire reste`() = runBlocking<Unit> {
        engine.sync(SyncTrigger.LAUNCH)
        val copies = db.bookDao().all().filter { it.name == "Abri de fortune" }
        assertThat(copies).hasSize(2)
        val (kept, doomed) = copies
        // L'exemplaire à supprimer a été commencé : il a des pistes sur le téléphone.
        queueMove(doomed, BookStatus.IN_PROGRESS, D.IN_PROGRESS_ID)
        engine.sync(SyncTrigger.STATUS_CHANGE)
        val localFiles = db.trackDao().forBook(doomed.driveId).mapNotNull { it.localPath?.let(::File) }
        assertThat(localFiles).isNotEmpty()
        assertThat(localFiles.all { it.exists() }).isTrue()

        queueTrash(db.bookDao().get(doomed.driveId)!!)
        engine.sync(SyncTrigger.STATUS_CHANGE)

        // Corbeille Drive (restaurable), jamais de suppression définitive.
        assertThat(fake.getFile(doomed.driveId).trashed).isTrue()
        assertThat(fake.getFile(kept.driveId).trashed).isFalse()
        assertThat(db.bookDao().get(doomed.driveId)).isNull()
        assertThat(db.pendingOperationDao().all()).isEmpty()
        assertThat(localFiles.none { it.exists() }).isTrue()
        val row = db.bookDao().observeLibrary(FakeDriveService.ACCOUNT).first().single { it.book.driveId == kept.driveId }
        assertThat(row.duplicateCount).isEqualTo(1)
        assertThat(events().any { it.type == SyncEventType.LIBRARY_CHANGE && it.bookId == doomed.driveId && it.message.orEmpty().contains("corbeille") }).isTrue()

        // Ni le flux de changements ni un inventaire complet ne font revenir le dossier.
        engine.sync(SyncTrigger.PERIODIC)
        engine.sync(SyncTrigger.MANUAL, forceFull = true)
        assertThat(db.bookDao().get(doomed.driveId)).isNull()
        assertThat(db.bookDao().all().count { it.name == "Abri de fortune" }).isEqualTo(1)
    }

    @Test
    fun `doublon - refus de Drive, le livre réapparaît dans la bibliothèque`() = runBlocking<Unit> {
        engine.sync(SyncTrigger.LAUNCH)
        val doomed = db.bookDao().all().first { it.name == "Abri de fortune" }
        fake.forbiddenMoves += doomed.driveId
        queueTrash(doomed)

        engine.sync(SyncTrigger.STATUS_CHANGE)

        val b = db.bookDao().get(doomed.driveId)!!
        assertThat(b.trashPending).isFalse()
        assertThat(fake.getFile(doomed.driveId).trashed).isFalse()
        assertThat(db.pendingOperationDao().all()).isEmpty()
        assertThat(events().any { it.type == SyncEventType.ERROR && it.bookId == doomed.driveId && it.message.orEmpty().contains("refusée") }).isTrue()
    }

    @Test
    fun `doublon - déplacé hors de la bibliothèque entre-temps, Drive fait foi et rien n'est supprimé`() = runBlocking<Unit> {
        engine.sync(SyncTrigger.LAUNCH)
        val doomed = db.bookDao().all().first { it.name == "Abri de fortune" }
        queueTrash(doomed)
        fake.externalMove(doomed.driveId, "dossier-hors-bibliotheque")

        engine.sync(SyncTrigger.STATUS_CHANGE)

        assertThat(fake.getFile(doomed.driveId).trashed).isFalse()
        assertThat(db.pendingOperationDao().all()).isEmpty()
        // Hors des dossiers suivis : il quitte la bibliothèque par l'inventaire, pas par la corbeille.
        assertThat(events().any { it.type == SyncEventType.ERROR && it.bookId == doomed.driveId && it.message.orEmpty().contains("Drive fait foi") }).isTrue()
    }

    // ------------------------------------------------------------------ archives .zip & renommage Drive

    @Test
    fun `archive zip - téléchargée, décompressée hors ligne et progression synchronisée`() = runBlocking<Unit> {
        engine.sync(SyncTrigger.LAUNCH)

        val zipBytes = ByteArrayOutputStream().apply {
            ZipOutputStream(this).use { zos ->
                zos.putNextEntry(ZipEntry("Voyage au centre de la Terre/02 - Chapitre 2.mp3"))
                zos.write(File(fixtures, "chapitre_2.mp3").readBytes())
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("Voyage au centre de la Terre/01 - Chapitre 1.mp3"))
                zos.write(File(fixtures, "chapitre_1.mp3").readBytes())
                zos.closeEntry()
                zos.putNextEntry(ZipEntry("Voyage au centre de la Terre/cover.jpg"))
                zos.write(File(fixtures, "cover_police.jpg").readBytes())
                zos.closeEntry()
            }
        }.toByteArray()

        val created = fake.createFile("Voyage au centre de la Terre - Jules VERNE.zip", D.NEW_ID, zipBytes, "application/zip")
        engine.sync(SyncTrigger.PERIODIC)

        val zipBook = db.bookDao().get(created.id)!!
        assertThat(zipBook.kind).isEqualTo(BookKind.ZIP)
        assertThat(zipBook.trackCount).isEqualTo(0)

        // Déplacement vers _En écoute : l'archive est téléchargée, décompressée et supprimée.
        queueMove(zipBook, BookStatus.IN_PROGRESS, D.IN_PROGRESS_ID)
        engine.sync(SyncTrigger.STATUS_CHANGE)

        val extractedBook = db.bookDao().get(created.id)!!
        val extractedTracks = db.trackDao().forBook(created.id)
        assertThat(extractedBook.trackCount).isEqualTo(2)
        assertThat(extractedBook.durationExact).isTrue()
        assertThat(extractedBook.durationMs).isGreaterThan(0L)
        assertThat(extractedBook.coverPath?.let(::File)?.exists()).isTrue()
        assertThat(extractedTracks).hasSize(2)
        assertThat(extractedTracks[0].name).isEqualTo("01 - Chapitre 1.mp3")
        assertThat(extractedTracks[1].name).isEqualTo("02 - Chapitre 2.mp3")
        assertThat(extractedTracks.all { it.state == TrackState.PRESENT && File(it.localPath!!).exists() }).isTrue()
        assertThat(root.walkTopDown().none { it.name.endsWith(".zip.part") }).isTrue()

        // Progression d'un livre .zip synchronisée dans le dossier de statut Drive.
        now += 10_000L
        db.progressDao().upsert(
            ProgressEntity(
                bookId = created.id,
                account = FakeDriveService.ACCOUNT,
                trackId = extractedTracks[1].driveId,
                trackIndex = 1,
                positionMs = 4_200L,
                percent = 55f,
                lastListenedAt = now,
                updatedAt = now,
                dirty = true,
            ),
        )
        assertThat(engine.pushProgress(created.id)).isTrue()
        val synced = db.progressDao().get(created.id, FakeDriveService.ACCOUNT)!!
        assertThat(synced.dirty).isFalse()
        assertThat(synced.remoteFileId).isNotNull()
    }

    @Test
    fun `renommage normalisé - mis en file hors ligne puis appliqué dans Drive au retour du réseau`() = runBlocking<Unit> {
        engine.sync(SyncTrigger.LAUNCH)
        val target = book("Laurent Binet - PERSPECTIVE(S)")
        val normalized = "Perspective(s) - Laurent BINET"

        fake.offline = true
        db.pendingOperationDao().insert(
            PendingOperationEntity(
                type = OperationType.RENAME_BOOK,
                bookId = target.driveId,
                fromParentId = target.parentId,
                toParentId = target.parentId,
                fromStatus = target.status,
                toStatus = target.status,
                createdAt = now,
                targetName = normalized,
            ),
        )
        db.bookDao().setPendingName(target.driveId, normalized)

        val failed = engine.sync(SyncTrigger.STATUS_CHANGE)
        assertThat(failed.result).isEqualTo(SyncResult.FAILED)
        assertThat(db.bookDao().get(target.driveId)!!.pendingName).isEqualTo(normalized)

        // Retour du réseau et délai de nouvel essai écoulé.
        fake.offline = false
        now += 60_000L
        val ok = engine.sync(SyncTrigger.PERIODIC)
        assertThat(ok.result).isEqualTo(SyncResult.SUCCESS)
        assertThat(fake.getFile(target.driveId).name).isEqualTo(normalized)
        val refreshed = db.bookDao().get(target.driveId)!!
        assertThat(refreshed.name).isEqualTo(normalized)
        assertThat(refreshed.pendingName).isNull()
        assertThat(refreshed.parsedTitle).isEqualTo("Perspective(s)")
        assertThat(refreshed.parsedAuthor).isEqualTo("Laurent Binet")
        assertThat(db.pendingOperationDao().all()).isEmpty()
    }

    @Test
    fun `renommage normalisé - refusé par Drive (403), l'ancien nom est conservé`() = runBlocking<Unit> {
        engine.sync(SyncTrigger.LAUNCH)
        val target = book("Laurent Binet - PERSPECTIVE(S)")
        fake.forbiddenMoves += target.driveId
        db.pendingOperationDao().insert(
            PendingOperationEntity(
                type = OperationType.RENAME_BOOK,
                bookId = target.driveId,
                fromParentId = target.parentId,
                toParentId = target.parentId,
                fromStatus = target.status,
                toStatus = target.status,
                createdAt = now,
                targetName = "Perspective(s) - Laurent BINET",
            ),
        )
        db.bookDao().setPendingName(target.driveId, "Perspective(s) - Laurent BINET")

        engine.sync(SyncTrigger.STATUS_CHANGE)

        val refreshed = db.bookDao().get(target.driveId)!!
        assertThat(refreshed.name).isEqualTo("Laurent Binet - PERSPECTIVE(S)")
        assertThat(refreshed.pendingName).isNull()
        assertThat(db.pendingOperationDao().all()).isEmpty()
    }

    // ------------------------------------------------------------------ mode streaming & nettoyage local

    @Test
    fun `mode streaming par défaut - seules les pistes N-1, N et N+1 sont gardées au fil de l'écoute`() = runBlocking<Unit> {
        assertThat(settings.current().streamingMode).isTrue()
        engine.sync(SyncTrigger.LAUNCH)
        val civ = book(LibraryFixture.civilisations.name)

        suspend fun presentIndices(): List<Int> {
            val all = db.trackDao().forBook(civ.driveId)
            all.forEach { t ->
                if (t.state == TrackState.PRESENT) {
                    assertThat(File(t.localPath!!).exists()).isTrue()
                } else {
                    assertThat(t.localPath).isNull()
                }
            }
            return all.filter { it.state == TrackState.PRESENT }.map { it.sortIndex }
        }

        // Début du livre (N = 0) : pistes 0 et 1 sur le disque.
        assertThat(presentIndices()).containsExactly(0, 1).inOrder()

        // Passage à la piste N = 1 : la piste 0 (N-1) est gardée et la piste 2 (N+1) est téléchargée.
        val allTracks = db.trackDao().forBook(civ.driveId)
        now += 10_000L
        db.progressDao().upsert(
            ProgressEntity(
                bookId = civ.driveId,
                account = FakeDriveService.ACCOUNT,
                trackId = allTracks[1].driveId,
                trackIndex = 1,
                updatedAt = now,
            ),
        )
        engine.onPlaybackTrackChanged(civ.driveId)
        engine.sync(SyncTrigger.PLAYBACK)
        assertThat(presentIndices()).containsExactly(0, 1, 2).inOrder()

        // Passage à la piste N = 2 : si la piste 0 était encore verrouillée par le lecteur, elle est protégée…
        trackInUse = allTracks[0].driveId
        now += 10_000L
        db.progressDao().upsert(
            ProgressEntity(
                bookId = civ.driveId,
                account = FakeDriveService.ACCOUNT,
                trackId = allTracks[2].driveId,
                trackIndex = 2,
                updatedAt = now,
            ),
        )
        assertThat(engine.onPlaybackTrackChanged(civ.driveId)).isEqualTo(0L)
        assertThat(presentIndices()).containsExactly(0, 1, 2).inOrder()

        // …dès que la piste active devient la 2, la piste 0 (N-2) est supprimée aussitôt et la piste 3 (N+1) descend.
        trackInUse = allTracks[2].driveId
        assertThat(engine.onPlaybackTrackChanged(civ.driveId)).isGreaterThan(0L)
        assertThat(presentIndices()).containsExactly(1, 2).inOrder()
        engine.sync(SyncTrigger.PLAYBACK)
        assertThat(presentIndices()).containsExactly(1, 2, 3).inOrder()
        trackInUse = null

        // Saut direct vers la dernière piste (N = 9) : seules les pistes 8 et 9 restent sur le disque.
        now += 10_000L
        db.progressDao().upsert(
            ProgressEntity(
                bookId = civ.driveId,
                account = FakeDriveService.ACCOUNT,
                trackId = allTracks[9].driveId,
                trackIndex = 9,
                updatedAt = now,
            ),
        )
        engine.onPlaybackTrackChanged(civ.driveId)
        engine.sync(SyncTrigger.PLAYBACK)
        assertThat(presentIndices()).containsExactly(8, 9).inOrder()
    }

    @Test
    fun `hors mode streaming - les pistes non écoutées descendent et chaque piste écoutée est supprimée`() = runBlocking<Unit> {
        settings.update { it.copy(streamingMode = false) }
        engine.sync(SyncTrigger.LAUNCH)
        val civ = book(LibraryFixture.civilisations.name)
        val allTracks = db.trackDao().forBook(civ.driveId)

        // Hors streaming, le livre en écoute descend en entier (0..9) et sa durée devient exacte.
        assertThat(db.trackDao().forBook(civ.driveId).filter { it.state == TrackState.PRESENT }.map { it.sortIndex })
            .containsExactlyElementsIn(0..9)
            .inOrder()
        assertThat(db.bookDao().get(civ.driveId)!!.durationExact).isTrue()

        // Les pistes 0 et 1 viennent d'être écoutées (passage à N = 2) -> supprimées immédiatement du disque.
        now += 10_000L
        db.progressDao().upsert(
            ProgressEntity(
                bookId = civ.driveId,
                account = FakeDriveService.ACCOUNT,
                trackId = allTracks[2].driveId,
                trackIndex = 2,
                updatedAt = now,
            ),
        )
        val freed = engine.onPlaybackTrackChanged(civ.driveId)
        assertThat(freed).isGreaterThan(0L)
        val remaining = db.trackDao().forBook(civ.driveId)
        assertThat(remaining.take(2).all { it.state == TrackState.ABSENT && it.localPath == null }).isTrue()
        assertThat(remaining.drop(2).all { it.state == TrackState.PRESENT && File(it.localPath!!).exists() }).isTrue()

        // Une synchro ultérieure ne re-télécharge PAS les pistes déjà écoutées (< N).
        engine.sync(SyncTrigger.PERIODIC)
        assertThat(db.trackDao().forBook(civ.driveId).filter { it.state == TrackState.PRESENT }.map { it.sortIndex })
            .containsExactlyElementsIn(2..9)
            .inOrder()

        // Si le livre est épinglé (« Garder sur ce téléphone »), toutes les pistes descendent et aucune n'est supprimée.
        db.bookDao().setPinnedOffline(civ.driveId, true)
        engine.sync(SyncTrigger.STATUS_CHANGE)
        assertThat(db.trackDao().presentCount(civ.driveId)).isEqualTo(10)
        assertThat(engine.onPlaybackTrackChanged(civ.driveId)).isEqualTo(0L)
        assertThat(db.trackDao().presentCount(civ.driveId)).isEqualTo(10)

        // On retire l'épinglage et on termine la dernière piste : toutes les pistes locales sont nettoyées.
        db.bookDao().setPinnedOffline(civ.driveId, false)
        now += 10_000L
        db.progressDao().upsert(
            ProgressEntity(
                bookId = civ.driveId,
                account = FakeDriveService.ACCOUNT,
                trackId = allTracks[9].driveId,
                trackIndex = 9,
                percent = 100f,
                finished = true,
                updatedAt = now,
            ),
        )
        assertThat(engine.onPlaybackTrackChanged(civ.driveId)).isGreaterThan(0L)
        assertThat(db.trackDao().presentCount(civ.driveId)).isEqualTo(0)
        assertThat(root.walkTopDown().none { it.isFile && it.extension == "mp3" }).isTrue()
    }

    @Test
    fun `nettoyage manuel - supprimer les fichiers locaux d'un livre ou de tout le téléphone sans toucher à Drive`() = runBlocking<Unit> {
        engine.sync(SyncTrigger.LAUNCH)
        val civ = book(LibraryFixture.civilisations.name)
        val police = book("Police - Hugo Boris")

        // On épingle « Police » pour avoir un second livre présent localement.
        db.bookDao().setPinnedOffline(police.driveId, true)
        engine.sync(SyncTrigger.STATUS_CHANGE)
        assertThat(db.trackDao().presentCount(civ.driveId)).isEqualTo(2)
        assertThat(db.trackDao().presentCount(police.driveId)).isEqualTo(3)

        val policeProgressAt = now + 5_000L
        db.progressDao().upsert(
            ProgressEntity(
                bookId = police.driveId,
                account = FakeDriveService.ACCOUNT,
                trackIndex = 1,
                positionMs = 15_000L,
                updatedAt = policeProgressAt,
            ),
        )

        // 1. Suppression manuelle d'un seul livre (« Police ») : fichiers audio supprimés, épinglage levé,
        //    mais Drive, pochette et progression sont intacts.
        val freedBook = engine.deleteLocalBookFiles(police.driveId)
        assertThat(freedBook).isGreaterThan(0L)
        assertThat(db.trackDao().presentCount(police.driveId)).isEqualTo(0)
        assertThat(db.bookDao().get(police.driveId)!!.pinnedOffline).isFalse()
        assertThat(db.bookDao().get(police.driveId)!!.coverPath?.let(::File)?.exists()).isTrue()
        assertThat(db.progressDao().get(police.driveId, FakeDriveService.ACCOUNT)!!.positionMs).isEqualTo(15_000L)
        assertThat(fake.getFile(police.driveId).trashed).isFalse()
        assertThat(fake.listChildren(police.driveId).count { it.name.endsWith(".mp3") }).isEqualTo(3)
        // « Civilisations » n'a pas été touché.
        assertThat(db.trackDao().presentCount(civ.driveId)).isEqualTo(2)

        // 2. Suppression globale de tous les fichiers audio locaux du téléphone.
        val freedAll = engine.deleteAllLocalAudioFiles()
        assertThat(freedAll).isGreaterThan(0L)
        assertThat(db.trackDao().presentCount(civ.driveId)).isEqualTo(0)
        assertThat(root.walkTopDown().none { it.isFile && it.extension == "mp3" }).isTrue()
        assertThat(db.bookDao().get(civ.driveId)!!.coverPath?.let(::File)?.exists()).isTrue()
        assertThat(fake.getFile(civ.driveId).trashed).isFalse()
    }

    @Test
    fun `bascule vers le mode streaming - cleanupNow nettoie aussitôt hors fenêtre tout en protégeant la piste en lecture`() = runBlocking<Unit> {
        settings.update { it.copy(streamingMode = false) }
        engine.sync(SyncTrigger.LAUNCH)
        val civ = book(LibraryFixture.civilisations.name)
        val allTracks = db.trackDao().forBook(civ.driveId)
        assertThat(db.trackDao().presentCount(civ.driveId)).isEqualTo(10)

        // L'auditeur est sur la piste 5, mais ExoPlayer finit encore de relâcher la piste 0.
        now += 10_000L
        db.progressDao().upsert(
            ProgressEntity(
                bookId = civ.driveId,
                account = FakeDriveService.ACCOUNT,
                trackId = allTracks[5].driveId,
                trackIndex = 5,
                positionMs = 12_000L,
                updatedAt = now,
            ),
        )
        trackInUse = allTracks[0].driveId

        // Activation du mode streaming dans les réglages -> cleanupNow immédiat sans réseau.
        settings.update { it.copy(streamingMode = true) }
        engine.cleanupNow()

        // Seules la fenêtre 4..6 et la piste 0 verrouillée restent présentes.
        assertThat(db.trackDao().forBook(civ.driveId).filter { it.state == TrackState.PRESENT }.map { it.sortIndex })
            .containsExactly(0, 4, 5, 6)
            .inOrder()

        // Dès que la piste 0 n'est plus utilisée, elle est purgée à son tour.
        trackInUse = null
        engine.cleanupNow()
        assertThat(db.trackDao().forBook(civ.driveId).filter { it.state == TrackState.PRESENT }.map { it.sortIndex })
            .containsExactly(4, 5, 6)
            .inOrder()
    }
}

