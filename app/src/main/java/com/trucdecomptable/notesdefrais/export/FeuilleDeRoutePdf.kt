package com.trucdecomptable.notesdefrais.export

import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.text.TextPaint
import com.trucdecomptable.notesdefrais.data.BaremeKm
import com.trucdecomptable.notesdefrais.data.Depense
import com.trucdecomptable.notesdefrais.data.Parametres
import com.trucdecomptable.notesdefrais.data.Projet
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * A mileage allowance has no receipt to photograph: the proof is the trip
 * itself. This generates the feuille de route an auditor expects, with the
 * vehicle, the route, the bracket used and the signature line.
 */
object FeuilleDeRoutePdf {

    private val formatDate = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    fun generer(
        destination: File,
        parametres: Parametres,
        projet: Projet,
        depense: Depense,
    ) {
        val document = PdfDocument()
        try {
            val largeur = 595
            val hauteur = 842
            val page = document.startPage(PdfDocument.PageInfo.Builder(largeur, hauteur, 1).create())
            val canvas = page.canvas
            canvas.drawColor(Color.WHITE)

            val marge = 40f
            var y = 70f
            val titre = TextPaint().apply {
                color = Color.BLACK
                textSize = 18f
                isFakeBoldText = true
                isAntiAlias = true
            }
            val gras = TextPaint().apply {
                color = Color.BLACK
                textSize = 11f
                isFakeBoldText = true
                isAntiAlias = true
            }
            val texte = TextPaint().apply {
                color = Color.BLACK
                textSize = 11f
                isAntiAlias = true
            }
            val filet = Paint().apply {
                color = Color.parseColor("#1B6B4A")
                strokeWidth = 1.5f
            }

            canvas.drawText("Feuille de route", marge, y, titre)
            y += 8f
            canvas.drawLine(marge, y, largeur - marge, y, filet)
            y += 26f

            val lignesIdentite = buildList {
                if (parametres.mention.isNotBlank()) add("Titulaire" to parametres.mention)
                if (parametres.adresse.isNotBlank()) add("Adresse" to parametres.adresse)
                if (parametres.siret.isNotBlank()) add("SIRET" to parametres.siret)
                add("Projet" to projet.nom)
            }
            for ((libelle, valeur) in lignesIdentite) {
                canvas.drawText("$libelle :", marge, y, gras)
                canvas.drawText(valeur, marge + 90f, y, texte)
                y += 18f
            }

            y += 10f
            canvas.drawLine(marge, y, largeur - marge, y, filet)
            y += 26f

            val categorie = parametres.categorie
            val cv = parametres.puissanceFiscale
            val distance = depense.distanceKm ?: 0
            val lignesTrajet = buildList {
                add("Date du déplacement" to runCatching {
                    LocalDate.parse(depense.date).format(formatDate)
                }.getOrDefault(depense.date))
                if (depense.trajet.isNotBlank()) add("Trajet" to depense.trajet)
                add("Distance" to "$distance km (aller-retour si indiqué)")
                add("Véhicule" to buildString {
                    append(categorie.label)
                    if (cv > 0) append(" · $cv CV")
                    if (parametres.electrique) append(" · électrique")
                })
                if (parametres.plaque.isNotBlank()) add("Immatriculation" to parametres.plaque)
                if (depense.libelle.isNotBlank()) add("Objet" to depense.libelle)
            }
            for ((libelle, valeur) in lignesTrajet) {
                canvas.drawText("$libelle :", marge, y, gras)
                canvas.drawText(valeur, marge + 130f, y, texte)
                y += 18f
            }

            y += 16f
            canvas.drawText("Calcul de l'indemnité", marge, y, gras)
            y += 18f
            val explication = BaremeKm.explication(
                distanceKm = distance,
                cumulAnnuelKm = parametres.kmAnnuels,
                categorie = categorie,
                puissanceFiscale = cv,
                electrique = parametres.electrique,
            )
            y = ecrireParagraphe(canvas, explication, marge, y, largeur - 2 * marge, texte)

            y += 12f
            val indemnite = depense.indemniteCentimes ?: 0L
            val frais = depense.fraisAnnexesCentimes ?: 0L
            val total = depense.montantCentimes
            val lignesMontants = buildList {
                add("Indemnité kilométrique" to montant(indemnite))
                if (frais > 0) add("Péages et stationnement" to montant(frais))
                add("Total" to montant(total))
            }
            for ((i, ligne) in lignesMontants.withIndex()) {
                val grasLigne = i == lignesMontants.lastIndex
                canvas.drawText(ligne.first, marge, y, if (grasLigne) gras else texte)
                canvas.drawText(ligne.second, largeur - marge - 90f, y, if (grasLigne) gras else texte)
                y += 18f
            }

            y += 24f
            canvas.drawText(
                "Les frais de péage et de stationnement sont justifiés par les tickets joints.",
                marge,
                y,
                TextPaint().apply {
                    color = Color.DKGRAY
                    textSize = 9f
                    isAntiAlias = true
                },
            )

            y += 50f
            canvas.drawText("Signature :", marge, y, gras)
            canvas.drawLine(marge + 90f, y + 4f, largeur - marge, y + 4f, Paint().apply {
                color = Color.LTGRAY
                strokeWidth = 1f
            })
            canvas.drawText(
                "Édité le " + LocalDate.now().format(formatDate),
                marge,
                hauteur - 40f,
                TextPaint().apply {
                    color = Color.GRAY
                    textSize = 9f
                    isAntiAlias = true
                },
            )

            document.finishPage(page)
            FileOutputStream(destination).use { document.writeTo(it) }
        } finally {
            document.close()
        }
    }

    private fun ecrireParagraphe(
        canvas: android.graphics.Canvas,
        phrase: String,
        x: Float,
        yDepart: Float,
        largeur: Float,
        paint: TextPaint,
    ): Float {
        var y = yDepart
        var reste = phrase
        while (reste.isNotEmpty()) {
            val coupe = paint.breakText(reste, true, largeur, null)
            val morceau = reste.substring(0, coupe.coerceAtLeast(1))
            canvas.drawText(morceau, x, y, paint)
            y += 15f
            reste = reste.substring(morceau.length)
        }
        return y
    }

    private fun montant(centimes: Long): String =
        String.format(Locale.FRANCE, "%.2f €", centimes / 100.0)
}
