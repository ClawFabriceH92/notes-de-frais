package com.trucdecomptable.notesdefrais.ocr

import android.content.Context
import android.net.Uri
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.trucdecomptable.notesdefrais.util.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

/**
 * Lecture du code-barres d'un ticket avec ML Kit, on-device comme l'OCR déjà
 * présent : rien ne quitte le téléphone. On ne cherche que les formats utiles
 * pour un portail « facture en ligne » : le code 128 (tickets Auchan), les
 * EAN/UPC et les QR codes.
 */
object CodeBarre {

    private val scanner by lazy {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder()
                .setBarcodeFormats(
                    Barcode.FORMAT_CODE_128,
                    Barcode.FORMAT_EAN_13,
                    Barcode.FORMAT_EAN_8,
                    Barcode.FORMAT_UPC_A,
                    Barcode.FORMAT_UPC_E,
                    Barcode.FORMAT_QR_CODE,
                )
                .build(),
        )
    }

    /**
     * Valeurs brutes des codes-barres lus dans l'image, dans l'ordre où ML Kit
     * les trouve. Un échec de lecture ne bloque rien : on renvoie une liste
     * vide (le bouton ouvre quand même le portail, sans code à copier).
     */
    suspend fun lire(context: Context, fichier: File): List<String> = withContext(Dispatchers.IO) {
        val image = runCatching { InputImage.fromFilePath(context, Uri.fromFile(fichier)) }
            .onFailure { Diag.record("code-barres/ouverture", it) }
            .getOrNull() ?: return@withContext emptyList()
        suspendCancellableCoroutine { continuation ->
            scanner.process(image)
                .addOnSuccessListener { result ->
                    continuation.resume(result.mapNotNull { it.rawValue })
                }
                .addOnFailureListener { e ->
                    Diag.record("code-barres", e)
                    continuation.resume(emptyList())
                }
        }
    }

    /**
     * Le meilleur candidat pour un portail enseigne : on ne garde que les
     * chaînes de chiffres d'au moins 8 caractères, on préfère les codes d'au
     * moins 22 chiffres (exigence d'Auchan), puis les plus longs. Renvoie null
     * quand rien n'est exploitable.
     */
    fun meilleur(codes: List<String>): String? {
        val numeriques = codes.map { it.trim() }
            .filter { it.length >= 8 && it.all { caractere -> caractere.isDigit() } }
        if (numeriques.isEmpty()) return null
        return numeriques.maxWithOrNull { a, b ->
            val aLong = a.length >= 22
            val bLong = b.length >= 22
            when {
                aLong && !bLong -> 1   // 22+ chiffres d'abord
                !aLong && bLong -> -1
                else -> a.length.compareTo(b.length) // puis les plus longues
            }
        }
    }
}
