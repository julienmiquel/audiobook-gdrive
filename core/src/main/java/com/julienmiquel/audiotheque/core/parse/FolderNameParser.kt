package com.julienmiquel.audiotheque.core.parse

import com.julienmiquel.audiotheque.core.model.BookKind
import java.util.Locale

/** Proposition de titre, d'auteur et de voix tirée d'un nom de dossier (à confirmer). */
data class ParsedName(val title: String, val author: String?, val narrator: String?)

/** Série et numéro de tome détectés dans le titre ou le nom de dossier (« Les Rois maudits · T.2 »). */
data class SeriesInfo(val name: String, val volume: Int?) {
    val displayLabel: String get() = if (volume != null) "$name · T.$volume" else name
}

/**
 * Découpage automatique d'un nom de dossier Drive en titre et auteur : « - » comme séparateur,
 * « _ » remplacé par une espace (cf. spécification, « Titre et auteur »).
 *
 * Tolère les anomalies observées : « Titre - AUTEUR » (le plus fréquent), « Auteur - Titre »,
 * MAJUSCULES_SOULIGNÉES, titre seul, auteur seul, doubles soulignés, piste isolée.
 */
object FolderNameParser {
    private val fileExtension =
        Regex("""\.(mp3|m4a|m4b|aac|ogg|oga|opus|flac|wav|wma|zip)$""", RegexOption.IGNORE_CASE)
    private val narratorInBrackets = Regex(
        """[(\[]\s*(?:lu|lue|raconté|racontée|narré|narrée)\s+par\s+([^)\]]+)[)\]]""",
        RegexOption.IGNORE_CASE,
    )
    private val narratorTrailing =
        Regex("""(?:^|[\s,;-])(?:lu|lue)\s+par\s+(.+)$""", RegexOption.IGNORE_CASE)
    private val noise = Regex(
        """[(\[]\s*(?:mp3|aac|m4b|audio\s*book|audiobook|livre\s+audio|\d{2,3}\s*kbps|""" +
            """version\s+int[ée]grale|int[ée]grale|unabridged)\s*[)\]]""",
        RegexOption.IGNORE_CASE,
    )
    private val separator = Regex("""\s+[-–—]\s+""")
    private val spaces = Regex("""\s+""")
    private val elision = Regex(
        """\b([LDJMNSTC]|QU|JUSQU|LORSQU|PUISQU)\s+(?=[AEIOUYHÀÂÄÉÈÊËÎÏÔÖÛÜ])""",
        RegexOption.IGNORE_CASE,
    )
    private val trailingChapter = Regex(
        """[\s_-]*(?:chap(?:itre)?|ch|cd|partie|part|piste|track)[\s_.-]?\d{1,3}$""",
        RegexOption.IGNORE_CASE,
    )

    private val articles = setOf(
        "le", "la", "les", "l", "un", "une", "des", "du", "de", "d", "au", "aux", "the", "a", "an",
        "ce", "cet", "cette", "ces", "mon", "ma", "mes", "son", "sa", "ses", "et", "en", "dans",
    )
    private val particles = setOf(
        "de", "du", "des", "la", "le", "van", "von", "der", "den", "da", "di", "del", "della",
        "dos", "das", "y", "ibn", "bin", "al", "el",
    )

