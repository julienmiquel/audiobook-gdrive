package com.julienmiquel.audiotheque.sync.drive

import java.io.IOException
import java.time.Instant
import kotlin.coroutines.resumeWithException
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response

/**
 * Client REST de Google Drive API v3 (OkHttp). Les appels idempotents sont retentés avec des
 * délais croissants en cas de limite de débit ou d'erreur serveur ; un jeton refusé (401) est
 * renouvelé une fois.
 */
class RestDriveService(
    private val http: OkHttpClient,
    private val tokens: AccessTokenProvider,
    baseUrl: String = "https://www.googleapis.com/",
    private val maxAttempts: Int = 4,
    private val backoffBaseMs: Long = 500,
) : DriveService {

    private val base: HttpUrl = baseUrl.toHttpUrl()
    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }
    private val jsonType = "application/json; charset=UTF-8".toMediaType()

    override suspend fun listChildren(folderId: String): List<DriveFile> =
        listAll("'${escape(folderId)}' in parents and trashed = false")

    override suspend fun findChildren(folderId: String, name: String): List<DriveFile> =
        listAll("'${escape(folderId)}' in parents and name = '${escape(name)}' and trashed = false")

    override suspend fun findByName(name: String): List<DriveFile> =
        listAll("name = '${escape(name)}' and trashed = false")

    /** files.list paginé pour une requête q donnée. */
    private suspend fun listAll(q: String): List<DriveFile> {
        val result = mutableListOf<DriveFile>()
        var pageToken: String? = null
        do {
            val url = api("drive/v3/files")
                .addQueryParameter("q", q)
                .addQueryParameter("fields", "nextPageToken,files($FILE_FIELDS)")
                .addQueryParameter("pageSize", "1000")
                .addQueryParameter("supportsAllDrives", "true")
                .addQueryParameter("includeItemsFromAllDrives", "true")
                .apply { pageToken?.let { addQueryParameter("pageToken", it) } }
                .build()
            val page = getJson<FileListDto>(url)
            page.files.mapTo(result) { it.toModel() }
            pageToken = page.nextPageToken
        } while (pageToken != null)
        return result
    }

    override suspend fun getFile(fileId: String): DriveFile {
        val url = api("drive/v3/files/$fileId")
            .addQueryParameter("fields", FILE_FIELDS)
            .addQueryParameter("supportsAllDrives", "true")
            .build()
        return getJson<FileDto>(url).toModel()
    }

    override suspend fun startPageToken(): String {
        val url = api("drive/v3/changes/startPageToken")
            .addQueryParameter("supportsAllDrives", "true")
            .build()
        return getJson<StartPageTokenDto>(url).startPageToken
    }

    override suspend fun changes(pageToken: String): ChangePage {
        val url = api("drive/v3/changes")
            .addQueryParameter("pageToken", pageToken)
            .addQueryParameter("pageSize", "1000")
            .addQueryParameter("includeRemoved", "true")
            .addQueryParameter("spaces", "drive")
            .addQueryParameter("supportsAllDrives", "true")
            .addQueryParameter("includeItemsFromAllDrives", "true")
            .addQueryParameter(
                "fields",
                "nextPageToken,newStartPageToken,changes(fileId,removed,file($FILE_FIELDS))",
            )
            .build()
        val dto = getJson<ChangeListDto>(url)
        return ChangePage(
            changes = dto.changes.mapNotNull { c ->
                val id = c.fileId ?: c.file?.id ?: return@mapNotNull null
                DriveChange(id, c.removed, c.file?.toModel())
            },
            nextPageToken = dto.nextPageToken,
            newStartPageToken = dto.newStartPageToken,
        )
    }

    override suspend fun openContent(fileId: String, fromByte: Long, toByteInclusive: Long?): DriveContent {
        val url = api("drive/v3/files/$fileId")
            .addQueryParameter("alt", "media")
            .addQueryParameter("supportsAllDrives", "true")
            .build()
        val builder = Request.Builder().url(url).get()
        if (fromByte > 0 || toByteInclusive != null) {
            builder.header("Range", "bytes=$fromByte-${toByteInclusive ?: ""}")
        }
        val response = execute(builder, retry = true)
        if (!response.isSuccessful) {
            response.use { throw httpError(it) }
        }
        val body = response.body ?: run {
            response.close()
            throw IOException("Réponse vide pour $fileId")
        }
        return DriveContent(
            stream = body.byteStream(),
            contentLength = body.contentLength(),
            partial = response.code == 206,
            onClose = { response.close() },
        )
    }

    override suspend fun move(fileId: String, addParent: String, removeParents: List<String>): DriveFile {
        val url = api("drive/v3/files/$fileId")
            .addQueryParameter("addParents", addParent)
            .apply { if (removeParents.isNotEmpty()) addQueryParameter("removeParents", removeParents.joinToString(",")) }
            .addQueryParameter("fields", FILE_FIELDS)
            .addQueryParameter("supportsAllDrives", "true")
            .build()
        val request = Request.Builder().url(url).patch("{}".toRequestBody(jsonType))
        return send<FileDto>(request, retry = true).toModel()
    }

    override suspend fun trash(fileId: String): DriveFile {
        val url = api("drive/v3/files/$fileId")
            .addQueryParameter("fields", FILE_FIELDS)
            .addQueryParameter("supportsAllDrives", "true")
            .build()
        val request = Request.Builder().url(url).patch("""{"trashed":true}""".toRequestBody(jsonType))
        return send<FileDto>(request, retry = true).toModel()
    }

    override suspend fun about(): DriveAbout {
        val url = api("drive/v3/about")
            .addQueryParameter("fields", "user(emailAddress,displayName),storageQuota(limit,usage)")
            .build()
        val dto = getJson<AboutDto>(url)
        return DriveAbout(
            email = dto.user?.emailAddress,
            displayName = dto.user?.displayName,
            quotaLimit = dto.storageQuota?.limit?.toLongOrNull(),
            quotaUsage = dto.storageQuota?.usage?.toLongOrNull(),
        )
    }

    override suspend fun createFile(name: String, parentId: String, content: ByteArray, mimeType: String): DriveFile {
        val url = api("upload/drive/v3/files")
            .addQueryParameter("uploadType", "multipart")
            .addQueryParameter("fields", FILE_FIELDS)
            .addQueryParameter("supportsAllDrives", "true")
            .build()
        val metadata = json.encodeToString(NewFileDto.serializer(), NewFileDto(name, listOf(parentId), mimeType))
        val body = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(metadata.toRequestBody(jsonType))
            .addPart(content.toRequestBody(mimeType.toMediaType()))
            .build()
        // Une création n'est pas idempotente : pas de nouvelle tentative automatique.
        return send<FileDto>(Request.Builder().url(url).post(body), retry = false).toModel()
    }

    override suspend fun updateContent(fileId: String, content: ByteArray, mimeType: String): DriveFile {
        val url = api("upload/drive/v3/files/$fileId")
            .addQueryParameter("uploadType", "media")
            .addQueryParameter("fields", FILE_FIELDS)
            .addQueryParameter("supportsAllDrives", "true")
            .build()
        return send<FileDto>(Request.Builder().url(url).patch(content.toRequestBody(mimeType.toMediaType())), retry = true).toModel()
    }

    // ------------------------------------------------------------------ plomberie HTTP

    private fun api(path: String): HttpUrl.Builder = base.newBuilder().addPathSegments(path)

    /** Valeur entre apostrophes dans une requête q (files.list). */
    private fun escape(value: String): String = value.replace("\\", "\\\\").replace("'", "\\'")

    private suspend inline fun <reified T> getJson(url: HttpUrl): T =
        send(Request.Builder().url(url).get(), retry = true)

    private suspend inline fun <reified T> send(builder: Request.Builder, retry: Boolean): T {
        val response = execute(builder, retry)
        response.use {
            if (!it.isSuccessful) throw httpError(it)
            val text = it.body?.string().orEmpty()
            return json.decodeFromString<T>(text)
        }
    }

    /** Exécute la requête avec jeton, renouvellement sur 401 et nouvelles tentatives espacées. */
    private suspend fun execute(builder: Request.Builder, retry: Boolean): Response {
        var attempt = 0
        var refreshedToken = false
        while (true) {
            attempt++
            val token = tokens.accessToken()
            val request = builder.header("Authorization", "Bearer $token").build()
            val response = try {
                http.newCall(request).await()
            } catch (e: IOException) {
                if (retry && attempt < maxAttempts) {
                    delay(backoffBaseMs shl (attempt - 1))
                    continue
                }
                throw e
            }
            if (response.code == 401) {
                response.close()
                tokens.invalidate(token)
                if (!refreshedToken) {
                    refreshedToken = true
                    continue
                }
                throw DriveAuthException("Accès à Google Drive refusé : reconnectez-vous.")
            }
            if (retry && attempt < maxAttempts && isRetryable(response)) {
                val retryAfter = response.header("Retry-After")?.toLongOrNull()?.times(1000)
                response.close()
                delay(retryAfter ?: (backoffBaseMs shl (attempt - 1)))
                continue
            }
            return response
        }
    }

    private fun isRetryable(response: Response): Boolean {
        if (response.code == 429 || response.code >= 500) return true
        if (response.code != 403) return false
        // 403 pour limite de débit : on regarde la raison sans consommer le corps.
        val peek = runCatching { response.peekBody(16 * 1024).string() }.getOrDefault("")
        return DriveHttpException.RATE_LIMIT_REASONS.any { peek.contains(it) }
    }

    private fun httpError(response: Response): DriveHttpException {
        val text = runCatching { response.body?.string() }.getOrNull().orEmpty()
        val error = runCatching { json.decodeFromString<ErrorEnvelopeDto>(text).error }.getOrNull()
        val reason = error?.errors?.firstOrNull()?.reason
        val message = "Drive ${response.code}" + (error?.message?.let { " : $it" } ?: "")
        return when (response.code) {
            404 -> DriveNotFoundException(message)
            416 -> DriveRangeNotSatisfiableException(message)
            else -> DriveHttpException(response.code, reason, message)
        }
    }

    companion object {
        const val FILE_FIELDS = "id,name,mimeType,parents,size,md5Checksum,createdTime,modifiedTime,trashed"
    }
}

