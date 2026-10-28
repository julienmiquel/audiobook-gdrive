package com.julienmiquel.audiotheque.player

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.database.MatrixCursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import kotlinx.coroutines.runBlocking

/**
 * Pochettes servies aux autres applications par une adresse `content://` : Android Auto et la
 * vignette de reprise du système tournent dans d'autres processus et ne peuvent pas lire un
 * fichier du dossier privé de l'app (`file://`). Une seule chose est servie : l'image enregistrée
 * comme pochette d'un livre (`content://<paquet>.covers/<id du livre>`), en lecture seule.
 *
 * Le fournisseur n'est pas exporté : le service de lecture accorde le droit de lecture (sur tout
 * le préfixe) à chaque application qui s'y connecte, voir [grantTo].
 */
class CoverArtProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String = when (coverFile(uri)?.extension?.lowercase()) {
        "png" -> "image/png"
        "webp" -> "image/webp"
        else -> "image/jpeg"
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (mode != "r") throw FileNotFoundException("Pochettes en lecture seule")
        val file = coverFile(uri) ?: throw FileNotFoundException("Pas de pochette pour $uri")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val file = coverFile(uri)
        val columns = projection ?: arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        val cursor = MatrixCursor(columns, 1)
        if (file != null) {
            cursor.addRow(
                columns.map { column ->
                    when (column) {
                        OpenableColumns.DISPLAY_NAME -> file.name
                        OpenableColumns.SIZE -> file.length()
                        else -> null
                    }
                },
            )
        }
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException("Lecture seule")

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Lecture seule")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("Lecture seule")

    /** Fichier de pochette du livre désigné par l'adresse, s'il existe (jamais un autre chemin). */
    private fun coverFile(uri: Uri): File? {
        val bookId = uri.pathSegments.firstOrNull()?.takeIf { it.isNotBlank() } ?: return null
        val app = context?.applicationContext as? PlayerDependenciesProvider ?: return null
        val path = runCatching { runBlocking { app.playerDependencies.database.bookDao().get(bookId)?.coverPath } }
            .onFailure { Log.w(TAG, "Pochette introuvable en base", it) }
            .getOrNull() ?: return null
        return File(path).takeIf { it.isFile }
    }

    companion object {
        private const val TAG = "CoverArtProvider"

        fun authority(context: Context): String = "${context.packageName}.covers"

        /** Préfixe de toutes les pochettes : c'est lui qui est accordé aux applications connectées. */
        fun baseUri(context: Context): Uri = Uri.parse("content://${authority(context)}/")

        /**
         * Adresse de la pochette d'un livre. La date du fichier est ajoutée pour qu'une nouvelle
         * pochette (choisie dans les photos, déposée dans Drive) remplace celle mise en cache.
         */
        fun uriFor(context: Context, bookId: String, file: File): Uri =
            baseUri(context).buildUpon().appendPath(bookId).appendQueryParameter("v", file.lastModified().toString()).build()

        /** Droit de lecture de toutes les pochettes pour [packageName] (Android Auto, System UI, Bluetooth…). */
        fun grantTo(context: Context, packageName: String?) {
            if (packageName.isNullOrBlank() || packageName == context.packageName || packageName == LEGACY_CONTROLLER_PACKAGE) return
            runCatching {
                context.grantUriPermission(
                    packageName,
                    baseUri(context),
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION,
                )
            }.onFailure { Log.d(TAG, "Pochettes non partagées avec $packageName : ${it.message}") }
        }

        /** Nom de paquet que Media3 donne aux contrôleurs anciens anonymes : pas une vraie application. */
        private const val LEGACY_CONTROLLER_PACKAGE = "android.media.session.MediaController"
    }
}

/** Petite copie JPEG d'une pochette, à embarquer dans un élément média (vignette de reprise après redémarrage). */
object CoverThumbnails {
    fun jpegBytes(path: String?, maxPx: Int, quality: Int = 85): ByteArray? {
        val file = path?.let(::File)?.takeIf { it.isFile } ?: return null
        return runCatching {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(file.path, bounds)
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
            var sample = 1
            while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
            val decoded = BitmapFactory.decodeFile(file.path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return null
            val longest = maxOf(decoded.width, decoded.height)
            val bitmap = if (longest > maxPx) {
                val scale = maxPx.toFloat() / longest
                Bitmap.createScaledBitmap(decoded, (decoded.width * scale).toInt().coerceAtLeast(1), (decoded.height * scale).toInt().coerceAtLeast(1), true)
            } else {
                decoded
            }
            ByteArrayOutputStream().also { bitmap.compress(Bitmap.CompressFormat.JPEG, quality, it) }.toByteArray()
        }.getOrNull()
    }
}
