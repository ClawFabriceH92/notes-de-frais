package com.trucdecomptable.notesdefrais.export

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.trucdecomptable.notesdefrais.data.Depense
import com.trucdecomptable.notesdefrais.data.Projet
import com.trucdecomptable.notesdefrais.data.TypeDepense
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.time.LocalDate
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Builds the deliverable Fabrice asks for: one ZIP per project holding a
 * recap.xlsx and one PDF per expense, renamed projet_date_type_TTC.pdf.
 */
object ExportManager {

    const val DOSSIER_JUSTIFICATIFS = "justificatifs"
    const val DOSSIER_EXPORTS = "exports"

    fun dossier(context: Context, nom: String): File =
        File(context.filesDir, nom).apply { mkdirs() }

    fun dossierJustificatifs(context: Context): File = dossier(context, DOSSIER_JUSTIFICATIFS)

    fun dossierExports(context: Context): File = dossier(context, DOSSIER_EXPORTS)

    /** "Mission Paris" -> "Mission_Paris", safe on every file system. */
    fun slugProjet(nom: String): String {
        val sansAccents = java.text.Normalizer.normalize(nom, java.text.Normalizer.Form.NFD)
            .replace(Regex("""\p{Mn}+"""), "")
        val propre = sansAccents.replace(Regex("""[^A-Za-z0-9]+"""), "_").trim('_')
        return propre.ifBlank { "Projet" }.take(24)
    }

    /** projet_date_type_montantTTCdevise.pdf */
    fun nomJustificatif(slugProjet: String, depense: Depense): String {
        val montant = String.format(Locale.US, "%.2f", depense.montantCentimes / 100.0)
        return "${slugProjet}_${depense.date}_${depense.typeDepense.slug}_${montant}${depense.devise}.pdf"
    }

    /**
     * Zips the project: recap.xlsx plus one PDF per expense. Returned file lives
     * in filesDir/exports so the FileProvider can hand it to any app.
     */
    suspend fun exporter(context: Context, projet: Projet, depenses: List<Depense>): File =
        withContext(Dispatchers.IO) {
            val slug = slugProjet(projet.nom)
            val horodatage = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            val zipFile = File(dossierExports(context), "${slug}_$horodatage.zip")
            val pris = mutableSetOf<String>()

            ZipOutputStream(FileOutputStream(zipFile)).use { zip ->
                zip.putNextEntry(ZipEntry("recap.xlsx"))
                XlsxWriter().ecrire(feuilles(projet, depenses), zip)
                zip.closeEntry()

                for (depense in depenses.sortedWith(compareBy({ it.date }, { it.creeLe }))) {
                    val source = depense.fichier?.let { File(dossierJustificatifs(context), it) }
                        ?.takeIf { it.exists() } ?: continue
                    val base = nomJustificatif(slug, depense)
                    val nom = unique(base, pris)
                    zip.putNextEntry(ZipEntry(nom))
                    if (estPdf(source)) {
                        source.inputStream().use { it.copyTo(zip) }
                    } else {
                        val pdf = File(context.cacheDir, "tmp-${depense.id}.pdf")
                        imageVersPdf(source, pdf)
                        pdf.inputStream().use { it.copyTo(zip) }
                        pdf.delete()
                    }
                    zip.closeEntry()
                }
            }
            zipFile
        }

    private fun unique(nom: String, pris: MutableSet<String>): String {
        if (pris.add(nom)) return nom
        val base = nom.removeSuffix(".pdf")
        var n = 2
        while (true) {
            val candidat = "${base}_$n.pdf"
            if (pris.add(candidat)) return candidat
            n++
        }
    }

    fun estPdf(fichier: File): Boolean = runCatching {
        fichier.inputStream().use { flux ->
            val entete = ByteArray(4)
            flux.read(entete) == 4 && String(entete) == "%PDF"
        }
    }.getOrDefault(false)

    /** Wraps a receipt photo in an A4 PDF, keeping the whole image visible. */
    fun imageVersPdf(image: File, sortie: File) {
        val bitmap = BitmapFactory.decodeFile(image.absolutePath) ?: return
        val document = PdfDocument()
        try {
            val largeur = 595
            val hauteur = 842
            val marge = 24f
            val page = document.startPage(PdfDocument.PageInfo.Builder(largeur, hauteur, 1).create())
            val canvas = page.canvas
            canvas.drawColor(Color.WHITE)
            val dispoLargeur = largeur - 2 * marge
            val dispoHauteur = hauteur - 2 * marge
            val echelle = minOf(dispoLargeur / bitmap.width, dispoHauteur / bitmap.height, 3f)
            val dessineLargeur = bitmap.width * echelle
            val dessineHauteur = bitmap.height * echelle
            val gauche = (largeur - dessineLargeur) / 2f
            val haut = (hauteur - dessineHauteur) / 2f
            canvas.drawBitmap(
                bitmap,
                null,
                android.graphics.RectF(gauche, haut, gauche + dessineLargeur, haut + dessineHauteur),
                Paint(Paint.FILTER_BITMAP_FLAG),
            )
            document.finishPage(page)
            FileOutputStream(sortie).use { document.writeTo(it) }
        } finally {
            document.close()
            bitmap.recycle()
        }
    }

