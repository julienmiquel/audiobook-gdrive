package com.julienmiquel.audiotheque.core.parse

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import org.junit.Test

/** Analyse ISO BMFF (.m4b / .m4a) : durée mvhd, étiquettes iTunes ilst, chapitres Nero chpl et QuickTime tref/chap, lecture par plages. */
class Mp4ProbeTest {

    private fun box(type: String, body: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(8 + body.size)
        val dos = DataOutputStream(out)
        dos.writeInt(8 + body.size)
        dos.write(type.toByteArray(Charsets.ISO_8859_1))
        dos.write(body)
        return out.toByteArray()
    }

    private fun mvhdV0(timescale: Int, durationTicks: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val dos = DataOutputStream(out)
        dos.writeInt(0) // version 0 + flags
        dos.writeInt(0) // creation_time
        dos.writeInt(0) // modification_time
        dos.writeInt(timescale)
        dos.writeInt(durationTicks)
        dos.write(ByteArray(80))
        return box("mvhd", out.toByteArray())
    }

    private fun ilstTextItem(fourcc: String, value: String): ByteArray {
        val utf8 = value.toByteArray(Charsets.UTF_8)
        val dataBody = ByteArrayOutputStream().also {
            val dos = DataOutputStream(it)
            dos.writeInt(1) // UTF-8
            dos.writeInt(0) // locale
            dos.write(utf8)
        }.toByteArray()
        return box(fourcc, box("data", dataBody))
    }

    private fun ilstCoverItem(pngBytes: ByteArray): ByteArray {
        val dataBody = ByteArrayOutputStream().also {
            val dos = DataOutputStream(it)
            dos.writeInt(14) // PNG
            dos.writeInt(0)
            dos.write(pngBytes)
        }.toByteArray()
        return box("covr", box("data", dataBody))
    }

    private fun metaBox(vararg items: ByteArray): ByteArray {
        val ilst = box("ilst", items.fold(ByteArray(0)) { a, b -> a + b })
        val body = ByteArray(4) + ilst // 4 bytes version/flags
        return box("meta", body)
    }

    private fun chplV1(chapters: List<Pair<Long, String>>): ByteArray {
        val out = ByteArrayOutputStream()
        val dos = DataOutputStream(out)
        dos.writeByte(1) // version 1
        dos.write(ByteArray(3)) // flags
        dos.writeByte(0) // reserved
        dos.writeInt(chapters.size)
        for ((startMs, title) in chapters) {
            dos.writeLong(startMs * 10_000L) // 100 ns units
            val utf8 = title.toByteArray(Charsets.UTF_8)
            dos.writeByte(utf8.size)
            dos.write(utf8)
        }
        return box("chpl", out.toByteArray())
    }

    @Test
    fun `étiquettes iTunes, voix ©nrt, pochette et chapitres Nero chpl`() {
        val ftyp = box("ftyp", "M4B \u0000\u0000\u0000\u0000M4B mp42".toByteArray(Charsets.ISO_8859_1))
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 1, 2, 3)
        val meta = metaBox(
            ilstTextItem("\u00A9nam", "La Promesse de l'aube"),
            ilstTextItem("\u00A9ART", "Romain Gary"),
            ilstTextItem("\u00A9nrt", "Thierry Blanc"),
            ilstCoverItem(png),
        )
        val chpl = chplV1(
            listOf(
                0L to "Première partie",
                600_000L to "Deuxième partie",
                1_500_000L to "Épilogue",
            ),
        )
        val udta = box("udta", meta + chpl)
        val moov = box("moov", mvhdV0(1000, 2_400_000) + udta)
        val mdat = box("mdat", ByteArray(64))

