package com.julienmiquel.audiotheque.core.model

/**
 * Préréglages audio du lecteur : traitement du signal appliqué avant la sortie, mémorisé pour
 * tous les livres (Lecteur › Son, ou Réglages › Lecture). Les chaînes reprennent les standards
 * de la chaîne vocale « Netophonix » (coupe-bas 80 Hz, présence 3,5 kHz, compression 3:1,
 * normalisation à −20 dBFS) et le moteur binaural (voix placée dans une pièce).
 */
enum class AudioPreset(val label: String, val description: String) {
    NONE(
        "Son d'origine",
        "Aucun traitement : le fichier tel qu'il a été enregistré.",
    ),
    NORMALIZE(
        "Volume constant",
        "Normalisation : les passages faibles sont relevés, les forts contenus, puis un limiteur évite toute saturation. " +
            "Même niveau d'un chapitre à l'autre et d'un livre à l'autre.",
    ),
    VOICE_BOOST(
        "Voix renforcée",
        "Chaîne vocale : coupe-bas 80 Hz, présence 3,5 kHz, compression 3:1 et normalisation à −20 dBFS. " +
            "Articulation plus nette, pratique en voiture ou dans le bruit.",
    ),
    NIGHT(
        "Niveau très bas",
        "Pour écouter à très faible volume : dynamique fortement réduite (les murmures restent audibles), " +
            "sortie atténuée de 12 dB pour que le volume minimal du téléphone devienne vraiment bas.",
    ),
    BINAURAL(
        "Binaural (casque)",
        "La voix est placée devant vous dans une petite pièce : modèle de tête (délai et ombre entre les oreilles), " +
            "premières réflexions et réverbération discrète. Son « hors de la tête », moins fatigant au casque.",
    ),
    ;

    companion object {
        fun fromName(name: String?): AudioPreset = entries.firstOrNull { it.name == name } ?: NONE
        fun fromNameOrNull(name: String?): AudioPreset? = entries.firstOrNull { it.name == name }
    }
}

/** Acoustique simulée pour le moteur binaural. */
enum class BinauralRoom(val label: String, val description: String) {
    INTIMATE("Intime", "Voix proche, réflexions très discrètes"),
    SMALL("Petite pièce", "Équilibre naturel, son aéré"),
    MEETING("Salon", "Plus de recul et d'espace"),
    ;

    companion object {
        fun fromName(name: String?): BinauralRoom = entries.firstOrNull { it.name == name } ?: SMALL
    }
}

/** Réglages fins du rendu binaural (angle horizontal de la voix et acoustique de la pièce). */
data class BinauralConfig(
    val azimuthDeg: Int = 0,
    val room: BinauralRoom = BinauralRoom.SMALL,
) {
    companion object {
        val AZIMUTH_CHOICES = listOf(-30, -15, 0, 15, 30)

        fun azimuthLabel(deg: Int): String = when {
            deg < 0 -> "${-deg}° G"
            deg > 0 -> "${deg}° D"
            else -> "Face (0°)"
        }
    }
}