    /** The recap workbook: one detail sheet, one recap by expense type. */
    fun feuilles(projet: Projet, depenses: List<Depense>): List<XlsxWriter.Feuille> {
        val detail = XlsxWriter.Feuille("Dépenses")
        detail.largeurs.addAll(listOf(12, 18, 30, 12, 8, 10, 12, 8, 12, 12, 46))
        detail.ligne(
            entete("Date"), entete("Type"), entete("Libellé"), entete("Montant"), entete("Devise"),
            entete("Taux"), entete("Montant EUR"), entete("TVA %"), entete("HT EUR"), entete("TVA EUR"),
            entete("Justificatif"),
        )

        var totalEur = 0.0
        var totalTva = 0.0
        val parType = linkedMapOf<TypeDepense, Triple<Int, Double, Double>>()

        for (d in depenses.sortedWith(compareBy({ it.date }, { it.creeLe }))) {
            val eur = d.montantEurCentimes?.let { it / 100.0 }
            if (eur != null) totalEur += eur
            val tva = d.tvaCentimes?.let { it / 100.0 }
            if (tva != null) totalTva += tva
            val ht = d.htCentimes?.let { it / 100.0 }
            val courant = parType[d.typeDepense] ?: Triple(0, 0.0, 0.0)
            parType[d.typeDepense] = Triple(courant.first + 1, courant.second + (eur ?: 0.0), courant.third + (tva ?: 0.0))

            detail.ligne(
                XlsxWriter.Cellule(texte = d.date),
                XlsxWriter.Cellule(texte = d.typeDepense.label),
                XlsxWriter.Cellule(texte = d.libelle),
                XlsxWriter.Cellule(nombre = d.montantCentimes / 100.0, style = XlsxWriter.MONTANT),
                XlsxWriter.Cellule(texte = d.devise),
                d.tauxChange?.let { XlsxWriter.Cellule(nombre = it, style = XlsxWriter.MONTANT) } ?: XlsxWriter.Cellule(),
                eur?.let { XlsxWriter.Cellule(nombre = it, style = XlsxWriter.MONTANT) } ?: XlsxWriter.Cellule(),
                d.tvaTauxBp?.let { XlsxWriter.Cellule(nombre = it / 100.0, style = XlsxWriter.MONTANT) } ?: XlsxWriter.Cellule(),
                ht?.let { XlsxWriter.Cellule(nombre = it, style = XlsxWriter.MONTANT) } ?: XlsxWriter.Cellule(),
                tva?.let { XlsxWriter.Cellule(nombre = it, style = XlsxWriter.MONTANT) } ?: XlsxWriter.Cellule(),
                XlsxWriter.Cellule(texte = d.fichier?.let { nomJustificatif(slugProjet(projet.nom), d) } ?: ""),
            )
        }

        detail.ligne()
        detail.ligne(
            entete("TOTAL"), XlsxWriter.Cellule(), XlsxWriter.Cellule(),
            XlsxWriter.Cellule(), XlsxWriter.Cellule(), XlsxWriter.Cellule(),
            XlsxWriter.Cellule(nombre = totalEur, style = XlsxWriter.GRAS_MONTANT),
            XlsxWriter.Cellule(),
            XlsxWriter.Cellule(),
            XlsxWriter.Cellule(nombre = totalTva, style = XlsxWriter.GRAS_MONTANT),
            XlsxWriter.Cellule(texte = "${depenses.size} dépense(s)"),
        )

        val synthese = XlsxWriter.Feuille("Synthèse")
        synthese.largeurs.addAll(listOf(20, 10, 14, 12))
        synthese.ligne(entete("Projet"), XlsxWriter.Cellule(texte = projet.nom))
        synthese.ligne(entete("Dépenses"), XlsxWriter.Cellule(nombre = depenses.size.toDouble()))
        synthese.ligne(entete("Total EUR"), XlsxWriter.Cellule(nombre = totalEur, style = XlsxWriter.GRAS_MONTANT))
        synthese.ligne(entete("Dont TVA"), XlsxWriter.Cellule(nombre = totalTva, style = XlsxWriter.MONTANT))
        synthese.ligne(entete("Édité le"), XlsxWriter.Cellule(texte = LocalDate.now().toString()))
        synthese.ligne(entete("Taux de change"), XlsxWriter.Cellule(texte = "Banque de France (parité du jour de la dépense)"))
        synthese.ligne()
        synthese.ligne(entete("Type"), entete("Nombre"), entete("Total EUR"), entete("Dont TVA"))
        for ((type, valeurs) in parType) {
            synthese.ligne(
                XlsxWriter.Cellule(texte = type.label),
                XlsxWriter.Cellule(nombre = valeurs.first.toDouble()),
                XlsxWriter.Cellule(nombre = valeurs.second, style = XlsxWriter.MONTANT),
                XlsxWriter.Cellule(nombre = valeurs.third, style = XlsxWriter.MONTANT),
            )
        }

        return listOf(detail, synthese)
    }

    private fun entete(texte: String) = XlsxWriter.Cellule(texte = texte, style = XlsxWriter.GRAS)
}
