package com.trucdecomptable.notesdefrais.ocr

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
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

    /** An invoice received by mail often runs over two or three pages. */
    private const val PAGES_PDF = 3

    /** Rendered width of a PDF page handed to ML Kit. */
    private const val LARGEUR_PDF = 1700

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

    /**
     * Reads the text of a stored receipt. A photo goes straight to ML Kit; a
     * PDF is rendered page by page first, since the recogniser only takes
     * bitmaps. An encrypted or damaged PDF yields "" and the form opens, which
     * is still better than refusing the file.
     */
    suspend fun texte(context: Context, fichier: File): String = withContext(Dispatchers.IO) {
        if (ExportManager.estPdf(fichier)) textePdf(fichier) else texteImage(fichier)
    }

    private suspend fun texteImage(fichier: File): String {
        val bitmap = decoder(fichier) ?: return ""
        try {
            return texteBitmap(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /** Renders the first pages of a PDF and reads each one. */
    private suspend fun textePdf(fichier: File): String {
        if (!fichier.exists()) return ""
        val descripteur = runCatching {
            ParcelFileDescriptor.open(fichier, ParcelFileDescriptor.MODE_READ_ONLY)
        }.onFailure { Diag.record("ocr/pdf-ouverture", it) }.getOrNull() ?: return ""

        try {
            PdfRenderer(descripteur).use { moteur ->
                val texte = StringBuilder()
                val pages = minOf(moteur.pageCount, PAGES_PDF)
                for (index in 0 until pages) {
                    val page = runCatching { moteur.openPage(index) }.getOrNull() ?: continue
                    try {
                        val bitmap = runCatching { rendre(page) }.getOrNull() ?: continue
                        try {
                            texte.append(texteBitmap(bitmap)).append('\n')
                        } finally {
                            bitmap.recycle()
                        }
                    } finally {
                        runCatching { page.close() }
                    }
                }
                return texte.toString()
            }
        } catch (e: Exception) {
            Diag.record("ocr/pdf", e)
            return ""
        } finally {
            runCatching { descripteur.close() }
        }
    }

    private fun rendre(page: PdfRenderer.Page): Bitmap {
        val echelle = (LARGEUR_PDF.toFloat() / page.width.toFloat()).coerceIn(1f, 3f)
        val largeur = (page.width * echelle).toInt().coerceAtLeast(1)
        val hauteur = (page.height * echelle).toInt().coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(largeur, hauteur, Bitmap.Config.ARGB_8888)
        // PdfRenderer leaves untouched areas transparent: paint the paper white
        // first, otherwise ML Kit reads white-on-white.
        android.graphics.Canvas(bitmap).drawColor(Color.WHITE)
        page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        return bitmap
    }

    private suspend fun texteBitmap(bitmap: Bitmap): String {
        val image = InputImage.fromBitmap(bitmap, 0)
        return suspendCancellableCoroutine { continuation ->
            recognizer.process(image)
                .addOnSuccessListener { result -> continuation.resume(result.text) }
                .addOnFailureListener { e ->
                    Diag.record("ocr", e)
                    continuation.resumeWithException(e)
                }
        }
    }

    /**
     * Lecture d'une pièce, avec recadrage automatique sur le ticket quand c'est
     * une photo.
     *
     * Les blocs de texte lus par ML Kit donnent l'étendue du ticket : on ne
     * garde que cette zone, plus une marge. L'image est réécrite sur place, donc
     * le justificatif joint, l'aperçu et l'export utilisent la photo recadrée.
     * Les PDF ne sont pas touchés.
     */
    suspend fun lire(
        context: Context,
        fichier: File,
        recadrage: Boolean = true,
    ): Lecture = withContext(Dispatchers.IO) {
        if (fichier.extension.equals("pdf", ignoreCase = true)) {
            return@withContext Lecture(texte(context, fichier), recadre = false)
        }
        val bitmap = decoder(fichier) ?: return@withContext Lecture("", recadre = false)
        val oriente = orienter(fichier, bitmap)
        val resultat = runCatching { reconnaitre(oriente) }.getOrNull()
            ?: return@withContext Lecture("", recadre = false)

        val zones = resultat.textBlocks
            .filter { it.text.trim().length >= 2 }
            .mapNotNull { bloc -> bloc.boundingBox }
            .map { Zone(it.left, it.top, it.right, it.bottom) }

        val zone = if (recadrage) {
            Cadrage.zoneDuTicket(zones, oriente.width, oriente.height)
        } else {
            null
        }

        if (zone == null) {
            // Sans recadrage on garde quand même la photo remise d'aplomb.
            if (oriente !== bitmap) enregistrerBitmap(fichier, oriente)
            return@withContext Lecture(resultat.text, recadre = false)
        }

        val decoupe = runCatching {
            Bitmap.createBitmap(oriente, zone.left, zone.top, zone.largeur, zone.hauteur)
        }.getOrNull() ?: return@withContext Lecture(resultat.text, recadre = false)

        if (decoupe !== oriente) enregistrerBitmap(fichier, decoupe)
        Diag.record(
            "ocr/recadrage",
            "${fichier.name} ${oriente.width}×${oriente.height} -> " +
                "${zone.largeur}×${zone.hauteur} (${zones.size} blocs)",
        )
        Lecture(resultat.text, recadre = true)
    }

    /** Redresse la photo selon son EXIF (appareil tenu en portrait, par exemple). */
    private fun orienter(fichier: File, bitmap: Bitmap): Bitmap {
        val orientation = runCatching {
            @Suppress("DEPRECATION")
            android.media.ExifInterface(fichier.absolutePath)
                .getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)
        }.getOrDefault(1)
        val degres = when (orientation) {
            3 -> 180f
            6 -> 90f
            8 -> 270f
            else -> 0f
        }
        if (degres == 0f) return bitmap
        val matrice = android.graphics.Matrix().apply { postRotate(degres) }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrice, true)
        }.getOrDefault(bitmap)
    }

    private suspend fun reconnaitre(bitmap: Bitmap): com.google.mlkit.vision.text.Text =
        suspendCancellableCoroutine { continuation ->
            recognizer.process(InputImage.fromBitmap(bitmap, 0))
                .addOnSuccessListener { result -> continuation.resume(result) }
                .addOnFailureListener { e ->
                    Diag.record("ocr", e)
                    continuation.resumeWithException(e)
                }
        }

    private fun enregistrerBitmap(cible: File, bitmap: Bitmap) {
        runCatching {
            val temporaire = File(cible.parentFile, "${cible.name}.tmp")
            java.io.FileOutputStream(temporaire).use { sortie ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 90, sortie)
            }
            if (!temporaire.renameTo(cible)) {
                temporaire.copyTo(cible, overwrite = true)
                temporaire.delete()
            }
        }.onFailure { Diag.record("ocr/ecriture", it) }
    }

    /** Texte lu et indication de recadrage. */
    data class Lecture(val texte: String, val recadre: Boolean)


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
