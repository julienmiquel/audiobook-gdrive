package com.julienmiquel.audiotheque.core.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

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
    version = 1,
    exportSchema = true,
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
