package com.julienmiquel.audiotheque.covers

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pochette choisie par l'utilisateur dans ses photos (sélecteur de photos du système, Google
 * Photos sur Pixel) : décodée, réduite à 1200 px de côté au plus et enregistrée en JPEG dans le
 * dossier des pochettes, prête à être déposée dans le dossier Drive du livre.
 */
class CoverImporter(private val context: Context) {

    /** Retourne le fichier écrit, ou null si l'image n'a pas pu être lue. */
    suspend fun import(uri: Uri, target: File): File? = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return@withContext null
        } catch (e: IOException) {
            return@withContext null
        } catch (e: SecurityException) {
            return@withContext null
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@withContext null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= MAX_SIDE && bounds.outHeight / (sample * 2) >= MAX_SIDE) sample *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = sample }
        val bitmap = try {
            resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, options) } ?: return@withContext null
        } catch (e: IOException) {
            return@withContext null
        } catch (e: SecurityException) {
            return@withContext null
        }
        val scaled = scaleDown(bitmap)
        target.parentFile?.mkdirs()
        target.outputStream().use { out -> scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, out) }
        if (scaled !== bitmap) scaled.recycle()
        bitmap.recycle()
        target
    }

    private fun scaleDown(bitmap: Bitmap): Bitmap {
        val longest = maxOf(bitmap.width, bitmap.height)
        if (longest <= MAX_SIDE) return bitmap
        val ratio = MAX_SIDE.toFloat() / longest
        return Bitmap.createScaledBitmap(bitmap, (bitmap.width * ratio).toInt().coerceAtLeast(1), (bitmap.height * ratio).toInt().coerceAtLeast(1), true)
    }

    companion object {
        const val MAX_SIDE = 1200
        const val QUALITY = 88
    }
}
