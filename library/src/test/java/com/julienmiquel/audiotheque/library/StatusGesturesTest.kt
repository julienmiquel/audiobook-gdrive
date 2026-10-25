package com.julienmiquel.audiotheque.library

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.julienmiquel.audiotheque.core.db.AppDatabase
import com.julienmiquel.audiotheque.core.db.BookEntity
import com.julienmiquel.audiotheque.core.db.ProgressEntity
import com.julienmiquel.audiotheque.core.model.BookStatus
import com.julienmiquel.audiotheque.core.model.DriveLibraryDefaults
import com.julienmiquel.audiotheque.core.model.SyncTrigger
import com.julienmiquel.audiotheque.core.model.displayTitle
import com.julienmiquel.audiotheque.core.model.effectiveStatus
import com.julienmiquel.audiotheque.core.parse.TextNormalizer
import com.julienmiquel.audiotheque.core.settings.SettingsRepository
import com.julienmiquel.audiotheque.core.sync.Clock
import com.julienmiquel.audiotheque.core.sync.SyncRequester
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

/** Gestes de statut : file d'opérations, statut affiché aussitôt, « Annuler », hors ligne. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class StatusGesturesTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var settings: SettingsRepository
    private val requests = mutableListOf<SyncTrigger>()
    private val sync = object : SyncRequester {
        override fun requestSync(trigger: SyncTrigger) {
            requests += trigger
        }

        override fun requestFolderRefresh(pairId: Long) = Unit
    }
    private var now = 1_000_000L
    private val clock = Clock { now }
    private lateinit var gestures: StatusGestures

    @Before
    fun setUp() = runBlocking<Unit> {
        db = AppDatabase.inMemory(ApplicationProvider.getApplicationContext())
        settings = SettingsRepository.forFile(tmp.newFile("settings.preferences_pb").also { it.delete() })
        settings.update { it.copy(demoMode = true, onboardingDone = true) }
        db.syncPairDao().insertAll(DriveLibraryDefaults.defaultPairs())
        gestures = StatusGestures(db, settings, sync, clock)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun book(id: String, name: String, parent: String, status: BookStatus): BookEntity {
        val b = BookEntity(
            driveId = id,
            name = name,
            parentId = parent,
            status = status,
            statusChangedAt = 0,
            parsedTitle = name,
            normalizedKey = TextNormalizer.key(name),
            addedAt = 0,
        )
        db.bookDao().upsert(b)
        return b
    }

    @Test
    fun `Commencer - déplacement mis en file, statut affiché aussitôt, synchro demandée`() = runBlocking<Unit> {
        book("police", "Police", DriveLibraryDefaults.NEW_ID, BookStatus.NEW)

        val result = gestures.apply("police", Gesture.COMMENCER)!!

        assertThat(result.from).isEqualTo(BookStatus.NEW)
        assertThat(result.to).isEqualTo(BookStatus.IN_PROGRESS)
        val b = db.bookDao().get("police")!!
        assertThat(b.status).isEqualTo(BookStatus.NEW) // statut confirmé par Drive inchangé
        assertThat(b.effectiveStatus).isEqualTo(BookStatus.IN_PROGRESS)
        val op = db.pendingOperationDao().all().single()
        assertThat(op.fromParentId).isEqualTo(DriveLibraryDefaults.NEW_ID)
        assertThat(op.toParentId).isEqualTo(DriveLibraryDefaults.IN_PROGRESS_ID)
        assertThat(requests).containsExactly(SyncTrigger.STATUS_CHANGE)
    }

    @Test
    fun `Annuler avant la synchro - le livre retrouve son statut`() = runBlocking<Unit> {
        book("police", "Police", DriveLibraryDefaults.NEW_ID, BookStatus.NEW)
        val result = gestures.apply("police", Gesture.COMMENCER)!!

        gestures.undo(result)

        val b = db.bookDao().get("police")!!
        assertThat(b.pendingStatus).isNull()
        assertThat(b.effectiveStatus).isEqualTo(BookStatus.NEW)
        // L'opération restante vise le dossier d'origine : sans effet dans Drive.
        db.pendingOperationDao().all().forEach { assertThat(it.toParentId).isEqualTo(DriveLibraryDefaults.NEW_ID) }
    }

    @Test
    fun `gestes successifs hors ligne - une seule opération, dernière cible`() = runBlocking<Unit> {
        book("civ", "Civilisations", DriveLibraryDefaults.IN_PROGRESS_ID, BookStatus.IN_PROGRESS)
        gestures.apply("civ", Gesture.METTRE_DE_COTE)
        gestures.apply("civ", Gesture.REPRENDRE)
        gestures.apply("civ", Gesture.MARQUER_ECOUTE)

        val ops = db.pendingOperationDao().all()
        assertThat(ops).hasSize(1)
        assertThat(ops.single().toParentId).isEqualTo(DriveLibraryDefaults.DONE_ID)
        assertThat(db.bookDao().get("civ")!!.effectiveStatus).isEqualTo(BookStatus.DONE)
    }

    @Test
    fun `Mettre de côté garde la position et note l'avancement`() = runBlocking<Unit> {
        book("guerriers", "Les guerriers de l'hiver", DriveLibraryDefaults.IN_PROGRESS_ID, BookStatus.IN_PROGRESS)
        val account = settings.current().accountKey
        db.progressDao().upsert(ProgressEntity(bookId = "guerriers", account = account, trackIndex = 3, positionMs = 42_000, percent = 46f, updatedAt = 1))

        gestures.apply("guerriers", Gesture.METTRE_DE_COTE)

        val p = db.progressDao().get("guerriers", account)!!
        assertThat(p.positionMs).isEqualTo(42_000)
        assertThat(p.trackIndex).isEqualTo(3)
        assertThat(p.setAsideAtPercent).isEqualTo(46f)
        assertThat(db.bookDao().get("guerriers")!!.effectiveStatus).isEqualTo(BookStatus.LATER)
    }

    @Test
    fun `Marquer écouté termine la progression, Réécouter repart du début`() = runBlocking<Unit> {
        book("civ", "Civilisations", DriveLibraryDefaults.IN_PROGRESS_ID, BookStatus.IN_PROGRESS)
        val account = settings.current().accountKey
        db.progressDao().upsert(ProgressEntity(bookId = "civ", account = account, trackIndex = 9, positionMs = 15_000, percent = 99f, speed = 1.3f, updatedAt = 1))

        gestures.apply("civ", Gesture.MARQUER_ECOUTE)
        assertThat(db.progressDao().get("civ", account)!!.finished).isTrue()

        gestures.apply("civ", Gesture.REECOUTER)
        val p = db.progressDao().get("civ", account)!!
        assertThat(p.finished).isFalse()
        assertThat(p.positionMs).isEqualTo(0)
        assertThat(p.speed).isEqualTo(1.3f) // vitesse mémorisée par livre
    }

    @Test
    fun `déplacement vers n'importe quel statut depuis la fiche`() = runBlocking<Unit> {
        book("guerriers", "Les guerriers de l'hiver", DriveLibraryDefaults.DONE_ID, BookStatus.DONE)
        val result = gestures.moveTo("guerriers", BookStatus.LATER)!!
        assertThat(result.to).isEqualTo(BookStatus.LATER)
        assertThat(db.pendingOperationDao().all().single().toParentId).isEqualTo(DriveLibraryDefaults.LATER_ID)
    }

    @Test
    fun `gestes proposés selon le statut (cycle de vie)`() {
        assertThat(Gesture.forStatus(BookStatus.UNSORTED)).contains(Gesture.CLASSER)
        assertThat(Gesture.forStatus(BookStatus.NEW)).containsExactly(Gesture.COMMENCER, Gesture.PLUS_TARD)
        assertThat(Gesture.forStatus(BookStatus.IN_PROGRESS)).containsExactly(Gesture.METTRE_DE_COTE, Gesture.MARQUER_ECOUTE)
        assertThat(Gesture.forStatus(BookStatus.LATER)).contains(Gesture.REPRENDRE)
        assertThat(Gesture.forStatus(BookStatus.DONE)).containsExactly(Gesture.REECOUTER, Gesture.ARCHIVER)
    }

    @Test
    fun `corrections stockées par l'app, dossier Drive jamais renommé`() = runBlocking<Unit> {
        book("dune", "DUNES__LES_HERETIQUES_DE_DUNE", DriveLibraryDefaults.DONE_ID, BookStatus.DONE)
        BookEditor(db).save("dune", "Les Hérétiques de Dune", "Frank Herbert", "")
        val b = db.bookDao().get("dune")!!
        assertThat(b.name).isEqualTo("DUNES__LES_HERETIQUES_DE_DUNE")
        assertThat(b.displayTitle).isEqualTo("Les Hérétiques de Dune")
        assertThat(b.customAuthor).isEqualTo("Frank Herbert")
        assertThat(b.titleConfirmed).isTrue()
    }

    @Test
    fun `bibliothèque - recherche globale, filtre Lu par et doublons`() = runBlocking<Unit> {
        book("a", "Le charme discret de l'intestin", DriveLibraryDefaults.ROOT_ID, BookStatus.UNSORTED)
        book("b", "Le charme discret de l'intestin", DriveLibraryDefaults.ROOT_ID, BookStatus.UNSORTED)
        db.bookDao().upsert(
            BookEntity(
                driveId = "c", name = "La vie devant soi - Romain Gary (lu par Thierry Blanc)", parentId = DriveLibraryDefaults.NEW_ID,
                status = BookStatus.NEW, statusChangedAt = 0, parsedTitle = "La vie devant soi", parsedAuthor = "Romain Gary",
                parsedNarrator = "Thierry Blanc", normalizedKey = TextNormalizer.key("La vie devant soi"), addedAt = 0,
            ),
        )
        val repo = LibraryRepository(db, settings)
        val items = repo.observeLibrary().first()

        assertThat(items.filter { it.isDuplicate }.map { it.id }).containsExactly("a", "b")
        assertThat(LibraryRepository.narrators(items)).containsExactly("Thierry Blanc")
        assertThat(LibraryRepository.filter(items, BookStatus.UNSORTED, narrator = "Thierry Blanc")).isEmpty()
        // La recherche porte sur toute la bibliothèque, quel que soit l'onglet.
        assertThat(LibraryRepository.filter(items, BookStatus.UNSORTED, query = "gary").map { it.id }).containsExactly("c")
        assertThat(LibraryRepository.filter(items, BookStatus.NEW, narrator = "thierry blanc").map { it.id }).containsExactly("c")
    }
}