    fun parse(raw: String, isFile: Boolean = false): ParsedName {
        var s = raw.trim()
        if (isFile) s = s.replace(fileExtension, "")
        val hadUnderscores = s.contains('_')

        var narrator: String? = null
        narratorInBrackets.find(s)?.let { m ->
            narrator = cleanPart(m.groupValues[1])
            s = s.removeRange(m.range)
        }
        s = s.replace(noise, " ")

        // « __ » et « _-_ » séparent souvent titre et auteur ; « _ » remplace l'espace.
        s = s.replace("_-_", " - ").replace(Regex("_{2,}"), " - ").replace('_', ' ')
        s = s.replace(spaces, " ").trim()
        if (narrator == null) {
            narratorTrailing.find(s)?.let { m ->
                narrator = cleanPart(m.groupValues[1])
                s = s.removeRange(m.range)
            }
        }
        s = s.trim().trim('-', '–', '—', ',', ';').trim()
        if (hadUnderscores) s = s.replace(elision) { "${it.groupValues[1]}'" }
        if (s.isEmpty()) return ParsedName(raw.trim(), null, narrator?.let(::prettyName))

        val parts = s.split(separator).map { cleanPart(it) }.filter { it.isNotEmpty() }
        if (parts.size < 2) {
            return ParsedName(finishTitle(s, isFile), null, narrator?.let(::prettyName))
        }

        val firstScore = personScore(parts.first())
        val lastScore = personScore(parts.last())
        val authorIsLast: Boolean? = when {
            maxOf(firstScore, lastScore) >= 2 -> lastScore >= firstScore
            // Convention la plus fréquente : « Titre - AUTEUR ».
            lastScore >= 0 && lastScore >= firstScore -> true
            else -> null
        }
        return when (authorIsLast) {
            true -> ParsedName(
                title = finishTitle(parts.dropLast(1).joinToString(" - "), isFile),
                author = prettyName(parts.last()),
                narrator = narrator?.let(::prettyName),
            )
            false -> ParsedName(
                title = finishTitle(parts.drop(1).joinToString(" - "), isFile),
                author = prettyName(parts.first()),
                narrator = narrator?.let(::prettyName),
            )
            null -> ParsedName(finishTitle(parts.joinToString(" - "), isFile), null, narrator?.let(::prettyName))
        }
    }

    private fun finishTitle(title: String, isFile: Boolean): String {
        var t = title
        if (isFile) t = t.replace(trailingChapter, "").trim().trim('-').trim()
        return prettyTitle(t.ifEmpty { title })
    }

    private fun cleanPart(s: String): String = s.replace(spaces, " ").trim().trim('-', '–', '—').trim()

    /** Plus le score est élevé, plus la partie ressemble à un nom de personne. */
    internal fun personScore(part: String): Int {
        val words = part.split(' ').filter { it.isNotBlank() }
        if (words.isEmpty() || words.size > 5) return -3
        if (part.any { it.isDigit() }) return -2
        val first = words.first().lowercase(Locale.FRENCH)
        if (first in articles || first.startsWith("l'") || first.startsWith("d'") ||
            first.startsWith("l’") || first.startsWith("d’")
        ) {
            return -3
        }
        if (words.size == 1) return if (isAllCaps(words[0])) -1 else 0

        val lowercaseWords = words.drop(1).count { w ->
            w.first().isLowerCase() && w.lowercase(Locale.FRENCH) !in particles
        }
        if (lowercaseWords > 0) return -2 * lowercaseWords

        val lastAllCaps = isAllCaps(words.last()) && words.last().count { it.isLetter() } >= 2
        val othersMixed = words.dropLast(1).all { !isAllCaps(it) || isInitials(it) }
        val allCaps = words.all { isAllCaps(it) }
        val capitalized = words.filterIndexed { i, w ->
            !(i > 0 && w.first().isLowerCase() && w.lowercase(Locale.FRENCH) in particles)
        }.all { it.first().isUpperCase() }
        var score = when {
            lastAllCaps && othersMixed -> 3 // « Laurent BINET »
            allCaps -> -1 // TITRE EN MAJUSCULES
            capitalized -> 2 // « Hugo Boris »
            else -> 0
        }
        if (words.size > 4) score -= 1
        return score
    }

    private fun isAllCaps(w: String): Boolean {
        val letters = w.filter { it.isLetter() }
        return letters.isNotEmpty() && letters.all { it.isUpperCase() }
    }

    private fun isInitials(w: String): Boolean = Regex("""^(\p{Lu}\.)+$""").matches(w)

