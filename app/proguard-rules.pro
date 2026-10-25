# Règles R8 de l'application. Les bibliothèques (Room, Media3, OkHttp, kotlinx.serialization,
# WorkManager) fournissent leurs propres règles consommateur.

# Modèles JSON (Drive, Google Books, progression) sérialisés par kotlinx.serialization.
-keepattributes *Annotation*, InnerClasses, Signature
-keepclassmembers @kotlinx.serialization.Serializable class com.julienmiquel.audiotheque.** {
    *** Companion;
    kotlinx.serialization.KSerializer serializer(...);
}
