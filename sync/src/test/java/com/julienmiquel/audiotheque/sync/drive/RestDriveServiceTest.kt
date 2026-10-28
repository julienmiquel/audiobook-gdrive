package com.julienmiquel.audiotheque.sync.drive

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Before
import org.junit.Test

/** Appels Drive API v3 de la spécification, contre un serveur HTTP local (MockWebServer). */
class RestDriveServiceTest {

    private lateinit var server: MockWebServer
    private val issued = mutableListOf<String>()
    private val invalidated = mutableListOf<String>()
    private var tokenCounter = 0

    private val tokens = object : AccessTokenProvider {
        override suspend fun accessToken(): String = "jeton-$tokenCounter".also { issued += it }
        override suspend fun invalidate(token: String) {
            invalidated += token
            tokenCounter++
        }
    }

    private lateinit var drive: RestDriveService

    @Before
    fun setUp() {
        server = MockWebServer()
        server.start()
        drive = RestDriveService(OkHttpClient(), tokens, baseUrl = server.url("/").toString(), backoffBaseMs = 1)
    }

    @After
    fun tearDown() = server.shutdown()

    @Test
    fun `inventaire d'un statut - files list avec q, toutes les pages`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("""{"nextPageToken":"p2","files":[{"id":"a","name":"Police - Hugo Boris","mimeType":"application/vnd.google-apps.folder","parents":["19dbp"]}]}"""))
        server.enqueue(MockResponse().setBody("""{"files":[{"id":"b","name":"Solo - William Boyd","mimeType":"application/vnd.google-apps.folder","parents":["19dbp"]}]}"""))

        val files = drive.listChildren("19dbp")

        assertThat(files.map { it.id }).containsExactly("a", "b").inOrder()
        assertThat(files.first().isFolder).isTrue()
        val first = server.takeRequest()
        assertThat(first.requestUrl!!.queryParameter("q")).isEqualTo("'19dbp' in parents and trashed = false")
        assertThat(first.getHeader("Authorization")).isEqualTo("Bearer jeton-0")
        assertThat(server.takeRequest().requestUrl!!.queryParameter("pageToken")).isEqualTo("p2")
    }

    @Test
    fun `changement de statut - files update avec addParents et removeParents`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("""{"id":"civ","name":"CIVILISATIONS - Laurent BINET","mimeType":"application/vnd.google-apps.folder","parents":["1WSba"]}"""))

        val moved = drive.move("civ", addParent = "1WSba", removeParents = listOf("1DXIX"))

        assertThat(moved.parents).containsExactly("1WSba")
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("PATCH")
        assertThat(request.requestUrl!!.encodedPath).isEqualTo("/drive/v3/files/civ")
        assertThat(request.requestUrl!!.queryParameter("addParents")).isEqualTo("1WSba")
        assertThat(request.requestUrl!!.queryParameter("removeParents")).isEqualTo("1DXIX")
    }

    @Test
    fun `téléchargement - alt media et en-tête Range pour reprendre`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(206).setBody("suite"))

        val content = drive.openContent("piste", fromByte = 1000)
        val text = content.use { it.stream.readBytes().decodeToString() }

        assertThat(text).isEqualTo("suite")
        assertThat(content.partial).isTrue()
        val request = server.takeRequest()
        assertThat(request.requestUrl!!.queryParameter("alt")).isEqualTo("media")
        assertThat(request.getHeader("Range")).isEqualTo("bytes=1000-")
    }

    @Test
    fun `vérification - champs size et md5Checksum`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("""{"id":"t","name":"001.mp3","mimeType":"audio/mpeg","size":"6900000","md5Checksum":"abc123","modifiedTime":"2026-10-25T08:00:00Z"}"""))
        val f = drive.getFile("t")
        assertThat(f.size).isEqualTo(6_900_000)
        assertThat(f.md5).isEqualTo("abc123")
        assertThat(f.isAudio).isTrue()
        assertThat(server.takeRequest().requestUrl!!.queryParameter("fields")).contains("md5Checksum")
    }

    @Test
    fun `jeton refusé - renouvelé une fois puis requête rejouée`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setBody("""{"startPageToken":"42"}"""))

        assertThat(drive.startPageToken()).isEqualTo("42")
        assertThat(invalidated).containsExactly("jeton-0")
        server.takeRequest()
        assertThat(server.takeRequest().getHeader("Authorization")).isEqualTo("Bearer jeton-1")
    }

    @Test
    fun `limite de débit - nouvelle tentative espacée`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setResponseCode(503))
        server.enqueue(MockResponse().setBody("""{"user":{"emailAddress":"julien@example.com","displayName":"Julien"},"storageQuota":{"limit":"2000000000000","usage":"1268400000000"}}"""))

        val about = drive.about()

        assertThat(about.email).isEqualTo("julien@example.com")
        assertThat(about.quotaUsage).isEqualTo(1_268_400_000_000)
        assertThat(server.requestCount).isEqualTo(3)
    }

    @Test
    fun `refus définitif - erreur non passagère`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"code":403,"message":"Insufficient permissions","errors":[{"reason":"insufficientFilePermissions"}]}}"""))
        val error = runCatching { drive.move("x", "y", emptyList()) }.exceptionOrNull()
        assertThat(error).isInstanceOf(DriveHttpException::class.java)
        assertThat((error as DriveHttpException).isTransient).isFalse()
        assertThat(error.reason).isEqualTo("insufficientFilePermissions")
    }

    @Test
    fun `progression - fichier JSON cherché par nom dans le dossier du livre`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("""{"files":[{"id":"p1","name":".audiotheque-progress-julien@example.com.json","mimeType":"application/json","parents":["civ"],"modifiedTime":"2026-10-25T08:00:00Z"}]}"""))
        val files = drive.findChildren("civ", ".audiotheque-progress-julien@example.com.json")
        assertThat(files.single().parents).containsExactly("civ")
        assertThat(server.takeRequest().requestUrl!!.queryParameter("q"))
            .isEqualTo("'civ' in parents and name = '.audiotheque-progress-julien@example.com.json' and trashed = false")
    }

    @Test
    fun `progression - tous les dossiers en une requête par nom`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("""{"files":[{"id":"p1","name":"x.json","parents":["a"]},{"id":"p2","name":"x.json","parents":["b"]}]}"""))
        val files = drive.findByName("x.json")
        assertThat(files.map { it.parents.single() }).containsExactly("a", "b")
        assertThat(server.takeRequest().requestUrl!!.queryParameter("q")).isEqualTo("name = 'x.json' and trashed = false")
    }

    @Test
    fun `dépôt dans le dossier du livre - files create multipart avec parents et type MIME`() = runBlocking<Unit> {
        server.enqueue(MockResponse().setBody("""{"id":"new","name":"cover.jpg","mimeType":"image/jpeg","parents":["civ"]}"""))
        val created = drive.createFile("cover.jpg", "civ", byteArrayOf(1, 2, 3), "image/jpeg")
        assertThat(created.id).isEqualTo("new")
        val request = server.takeRequest()
        assertThat(request.method).isEqualTo("POST")
        assertThat(request.requestUrl!!.encodedPath).isEqualTo("/upload/drive/v3/files")
        assertThat(request.requestUrl!!.queryParameter("uploadType")).isEqualTo("multipart")
        val body = request.body.readUtf8()
        assertThat(body).contains("\"parents\":[\"civ\"]")
        assertThat(body).contains("\"mimeType\":\"image/jpeg\"")
        assertThat(body).contains("Content-Type: image/jpeg")

        server.enqueue(MockResponse().setBody("""{"id":"new","name":"cover.jpg","mimeType":"image/jpeg","parents":["civ"]}"""))
        drive.updateContent("new", byteArrayOf(4), "application/json")
        val update = server.takeRequest()
        assertThat(update.method).isEqualTo("PATCH")
        assertThat(update.requestUrl!!.encodedPath).isEqualTo("/upload/drive/v3/files/new")
        assertThat(update.requestUrl!!.queryParameter("uploadType")).isEqualTo("media")
    }
}
