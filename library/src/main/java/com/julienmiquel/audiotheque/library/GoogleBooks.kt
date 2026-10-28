package com.julienmiquel.audiotheque.library

import com.julienmiquel.audiotheque.core.db.AppDatabase
import com.julienmiquel.audiotheque.core.db.BookEntity
import com.julienmiquel.audiotheque.core.model.BookKind
import com.julienmiquel.audiotheque.core.model.displayAuthor
import com.julienmiquel.audiotheque.core.model.displayTitle
import com.julienmiquel.audiotheque.core.parse.TextNormalizer
import com.julienmiquel.audiotheque.core.sync.Clock
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** Pochette et résumé trouvés en ligne. */
data class BookInfo(
    val title: String?,
    val authors: List<String>,
    val description: String?,
    val coverUrl: String?,
    /** Service qui a répondu (« Google Books », « Apple Books »…). */
    val source: String,
)

/** Ce qu'on cherche : titre et auteur affichés, et si l'utilisateur les a confirmés. */
data class CoverQuery(val title: String, val author: String?, val confirmed: Boolean)

/**
 * Une source de pochettes (et éventuellement de résumés). Avec un titre **non confirmé** (simple
 * découpage du nom de dossier), seule une correspondance nette du titre — et de l'auteur s'il est
 * connu — est acceptée : jamais une pochette au hasard sur un dossier « julien » ou « 21 ».
 */
interface CoverSource {
    val name: String

    /** Fournit aussi un résumé (Google Books) : consultée même quand la pochette est déjà là. */
    val providesDescription: Boolean get() = false

    suspend fun search(query: CoverQuery): BookInfo?
}

/** Correspondance titre / auteur entre un résultat en ligne et le livre. */
object Matching {
    private const val MIN_TITLE_KEY = 4

    fun titleMatches(candidate: String?, wanted: String): Boolean {
        val c = candidate?.let(TextNormalizer::key).orEmpty()
        val w = TextNormalizer.key(wanted)
        if (c.length < MIN_TITLE_KEY || w.length < MIN_TITLE_KEY) return c == w && c.isNotEmpty()
        return c.contains(w) || w.contains(c)
    }

    /** Vrai si le nom de famille (dernier mot) de l'auteur voulu apparaît chez un auteur du résultat. */
    fun authorMatches(candidates: List<String>, wanted: String?): Boolean {
        val w = wanted?.let(TextNormalizer::key)?.split(' ')?.filter { it.length >= 3 } ?: return true
        if (w.isEmpty()) return true
        val last = w.last()
        return candidates.any { c -> TextNormalizer.key(c).let { k -> k.contains(last) || w.all { k.contains(it) } } }
    }

    /** Résultat acceptable pour cette recherche. */
    fun accept(query: CoverQuery, candidateTitle: String?, candidateAuthors: List<String>): Boolean {
        val titleOk = titleMatches(candidateTitle, query.title)
        if (!query.confirmed) return titleOk && (query.author == null || authorMatches(candidateAuthors, query.author))
        return titleOk
    }
}

/** Réponse HTTP en erreur d'un service d'enrichissement. */
class RemoteServiceException(val service: String, val code: Int) : IOException("$service $code") {
    /** Limite de débit ou serveur indisponible : vaut une nouvelle tentative. */
    val isTransient: Boolean get() = code == 429 || code == 408 || code >= 500
}

/**
 * Appels HTTP des services d'enrichissement : nouvelles tentatives espacées quand le service est
 * injoignable ou passagèrement en erreur (limite de débit, serveur indisponible). Google Books
 * sans clé est soumis à des quotas par adresse IP : sur un réseau mobile partagé, un 429 n'est
 * pas rare.
 */
