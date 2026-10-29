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
    version = 8,
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
        // v8 : multi-utilisateurs & partage familial (statuts personnels, signets synchronisés par compte, métadonnées partagées, épinglage hors ligne).
        AutoMigration(from = 7, to = 8, spec = AppDatabase.MultiUserV8::class),
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
     * v8 : rattache les signets existants au compte connecté (s'il est unique), leur attribue un
     * identifiant de synchro déterministe et marque la progression correspondante à synchroniser.
     */
    class MultiUserV8 : AutoMigrationSpec {
        override fun onPostMigrate(db: SupportSQLiteDatabase) {
            db.execSQL(
                """
                UPDATE bookmarks
                SET account = CASE
                        WHEN account = 'local' AND (SELECT COUNT(DISTINCT account) FROM progress WHERE account != 'local') = 1
                        THEN (SELECT account FROM progress WHERE account != 'local' LIMIT 1)
                        ELSE account
                    END,
                    syncId = CASE
                        WHEN syncId = '' THEN CAST(trackIndex AS TEXT) || ':' || CAST(positionMs AS TEXT) || ':' || CAST(createdAt AS TEXT)
                        ELSE syncId
                    END,
                    updatedAt = CASE WHEN updatedAt = 0 THEN createdAt ELSE updatedAt END
                """.trimIndent(),
            )
            db.execSQL(
                """
                INSERT OR IGNORE INTO progress (bookId, account, trackIndex, positionMs, speed, percent, finished, updatedAt, dirty)
                SELECT b.bookId, b.account, 0, 0, 1.0, 0.0, 0, MAX(b.updatedAt), 1
                FROM bookmarks b
                WHERE b.account != 'local' AND b.deleted = 0
                GROUP BY b.bookId, b.account
                """.trimIndent(),
            )
            db.execSQL(
                """
                UPDATE progress SET dirty = 1
                WHERE EXISTS (
                    SELECT 1 FROM bookmarks b
                    WHERE b.bookId = progress.bookId AND b.account = progress.account AND b.deleted = 0
                )
                """.trimIndent(),
            )
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
