package com.julienmiquel.audiotheque.sync

import com.google.common.truth.Truth.assertThat
import com.julienmiquel.audiotheque.sync.engine.AudioFileInfo
import com.julienmiquel.audiotheque.sync.engine.ZipExtractor
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.charset.Charset
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertThrows
import org.junit.Test

class ZipExtractorTest {

    @Test
    fun `extrait une archive UTF-8 avec dossier englobant, ordonne les pistes et extrait la pochette`() {
        val dir = Files.createTempDirectory("zip-utf8").toFile()
        try {
            val zip = File(dir, "book.zip")
            ZipOutputStream(FileOutputStream(zip), Charsets.UTF_8).use { zos ->
                put(zos, "Mon Livre/02 - Chapitre 2.mp3", ByteArray(2000) { 2 })
                put(zos, "Mon Livre/01 - Chapitre 1.mp3", ByteArray(1000) { 1 })
                put(zos, "Mon Livre/cover.jpg", byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xD9.toByte()))
                put(zos, "__MACOSX/._01 - Chapitre 1.mp3", byteArrayOf(0, 1, 2))
            }

            val outDir = File(dir, "extracted")
            val result = ZipExtractor.extract(zip, outDir, inspector = { file ->
                AudioFileInfo(durationMs = file.length() * 10L, narrator = "Thierry Blanc", picture = null)
            })

            assertThat(result.tracks).hasSize(2)
            assertThat(result.tracks[0].name).isEqualTo("01 - Chapitre 1.mp3")
            assertThat(result.tracks[0].relativePath).isEqualTo("01 - Chapitre 1.mp3")
            assertThat(result.tracks[0].info?.durationMs).isEqualTo(10_000L)
            assertThat(result.tracks[1].name).isEqualTo("02 - Chapitre 2.mp3")
            assertThat(result.tracks[1].info?.durationMs).isEqualTo(20_000L)
            assertThat(result.coverBytes).hasLength(4)
            assertThat(result.coverExt).isEqualTo("jpg")
            assertThat(result.narrator).isEqualTo("Thierry Blanc")
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `tolère les noms accentués encodés en CP437`() {
        val cp437 = runCatching { Charset.forName("CP437") }.getOrNull()
            ?: runCatching { Charset.forName("IBM437") }.getOrNull()
            ?: Charsets.ISO_8859_1
        val dir = Files.createTempDirectory("zip-cp437").toFile()
        try {
            val zip = File(dir, "accent.zip")
            ZipOutputStream(FileOutputStream(zip), cp437).use { zos ->
                put(zos, "Chapitre 1 - L'été à la forêt.mp3", ByteArray(512) { 7 })
            }
            val outDir = File(dir, "extracted")
            val result = ZipExtractor.extract(zip, outDir, inspector = { AudioFileInfo(5000L, null, null) })
            assertThat(result.tracks).hasSize(1)
            assertThat(result.tracks[0].name).contains("été")
            assertThat(result.tracks[0].file.exists()).isTrue()
        } finally {
            dir.deleteRecursively()
        }
    }

    @Test
    fun `rejette une entrée Zip-Slip contenant deux points`() {
        val dir = Files.createTempDirectory("zip-slip").toFile()
        try {
            val zip = File(dir, "evil.zip")
            ZipOutputStream(FileOutputStream(zip), Charsets.UTF_8).use { zos ->
                put(zos, "../dehors.mp3", byteArrayOf(1, 2, 3))
            }
            val outDir = File(dir, "extracted")
            assertThrows(IOException::class.java) {
                ZipExtractor.extract(zip, outDir, inspector = { null })
            }
            assertThat(File(dir, "dehors.mp3").exists()).isFalse()
        } finally {
            dir.deleteRecursively()
        }
    }

    private fun put(zos: ZipOutputStream, name: String, bytes: ByteArray) {
        zos.putNextEntry(ZipEntry(name))
        zos.write(bytes)
        zos.closeEntry()
    }
}
