package com.trucdecomptable.notesdefrais.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.trucdecomptable.notesdefrais.export.ExportManager
import com.trucdecomptable.notesdefrais.util.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * On-device OCR. Nothing leaves the phone: ML Kit reads the text locally, and
 * the image is copied into the app's private storage so an export still works
 * after the gallery entry is gone.
 */
object ReceiptOcr {

    private const val MAX_DIMENSION = 2400

    private val recognizer by lazy {
        TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    }

    /** Copies [uri] into filesDir/justificatifs and returns the stored file. */
    fun stocker(context: Context, uri: Uri): File {
        val dossier = ExportManager.dossierJustificatifs(context)
        val flux = context.contentResolver.openInputStream(uri)
            ?: error("Fichier illisible")
        flux.use { entree ->
            val tete = ByteArray(4)
            val lu = entree.read(tete)
            val pdf = lu == 4 && String(tete) == "%PDF"
            val nom = if (pdf) "${UUID.randomUUID()}.pdf" else "${UUID.randomUUID()}.jpg"
            val cible = File(dossier, nom)
            // Re-open: the first four bytes are already consumed.
            context.contentResolver.openInputStream(uri)?.use { complet ->
                cible.outputStream().use { sortie -> complet.copyTo(sortie) }
            } ?: error("Fichier illisible")
            return cible
        }
    }

    /** Reads the text of a stored receipt; a PDF gives its first page picture only. */
    suspend fun texte(context: Context, fichier: File): String = withContext(Dispatchers.IO) {
        val bitmap = decoder(fichier) ?: return@withContext ""
        try {
            val image = InputImage.fromBitmap(bitmap, 0)
            suspendCancellableCoroutine { continuation ->
                recognizer.process(image)
                    .addOnSuccessListener { result -> continuation.resume(result.text) }
                    .addOnFailureListener { e ->
                        Diag.record("ocr", e)
                        continuation.resumeWithException(e)
                    }
            }
        } finally {
            bitmap.recycle()
        }
    }

    fun decoder(fichier: File): Bitmap? {
        if (!fichier.exists()) return null
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(fichier.absolutePath, options)
        if (options.outWidth <= 0) return null
        var facteur = 1
        var largeur = options.outWidth
        var hauteur = options.outHeight
        while (largeur / facteur > MAX_DIMENSION || hauteur / facteur > MAX_DIMENSION) facteur *= 2
        val reel = BitmapFactory.Options().apply { inSampleSize = facteur }
        return BitmapFactory.decodeFile(fichier.absolutePath, reel)
    }
}
