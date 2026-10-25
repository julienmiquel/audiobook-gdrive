package com.julienmiquel.audiotheque.library

import com.julienmiquel.audiotheque.core.db.AppDatabase
import com.julienmiquel.audiotheque.core.model.displayAuthor
import com.julienmiquel.audiotheque.core.model.displayTitle
import com.julienmiquel.audiotheque.core.parse.TextNormalizer
import com.julienmiquel.audiotheque.core.sync.Clock
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request

/** Pochette et résumé trouvés dans Google Books. */
data class BookInfo(val title: String?, val authors: List<String>, val description: String?, val coverUrl: String?)

/**
 * Enrichissement via l'API Google Books (sans clé, faible volume), à partir du titre et de
 * l'auteur confirmés. Seuls ces deux champs sont envoyés.
 */
class GoogleBooksClient(
    private val http: OkHttpClient,
    baseUrl: String = "https://www.googleapis.com/books/v1/",
) {
    private val base: HttpUrl = baseUrl.toHttpUrl()
    private val json = Json { ignoreUnknownKeys = true }

    suspend fun search(title: String, author: String?): BookInfo? = withContext(Dispatchers.IO) {
        val query = buildString {
            append("intitle:").append(title)
            if (!author.isNullOrBlank()) append(" inauthor:").append(author)
        }
        val volumes = fetch(query, "fr").ifEmpty { fetch(query, null) }
        val wanted = TextNormalizer.key(title)
        val best = volumes.firstOrNull { v ->
            val t = v.volumeInfo?.title?.let(TextNormalizer::key).orEmpty()
            (t.contains(wanted) || wanted.contains(t)) && (v.volumeInfo?.imageLinks != null || v.volumeInfo?.description != null)
        } ?: volumes.firstOrNull { it.volumeInfo?.imageLinks != null || it.volumeInfo?.description != null }
        val info = best?.volumeInfo ?: return@withContext null
        BookInfo(
            title = info.title,
            authors = info.authors,
            description = info.description,
            coverUrl = (info.imageLinks?.thumbnail ?: info.imageLinks?.smallThumbnail)
                ?.replace("http://", "https://")
                ?.replace("&edge=curl", ""),
        )
    }

    private fun fetch(query: String, language: String?): List<VolumeDto> {
        val url = base.newBuilder().addPathSegment("volumes")
            .addQueryParameter("q", query)
            .addQueryParameter("maxResults", "5")
            .addQueryParameter("printType", "books")
            .apply { language?.let { addQueryParameter("langRestrict", it) } }
            .build()
        http.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Google Books ${response.code}")
            return json.decodeFromString(VolumesDto.serializer(), response.body?.string().orEmpty()).items
        }
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
        val imageLinks: ImageLinksDto? = null,
    )

    @Serializable
    internal data class ImageLinksDto(val smallThumbnail: String? = null, val thumbnail: String? = null)
}

/** Applique l'enrichissement à un livre. */
class Enricher(private val db: AppDatabase, private val client: GoogleBooksClient, private val clock: Clock = Clock.SYSTEM) {

    /** Retourne vrai si une pochette ou un résumé a été trouvé. */
    suspend fun enrich(bookId: String): Boolean {
        val book = db.bookDao().get(bookId) ?: return false
        val info = client.search(book.displayTitle, book.displayAuthor)
        db.bookDao().setEnrichment(
            bookId,
            coverUrl = info?.coverUrl ?: book.coverUrl,
            summary = info?.description ?: book.summary,
            at = clock.now(),
        )
        return info?.coverUrl != null || info?.description != null
    }
}
