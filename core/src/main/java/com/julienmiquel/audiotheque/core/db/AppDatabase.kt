package com.julienmiquel.audiotheque.core.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.withTransaction

/** Base locale partagée par les trois modules (bibliothèque, synchro, lecture). */
@Database(
    entities = [
        BookEntity::class,
        TrackEntity::class,
        ProgressEntity::class,
        BookmarkEntity::class,
        SyncEventEntity::class,
        SyncRunEntity::class,
        PendingOperationEntity::class,
        SyncPairEntity::class,
    ],
    version = 2,
    exportSchema = true,
    autoMigrations = [
        // v2 : clé d'auteur pour les doublons (colonne facultative, remplie au fil des synchros).
        AutoMigration(from = 1, to = 2),
    ],
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun bookDao(): BookDao
    abstract fun trackDao(): TrackDao
    abstract fun progressDao(): ProgressDao
    abstract fun bookmarkDao(): BookmarkDao
    abstract fun syncEventDao(): SyncEventDao
    abstract fun syncRunDao(): SyncRunDao
    abstract fun pendingOperationDao(): PendingOperationDao
    abstract fun syncPairDao(): SyncPairDao

    /**
     * Déconnexion : la bibliothèque du compte est vidée (livres, pistes, file d'opérations,
     * historique, paires). La progression (par compte) et les signets restent sur le téléphone.
     */
    suspend fun clearLibrary() = withTransaction {
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
