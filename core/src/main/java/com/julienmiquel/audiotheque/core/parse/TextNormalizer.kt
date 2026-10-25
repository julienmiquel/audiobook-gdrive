package com.julienmiquel.audiotheque.core.parse

import java.text.Normalizer
import java.util.Locale

/** Normalisation du texte pour la recherche et le repérage des doublons. */
object TextNormalizer {
    private val marks = Regex("\\p{Mn}+")
    private val nonAlphanumeric = Regex("[^a-z0-9]+")

    fun stripAccents(s: String): String =
        Normalizer.normalize(s, Normalizer.Form.NFD)
            .replace(marks, "")
            .replace("œ", "oe").replace("Œ", "OE")
            .replace("æ", "ae").replace("Æ", "AE")
            .replace("ß", "ss")

    /**
     * Clé de comparaison : minuscules, sans accents ni ponctuation, espaces réduits.
     * « LE_CHARME_DISCRET_DE_L_INTESTIN » et « Le charme discret de l'intestin » ont la même clé.
     */
    fun key(s: String): String =
        stripAccents(s).lowercase(Locale.ROOT).replace(nonAlphanumeric, " ").trim()

    /** Clé d'un champ facultatif (auteur) : null s'il est vide. */
    fun keyOrNull(s: String?): String? = s?.let(::key)?.takeIf { it.isNotEmpty() }

    /** Vrai si tous les mots de [query] apparaissent dans [text] (insensible aux accents). */
    fun matches(text: String?, query: String): Boolean {
        if (text.isNullOrBlank()) return false
        val haystack = key(text)
        return key(query).split(' ').filter { it.isNotEmpty() }.all { haystack.contains(it) }
    }
}

/** Tri « naturel » : « piste 2 » avant « piste 10 », sans tenir compte de la casse ni des accents. */
object NaturalOrder : Comparator<String> {
    private val chunk = Regex("""\d+|\D+""")

    override fun compare(a: String, b: String): Int {
        val ca = chunk.findAll(TextNormalizer.stripAccents(a).lowercase(Locale.ROOT)).map { it.value }.toList()
        val cb = chunk.findAll(TextNormalizer.stripAccents(b).lowercase(Locale.ROOT)).map { it.value }.toList()
        for (i in 0 until minOf(ca.size, cb.size)) {
            val x = ca[i]
            val y = cb[i]
            val cmp = if (x[0].isDigit() && y[0].isDigit()) compareNumbers(x, y) else x.compareTo(y)
            if (cmp != 0) return cmp
        }
        return if (ca.size != cb.size) ca.size.compareTo(cb.size) else a.compareTo(b)
    }

    private fun compareNumbers(x: String, y: String): Int {
        val tx = x.trimStart('0')
        val ty = y.trimStart('0')
        if (tx.length != ty.length) return tx.length.compareTo(ty.length)
        val cmp = tx.compareTo(ty)
        return if (cmp != 0) cmp else x.length.compareTo(y.length)
    }
}
