package com.julienmiquel.audiotheque.core.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.AutoMigrationSpec
import androidx.room.withTransaction
import androidx.sqlite.db.SupportSQLiteDatabase

/** Base locale partagée par les trois modules (bibliothèque, synchro, lecture). */
@Database(
    entities = [
        BookEntity::class,
        TrackEntity::class,
        EmbeddedChapterEntity::class,
        ProgressEntity::class,
        BookmarkEntity::class,
        SyncEventEntity::class,
        SyncRunEntity::class,
        PendingOperationEntity::class,
        SyncPairEntity::class,
        ListeningSessionEntity::class,
    ],
    version = 7,
    exportSchema = true,
    autoMigrations = [
        // v2 : clé d'auteur pour les doublons (colonne facultative, remplie au fil des synchros).
        AutoMigration(from = 1, to = 2),
        // v3 : pochette à déposer dans Drive ; progression désormais dans le dossier Drive du livre.
        AutoMigration(from = 2, to = 3, spec = AppDatabase.ProgressInBookFolder::class),
        // v4 : suppression d'un doublon (dossier mis à la corbeille Drive par la file d'opérations).
        AutoMigration(from = 3, to = 4),
        // v5 : préréglage audio par livre et chapitres embarqués (.m4b / ID3 CHAP).
        AutoMigration(from = 4, to = 5),
        // v6 : renommage normalisé dans Drive (« Titre - AUTEUR ») via la file d'opérations.
        AutoMigration(from = 5, to = 6),
        // v7 : sessions d'écoute locales pour les statistiques d'écoute.
        AutoMigration(from = 6, to = 7),
    ],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun trackDao(): TrackDao
    abstract fun embeddedChapterDao(): EmbeddedChapterDao
    abstract fun progressDao(): ProgressDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun syncEventDao(): SyncEventDao
    abstract fun syncRunDao(): SyncRunDao
    abstract fun pendingOperationDao(): PendingOperationDao
    abstract fun syncPairDao(): SyncPairDao
    abstract fun listeningSessionDao(): ListeningSessionDao

    /**
     * v3 : la progression quitte l'espace caché (appDataFolder) pour un fichier dans le dossier
     * Drive de chaque livre. Toutes les positions connues sont à renvoyer ; la plus récente
     * l'emportera quand les téléphones se rencontreront dans Drive.
     */
    class ProgressInBookFolder : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            db.execSQL("UPDATE progress SET dirty = 1, remoteFileId = NULL, remoteSyncedAt = NULL")
        }
    }

    /**
     * Déconnexion : la bibliothèque du compte est vidée (livres, pistes, file d'opérations,
     * historique, paires). La progression (par compte) et les signets restent sur le téléphone.
     */
    suspend fun clearLibrary() = withTransaction {
        embeddedChapterDao().deleteAll()
        trackDao().deleteAll()
        pendingOperationDao().deleteAll()
        bookDao().deleteAll()
        syncEventDao().deleteAll()
        syncRunDao().deleteAll()
        syncPairDao().deleteAll()
    }

    companion object {
        const val NAME = "audiotheque.db"

        fun build(context: Context): AppDatabase =
            Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                .build()

        /** Base en mémoire pour les tests. */
        fun inMemory(context: Context): AppDatabase =
            Room.inMemoryDatabaseBuilder(context.applicationContext, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
    }
}
