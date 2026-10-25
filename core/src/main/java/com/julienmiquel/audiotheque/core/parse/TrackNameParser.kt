package com.julienmiquel.audiotheque.core.parse

/** Ce que le nom d'une piste dit de sa place dans le livre. */
data class ParsedTrack(
    /** Ordre global (« 091 »). */
    val number: Int?,
    val part: Int?,
    val chapter: Int?,
    val title: String,
)

/**
 * Découpe le livre en parties et chapitres à partir des noms de pistes.
 * « 091_P4_04_LES_AVENTURES_DE_CERVANTES.mp3 » : ordre global 091, partie 4, chapitre 04, titre.
 */
object TrackNameParser {
    private val audioExtension =
        Regex("""\.(mp3|m4a|m4b|aac|ogg|oga|opus|flac|wav|wma)$""", RegexOption.IGNORE_CASE)
    private const val SEP = """[\s_.\-]"""

    private val globalPartChapter = Regex(
        """^(\d{1,4})$SEP+P(?:artie)?$SEP?(\d{1,2})$SEP+(?:C(?:hap(?:itre)?)?$SEP?)?(\d{1,3})(?:$SEP+(.*))?$""",
        RegexOption.IGNORE_CASE,
    )
    private val trackWord = Regex("""^(?:piste|track|plage)$SEP?(\d{1,4})$SEP*(.*)$""", RegexOption.IGNORE_CASE)
    private val leadingNumber = Regex("""^(\d{1,4})(?:$SEP+(.*))?$""")
    private val partChapter = Regex(
        """(?:^|$SEP)(?:p|partie|part|livre|book)$SEP?(\d{1,2})$SEP*(?:c|ch|chap|chapitre|chapter)$SEP?(\d{1,3})(?=$|$SEP)""",
        RegexOption.IGNORE_CASE,
    )
    private val chapterOnly =
        Regex("""(?:^|$SEP)(?:chap|chapitre|chapter|ch)$SEP?(\d{1,3})(?=$|$SEP)""", RegexOption.IGNORE_CASE)
    private val disc = Regex("""(?:^|$SEP)(?:cd|disque|disc)$SEP?(\d{1,2})(?=$|$SEP)""", RegexOption.IGNORE_CASE)

    fun parse(fileName: String): ParsedTrack {
        val base = fileName.replace(audioExtension, "")
        globalPartChapter.matchEntire(base)?.let { m ->
            val chapter = m.groupValues[3].toInt()
            return ParsedTrack(
                number = m.groupValues[1].toInt(),
                part = m.groupValues[2].toInt(),
                chapter = chapter,
                title = cleanTitle(m.groupValues[4]).ifEmpty { "Chapitre $chapter" },
            )
        }

        var number: Int? = null
        var part: Int? = null
        var chapter: Int? = null
        var rest = base
        trackWord.matchEntire(rest)?.let { m ->
            number = m.groupValues[1].toInt()
            rest = m.groupValues[2]
        }
        leadingNumber.matchEntire(rest)?.let { m ->
            if (number == null) number = m.groupValues[1].toInt()
            rest = m.groupValues[2]
        }
        partChapter.find(rest)?.let { m ->
            part = m.groupValues[1].toInt()
            chapter = m.groupValues[2].toInt()
            rest = rest.removeRange(m.range)
        }
        if (chapter == null) {
            chapterOnly.find(rest)?.let { m ->
                chapter = m.groupValues[1].toInt()
                rest = rest.removeRange(m.range)
            }
        }
        if (part == null) {
            disc.find(rest)?.let { m ->
                part = m.groupValues[1].toInt()
                rest = rest.removeRange(m.range)
            }
        }
        val title = cleanTitle(rest).ifEmpty {
            when {
                chapter != null -> "Chapitre $chapter"
                number != null -> "Piste $number"
                else -> cleanTitle(base).ifEmpty { base }
            }
        }
        return ParsedTrack(number, part, chapter, title)
    }

    private fun cleanTitle(s: String): String {
        val t = s.replace('_', ' ').replace(Regex("""\s+"""), " ").trim().trim('-', '.', '–').trim()
        return FolderNameParser.prettyTitle(t)
    }
}

/** Fichier audio candidat d'un livre, avant tri. */
data class TrackCandidate(val id: String, val relativePath: String, val name: String)

object TrackOrdering {
    /**
     * Ordre de lecture : numéro global lu dans les noms quand toutes les pistes en ont un,
     * sinon ordre alphabétique (naturel) des pistes, sous-dossier par sous-dossier.
     */
    fun sort(candidates: List<TrackCandidate>): List<Pair<TrackCandidate, ParsedTrack>> {
        val parsed = candidates.map { it to TrackNameParser.parse(it.name) }
        fun dir(c: TrackCandidate) = c.relativePath.substringBeforeLast('/', "")
        val numbered = parsed.all { it.second.number != null } &&
            parsed.map { dir(it.first) to it.second.number }.toSet().size == parsed.size
        val comparator: Comparator<Pair<TrackCandidate, ParsedTrack>> = if (numbered) {
            compareBy<Pair<TrackCandidate, ParsedTrack>, String>(NaturalOrder) { dir(it.first) }
                .thenBy { it.second.number }
                .thenBy(NaturalOrder) { it.first.name }
        } else {
            compareBy(NaturalOrder) { it.first.relativePath }
        }
        return parsed.sortedWith(comparator)
    }
}
