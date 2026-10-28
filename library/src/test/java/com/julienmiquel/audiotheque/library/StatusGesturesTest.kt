package com.julienmiquel.audiotheque.library

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.julienmiquel.audiotheque.core.db.AppDatabase
import com.julienmiquel.audiotheque.core.db.BookEntity
import com.julienmiquel.audiotheque.core.db.ProgressEntity
import com.julienmiquel.audiotheque.core.model.BookStatus
import com.julienmiquel.audiotheque.core.model.DriveLibraryDefaults
import com.julienmiquel.audiotheque.core.model.OperationType
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
    }
    private var now = 1_000_000L
    private val clock = Clock { now }
    private lateinit var gestures: StatusGestures

    @Before
    fun setUp() = runBlocking<Unit> {
        db = AppDatabase.inMemory(ApplicationProvider.getApplicationContext())
        settings = SettingsRepository.forFile(tmp.newFile("settings.preferences_pb").also { it.delete() })
        settings.update { it.copy(accountEmail = "lecteur@example.com", onboardingDone = true) }
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
        assertThat(b.effectiveStatus).isEqualTo(BookStatus.NEW)
        // Le déplacement aller est peut-être en cours : le statut reste « en attente » (vers le
        // dossier d'origine) jusqu'à ce que le moteur ait vérifié Drive, sans changer de date.
        assertThat(b.pendingParentId).isEqualTo(DriveLibraryDefaults.NEW_ID)
        assertThat(b.pendingSince).isEqualTo(b.statusChangedAt)
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
    fun `Réécouter un livre pas fini garde sa position`() = runBlocking<Unit> {
        // « Les guerriers de l'hiver » : dans _Ecouté à 46 % (exemple de la spécification).
        book("guerriers", "Les guerriers de l'hiver", DriveLibraryDefaults.DONE_ID, BookStatus.DONE)
        val account = settings.current().accountKey
        db.progressDao().upsert(ProgressEntity(bookId = "guerriers", account = account, trackIndex = 5, positionMs = 61_000, percent = 46f, updatedAt = 1))

        gestures.apply("guerriers", Gesture.REECOUTER)

        val p = db.progressDao().get("guerriers", account)!!
        assertThat(p.trackIndex).isEqualTo(5)
        assertThat(p.positionMs).isEqualTo(61_000)
        assertThat(db.bookDao().get("guerriers")!!.effectiveStatus).isEqualTo(BookStatus.IN_PROGRESS)
    }

    @Test
    fun `Annuler rétablit la progression modifiée par le geste`() = runBlocking<Unit> {
        book("civ", "Civilisations", DriveLibraryDefaults.IN_PROGRESS_ID, BookStatus.IN_PROGRESS)
        val account = settings.current().accountKey
        db.progressDao().upsert(ProgressEntity(bookId = "civ", account = account, trackIndex = 4, positionMs = 9_000, percent = 52f, updatedAt = 1))

        val marked = gestures.apply("civ", Gesture.MARQUER_ECOUTE)!!
        assertThat(db.progressDao().get("civ", account)!!.finished).isTrue()
        now += 3_000
        gestures.undo(marked)

        val p = db.progressDao().get("civ", account)!!
        assertThat(p.finished).isFalse()
        assertThat(p.positionMs).isEqualTo(9_000)
        assertThat(db.bookDao().get("civ")!!.effectiveStatus).isEqualTo(BookStatus.IN_PROGRESS)

        val setAside = gestures.apply("civ", Gesture.METTRE_DE_COTE)!!
        assertThat(db.progressDao().get("civ", account)!!.setAsideAtPercent).isEqualTo(52f)
        gestures.undo(setAside)
        assertThat(db.progressDao().get("civ", account)!!.setAsideAtPercent).isNull()
    }

    @Test
    fun `Annuler remet le livre dans son dossier de départ, pas le premier du même statut`() = runBlocking<Unit> {
        val contesId = db.syncPairDao().insert(
            com.julienmiquel.audiotheque.core.db.SyncPairEntity(
                status = BookStatus.COLLECTION,
                name = "_contes",
                driveFolderId = "contes-folder",
                drivePath = "/AudioBook/_contes",
                mode = com.julienmiquel.audiotheque.core.model.PairMode.ON_OPEN,
                position = 7,
            ),
        )
        book("conte", "Le petit prince", "contes-folder", BookStatus.COLLECTION)

        val moved = gestures.moveTo("conte", BookStatus.LATER)!!
        assertThat(moved.fromParentId).isEqualTo("contes-folder")
        gestures.undo(moved)

        val b = db.bookDao().get("conte")!!
        assertThat(b.parentId).isEqualTo("contes-folder")
        // Pas _meditations, la première paire « collection ».
        assertThat(b.pendingParentId).isEqualTo("contes-folder")
        assertThat(db.pendingOperationDao().all().single().toParentId).isEqualTo("contes-folder")
        assertThat(db.syncPairDao().get(contesId)).isNotNull()
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

    @Test
    fun `doublons - même titre, sauf si les deux auteurs connus diffèrent`() = runBlocking<Unit> {
        fun entity(id: String, title: String, author: String?) = BookEntity(
            driveId = id, name = listOfNotNull(author, title).joinToString(" - "), parentId = DriveLibraryDefaults.NEW_ID,
            status = BookStatus.NEW, statusChangedAt = 0, parsedTitle = title, parsedAuthor = author,
            normalizedKey = TextNormalizer.key(title), authorKey = TextNormalizer.keyOrNull(author), addedAt = 0,
        )
        db.bookDao().upsertAll(
            listOf(
                entity("dune-herbert", "Dune", "Frank Herbert"),
                entity("dune-autre", "Dune", "Autre Auteur"),
                entity("abri-1", "Abri de fortune", "Paul Cassidy"),
                entity("abri-2", "Abri de fortune", null),
            ),
        )
        val repo = LibraryRepository(db, settings)
        val items = repo.observeLibrary().first().associateBy { it.id }

        assertThat(items.getValue("dune-herbert").isDuplicate).isFalse()
        assertThat(items.getValue("dune-autre").isDuplicate).isFalse()
        assertThat(items.getValue("abri-1").isDuplicate).isTrue()
        assertThat(items.getValue("abri-2").isDuplicate).isTrue()
        assertThat(repo.duplicatesOf(db.bookDao().get("abri-1")!!).map { it.driveId }).containsExactly("abri-2")
    }

    @Test
    fun `Supprimer un doublon - corbeille Drive en file, livre masqué aussitôt, Annuler possible`() = runBlocking<Unit> {
        book("abri-1", "Abri de fortune", DriveLibraryDefaults.LATER_ID, BookStatus.LATER)
        book("abri-2", "Abri de fortune", DriveLibraryDefaults.LATER_ID, BookStatus.LATER)
        val repo = LibraryRepository(db, settings)
        assertThat(repo.observeLibrary().first().filter { it.isDuplicate }.map { it.id }).containsExactly("abri-1", "abri-2")

        // Jamais pour le livre chargé dans le lecteur.
        assertThat(gestures.trash("abri-2", isInUse = true)).isNull()
        assertThat(db.pendingOperationDao().all()).isEmpty()

        val result = gestures.trash("abri-2")!!
        assertThat(result.title).isEqualTo("Abri de fortune")
        val op = db.pendingOperationDao().all().single()
        assertThat(op.type).isEqualTo(OperationType.TRASH_BOOK)
        assertThat(op.bookId).isEqualTo("abri-2")
        assertThat(db.bookDao().get("abri-2")!!.trashPending).isTrue()
        assertThat(requests).containsExactly(SyncTrigger.STATUS_CHANGE)
        // Disparu de la bibliothèque sans attendre Drive ; l'autre exemplaire n'est plus un doublon.
        val items = repo.observeLibrary().first()
        assertThat(items.map { it.id }).containsExactly("abri-1")
        assertThat(items.single().isDuplicate).isFalse()
        // Un geste de statut sur un livre en cours de suppression est ignoré.
        assertThat(gestures.moveTo("abri-2", BookStatus.DONE)).isNull()
        assertThat(db.pendingOperationDao().all().single().type).isEqualTo(OperationType.TRASH_BOOK)

        assertThat(gestures.undoTrash("abri-2")).isTrue()
        assertThat(db.pendingOperationDao().all()).isEmpty()
        assertThat(db.bookDao().get("abri-2")!!.trashPending).isFalse()
        assertThat(repo.observeLibrary().first().map { it.id }).containsExactly("abri-1", "abri-2")
    }

    @Test
    fun `Supprimer un doublon remplace un déplacement encore en file`() = runBlocking<Unit> {
        book("abri-2", "Abri de fortune", DriveLibraryDefaults.LATER_ID, BookStatus.LATER)
        gestures.apply("abri-2", Gesture.REPRENDRE)
        assertThat(db.bookDao().get("abri-2")!!.effectiveStatus).isEqualTo(BookStatus.IN_PROGRESS)

        gestures.trash("abri-2")

        val ops = db.pendingOperationDao().all()
        assertThat(ops).hasSize(1)
        assertThat(ops.single().type).isEqualTo(OperationType.TRASH_BOOK)
        val b = db.bookDao().get("abri-2")!!
        assertThat(b.pendingStatus).isNull()
        assertThat(b.trashPending).isTrue()
    }
}