    /** Titre en MAJUSCULES → casse de phrase (« CIVILISATIONS » → « Civilisations »). */
    fun prettyTitle(s: String): String {
        val letters = s.filter { it.isLetter() }
        if (letters.length < 2 || letters.any { it.isLowerCase() }) return s
        val sb = StringBuilder(s.lowercase(Locale.FRENCH))
        var capitalizeNext = true
        for (i in sb.indices) {
            val c = sb[i]
            when {
                capitalizeNext && c.isLetter() -> {
                    sb.setCharAt(i, c.titlecaseChar())
                    capitalizeNext = false
                }
                c == '-' && i > 0 && sb[i - 1] == ' ' -> capitalizeNext = true
                c == ':' || c == '!' || c == '?' -> capitalizeNext = true
            }
        }
        return sb.toString()
    }

    /** Nom en MAJUSCULES → casse de nom propre (« Laurent BINET » → « Laurent Binet »). */
    fun prettyName(s: String): String {
        val words = s.trim().split(' ').filter { it.isNotBlank() }
        return words.mapIndexed { index, word ->
            when {
                !isAllCaps(word) || word.count { it.isLetter() } < 2 || isInitials(word) -> word
                index > 0 && word.lowercase(Locale.FRENCH) in particles -> word.lowercase(Locale.FRENCH)
                else -> word.lowercase(Locale.FRENCH).split('-').joinToString("-") { piece ->
                    piece.split('\'').joinToString("'") { it.replaceFirstChar { c -> c.titlecaseChar() } }
                }
            }
        }.joinToString(" ")
    }

    /**
     * Formate un nom d'auteur selon la convention Drive « Prénom NOM » (« Laurent BINET »,
     * « Guy de MAUPASSANT »). Un auteur d'un seul mot garde sa casse de nom propre (« Platon »)
     * afin de ne pas être confondu avec un titre en majuscules.
     */
    fun formatAuthorForDrive(author: String): String {
        val pretty = prettyName(author.replace(spaces, " ").trim())
        val words = pretty.split(' ').filter { it.isNotBlank() }
        if (words.size < 2) return pretty
        val head = words.dropLast(1).joinToString(" ")
        val last = words.last().uppercase(Locale.FRENCH)
        return "$head $last"
    }

    /** Nettoie un nom proposé pour Google Drive (supprime `/` et les caractères de contrôle). */
    fun sanitizeDriveName(raw: String): String =
        raw.replace('/', '-')
            .replace('\\', '-')
            .filter { it >= ' ' }
            .replace(spaces, " ")
            .trim()

    /**
     * Construit le nom normalisé « Titre - AUTEUR » (ou « Titre » sans auteur) pour un dossier ou
     * un fichier Drive, en préservant l'extension d'origine (`.zip`, `.mp3`, `.m4b`…) lorsqu'il
     * ne s'agit pas d'un dossier.
     */
    fun normalizedDriveName(
        title: String,
        author: String?,
        originalName: String = "",
        kind: BookKind = BookKind.FOLDER,
    ): String {
        val ext = if (kind != BookKind.FOLDER) {
            fileExtension.find(originalName.trim())?.value?.lowercase(Locale.ROOT).orEmpty()
        } else {
            ""
        }
        val cleanTitle = sanitizeDriveName(title).let {
            if (ext.isNotEmpty()) it.replace(fileExtension, "").trim() else it
        }.ifEmpty { sanitizeDriveName(originalName.replace(fileExtension, "")).ifEmpty { "Sans titre" } }
        val cleanAuthor = author?.let(::sanitizeDriveName)?.takeIf { it.isNotBlank() }
        val base = if (cleanAuthor != null) {
            "$cleanTitle - ${formatAuthorForDrive(cleanAuthor)}"
        } else {
            cleanTitle
        }
        return base + ext
    }

    private val nonSeriesKeys = setOf(
        "chapitre", "chap", "chapter", "ch", "partie", "part", "cd", "disque", "disc",
        "piste", "track", "plage", "mp3", "m4a", "m4b", "flac", "ogg", "opus",
        "audiobook", "audio book", "livre audio", "integrale", "version integrale", "unabridged",
        "tome", "volume", "vol", "livre", "book", "saison", "episode",
    )