internal class RetryingHttp(
    private val http: OkHttpClient,
    private val maxAttempts: Int = 3,
    private val backoffBaseMs: Long = 700,
) {
    /** GET avec nouvelles tentatives ; retourne le corps (texte) d'une réponse 2xx. */
    suspend fun getText(url: HttpUrl, service: String): String = retrying(service, Request.Builder().url(url).get().build()) { request ->
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw RemoteServiceException(service, response.code)
            response.body?.string().orEmpty()
        }
    }

    /**
     * GET d'une réponse JSON. Une réponse 2xx illisible (portail captif, corps tronqué) vaut un
     * service injoignable — nouvel essai plus tard — et non une erreur de l'app.
     */
    suspend fun <T> getJson(url: HttpUrl, service: String, json: Json, deserializer: DeserializationStrategy<T>): T {
        val text = getText(url, service)
        return try {
            json.decodeFromString(deserializer, text)
        } catch (e: SerializationException) {
            throw IOException("$service : réponse illisible", e)
        } catch (e: IllegalArgumentException) {
            throw IOException("$service : réponse illisible", e)
        }
    }

    /** GET d'un contenu binaire plafonné à [maxBytes] octets (pochette). */
    suspend fun getBytes(url: HttpUrl, service: String, maxBytes: Int): ByteArray = retrying(service, Request.Builder().url(url).get().build()) { request ->
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw RemoteServiceException(service, response.code)
            val body = response.body ?: throw IOException("$service : réponse vide")
            if (body.contentLength() > maxBytes) throw IOException("$service : image trop grande")
            val bytes = body.byteStream().readNBytes(maxBytes + 1)
            if (bytes.size > maxBytes) throw IOException("$service : image trop grande")
            bytes
        }
    }

    private suspend fun <T> retrying(service: String, request: Request, block: (Request) -> T): T = withContext(Dispatchers.IO) {
        var attempt = 0
        while (true) {
            attempt++
            try {
                return@withContext block(request)
            } catch (e: IOException) {
                val retryable = e !is RemoteServiceException || e.isTransient
                if (!retryable || attempt >= maxAttempts) throw e
            }
            delay(backoffBaseMs shl (attempt - 1))
        }
        @Suppress("UNREACHABLE_CODE")
        error("$service : sans réponse")
    }
}

/**
 * Google Books (sans clé, faible volume) : pochette **et résumé**. Une seule requête par livre
 * (les résultats en français sont préférés côté app). Seuls le titre et l'auteur sont envoyés.
 */
class GoogleBooksClient(
    http: OkHttpClient,
    baseUrl: String = "https://www.googleapis.com/books/v1/",
    maxAttempts: Int = 3,
    backoffBaseMs: Long = 700,
) : CoverSource {
    override val name: String get() = SERVICE
    override val providesDescription: Boolean get() = true
    private val base: HttpUrl = baseUrl.toHttpUrl()
    private val json = Json { ignoreUnknownKeys = true }
    private val retrying = RetryingHttp(http, maxAttempts, backoffBaseMs)

    override suspend fun search(query: CoverQuery): BookInfo? {
        val q = buildString {
            append("intitle:").append(query.title)
            if (!query.author.isNullOrBlank()) append(" inauthor:").append(query.author)
        }
        val url = base.newBuilder().addPathSegment("volumes")
            .addQueryParameter("q", q)
            .addQueryParameter("maxResults", "10")
            .addQueryParameter("printType", "books")
            .build()
        val volumes = retrying.getJson(url, SERVICE, json, VolumesDto.serializer()).items
        val acceptable = volumes.filter { v ->
            val info = v.volumeInfo ?: return@filter false
            (info.imageLinks != null || info.description != null) && Matching.accept(query, info.title, info.authors)
        }
        val pool = acceptable.ifEmpty {
            // Titre confirmé par l'utilisateur : à défaut d'un titre identique, le premier résultat illustré.
            if (query.confirmed) volumes.filter { it.volumeInfo?.imageLinks != null || it.volumeInfo?.description != null } else emptyList()
        }
        val best = pool.sortedWith(
            compareByDescending<VolumeDto> { Matching.titleMatches(it.volumeInfo?.title, query.title) }
                .thenByDescending { it.volumeInfo?.language == "fr" }
                .thenByDescending { it.volumeInfo?.imageLinks != null }
                .thenByDescending { it.volumeInfo?.description != null },
        ).firstOrNull()
        val info = best?.volumeInfo ?: return null
        return BookInfo(
            title = info.title,
            authors = info.authors,
            description = info.description,
            coverUrl = (info.imageLinks?.thumbnail ?: info.imageLinks?.smallThumbnail)
                ?.replace("http://", "https://")
                ?.replace("&edge=curl", ""),
            source = SERVICE,
        )
    }

    @Serializable
    internal data class VolumesDto(val items: List<VolumeDto> = emptyList())

    @Serializable
    internal data class VolumeDto(val volumeInfo: VolumeInfoDto? = null)

    @Serializable
    internal data class VolumeInfoDto(
        val title: String? = null,
        val authors: List<String> = emptyList(),
        val description: String? = null,
        val language: String? = null,
        val imageLinks: ImageLinksDto? = null,
    )

    @Serializable
    internal data class ImageLinksDto(val smallThumbnail: String? = null, val thumbnail: String? = null)

    companion object {
        const val SERVICE = "Google Books"
    }
}

