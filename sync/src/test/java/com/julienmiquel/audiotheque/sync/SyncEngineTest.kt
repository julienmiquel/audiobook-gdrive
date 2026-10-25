package com.julienmiquel.audiotheque.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.julienmiquel.audiotheque.core.db.AppDatabase
import com.julienmiquel.audiotheque.core.db.BookEntity
import com.julienmiquel.audiotheque.core.db.PendingOperationEntity
import com.julienmiquel.audiotheque.core.db.ProgressEntity
import com.julienmiquel.audiotheque.core.model.Accounts
import com.julienmiquel.audiotheque.core.model.BookKind
import com.julienmiquel.audiotheque.core.model.BookStatus
import com.julienmiquel.audiotheque.core.model.CleanupPolicy
import com.julienmiquel.audiotheque.core.model.DriveLibraryDefaults as D
import com.julienmiquel.audiotheque.core.model.SyncEventType
import com.julienmiquel.audiotheque.core.model.SyncResult
import com.julienmiquel.audiotheque.core.model.SyncTrigger
import com.julienmiquel.audiotheque.core.model.TrackState
import com.julienmiquel.audiotheque.core.parse.Mp3Probe
import com.julienmiquel.audiotheque.core.settings.SettingsRepository
import com.julienmiquel.audiotheque.core.sync.Clock
import com.julienmiquel.audiotheque.sync.demo.DemoLibrary
import com.julienmiquel.audiotheque.sync.demo.FakeDriveService
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
import java.io.File
import java.util.Collections
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Moteur de synchro de bout en bout sur le Drive simulé (bibliothèque de la spécification) avec
 * les vrais mp3 de démonstration : inventaire, téléchargements, gestes, Drive fait foi, hors
 * ligne, refus définitif, nettoyage, espace, reprise de transfert et progression appData.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncEngineTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private val assets = File("../app/src/main/assets/demo")
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
        assumeTrue("assets de démonstration absents", File(assets, "civilisations_01.mp3").exists())
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = AppDatabase.inMemory(context)
        settings = SettingsRepository.forFile(File(tmp.root, "settings.preferences_pb"))
        settings.update { it.copy(demoMode = true, onboardingDone = true) }
        root = tmp.newFolder("Audiobooks")
        val storage = object : LocalStorage(context, settings) {
            override fun sharedStorageGranted(): Boolean = false
            override suspend fun root(): File = root
            override fun freeBytes(dir: File): Long = free
        }
        fake = FakeDriveService(content = { File(assets, it).readBytes() }, clock = clock, latencyMs = 0, bytesPerSecond = 0)
        drive = RecordingDrive(fake)
        val network = object : NetworkStatus {
            override fun isConnected() = true
            override fun isUnmetered() = unmetered
        }
        engine = SyncEngine(db, settings, { drive }, storage, network, inspector, clock, "test", isBookInUse = { it == inUse })
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
    fun `première synchro - inventaire complet, seul En écoute descend en entier`() = runBlocking<Unit> {
        val outcome = engine.sync(SyncTrigger.LAUNCH)
        assertThat(outcome.result).isEqualTo(SyncResult.SUCCESS)

        val all = db.bookDao().all()
        assertThat(all).hasSize(54)
        assertThat(all.count { it.status == BookStatus.NEW }).isEqualTo(16)
        assertThat(all.count { it.status == BookStatus.UNSORTED }).isEqualTo(11)
        assertThat(all.count { it.kind == BookKind.ZIP }).isEqualTo(3)
        assertThat(all.count { it.kind == BookKind.SINGLE_FILE }).isEqualTo(1)

        val civ = book(DemoLibrary.civilisations.name)
        assertThat(civ.status).isEqualTo(BookStatus.IN_PROGRESS)
        val tracks = db.trackDao().forBook(civ.driveId)
        assertThat(tracks).hasSize(10)
        assertThat(tracks.map { it.state }.toSet()).containsExactly(TrackState.PRESENT)
        // Intégrité : plus de « .part », taille et checksum de Drive.
        tracks.forEach { t ->
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
        assertThat(fresh.durationExact).isTrue()
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

        assertThat(events().count { it.type == SyncEventType.RECEIVED }).isEqualTo(10)
        val run = db.syncRunDao().observeLastFinished().first()!!
        assertThat(run.filesReceived).isEqualTo(10)
        assertThat(run.fullInventory).isTrue()
    }

    @Test
    fun `Marquer écouté - dossier déplacé dans Drive, pistes supprimées 24 h après`() = runBlocking<Unit> {
        engine.sync(SyncTrigger.LAUNCH)
        val civ = book(DemoLibrary.civilisations.name)

        queueMove(civ, BookStatus.DONE, D.DONE_ID)
        engine.sync(SyncTrigger.STATUS_CHANGE)

        assertThat(fake.getFile(civ.driveId).parents).containsExactly(D.DONE_ID)
        val moved = db.bookDao().get(civ.driveId)!!
        assertThat(moved.status).isEqualTo(BookStatus.DONE)
        assertThat(moved.pendingStatus).isNull()
        assertThat(db.pendingOperationDao().all()).isEmpty()
        assertThat(events().any { it.type == SyncEventType.DRIVE_MOVE && it.bookId == civ.driveId }).isTrue()
        // Pistes gardées 24 h dans _Ecouté.
        assertThat(db.trackDao().presentCount(civ.driveId)).isEqualTo(10)

        now += 25 * 3_600_000L
        engine.sync(SyncTrigger.PERIODIC)
        assertThat(db.trackDao().presentCount(civ.driveId)).isEqualTo(0)
        assertThat(db.trackDao().forBook(civ.driveId).none { it.localPath?.let(::File)?.exists() == true }).isTrue()
        assertThat(events().count { it.type == SyncEventType.LOCAL_DELETE }).isEqualTo(10)
    }

    @Test
    fun `Commencer - le livre descend en entier, en commençant à la position d'écoute`() = runBlocking<Unit> {
        engine.sync(SyncTrigger.LAUNCH)
        val police = book("Police - Hugo Boris")
        db.progressDao().upsert(ProgressEntity(bookId = police.driveId, account = Accounts.DEMO, trackIndex = 2, updatedAt = now))
        drive.opened.clear()

        queueMove(police, BookStatus.IN_PROGRESS, D.IN_PROGRESS_ID)
        engine.sync(SyncTrigger.STATUS_CHANGE)

        assertThat(db.trackDao().presentCount(police.driveId)).isEqualTo(3)
        val order = db.trackDao().forBook(police.driveId).associate { it.driveId to it.sortIndex }
        val firstDownloaded = drive.opened.first { it.first in order.keys && it.second == 0L }.first
        assertThat(order[firstDownloaded]).isEqualTo(2)
    }

    @Test
    fun `Drive fait foi - un dossier déplacé depuis le web change de statut`() = runBlocking<Unit> {
        engine.sync(SyncTrigger.LAUNCH)
        val guerriers = book(DemoLibrary.UNFINISHED_BOOK)
        assertThat(guerriers.status).isEqualTo(BookStatus.DONE)

        fake.simulateExternalMove(guerriers.driveId, D.LATER_ID)
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
        val civ = book(DemoLibrary.civilisations.name)
        assertThat(db.trackDao().presentCount(civ.driveId)).isEqualTo(0)
        assertThat(events().any { it.type == SyncEventType.ERROR && it.message.orEmpty().startsWith("Espace insuffisant") }).isTrue()
    }

    @Test
    fun `reprise après coupure - le fichier part est complété par une requête Range`() = runBlocking<Unit> {
        free = 1L
        engine.sync(SyncTrigger.LAUNCH) // inventaire seulement : pas de place
        val civ = book(DemoLibrary.civilisations.name)
        val first = db.trackDao().forBook(civ.driveId).first()
        val bytes = File(assets, "civilisations_01.mp3").readBytes()
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
    fun `progression par utilisateur dans l'espace caché de l'app`() = runBlocking<Unit> {
        engine.sync(SyncTrigger.LAUNCH)
        // Progression de démonstration importée : livre rangé à tort dans _Ecouté, pas fini.
        val guerriers = book(DemoLibrary.UNFINISHED_BOOK)
        val imported = db.progressDao().get(guerriers.driveId, Accounts.DEMO)!!
        assertThat(imported.percent).isEqualTo(46f)
        assertThat(imported.finished).isFalse()
        assertThat(imported.dirty).isFalse()

        val civ = book(DemoLibrary.civilisations.name)
        db.progressDao().upsert(ProgressEntity(bookId = civ.driveId, account = Accounts.DEMO, trackIndex = 4, positionMs = 12_000, speed = 1.3f, percent = 42f, updatedAt = now))
        engine.sync(SyncTrigger.MANUAL)

        val file = fake.listAppData().single { it.name == ProgressJson.fileName(civ.driveId) }
        val json = ProgressJson.decode(fake.readAppData(file.id))
        assertThat(json.trackIndex).isEqualTo(4)
        assertThat(json.positionMs).isEqualTo(12_000)
        assertThat(json.speed).isEqualTo(1.3f)
        assertThat(db.progressDao().get(civ.driveId, Accounts.DEMO)!!.dirty).isFalse()
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
        val civ = book(DemoLibrary.civilisations.name)
        assertThat(db.trackDao().presentCount(civ.driveId)).isEqualTo(10)

        // Archiver, mais Drive refuse passagèrement le déplacement : rien ne bouge sur le téléphone.
        fake.busyMoves += civ.driveId
        queueMove(civ, BookStatus.ARCHIVED, D.ARCHIVES_ID)
        val busy = engine.sync(SyncTrigger.STATUS_CHANGE)
        assertThat(db.bookDao().get(civ.driveId)!!.status).isEqualTo(BookStatus.IN_PROGRESS)
        assertThat(db.trackDao().presentCount(civ.driveId)).isEqualTo(10)
        assertThat(busy.retryAtMs).isAtLeast(now + SyncEngine.MIN_RETRY_DELAY_MS)

        // Déplacement fait : les pistes restent pendant le délai de grâce…
        fake.busyMoves.clear()
        now += 31_000
        engine.sync(SyncTrigger.STATUS_CHANGE)
        assertThat(db.bookDao().get(civ.driveId)!!.status).isEqualTo(BookStatus.ARCHIVED)
        assertThat(db.trackDao().presentCount(civ.driveId)).isEqualTo(10)

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
        fake.simulateExternalMove(police.driveId, D.DONE_ID) // déplacé depuis le web entre-temps

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
        // Créés par deux téléphones : le plus récent d'abord, l'ancien ensuite.
        val t0 = now
        now = t0 + 60_000
        fake.createAppData(ProgressJson.fileName(police.driveId), json(77_000, now))
        now = t0
        fake.createAppData(ProgressJson.fileName(police.driveId), json(11_000, now))
        now = t0 + 120_000

        engine.sync(SyncTrigger.MANUAL)

        val p = db.progressDao().get(police.driveId, Accounts.DEMO)!!
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
        val civ = book(DemoLibrary.civilisations.name)
        assertThat(db.trackDao().forBook(civ.driveId)).hasSize(10) // pistes listées quand même
        assertThat(db.trackDao().presentCount(civ.driveId)).isEqualTo(0)
        assertThat(metered.waitingForWifi).isTrue()
        // Ni échantillon audio ni pochette sur réseau mesuré.
        assertThat(book("Police - Hugo Boris").probedAt).isNull()
        assertThat(book("Police - Hugo Boris").coverPath).isNull()

        unmetered = true
        val wifi = engine.sync(SyncTrigger.WIFI)
        assertThat(wifi.waitingForWifi).isFalse()
        assertThat(db.trackDao().presentCount(civ.driveId)).isEqualTo(10)
        assertThat(book("Police - Hugo Boris").durationMs).isNotNull()
        assertThat(book("Police - Hugo Boris").coverPath).isNotNull()
    }

    @Test
    fun `livre en cours de lecture - retiré de la bibliothèque seulement après l'écoute`() = runBlocking<Unit> {
        engine.sync(SyncTrigger.LAUNCH)
        val civ = book(DemoLibrary.civilisations.name)
        inUse = civ.driveId
        fake.simulateExternalMove(civ.driveId, "dossier-hors-bibliotheque")

        engine.sync(SyncTrigger.PERIODIC)
        assertThat(db.bookDao().get(civ.driveId)).isNotNull()
        assertThat(db.trackDao().presentCount(civ.driveId)).isEqualTo(10)

        inUse = null
        engine.sync(SyncTrigger.PERIODIC)
        assertThat(db.bookDao().get(civ.driveId)).isNull()
        assertThat(root.walkTopDown().none { it.isFile && it.extension == "mp3" }).isTrue()
    }
}