    private val bracketedSeriesWithVol = Regex(
        """[(\[]\s*([^)\]]+?)(?:[\s,:-]+(?:tome|vol(?:ume)?\.?|livre|book|t\.?)\s*|\s+#?)(\d{1,2})\s*[)\]]""",
        RegexOption.IGNORE_CASE,
    )

    private val bracketedStandaloneVol = Regex(
        """^(.*?)\s*[(\[]\s*(?:tome|vol(?:ume)?\.?|livre|book|t\.?)\s*(\d{1,2})\s*[)\]]""",
        RegexOption.IGNORE_CASE,
    )

    private val inlineExplicitTomeOrVol = Regex(
        """^(.+?)(?:[\s,:-]+(?:tome|vol(?:ume)?\.?)\s*|\s+t\.?\s*0*|\s*[-–—]\s*t\.?\s*0*|[\s,:-]+(?:livre|book)\s+)(\d{1,2})(?:\b|[\s,:.-].*|$)""",
        RegexOption.IGNORE_CASE,
    )

    private val inlineNumberedSubtitle = Regex(
        """^(\p{L}[^-–—:()\[\]]*?\p{L})\s+(\d{1,2})\s*[-–—:]\s*(.*\p{L}{3,}.*)$""",
    )

    /**
     * Détecte une série et un numéro de tome éventuel à partir du titre affiché ou du nom Drive brut
     * (« [Les Rois maudits 2] … », « Dune - T.3 », « Millénium, Vol. 3 », « Fondation - Tome 1 »).
     */
    fun extractSeries(title: String, rawName: String = ""): SeriesInfo? {
        extractFromSingleCandidate(title)?.let { return it }
        if (rawName.isNotBlank() && rawName != title) {
            val normalizedRaw = rawName.replace('_', ' ').replace(spaces, " ").trim()
            bracketedSeriesWithVol.find(normalizedRaw)?.let { m ->
                buildSeriesInfo(m.groupValues[1], m.groupValues[2].toIntOrNull())?.let { return it }
            }
            val parsedRawTitle = parse(rawName).title
            if (parsedRawTitle != title) {
                extractFromSingleCandidate(parsedRawTitle)?.let { return it }
            }
        }
        return null
    }

    private fun extractFromSingleCandidate(candidate: String): SeriesInfo? {
        val clean = candidate.replace('_', ' ').replace(spaces, " ").trim()
        if (clean.isEmpty()) return null

        bracketedSeriesWithVol.find(clean)?.let { m ->
            buildSeriesInfo(m.groupValues[1], m.groupValues[2].toIntOrNull())?.let { return it }
        }

        bracketedStandaloneVol.find(clean)?.let { m ->
            val rawPrefix = m.groupValues[1].split(separator).firstOrNull().orEmpty()
            buildSeriesInfo(rawPrefix, m.groupValues[2].toIntOrNull())?.let { return it }
        }

        inlineExplicitTomeOrVol.find(clean)?.let { m ->
            buildSeriesInfo(m.groupValues[1], m.groupValues[2].toIntOrNull())?.let { return it }
        }

        inlineNumberedSubtitle.find(clean)?.let { m ->
            val vol = m.groupValues[2].toIntOrNull()
            if (vol != null && vol in 1..30) {
                buildSeriesInfo(m.groupValues[1], vol)?.let { return it }
            }
        }

        return null
    }

    private fun buildSeriesInfo(rawSeries: String, volume: Int?): SeriesInfo? {
        if (volume != null && volume !in 1..99) return null
        val trimmed = rawSeries
            .replace(spaces, " ")
            .trim()
            .trim('-', '–', '—', ':', ',', ';', '.', '(', ')', '[', ']')
            .trim()
        if (trimmed.length < 2 || trimmed.none { it.isLetter() }) return null
        val key = TextNormalizer.key(trimmed)
        if (key.isBlank() || key in nonSeriesKeys) return null
        if (trimmed.all { it.isDigit() }) return null
        return SeriesInfo(name = prettyTitle(trimmed), volume = volume)
    }
}
