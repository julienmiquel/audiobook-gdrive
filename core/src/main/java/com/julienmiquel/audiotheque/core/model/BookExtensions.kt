package com.julienmiquel.audiotheque.core.model

import com.julienmiquel.audiotheque.core.db.BookEntity
import com.julienmiquel.audiotheque.core.db.TrackEntity
import com.julienmiquel.audiotheque.core.parse.TextNormalizer

/** Statut affiché : celui du geste en attente s'il y en a un, sinon celui confirmé par Drive. */
val BookEntity.effectiveStatus: BookStatus get() = pendingStatus ?: status

/** Dossier parent attendu une fois la file d'opérations rejouée. */
val BookEntity.effectiveParentId: String get() = pendingParentId ?: parentId

/** Date du dernier changement de statut (geste compris), base des délais de nettoyage. */
val BookEntity.effectiveStatusChangedAt: Long get() = pendingSince ?: statusChangedAt

val BookEntity.hasPendingMove: Boolean get() = pendingStatus != null

/** Nom du dossier/fichier dans Drive en tenant compte d'un renommage en attente. */
val BookEntity.effectiveDriveName: String get() = pendingName ?: name

/** Titre affiché : correction de l'utilisateur, sinon découpage automatique du nom. */
val BookEntity.displayTitle: String
    get() = customTitle?.takeIf { it.isNotBlank() } ?: parsedTitle

/** Auteur affiché. Une correction vide efface l'auteur proposé. */
val BookEntity.displayAuthor: String?
    get() = (customAuthor ?: parsedAuthor)?.takeIf { it.isNotBlank() }

/** Voix : correction, sinon tags ID3, sinon nom du dossier (« lu par … »). */
val BookEntity.displayNarrator: String?
    get() = (customNarrator ?: tagNarrator ?: parsedNarrator)?.takeIf { it.isNotBlank() }

/** Clé d'auteur attendue (doublons), d'après l'auteur affiché. */
val BookEntity.expectedAuthorKey: String?
    get() = TextNormalizer.keyOrNull(displayAuthor)

val BookEntity.isPlayableKind: Boolean get() = true

/** « Partie 4 · chapitre 4 », « Chapitre 12 » ou « Piste 3 ». */
val TrackEntity.chapterLabel: String
    get() = when {
        part != null && chapter != null -> "Partie $part · chapitre $chapter"
        chapter != null -> "Chapitre $chapter"
        else -> "Piste ${sortIndex + 1}"
    }

/** Libellé de groupe pour la liste des chapitres (groupée par partie). */
val TrackEntity.partLabel: String?
    get() = part?.let { "Partie $it" }

private val GENERIC_TRACK_WORDS = setOf(
    "piste", "track", "plage", "chapitre", "chap", "chapter", "ch",
    "partie", "part", "cd", "disque", "disc", "audio", "mp3", "m4a", "m4b", "flac", "sur", "of",
)

/**
 * Titre de piste réellement informatif, ou `null` lorsque [TrackEntity.title] ne fait que répéter
 * « Piste X », « Chapitre X », le titre du livre ou le nom brut numéroté du fichier.
 */
fun TrackEntity.meaningfulTitle(bookTitle: String? = null, bookAuthor: String? = null): String? {
    val trimmed = title.trim()
    if (trimmed.isEmpty()) return null
    if (trimmed.equals(chapterLabel, ignoreCase = true)) return null
    var key = " ${TextNormalizer.key(trimmed)} "
    if (key.isBlank()) return null
    val bookKey = TextNormalizer.keyOrNull(bookTitle)
    if (bookKey != null) {
        key = key.replace(" $bookKey ", " ")
    }
    val authorKey = TextNormalizer.keyOrNull(bookAuthor)
    if (authorKey != null) {
        key = key.replace(" $authorKey ", " ")
    }
    val remainingTokens = key.trim().split(' ').filter { token ->
        token.isNotBlank() &&
            !token.all { it.isDigit() } &&
            token !in GENERIC_TRACK_WORDS &&
            !Regex("""^(?:t|p|c|cd|ch|pt|vol)\d+$""").matches(token)
    }
    return if (remainingTokens.isEmpty()) null else trimmed
}

/** Clés de compte pour la progression. */
object Accounts {
    /** Progression locale avant toute connexion Google. */
    const val LOCAL = "local"
}