/**
 * Apple Books (API de recherche iTunes, sans clé) : le **catalogue des livres audio**, avec les
 * vraies pochettes d'audiobooks en haute définition (600 px), catalogue français d'abord.
 */
class AppleBooksClient(
    http: OkHttpClient,
    baseUrl: String = "https://itunes.apple.com/",
    private val countries: List<String> = listOf("fr", "us"),
    maxAttempts: Int = 2,
    backoffBaseMs: Long = 700,
) : CoverSource {
    override val name: String get() = SERVICE
    private val base: HttpUrl = baseUrl.toHttpUrl()
    private val json = Json { ignoreUnknownKeys = true }
    private val retrying = RetryingHttp(http, maxAttempts, backoffBaseMs)

    override suspend fun search(query: CoverQuery): BookInfo? {
        val term = listOfNotNull(query.title, query.author).joinToString(" ")
        for (country in countries) {
            val url = base.newBuilder().addPathSegment("search")
                .addQueryParameter("term", term)
                .addQueryParameter("media", "audiobook")
                .addQueryParameter("entity", "audiobook")
                .addQueryParameter("country", country)
                .addQueryParameter("limit", "8")
                .build()
            val results = retrying.getJson(url, SERVICE, json, SearchDto.serializer()).results
            val best = results
                .filter { it.artworkUrl100 != null && Matching.accept(query, it.collectionName, listOfNotNull(it.artistName)) }
                .sortedByDescending { Matching.titleMatches(it.collectionName, query.title) }
                .firstOrNull() ?: continue
            return BookInfo(
                title = best.collectionName,
                authors = listOfNotNull(best.artistName),
                description = null,
                coverUrl = best.artworkUrl100!!.replace("100x100bb", "600x600bb"),
                source = SERVICE,
            )
        }
        return null
    }

    @Serializable
    internal data class SearchDto(val results: List<ResultDto> = emptyList())

    @Serializable
    internal data class ResultDto(
        val collectionName: String? = null,
        val artistName: String? = null,
        val artworkUrl100: String? = null,
    )

    companion object {
        const val SERVICE = "Apple Books"
    }
}

/**
 * Open Library (sans clé) : l'API de recherche (titre et auteur seulement) et son service de
 * couvertures. Secours quand les catalogues précédents n'ont rien.
 */
