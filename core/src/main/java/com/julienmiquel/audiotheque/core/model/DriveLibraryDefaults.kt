package com.julienmiquel.audiotheque.core.model

import com.julienmiquel.audiotheque.core.db.SyncPairEntity

/**
 * La bibliothèque Drive existante (/AudioBook) et ses dossiers de statut, repris tels quels.
 * IDs relevés le 25/10/2026 (cf. spécification, « La bibliothèque Drive existante »).
 */
object DriveLibraryDefaults {
    const val ROOT_ID = "1JLdkxNQmrR19b8f0RBJy1mVrq52wBX7q"
    const val NEW_ID = "19dbpVKRuUQbhgVXW8w1sXnCRq0_9DLWZ"
    const val IN_PROGRESS_ID = "1DXIXliGH7l5xDKWpQEwJ71cyixyM3k2f"
    const val LATER_ID = "1n-8xjo2ChGMSr7ymN-xxETgcfOaFrKBQ"
    const val DONE_ID = "1WSba2vuiA7DjC_bJgkdNiQge6BHSSbbt"
    const val ARCHIVES_ID = "1QZ8X0-K8Wwa5mvgaXX2L0DDPGTEZhpMo"
    const val MEDITATIONS_ID = "1pDDvfpSYv23sa-8Qsf-glpRUpds5KkA_"

    const val ROOT_PATH = "/AudioBook"

    /** Dossiers de statut et de collection : jamais des livres, même si leur paire est retirée. */
    val FOLDER_IDS: Set<String> = setOf(ROOT_ID, NEW_ID, IN_PROGRESS_ID, LATER_ID, DONE_ID, ARCHIVES_ID, MEDITATIONS_ID)

    /** Une paire par statut, créée par défaut dans l'écran Dossiers synchronisés. */
    fun defaultPairs(): List<SyncPairEntity> = listOf(
        pair(BookStatus.IN_PROGRESS, "_En écoute", IN_PROGRESS_ID, PairMode.FULL, 0),
        pair(BookStatus.NEW, "_Nouveaux", NEW_ID, PairMode.METADATA, 1),
        pair(BookStatus.LATER, "_A écouter plus tard", LATER_ID, PairMode.METADATA, 2),
        pair(BookStatus.DONE, "_Ecouté", DONE_ID, PairMode.METADATA, 3),
        pair(BookStatus.ARCHIVED, "_archives", ARCHIVES_ID, PairMode.ON_OPEN, 4),
        SyncPairEntity(
            status = BookStatus.UNSORTED,
            name = "AudioBook",
            driveFolderId = ROOT_ID,
            drivePath = ROOT_PATH,
            mode = PairMode.ON_OPEN,
            position = 5,
        ),
        pair(BookStatus.COLLECTION, "_meditations", MEDITATIONS_ID, PairMode.ON_OPEN, 6),
    )

    private fun pair(status: BookStatus, name: String, id: String, mode: PairMode, position: Int) =
        SyncPairEntity(
            status = status,
            name = name,
            driveFolderId = id,
            drivePath = "$ROOT_PATH/$name",
            mode = mode,
            position = position,
        )
}
