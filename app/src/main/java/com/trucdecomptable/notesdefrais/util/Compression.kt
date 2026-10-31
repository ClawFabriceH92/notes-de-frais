package com.trucdecomptable.notesdefrais.util

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.Locale

/**
 * Allège les photos de justificatifs trop lourdes.
 *
 * Une photo de ticket prise avec un téléphone récent pèse 5 à 12 Mo, alors
 * qu'un ticket lisible tient dans quelques centaines de kilo-octets. Ce poids
 * est ce qui fait dépasser la limite d'un mail et oblige à découper l'export :
 * on le réduit à l'entrée, une fois pour toutes, sans jamais rendre la pièce
 * illisible (le côté le plus long est conservé jusqu'à [COTE_MAX] pixels, ce qui
 * laisse l'OCR et la comptabilité lire le ticket).
 *
 * Un PDF n'est jamais touché : le recompresser demanderait une bibliothèque
 * complète, et le risque de casser la pièce dépasse le gain.
 */
object Compression {

    /** Au-delà de cette taille, une photo est ré-encodée. */
    const val SEUIL_OCTETS = 5L * 1024 * 1024

    /** Côté le plus long gardé : au-delà, un ticket ne gagne rien à être net. */
    const val COTE_MAX = 2560

    /** Qualités essayées, de la meilleure à la plus légère. */
    private val QUALITES = intArrayOf(85, 75, 65)

    /**
     * Orientations EXIF qui retournent ou miroitent l'image. Le ré-encodage
     * perd les métadonnées : sur ces cas rares, on préfère ne rien toucher
     * plutôt que de risquer une pièce à l'envers.
     */
    private val ORIENTATIONS_RISQUEES = setOf(2, 4, 5, 7)

    data class Resultat(val avant: Long, val apres: Long) {
        val gain: Long get() = (avant - apres).coerceAtLeast(0L)
    }

    /**
     * Décision seule, sans toucher au fichier : c'est la partie testable sans
     * téléphone.
     */
    fun fautCompresser(nomFichier: String, octets: Long, seuil: Long = SEUIL_OCTETS): Boolean =
        octets > seuil && !nomFichier.endsWith(".pdf", ignoreCase = true)

    /** "7.4 Mo" : les tailles se lisent en Mo, comme dans les dialogues. */
    fun mo(octets: Long): String =
        String.format(Locale.FRANCE, "%.1f Mo", octets / (1024.0 * 1024.0))

    /**
     * Ré-encode [fichier] sur place quand il dépasse [seuil]. Rend null quand
     * rien n'a été fait (fichier léger, PDF, image illisible, ou ré-encodage
     * qui n'apporterait rien).
     */
    fun compresser(
        fichier: File,
        seuil: Long = SEUIL_OCTETS,
        coteMax: Int = COTE_MAX,
    ): Resultat? {
        if (!fichier.isFile) return null
        val avant = fichier.length()
        if (!fautCompresser(fichier.name, avant, seuil)) return null
        if (orientation(fichier) in ORIENTATIONS_RISQUEES) return null

        val bitmap = decoder(fichier, coteMax) ?: return null
        val oriente = orienter(fichier, bitmap)
        try {
            var meilleur: ByteArray? = null
            for (qualite in QUALITES) {
                val tampon = ByteArrayOutputStream()
                if (!oriente.compress(Bitmap.CompressFormat.JPEG, qualite, tampon)) continue
                val octets = tampon.toByteArray()
                if (meilleur == null || octets.size < meilleur!!.size) meilleur = octets
                if (octets.size.toLong() <= seuil) break
            }
            val retenu = meilleur ?: return null
            // Une "compression" plus lourde que l'original n'a aucun intérêt.
            if (retenu.size.toLong() >= avant) return null
            ecrire(fichier, retenu)
            return Resultat(avant, retenu.size.toLong())
        } finally {
            if (oriente !== bitmap) oriente.recycle()
            bitmap.recycle()
        }
    }

    private fun orientation(fichier: File): Int = runCatching {
        @Suppress("DEPRECATION")
        android.media.ExifInterface(fichier.absolutePath)
            .getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION, 1)
    }.getOrDefault(1)

    /** Redresse l'image selon son EXIF : le ré-encodage perd le tag. */
    private fun orienter(fichier: File, bitmap: Bitmap): Bitmap {
        val degres = when (orientation(fichier)) {
            3 -> 180f
            6 -> 90f
            8 -> 270f
            else -> 0f
        }
        if (degres == 0f) return bitmap
        val matrice = Matrix().apply { postRotate(degres) }
        return runCatching {
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrice, true)
        }.getOrDefault(bitmap)
    }

    /**
     * Décode en réduisant par puissances de deux jusqu'à tenir sous [coteMax] :
     * une photo de 12 Mo ne fait jamais exploser la mémoire du téléphone.
     */
    private fun decoder(fichier: File, coteMax: Int): Bitmap? {
        val bornes = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(fichier.absolutePath, bornes)
        if (bornes.outWidth <= 0 || bornes.outHeight <= 0) return null
        val plusGrand = maxOf(bornes.outWidth, bornes.outHeight)
        var facteur = 1
        while (plusGrand / facteur > coteMax) facteur *= 2
        val options = BitmapFactory.Options().apply { inSampleSize = facteur }
        return BitmapFactory.decodeFile(fichier.absolutePath, options)
    }

    /** Écrit à côté puis renomme : une photo jamais à moitié écrasée. */
    private fun ecrire(cible: File, octets: ByteArray) {
        val temporaire = File(cible.parentFile, "${cible.name}.tmp")
        temporaire.writeBytes(octets)
        if (!temporaire.renameTo(cible)) {
            temporaire.copyTo(cible, overwrite = true)
            temporaire.delete()
        }
    }
}