/** Appel OkHttp annulable depuis une coroutine (annuler la coroutine annule l'appel). */
suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { runCatching { cancel() } }
    enqueue(object : Callback {
        override fun onResponse(call: Call, response: Response) {
            continuation.resume(response) { _, value, _ -> value.close() }
        }

        override fun onFailure(call: Call, e: IOException) {
            continuation.resumeWithException(e)
        }
    })
}

private fun parseTime(value: String?): Long? = value?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull() }

private fun FileDto.toModel() = DriveFile(
    id = id,
    name = name,
    mimeType = mimeType,
    parents = parents,
    size = size?.toLongOrNull(),
    md5 = md5Checksum,
    createdTime = parseTime(createdTime),
    modifiedTime = parseTime(modifiedTime),
    trashed = trashed,
)

@Serializable
internal data class FileDto(
    val id: String,
    val name: String = "",
    val mimeType: String = "",
    val parents: List<String> = emptyList(),
    val size: String? = null,
    val md5Checksum: String? = null,
    val createdTime: String? = null,
    val modifiedTime: String? = null,
    val trashed: Boolean = false,
)

@Serializable
internal data class FileListDto(val files: List<FileDto> = emptyList(), val nextPageToken: String? = null)

@Serializable
internal data class ChangeDto(val fileId: String? = null, val removed: Boolean = false, val file: FileDto? = null)

@Serializable
internal data class ChangeListDto(
    val changes: List<ChangeDto> = emptyList(),
    val nextPageToken: String? = null,
    val newStartPageToken: String? = null,
)

@Serializable
internal data class StartPageTokenDto(val startPageToken: String)

@Serializable
internal data class AboutDto(val user: UserDto? = null, val storageQuota: QuotaDto? = null)

@Serializable
internal data class UserDto(val emailAddress: String? = null, val displayName: String? = null)

@Serializable
internal data class QuotaDto(val limit: String? = null, val usage: String? = null)

@Serializable
internal data class NewFileDto(val name: String, val parents: List<String>, val mimeType: String? = null)

@Serializable
internal data class ErrorEnvelopeDto(val error: ErrorDto? = null)

@Serializable
internal data class ErrorDto(val code: Int = 0, val message: String? = null, val errors: List<ErrorItemDto> = emptyList())

@Serializable
internal data class ErrorItemDto(val reason: String? = null)
