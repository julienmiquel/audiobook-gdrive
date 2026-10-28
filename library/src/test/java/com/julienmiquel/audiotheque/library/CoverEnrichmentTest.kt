package com.julienmiquel.audiotheque.library

import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.julienmiquel.audiotheque.core.db.AppDatabase
import com.julienmiquel.audiotheque.core.db.BookEntity
import com.julienmiquel.audiotheque.core.model.BookKind
import com.julienmiquel.audiotheque.core.model.BookStatus
import com.julienmiquel.audiotheque.core.model.DriveLibraryDefaults
import com.julienmiquel.audiotheque.core.parse.TextNormalizer
import com.julienmiquel.audiotheque.core.sync.Clock
import java.io.File
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import okhttp3.mockwebserver.SocketPolicy
import okio.Buffer
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Pochettes en ligne : chaîne de sources (Google Books → Apple Books → Open Library → Google
 * Images), correspondance stricte quand le titre n'est pas confirmé, reprise quand un service
 * est injoignable, et choix des livres qui méritent une recherche.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CoverEnrichmentTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private lateinit var db: AppDatabase
    private lateinit var server: MockWebServer
    private lateinit var coversDir: File
    private val http = OkHttpClient()
    private var now = 1_000_000L
    private val clock = Clock { now }

    /** Réponses par service, modifiables par test ; les images sont servies sous /img/. */
    private var googleBooks: MockResponse = json("""{"items":[]}""")
    private var appleBooks: MockResponse = json("""{"results":[]}""")
    private var openLibrary: MockResponse = json("""{"docs":[]}""")
    private var googleImages: MockResponse = json("""{"items":[]}""")
    private val requests = mutableListOf<String>()

    /** Tous les services injoignables (connexion coupée dès l'ouverture). */
    private var offline = false

    /** Portail captif : chaque service « répond » 200 avec une page HTML. */
    private var captivePortal = false

    @Before
    fun setUp() {
        db = AppDatabase.inMemory(ApplicationProvider.getApplicationContext())
        coversDir = tmp.newFolder("covers")
        server = MockWebServer()
        server.dispatcher = object : Dispatcher() {
            // MockWebServer lit la politique « couper dès l'ouverture » ici, avant de lire la requête.
            override fun peek(): MockResponse =
                if (offline) MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START) else super.peek()

            override fun dispatch(request: RecordedRequest): MockResponse {
                if (offline) return MockResponse().setSocketPolicy(SocketPolicy.DISCONNECT_AT_START)
                if (captivePortal) return MockResponse().setBody("<html><body>Connexion au réseau de l'hôtel</body></html>")
                val path = request.path.orEmpty()
                requests += path
                return when {
                    path.startsWith("/books/v1/volumes") -> googleBooks
                    path.startsWith("/search?") -> appleBooks
                    path.startsWith("/search.json") -> openLibrary
                    path.startsWith("/customsearch") -> googleImages
                    path.startsWith("/img/jpg") -> MockResponse().setBody(Buffer().write(fakeJpeg()))
                    path.startsWith("/img/html") -> MockResponse().setBody("<html>image non disponible</html>")
                    else -> MockResponse().setResponseCode(404)
                }
            }
        }
        server.start()
    }

    @After
    fun tearDown() {
        server.shutdown()
        db.close()
    }

    private fun json(body: String) = MockResponse().setHeader("Content-Type", "application/json").setBody(body)

    private fun url(path: String) = server.url(path).toString()

    /** Un JPEG « plausible » : en-tête JPEG et plus de 1 Ko, c'est tout ce que l'enrichissement vérifie. */
    private fun fakeJpeg(): ByteArray = ByteArray(2048).also { it[0] = 0xFF.toByte(); it[1] = 0xD8.toByte(); it[2] = 0xFF.toByte() }

    private fun enricher(withImages: Boolean = false): Enricher {
        val sources = buildList {
            add(GoogleBooksClient(http, url("/books/v1/"), maxAttempts = 1))
            add(AppleBooksClient(http, url("/"), countries = listOf("fr"), maxAttempts = 1))
            add(OpenLibraryClient(http, url("/"), url("/covers/"), maxAttempts = 1))
            if (withImages) add(GoogleImageSearchClient(http, "cle", "moteur", url("/customsearch/v1"), maxAttempts = 1, allowedSchemes = setOf("http", "https")))
        }
        return Enricher(db, sources, http, coversDir, clock)
    }

    private suspend fun book(id: String, title: String, author: String? = null, confirmed: Boolean = false): BookEntity {
        val b = BookEntity(
            driveId = id,
            name = listOfNotNull(title, author).joinToString(" - "),
            parentId = DriveLibraryDefaults.NEW_ID,
            status = BookStatus.NEW,
            statusChangedAt = 0,
            parsedTitle = title,
            parsedAuthor = author,
            titleConfirmed = confirmed,
            normalizedKey = TextNormalizer.key(title),
            authorKey = TextNormalizer.keyOrNull(author),
            addedAt = 0,
        )
        db.bookDao().upsert(b)
        return b
    }

    @Test
    fun `correspondance - stricte sans titre confirmé, souple une fois confirmé`() {
        val unconfirmed = CoverQuery("Civilisations", "Laurent Binet", confirmed = false)
        assertThat(Matching.accept(unconfirmed, "Civilisations", listOf("Laurent Binet"))).isTrue()
        assertThat(Matching.accept(unconfirmed, "Civilisations : roman", listOf("Binet, Laurent"))).isTrue()
        // Même titre, autre auteur : pas la bonne pochette.
        assertThat(Matching.accept(unconfirmed, "Civilisations", listOf("Jared Diamond"))).isFalse()
        // Titre sans rapport.
        assertThat(Matching.accept(unconfirmed, "La septième fonction du langage", listOf("Laurent Binet"))).isFalse()
        // Titre très court : égalité exacte exigée (« 21 » n'est pas « 21 leçons pour le XXIe siècle »).
        assertThat(Matching.accept(CoverQuery("21", null, false), "21 leçons pour le XXIe siècle", emptyList())).isFalse()
        // Confirmé par l'utilisateur : le titre suffit, l'auteur du catalogue peut différer (traducteur, narrateur…).
        assertThat(Matching.accept(unconfirmed.copy(confirmed = true), "Civilisations", listOf("Jared Diamond"))).isTrue()
    }

    @Test
    fun `Google Books injoignable - la pochette vient d'Apple Books et part vers Drive`() = runBlocking<Unit> {
        googleBooks = MockResponse().setResponseCode(503)
        appleBooks = json(
            """{"results":[{"collectionName":"Civilisations","artistName":"Laurent Binet","artworkUrl100":"${url("/img/jpg/100x100bb.jpg")}"}]}""",
        )
        book("civ", "Civilisations", "Laurent Binet")

        val outcome = enricher().enrich("civ")

        assertThat(outcome).isEqualTo(EnrichOutcome.FOUND)
        val b = db.bookDao().get("civ")!!
        assertThat(b.coverPath).isNotNull()
        assertThat(File(b.coverPath!!).parentFile).isEqualTo(coversDir)
        assertThat(File(b.coverPath!!).length()).isEqualTo(2048)
        assertThat(b.coverUrl).contains("600x600bb")
        assertThat(b.coverUploadPending).isTrue() // à déposer dans le dossier Drive du livre
        assertThat(b.enrichedAt).isEqualTo(now)
        assertThat(requests.none { it.startsWith("/search.json") }).isTrue() // Open Library pas sollicitée
    }

    @Test
    fun `titre non confirmé - une pochette d'un autre livre est refusée, pas de nouvel essai inutile`() = runBlocking<Unit> {
        googleBooks = json(
            """{"items":[{"volumeInfo":{"title":"Abri de fortune","authors":["Un Autre"],"imageLinks":{"thumbnail":"${url("/img/jpg/g.jpg")}"}}}]}""",
        )
        appleBooks = json("""{"results":[{"collectionName":"Fortune de mer","artistName":"Paul Cassidy","artworkUrl100":"${url("/img/jpg/a.jpg")}"}]}""")
        book("abri", "Abri de fortune", "Paul Cassidy")

        val e = enricher()
        assertThat(e.enrich("abri")).isEqualTo(EnrichOutcome.NOT_FOUND)
        val b = db.bookDao().get("abri")!!
        assertThat(b.coverPath).isNull()
        assertThat(b.enrichedAt).isNotNull()
        // Les services ont répondu « rien » : le livre sort de la file jusqu'à une correction du titre.
        assertThat(e.wants(b)).isFalse()
        BookEditor(db).confirm("abri")
        now += Enricher.RETRY_INTERVAL_MS + 1
        assertThat(e.wants(db.bookDao().get("abri")!!)).isTrue()
    }

    @Test
    fun `image morte chez une source - la suivante est essayée, tous injoignables = nouvel essai plus tard`() = runBlocking<Unit> {
        googleBooks = json(
            """{"items":[{"volumeInfo":{"title":"Police","authors":["Hugo Boris"],"description":"Un résumé.","imageLinks":{"thumbnail":"${url("/img/html/dead.jpg")}"}}}]}""",
        )
        openLibrary = json("""{"docs":[{"title":"Police","author_name":["Hugo Boris"],"cover_i":42,"language":["fre"]}]}""")
        book("police", "Police", "Hugo Boris")

        // Open Library sert la couverture /covers/b/id/42-L.jpg → 404 chez nous : rien de téléchargé, mais le résumé est gardé.
        val e = enricher()
        assertThat(e.enrich("police")).isEqualTo(EnrichOutcome.FOUND)
        val b = db.bookDao().get("police")!!
        assertThat(b.summary).isEqualTo("Un résumé.")
        assertThat(b.coverPath).isNull()

        // Tout le monde hors ligne : pas d'échec définitif.
        offline = true
        book("dune", "Les Hérétiques de Dune", "Frank Herbert")
        assertThat(e.enrich("dune")).isEqualTo(EnrichOutcome.UNREACHABLE)
        assertThat(db.bookDao().get("dune")!!.enrichedAt).isNull()

        // Portail captif (page HTML en 200) : même chose, pas de plantage ni d'abandon.
        offline = false
        captivePortal = true
        assertThat(e.enrich("dune")).isEqualTo(EnrichOutcome.UNREACHABLE)
        assertThat(db.bookDao().get("dune")!!.enrichedAt).isNull()
    }

    @Test
    fun `Google Images - dernier recours, seulement avec titre confirmé ou auteur, image au format pochette`() = runBlocking<Unit> {
        googleImages = json(
            """{"items":[
                {"title":"bannière","link":"${url("/img/jpg/wide.jpg")}","image":{"width":1200,"height":300}},
                {"title":"pochette","link":"${url("/img/jpg/cover.jpg")}","image":{"width":600,"height":600}}
            ]}""",
        )
        book("sans-auteur", "Les grandes marées", null)
        book("avec-auteur", "Les grandes marées", "Jacques Poulin")

        val e = enricher(withImages = true)
        assertThat(e.enrich("sans-auteur")).isEqualTo(EnrichOutcome.NOT_FOUND)
        assertThat(requests.none { it.startsWith("/customsearch") }).isTrue()

        assertThat(e.enrich("avec-auteur")).isEqualTo(EnrichOutcome.FOUND)
        val b = db.bookDao().get("avec-auteur")!!
        assertThat(b.coverUrl).endsWith("/img/jpg/cover.jpg") // pas la bannière 4:1
        assertThat(b.coverUploadPending).isTrue()
    }

    @Test
    fun `file d'enrichissement - tous les dossiers qui ressemblent à un livre, pas les noms douteux`() = runBlocking<Unit> {
        appleBooks = json("""{"results":[]}""")
        book("julien", "julien") // un mot, sans auteur : pas cherché
        book("num", "2024")
        book("police", "Police", "Hugo Boris") // auteur connu
        book("marees", "Les grandes marées") // deux mots
        book("confirme", "Zola", confirmed = true)
        book("zip", "archive", confirmed = true).also { db.bookDao().upsert(it.copy(kind = BookKind.ZIP)) }

        val e = enricher()
        val wanted = db.bookDao().all().filter { e.wants(it) }.map { it.driveId }
        assertThat(wanted).containsExactly("police", "marees", "confirme")

        e.enrichPending()
        // Chaque candidat a été cherché une fois (3 services × 3 livres), et pas avant une demi-heure.
        assertThat(requests.count { it.startsWith("/books/v1/volumes") }).isEqualTo(3)
        assertThat(db.bookDao().all().filter { e.wants(it) }).isEmpty()
        now += Enricher.RETRY_INTERVAL_MS + 1
        // Réponses « rien trouvé » enregistrées : plus de nouvel essai tant que le titre ne change pas.
        assertThat(db.bookDao().all().filter { e.wants(it) }).isEmpty()
    }
}