class OpenLibraryClient(
    http: OkHttpClient,
    baseUrl: String = "https://openlibrary.org/",
    coversBaseUrl: String = "https://covers.openlibrary.org/",
    maxAttempts: Int = 2,
    backoffBaseMs: Long = 700,
) : CoverSource {
    override val name: String get() = SERVICE
    private val base: HttpUrl = baseUrl.toHttpUrl()
    private val covers: HttpUrl = coversBaseUrl.toHttpUrl()
    private val json = Json { ignoreUnknownKeys = true }
    private val retrying = RetryingHttp(http, maxAttempts, backoffBaseMs)

    override suspend fun search(query: CoverQuery): BookInfo? {
        val url = base.newBuilder().addPathSegment("search.json")
            .addQueryParameter("title", query.title)
            .apply { if (!query.author.isNullOrBlank()) addQueryParameter("author", query.author) }
            .addQueryParameter("limit", "5")
            .addQueryParameter("fields", "key,title,author_name,cover_i,language")
            .build()
        val docs = retrying.getJson(url, SERVICE, json, SearchDto.serializer()).docs
        val best = docs.filter { it.cover_i != null && Matching.accept(query, it.title, it.author_name) }
            .sortedWith(
                compareByDescending<DocDto> { Matching.titleMatches(it.title, query.title) }
                    .thenByDescending { "fre" in it.language },
            )
            .firstOrNull() ?: return null
        return BookInfo(
            title = best.title,
            authors = best.author_name,
            description = null,
            coverUrl = covers.newBuilder().addPathSegments("b/id/${best.cover_i}-L.jpg").build().toString(),
            source = SERVICE,
        )
    }

    @Serializable
    internal data class SearchDto(val docs: List<DocDto> = emptyList())

    @Serializable
    internal data class DocDto(
        val key: String? = null,
        val title: String? = null,
        val author_name: List<String> = emptyList(),
        val cover_i: Long? = null,
        val language: List<String> = emptyList(),
    )

    companion object {
        const val SERVICE = "Open Library"
    }
}

/**
 * Recherche d'images Google (API Custom Search JSON, moteur programmable réglé sur « tout le
 * web » + recherche d'images), dernier recours quand aucun catalogue n'a la pochette. Nécessite
 * une clé d'API et l'identifiant du moteur (`audiotheque.customSearchKey` / `…Cx` dans
 * `local.properties`) ; 100 requêtes gratuites par jour. Faute de titre dans les résultats, on
 * exige un titre confirmé ou un auteur connu, et une image à peu près au format d'une pochette.
 */
class GoogleImageSearchClient(
    http: OkHttpClient,
    private val apiKey: String,
    private val engineId: String,
    baseUrl: String = "https://www.googleapis.com/customsearch/v1",
    maxAttempts: Int = 2,
    backoffBaseMs: Long = 700,
    /** Seules les images servies en HTTPS sont retenues (le trafic en clair est bloqué sur Android). */
    private val allowedSchemes: Set<String> = setOf("https"),
) : CoverSource {
    override val name: String get() = SERVICE
    private val base: HttpUrl = baseUrl.toHttpUrl()
    private val json = Json { ignoreUnknownKeys = true }
    private val retrying = RetryingHttp(http, maxAttempts, backoffBaseMs)

    override suspend fun search(query: CoverQuery): BookInfo? {
        if (!query.confirmed && query.author == null) return null
        val q = listOfNotNull(query.title, query.author, "livre audio couverture").joinToString(" ")
        val url = base.newBuilder()
            .addQueryParameter("key", apiKey)
            .addQueryParameter("cx", engineId)
            .addQueryParameter("searchType", "image")
            .addQueryParameter("num", "8")
            .addQueryParameter("imgSize", "large")
            .addQueryParameter("safe", "active")
            .addQueryParameter("q", q)
            .build()
        val items = retrying.getJson(url, SERVICE, json, SearchDto.serializer()).items
        val best = items.firstOrNull { item ->
            val w = item.image?.width ?: 0
            val h = item.image?.height ?: 0
            val ratio = if (h > 0) w.toDouble() / h else 0.0
            val scheme = item.link?.let { runCatching { it.toHttpUrl().scheme }.getOrNull() }
            w >= 250 && h >= 250 && ratio in 0.55..1.15 && scheme in allowedSchemes
        } ?: return null
        return BookInfo(title = best.title, authors = emptyList(), description = null, coverUrl = best.link, source = SERVICE)
    }

    @Serializable
    internal data class SearchDto(val items: List<ItemDto> = emptyList())

    @Serializable
    internal data class ItemDto(val title: String? = null, val link: String? = null, val image: ImageDto? = null)

    @Serializable
    internal data class ImageDto(val width: Int? = null, val height: Int? = null)

    companion object {
        const val SERVICE = "Google Images"
    }
}

/** Issue d'un enrichissement. */
enum class EnrichOutcome {
    /** Pochette ou résumé trouvés (et pochette enregistrée sur le téléphone si possible). */
    FOUND,