        val info = Mp4Probe.parse(ftyp + moov + mdat)!!
        assertThat(info.durationMs).isEqualTo(2_400_000L)
        assertThat(info.tags?.title).isEqualTo("La Promesse de l'aube")
        assertThat(info.tags?.artist).isEqualTo("Romain Gary")
        assertThat(info.tags?.narrator()).isEqualTo("Thierry Blanc")
        assertThat(info.tags?.pictureMime).isEqualTo("image/png")
        assertThat(info.tags?.picture).isEqualTo(png)
        assertThat(info.chapters).containsExactly(
            EmbeddedChapter("Première partie", 0L, 600_000L),
            EmbeddedChapter("Deuxième partie", 600_000L, 1_500_000L),
            EmbeddedChapter("Épilogue", 1_500_000L, 2_400_000L),
        ).inOrder()
    }

    @Test
    fun `chapitres QuickTime tref chap avec moov placé en fin de fichier après un gros mdat`() {
        val ftyp = box("ftyp", "M4B \u0000\u0000\u0000\u0000".toByteArray(Charsets.ISO_8859_1))
        fun qtSample(text: String): ByteArray {
            val utf8 = text.toByteArray(Charsets.UTF_8)
            val out = ByteArrayOutputStream()
            DataOutputStream(out).apply {
                writeShort(utf8.size)
                write(utf8)
            }
            return out.toByteArray()
        }
        val s1 = qtSample("Chapitre I — Le départ")
        val s2 = qtSample("Chapitre II — L'arrivée")
        // Simule un mdat de 512 Ko (plus grand que INITIAL_PROBE_BYTES = 256 Ko) contenant les échantillons texte.
        val mdatPayload = ByteArray(400_000) + s1 + s2
        val mdat = box("mdat", mdatPayload)
        val s1Offset = (ftyp.size + 8 + 400_000).toInt()
        val s2Offset = s1Offset + s1.size

        fun tkhd(trackId: Int): ByteArray {
            val out = ByteArrayOutputStream()
            DataOutputStream(out).apply {
                writeInt(0) // v0 + flags
                writeInt(0)
                writeInt(0)
                writeInt(trackId)
                write(ByteArray(68))
            }
            return box("tkhd", out.toByteArray())
        }

        val audioTrak = box(
            "trak",
            tkhd(1) + box("tref", box("chap", ByteArrayOutputStream().also { DataOutputStream(it).writeInt(2) }.toByteArray())),
        )

        val mdhd = box("mdhd", ByteArrayOutputStream().also {
            DataOutputStream(it).apply {
                writeInt(0)
                writeInt(0)
                writeInt(0)
                writeInt(1000) // timescale = 1000 ticks/s
                writeInt(900_000)
                writeInt(0)
            }
        }.toByteArray())
        val hdlr = box("hdlr", ByteArray(8) + "text".toByteArray(Charsets.ISO_8859_1) + ByteArray(12))
        val stts = box("stts", ByteArrayOutputStream().also {
            DataOutputStream(it).apply {
                writeInt(0)
                writeInt(2)
                writeInt(1); writeInt(300_000)
                writeInt(1); writeInt(600_000)
            }
        }.toByteArray())
        val stsc = box("stsc", ByteArrayOutputStream().also {
            DataOutputStream(it).apply {
                writeInt(0)
                writeInt(1)
                writeInt(1); writeInt(1); writeInt(1)
            }
        }.toByteArray())
        val stsz = box("stsz", ByteArrayOutputStream().also {
            DataOutputStream(it).apply {
                writeInt(0)
                writeInt(0) // variable sizes
                writeInt(2)
                writeInt(s1.size)
                writeInt(s2.size)
            }
        }.toByteArray())
        val stco = box("stco", ByteArrayOutputStream().also {
            DataOutputStream(it).apply {
                writeInt(0)
                writeInt(2)
                writeInt(s1Offset)
                writeInt(s2Offset)
            }
        }.toByteArray())
        val textTrak = box("trak", tkhd(2) + box("mdia", mdhd + hdlr + box("minf", box("stbl", stts + stsc + stsz + stco))))
        val moov = box("moov", mvhdV0(1000, 900_000) + audioTrak + textTrak)

        val fullFile = ftyp + mdat + moov
        var bytesRead = 0
        val info = Mp4Probe.probe(fullFile.size.toLong()) { offset, length ->
            val end = minOf(fullFile.size, (offset + length).toInt())
            bytesRead += (end - offset.toInt()).coerceAtLeast(0)
            fullFile.copyOfRange(offset.toInt(), end)
        }!!

        assertThat(info.durationMs).isEqualTo(900_000L)
        assertThat(info.chapters).containsExactly(
            EmbeddedChapter("Chapitre I — Le départ", 0L, 300_000L),
            EmbeddedChapter("Chapitre II — L'arrivée", 300_000L, 900_000L),
        ).inOrder()
        // Vérifie qu'on n'a pas lu tout le mdat de 400 Ko.
        assertThat(bytesRead).isLessThan(fullFile.size - 100_000)
    }
}