    /** Les services ont répondu, sans résultat. */
    NOT_FOUND,

    /** Services injoignables : nouvel essai automatique plus tard. */
    UNREACHABLE,
}

/**
 * Applique l'enrichissement à un livre : les sources sont interrogées dans l'ordre (Google
 * Books pour le résumé et une première pochette, Apple Books pour les vraies pochettes de
 * livres audio, Open Library, puis Google Images si configuré), puis la pochette est
 * **téléchargée une fois pour toutes** dans le dossier des pochettes du téléphone — plus aucun
 * réseau à l'affichage — et marquée à déposer dans le dossier Drive du livre, pour les autres
 * téléphones. Un service injoignable n'est pas un échec définitif : les livres concernés sont
 * repris par [enrichPending], au plus une fois par demi-heure.
 */
class Enricher(
    private val db: AppDatabase,
    private val sources: List<CoverSource>,
    http: OkHttpClient,
    private val coversDir: File,
    private val clock: Clock = Clock.SYSTEM,
) {
    private val images = RetryingHttp(http, maxAttempts = 3, backoffBaseMs = 500)

    /** Dernier essai par livre (durée de vie du processus) : pas de harcèlement des services. */
    private val attempts = ConcurrentHashMap<String, Long>()

    suspend fun enrich(bookId: String): EnrichOutcome {
        val book = db.bookDao().get(bookId) ?: return EnrichOutcome.NOT_FOUND
        attempts[bookId] = clock.now()
        val hasLocalCover = book.coverPath?.let { File(it).exists() } == true
        var downloaded = false
        var unreachable = false

        // Pochette déjà connue mais jamais téléchargée (réseau coupé la dernière fois) : reprise sans rechercher.
        val knownCoverUrl = book.coverUrl
        if (knownCoverUrl != null && !hasLocalCover && book.enrichedAt != null) {
            when (downloadCover(bookId, knownCoverUrl)) {
                Download.OK -> return EnrichOutcome.FOUND
                Download.UNREACHABLE -> return EnrichOutcome.UNREACHABLE
                Download.REJECTED -> Unit // adresse morte : on cherche à nouveau
            }
        }

        val query = CoverQuery(book.displayTitle, book.displayAuthor, book.titleConfirmed)
        var coverUrl: String? = null
        var description: String? = null
        var answered = false
        for (source in sources) {
            val needsCover = !downloaded && !hasLocalCover
            val needsDescription = description == null && source.providesDescription
            if (!needsCover && !needsDescription) continue
            try {
                val found = source.search(query)
                answered = true
                if (found == null) continue
                if (description == null) description = found.description
                val url = found.coverUrl
                if (needsCover && url != null) {
                    when (downloadCover(bookId, url)) {
                        Download.OK -> {
                            downloaded = true
                            coverUrl = url
                        }
                        Download.UNREACHABLE -> unreachable = true
                        Download.REJECTED -> Unit // image indisponible chez cette source : la suivante
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: IOException) {
                unreachable = true
            }
        }
        if (!downloaded && description == null) {
            if (!answered || (unreachable && !hasLocalCover)) return EnrichOutcome.UNREACHABLE
            db.bookDao().setEnrichment(bookId, book.coverUrl, book.summary, clock.now())
            return EnrichOutcome.NOT_FOUND
        }
        db.bookDao().setEnrichment(bookId, coverUrl ?: book.coverUrl, description ?: book.summary, clock.now())
        return EnrichOutcome.FOUND
    }

    /**
     * Livres sans pochette locale qui méritent une recherche : au plus [limit] par passe, arrêt
     * après [UNREACHABLE_STREAK] livres de suite sans service joignable (un seul service en
     * limite de débit ne bloque pas les autres). Retourne le nombre de livres enrichis.
     */
    suspend fun enrichPending(limit: Int = 25): Int {
        val candidates = db.bookDao().all().asSequence()
            .filter { wants(it) }
            .sortedWith(compareByDescending<BookEntity> { it.titleConfirmed }.thenByDescending { it.enrichedAt == null })
            .take(limit)
            .toList()
        var done = 0
        var unreachable = 0
        for ((index, book) in candidates.withIndex()) {
            if (index > 0) delay(PACING_MS)
            when (enrich(book.driveId)) {
                EnrichOutcome.FOUND -> {
                    done++
                    unreachable = 0
                }
                EnrichOutcome.NOT_FOUND -> unreachable = 0
                EnrichOutcome.UNREACHABLE -> if (++unreachable >= UNREACHABLE_STREAK) break
            }
        }
        return done
    }

    /**
     * Le livre mérite une recherche : visible, sans pochette locale, jamais cherché (ou pochette
     * connue mais pas téléchargée), pas réessayé depuis une demi-heure, et avec un nom qui
     * ressemble à un livre — un titre confirmé, ou un titre d'au moins deux mots ou un auteur.
     */
    fun wants(book: BookEntity): Boolean {
        if (book.hidden || book.trashPending || book.kind == BookKind.ZIP) return false
        if (book.coverPath?.let { File(it).exists() } == true) return false
        if (book.enrichedAt != null && book.coverUrl == null) return false
        if (clock.now() - (attempts[book.driveId] ?: Long.MIN_VALUE) in 0 until RETRY_INTERVAL_MS) return false
        return book.titleConfirmed || looksLikeABook(book)
    }

    private enum class Download { OK, UNREACHABLE, REJECTED }

    /** Télécharge la pochette dans le dossier des pochettes et demande son dépôt dans Drive. */
    private suspend fun downloadCover(bookId: String, url: String): Download {
        val httpUrl = url.toHttpUrlOrNull() ?: return Download.REJECTED
        val bytes = try {
            images.getBytes(httpUrl, "Pochette", MAX_COVER_BYTES)
        } catch (e: CancellationException) {
            throw e
        } catch (e: RemoteServiceException) {
            return if (e.isTransient) Download.UNREACHABLE else Download.REJECTED
        } catch (e: IOException) {
            return Download.UNREACHABLE
        }
        if (!looksLikeImage(bytes)) return Download.REJECTED
        coversDir.mkdirs()
        val file = File(coversDir, "$bookId.${if (isPng(bytes)) "png" else "jpg"}")
        file.writeBytes(bytes)
        db.bookDao().setCover(bookId, file.path, uploadPending = true)
        return Download.OK
    }

    companion object {
        const val MAX_COVER_BYTES = 3 * 1024 * 1024
        const val RETRY_INTERVAL_MS = 30 * 60_000L

        /** Entre deux livres d'une même passe : ménage les quotas des services. */
        const val PACING_MS = 400L

        /** Livres de suite sans service joignable avant d'arrêter la passe (hors ligne, pas juste un service en limite). */
        const val UNREACHABLE_STREAK = 3

        /** Un nom de dossier qui ressemble à un livre : titre d'au moins deux mots, ou auteur connu. */
        fun looksLikeABook(book: BookEntity): Boolean {
            val title = TextNormalizer.key(book.displayTitle)
            if (title.length < 4 || title.none { it.isLetter() }) return false
            return book.displayAuthor != null || title.split(' ').count { it.length >= 2 } >= 2
        }

        private fun String.toHttpUrlOrNull(): HttpUrl? = runCatching { toHttpUrl() }.getOrNull()

        fun isPng(bytes: ByteArray): Boolean =
            bytes.size >= 4 && bytes[0] == 0x89.toByte() && bytes[1] == 'P'.code.toByte() && bytes[2] == 'N'.code.toByte() && bytes[3] == 'G'.code.toByte()

        private fun isJpeg(bytes: ByteArray): Boolean =
            bytes.size >= 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte() && bytes[2] == 0xFF.toByte()

        private fun isWebp(bytes: ByteArray): Boolean =
            bytes.size >= 12 && String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF" && String(bytes, 8, 4, Charsets.US_ASCII) == "WEBP"

        /** Une vraie image, pas une page d'erreur ni un « image non disponible » vide. */
        fun looksLikeImage(bytes: ByteArray): Boolean = bytes.size >= 1024 && (isJpeg(bytes) || isPng(bytes) || isWebp(bytes))
    }
}
