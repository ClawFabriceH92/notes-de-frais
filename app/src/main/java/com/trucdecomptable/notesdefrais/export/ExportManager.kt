package com.trucdecomptable.notesdefrais.export

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import com.trucdecomptable.notesdefrais.data.Depense
import com.trucdecomptable.notesdefrais.data.Parametres
import com.trucdecomptable.notesdefrais.data.Profil
import com.trucdecomptable.notesdefrais.data.Projet
import com.trucdecomptable.notesdefrais.data.TypeDepense
import com.trucdecomptable.notesdefrais.util.Dates
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

    /**
     * Au-delà de 10 Mo, un ZIP ne passe plus en pièce jointe de mail courant :
     * l'app propose alors de le couper en deux.
     */
    const val SEUIL_DECOUPAGE = 10L * 1024 * 1024

    /**
     * Nombre de colonnes de la feuille « Dépenses ». La ligne de totaux se cale
     * par index dessus : ajouter une colonne ne décale plus les montants.
     */
    private const val COLONNES_DETAIL = 24

    /**
     * One line of the export: the expense, its running number in the archive
     * and the exact file names written. The workbook and the ZIP are built from
     * the same list, so a renamed or duplicated file can never drift apart.
     * [numero] is 0 when the expense has no attachment at all.
     */
    data class Justificatif(val depense: Depense, val numero: Int, val noms: List<String>)

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
     * Numbers the attachments in export order: 1, 2, 3… skipping expenses that
     * have no file at all, so the numbers match the ZIP one for one.
     * Chaque PDF finit par `_JustifN` : les pièces s'ordonnent comme le récap et
     * Fabrice retrouve le numéro de la ligne Excel sur le fichier.
     * [existe] drops attachments whose file is no longer on the phone.
     */
    fun planJustificatifs(
        slug: String,
        depenses: List<Depense>,
        existe: (String) -> Boolean = { true },
    ): List<Justificatif> {
        val pris = mutableSetOf<String>()
        var numero = 0
        return depenses.sortedWith(compareBy({ it.date }, { it.creeLe })).map { depense ->
            val presents = depense.tousLesFichiers.filter(existe)
            if (presents.isEmpty()) return@map Justificatif(depense, 0, emptyList())
            val base = nomJustificatif(slug, depense).removeSuffix(".pdf")
            val numeroJustif = numero + 1
            // Toutes les pages d'un même justificatif (ticket + facture, ou les
            // deux feuilles d'une facture photographiées l'une après l'autre)
            // tiennent dans UN seul PDF : un justificatif = un fichier, que la
            // comptabilité n'a pas à recoller.
            val noms = listOf(unique("$base" + "_Justif$numeroJustif.pdf", pris))
            Justificatif(depense, ++numero, noms)
        }
    }

    /**
     * Le nom du fichier inscrit en haut de la page du PDF. Il est déjà long
     * (projet, date, type, montant, numéro) : on retire l'extension, et on
     * coupe avec « … » plutôt que de laisser le texte sortir de la page.
     */
    fun enteteJustificatif(nomFichier: String, maxCaracteres: Int = 90): String {
        val sansExtension = nomFichier.removeSuffix(".pdf").trim()
        if (sansExtension.length <= maxCaracteres) return sansExtension
        return sansExtension.take(maxCaracteres - 1).trimEnd() + "…"
    }

    /**
     * Zips the project: recap.xlsx plus one PDF per expense. Returned file lives
     * in filesDir/exports so the FileProvider can hand it to any app.
     */
    suspend fun exporter(
        context: Context,
        projet: Projet,
        depenses: List<Depense>,
        parametres: Parametres = Parametres(),
        profil: Profil? = null,
    ): File =
        withContext(Dispatchers.IO) {
            val slug = slugProjet(projet.nom)
            val societe = profil?.nom?.takeIf { it.isNotBlank() } ?: parametres.societe
            val prefixe = societe.takeIf { it.isNotBlank() }?.let { slugProjet(it) + "_" }.orEmpty()
            val horodatage = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            val zipFile = File(dossierExports(context), "$prefixe${slug}_$horodatage.zip")
            val dossierPieces = dossierJustificatifs(context)
            val plan = planJustificatifs(slug, depenses) { nom -> File(dossierPieces, nom).exists() }
            ecrireZip(context, zipFile, feuilles(projet, depenses, parametres, plan, profil), plan)
            zipFile
        }

    /**
     * Les justificatifs seuls, en PDF, prêts à partir vers l'adresse du profil :
     * ni ZIP, ni classeur Excel. Une photo est convertie en PDF avec son nom de
     * fichier en haut de page, un PDF est copié tel quel. Les fichiers portent le
     * même nom que dans le ZIP (`…_JustifN`) pour rester alignés sur le récap.
     */
    suspend fun justificatifsPdf(
        context: Context,
        projet: Projet,
        depenses: List<Depense>,
        parametres: Parametres = Parametres(),
        profil: Profil? = null,
    ): List<File> =
        withContext(Dispatchers.IO) {
            val slug = slugProjet(projet.nom)
            val societe = profil?.nom?.takeIf { it.isNotBlank() } ?: parametres.societe
            val prefixe = societe.takeIf { it.isNotBlank() }?.let { slugProjet(it) + "_" }.orEmpty()
            val horodatage = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            val dossier = File(dossierExports(context), nomDossierPdf(prefixe, slug, horodatage)).apply { mkdirs() }
            purgerDossiersPdf(context, sauf = dossier)

            val dossierPieces = dossierJustificatifs(context)
            val plan = planJustificatifs(slug, depenses) { nom -> File(dossierPieces, nom).exists() }
            for (justificatif in plan.filter { it.noms.isNotEmpty() }) {
                val sources = justificatif.depense.tousLesFichiers
                    .map { File(dossierPieces, it) }
                    .filter { it.exists() }
                // Un justificatif = un PDF, ses pages à la suite.
                val nom = justificatif.noms.first()
                pagesVersPdf(sources, File(dossier, nom), enteteJustificatif(nom))
            }
            dossier.listFiles().orEmpty()
                .filter { it.isFile && it.length() > 0L }
                .sortedBy { it.name }
        }

    /**
     * Nom du dossier d'envoi PDF : même base que le ZIP (société, projet,
     * horodatage) avec le préfixe `pdf_`. Pur, donc vérifiable en test.
     */
    fun nomDossierPdf(prefixe: String, slug: String, horodatage: String): String =
        "pdf_${prefixe}${slug}_$horodatage"

    /**
     * Les dossiers d'envoi PDF d'hier ne servent plus : on les efface pour ne pas
     * garder des dizaines de Mo dans filesDir. Le dossier courant est épargné,
     * et on laisse deux jours de marge au cas où un mail serait encore en cours.
     */
    private fun purgerDossiersPdf(context: Context, sauf: File) {
        val limite = System.currentTimeMillis() - 2L * 24 * 3600 * 1000
        dossierExports(context).listFiles().orEmpty()
            .filter { it.isDirectory && it.name.startsWith("pdf_") && it != sauf }
            .filter { it.lastModified() < limite }
            .forEach { dossier -> dossier.listFiles().orEmpty().forEach { it.delete() }; dossier.delete() }
    }

    /**
     * Découpe l'export en autant d'archives que nécessaire pour qu'aucune ne
     * dépasse [SEUIL_DECOUPAGE] : le récap part dans la première, les
     * justificatifs sont répartis par taille. Des ZIP utilisables tels quels,
     * plutôt qu'une archive binaire à recoller sur un téléphone.
     */
    suspend fun decouper(
        context: Context,
        projet: Projet,
        depenses: List<Depense>,
        parametres: Parametres = Parametres(),
        profil: Profil? = null,
    ): List<File> =
        withContext(Dispatchers.IO) {
            val slug = slugProjet(projet.nom)
            val societe = profil?.nom?.takeIf { it.isNotBlank() } ?: parametres.societe
            val prefixe = societe.takeIf { it.isNotBlank() }?.let { slugProjet(it) + "_" }.orEmpty()
            val horodatage = java.time.LocalDateTime.now()
                .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
            val dossierPieces = dossierJustificatifs(context)
            val plan = planJustificatifs(slug, depenses) { nom -> File(dossierPieces, nom).exists() }
            val feuilles = feuilles(projet, depenses, parametres, plan, profil)

            val avecFichiers = plan.filter { it.noms.isNotEmpty() }
            val poids = avecFichiers.associateWith { justificatif ->
                justificatif.depense.tousLesFichiers
                    .map { File(dossierPieces, it) }
                    .filter { it.exists() }
                    .sumOf { it.length() }
            }
            val tas = repartirEnTas(avecFichiers, poids, SEUIL_DECOUPAGE)

            val base = "$prefixe${slug}_$horodatage"
            tas.mapIndexed { index, contenu ->
                val partie = File(dossierExports(context), "${base}_partie${index + 1}.zip")
                // Le récap n'est mis qu'une fois : il liste tout le projet.
                ecrireZip(context, partie, if (index == 0) feuilles else emptyList(), contenu)
                partie
            }
        }

    /**
     * Répartit les justificatifs en autant de tas qu'il faut pour qu'aucun ne
     * dépasse [seuil], le premier recevant le récap : les plus grosses pièces
     * sont placées d'abord, toujours dans le tas le plus léger qui les accepte,
     * puis l'ordre chronologique de l'export est rétabli dans chaque partie
     * (l'ordre des PDF doit rester lisible). Une pièce plus lourde que le seuil
     * part seule dans son tas, sinon elle ne partirait jamais.
     */
    fun repartirEnTas(
        justificatifs: List<Justificatif>,
        poids: Map<Justificatif, Long>,
        seuil: Long = SEUIL_DECOUPAGE,
    ): List<List<Justificatif>> {
        if (justificatifs.isEmpty()) return emptyList()
        val rang = justificatifs.withIndex().associate { (index, justificatif) -> justificatif to index }
        val tas = mutableListOf<MutableList<Justificatif>>()
        val charges = mutableListOf<Long>()
        justificatifs.sortedByDescending { poids[it] ?: 0L }.forEach { justificatif ->
            val taille = poids[justificatif] ?: 0L
            val cible = charges.indices
                .filter { charges[it] + taille <= seuil }
                .minByOrNull { charges[it] }
            if (cible == null) {
                tas.add(mutableListOf(justificatif))
                charges.add(taille)
            } else {
                tas[cible].add(justificatif)
                charges[cible] += taille
            }
        }
        // Le tas qui commence par la plus ancienne dépense devient la partie 1.
        return tas
            .map { contenu -> contenu.sortedBy { rang[it] } }
            .sortedBy { partie -> rang[partie.first()] ?: Int.MAX_VALUE }
    }

    /**
     * Regroupe des fichiers en envois dont chacun reste sous [seuil]. Un mail
     * alourdi par l'encodage des pièces jointes (base64, +33 %) atteint la
     * limite du serveur d'envoi bien avant le poids réel des fichiers : on
     * découpe donc les envois, quitte à faire plusieurs mails.
     */
    fun repartirEnEnvois(fichiers: List<File>, seuil: Long = SEUIL_DECOUPAGE): List<List<File>> {
        val envois = mutableListOf<List<File>>()
        var courant = mutableListOf<File>()
        var charge = 0L
        for (fichier in fichiers) {
            val taille = fichier.length()
            if (courant.isNotEmpty() && charge + taille > seuil) {
                envois.add(courant)
                courant = mutableListOf()
                charge = 0L
            }
            courant.add(fichier)
            charge += taille
        }
        if (courant.isNotEmpty()) envois.add(courant)
        return envois
    }

    /** Écrit une archive : le récap (si fourni) puis les justificatifs. */
    private fun ecrireZip(
        context: Context,
        sortie: File,
        feuilles: List<XlsxWriter.Feuille>,
        plan: List<Justificatif>,
    ) {
        val dossierPieces = dossierJustificatifs(context)
        ZipOutputStream(FileOutputStream(sortie)).use { zip ->
            if (feuilles.isNotEmpty()) {
                zip.putNextEntry(ZipEntry("recap.xlsx"))
                // Le classeur est lui-même un ZIP : son flux est blindé pour
                // qu'il ne referme pas le ZIP de l'export (« Stream closed »).
                val versClasseur = XlsxWriter.bouclier(zip)
                XlsxWriter().ecrire(feuilles, versClasseur)
                versClasseur.flush()
                zip.closeEntry()
            }

            for (justificatif in plan.filter { it.noms.isNotEmpty() }) {
                val sources = justificatif.depense.tousLesFichiers
                    .map { File(dossierPieces, it) }
                    .filter { it.exists() }
                val nom = justificatif.noms.first()
                zip.putNextEntry(ZipEntry(nom))
                if (sources.size == 1 && estPdf(sources.first())) {
                    sources.first().inputStream().use { it.copyTo(zip) }
                } else {
                    val pdf = File(context.cacheDir, "tmp-justif-${justificatif.depense.id}.pdf")
                    pagesVersPdf(sources, pdf, enteteJustificatif(nom))
                    pdf.inputStream().use { it.copyTo(zip) }
                    pdf.delete()
                }
                zip.closeEntry()
            }
        }
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

    /** Format A4 des justificatifs, en points (72 dpi). */
    private const val LARGEUR_A4 = 595
    private const val HAUTEUR_A4 = 842
    private const val MARGE_A4 = 24f

    /**
     * Écrit un justificatif en PDF, **toutes ses pages dans le même fichier**.
     *
     * Un justificatif tient parfois en plusieurs prises de vue : une facture
     * imprimée sur deux feuilles, un ticket plus sa facture, un devis qui suit
     * la note d'hôtel. Les réunir dans un seul PDF évite à la comptabilité de
     * recoller les morceaux et de se demander si la page 2 appartient bien à la
     * même dépense.
     *
     * Un PDF déjà fourni est recopié tel quel quand il est seul (aucune perte,
     * texte conservé) ; sinon ses pages sont rendues en images pour entrer dans
     * le document commun, faute de pouvoir fusionner deux PDF sans bibliothèque
     * supplémentaire.
     */
    fun pagesVersPdf(sources: List<File>, sortie: File, entete: String = "") {
        val pages = sources.filter { it.exists() && it.length() > 0L }
        if (pages.isEmpty()) return
        if (pages.size == 1 && estPdf(pages[0])) {
            pages[0].copyTo(sortie, overwrite = true)
            return
        }
        val document = PdfDocument()
        var numero = 0
        try {
            for (source in pages) {
                if (estPdf(source)) {
                    rendrePdf(source) { bitmap ->
                        numero++
                        ajouterPageImage(document, bitmap, entete, numero)
                    }
                } else {
                    val bitmap = BitmapFactory.decodeFile(source.absolutePath) ?: continue
                    try {
                        numero++
                        ajouterPageImage(document, bitmap, entete, numero)
                    } finally {
                        bitmap.recycle()
                    }
                }
            }
            if (numero > 0) FileOutputStream(sortie).use { document.writeTo(it) }
        } finally {
            document.close()
        }
    }

    /** Wraps a receipt photo in a single-page A4 PDF, whole image visible. */
    fun imageVersPdf(image: File, sortie: File, entete: String = "") {
        pagesVersPdf(listOf(image), sortie, entete)
    }

    /**
     * Rend chaque page d'un PDF source en image et la passe à [surPage] : c'est
     * le seul moyen d'insérer un PDF existant dans un document PdfDocument.
     */
    private fun rendrePdf(fichier: File, surPage: (android.graphics.Bitmap) -> Unit) {
        val descripteur = runCatching {
            android.os.ParcelFileDescriptor.open(
                fichier,
                android.os.ParcelFileDescriptor.MODE_READ_ONLY,
            )
        }.getOrNull() ?: return
        try {
            android.graphics.pdf.PdfRenderer(descripteur).use { moteur ->
                for (index in 0 until moteur.pageCount) {
                    moteur.openPage(index).use { page ->
                        val echelle = (1600f / page.width.toFloat()).coerceIn(0.5f, 3f)
                        val bitmap = android.graphics.Bitmap.createBitmap(
                            (page.width * echelle).toInt().coerceAtLeast(1),
                            (page.height * echelle).toInt().coerceAtLeast(1),
                            android.graphics.Bitmap.Config.ARGB_8888,
                        )
                        // PdfRenderer laisse les zones non peintes transparentes.
                        android.graphics.Canvas(bitmap).drawColor(Color.WHITE)
                        page.render(
                            bitmap,
                            null,
                            null,
                            android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY,
                        )
                        try {
                            surPage(bitmap)
                        } finally {
                            bitmap.recycle()
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Une page illisible ne doit pas faire perdre les autres.
        } finally {
            runCatching { descripteur.close() }
        }
    }

    /** Ajoute une image en page A4 du justificatif, en-tête en haut de page. */
    private fun ajouterPageImage(
        document: PdfDocument,
        bitmap: android.graphics.Bitmap,
        entete: String,
        numero: Int,
    ) {
        val page = document.startPage(
            PdfDocument.PageInfo.Builder(LARGEUR_A4, HAUTEUR_A4, numero).create(),
        )
        val canvas = page.canvas
        canvas.drawColor(Color.WHITE)

        // Le nom du fichier en haut de page : une fois la pièce imprimée ou
        // sortie du ZIP, elle reste identifiable sans son nom de fichier. Les
        // pages suivantes portent le même nom avec leur rang, pour qu'une page
        // égarée se rattache à son justificatif.
        val titre = when {
            entete.isBlank() -> ""
            numero <= 1 -> entete
            else -> "$entete (page $numero)"
        }
        var hautUtile = MARGE_A4
        if (titre.isNotBlank()) {
            val encre = Paint(Paint.ANTI_ALIAS_FLAG)
            encre.color = Color.DKGRAY
            encre.textSize = 9f
            canvas.drawText(titre, MARGE_A4, MARGE_A4 + 9f, encre)
            // Filet de séparation, pour que l'en-tête ne se confonde pas avec le
            // haut du ticket photographié.
            val trait = Paint().apply { color = Color.LTGRAY; strokeWidth = 0.5f }
            canvas.drawLine(MARGE_A4, MARGE_A4 + 14f, LARGEUR_A4 - MARGE_A4, MARGE_A4 + 14f, trait)
            hautUtile = MARGE_A4 + 22f
        }

        val dispoLargeur = LARGEUR_A4 - 2 * MARGE_A4
        val dispoHauteur = HAUTEUR_A4 - hautUtile - MARGE_A4
        val echelle = minOf(dispoLargeur / bitmap.width, dispoHauteur / bitmap.height, 3f)
        val dessineLargeur = bitmap.width * echelle
        val dessineHauteur = bitmap.height * echelle
        val gauche = (LARGEUR_A4 - dessineLargeur) / 2f
        val haut = hautUtile + (dispoHauteur - dessineHauteur) / 2f
        canvas.drawBitmap(
            bitmap,
            null,
            android.graphics.RectF(gauche, haut, gauche + dessineLargeur, haut + dessineHauteur),
            Paint(Paint.FILTER_BITMAP_FLAG),
        )
        document.finishPage(page)
    }

    /** The recap workbook: one detail sheet, one recap by expense type. */
    fun feuilles(
        projet: Projet,
        depenses: List<Depense>,
        parametres: Parametres = Parametres(),
        plan: List<Justificatif> = planJustificatifs(slugProjet(projet.nom), depenses),
        profil: Profil? = null,
    ): List<XlsxWriter.Feuille> {
        val detail = XlsxWriter.Feuille("Dépenses")
        detail.largeurs.addAll(
            listOf(6, 12, 18, 26, 16, 18, 24, 16, 24, 24, 12, 18, 20, 22, 12, 8, 10, 12, 8, 12, 12, 10, 12, 46),
        )
        detail.ligne(
            entete("N°"), entete("Date"), entete("Type"), entete("Libellé"), entete("N° facture"),
            entete("Paiement"),
            entete("Organisme"),
            entete("Sens"), entete("Gare départ"), entete("Gare arrivée"), entete("Date retour"),
            entete("Invités"), entete("Société invités"), entete("Motif"),
            entete("Montant"), entete("Devise"),
            entete("Taux"), entete("Montant EUR"), entete("TVA %"), entete("HT EUR"), entete("TVA EUR"),
            entete("Distance km"), entete("Refacturable"),
            entete("Justificatif"),
        )

        var totalEur = 0.0
        var totalTva = 0.0
        var totalKm = 0
        var totalRefacturable = 0.0
        var nbJustificatifs = 0
        val parType = linkedMapOf<TypeDepense, Triple<Int, Double, Double>>()
        val parPaiement = linkedMapOf<String, Pair<Int, Double>>()

        for (justificatif in plan) {
            val d = justificatif.depense
            val eur = d.montantEurCentimes?.let { it / 100.0 }
            if (eur != null) totalEur += eur
            if (eur != null && d.refacturable) totalRefacturable += eur
            totalKm += d.distanceKm ?: 0
            if (justificatif.noms.isNotEmpty()) nbJustificatifs++
            val tva = d.tvaCentimes?.let { it / 100.0 }
            if (tva != null) totalTva += tva
            val ht = d.htCentimes?.let { it / 100.0 }
            val courant = parType[d.typeDepense] ?: Triple(0, 0.0, 0.0)
            parType[d.typeDepense] = Triple(courant.first + 1, courant.second + (eur ?: 0.0), courant.third + (tva ?: 0.0))
            val cle = d.moyenPaiement.ifBlank { "non renseigné" }
            val cumul = parPaiement[cle] ?: (0 to 0.0)
            parPaiement[cle] = (cumul.first + 1) to (cumul.second + (eur ?: 0.0))

            detail.ligne(
                if (justificatif.numero > 0) {
                    XlsxWriter.Cellule(nombre = justificatif.numero.toDouble())
                } else {
                    XlsxWriter.Cellule()
                },
                XlsxWriter.Cellule(texte = Dates.affichage(d.date)),
                XlsxWriter.Cellule(texte = d.typeDepense.label),
                XlsxWriter.Cellule(texte = d.libelle),
                // Le numéro de facture, à côté du libellé : c'est ce que la
                // comptabilité rapproche du justificatif, et le repère doublon.
                XlsxWriter.Cellule(texte = d.numFacture),
                XlsxWriter.Cellule(texte = d.moyenPaiement),
                XlsxWriter.Cellule(texte = d.organisme),
                XlsxWriter.Cellule(texte = d.sens?.label.orEmpty()),
                // Train et avion : les gares et, pour un aller-retour, la date du
                // retour. Vides pour les autres types de dépense.
                XlsxWriter.Cellule(texte = d.gareDepart),
                XlsxWriter.Cellule(texte = d.gareArrivee),
                XlsxWriter.Cellule(texte = Dates.affichage(d.dateRetour).takeIf { d.dateRetour.isNotBlank() }.orEmpty()),
                XlsxWriter.Cellule(texte = d.participants),
                XlsxWriter.Cellule(texte = d.societeParticipant),
                XlsxWriter.Cellule(texte = d.motifRepas),
                XlsxWriter.Cellule(nombre = d.montantCentimes / 100.0, style = XlsxWriter.MONTANT),
                XlsxWriter.Cellule(texte = d.devise),
                d.tauxChange?.let { XlsxWriter.Cellule(nombre = it, style = XlsxWriter.TAUX) } ?: XlsxWriter.Cellule(),
                eur?.let { XlsxWriter.Cellule(nombre = it, style = XlsxWriter.MONTANT) } ?: XlsxWriter.Cellule(),
                d.tvaTauxBp?.let { XlsxWriter.Cellule(nombre = it / 100.0, style = XlsxWriter.MONTANT) } ?: XlsxWriter.Cellule(),
                ht?.let { XlsxWriter.Cellule(nombre = it, style = XlsxWriter.MONTANT) } ?: XlsxWriter.Cellule(),
                tva?.let { XlsxWriter.Cellule(nombre = it, style = XlsxWriter.MONTANT) } ?: XlsxWriter.Cellule(),
                d.distanceKm?.let { XlsxWriter.Cellule(nombre = it.toDouble()) } ?: XlsxWriter.Cellule(),
                XlsxWriter.Cellule(texte = if (d.refacturable) "Oui" else ""),
                // The names exactly as they appear in the archive, annexes included.
                XlsxWriter.Cellule(texte = justificatif.noms.joinToString(" ; ")),
            )
        }

        detail.ligne()
        // Colonne par colonne plutôt qu'une liste à la main : ajouter une
        // colonne au détail ne décale plus les totaux.
        val total = MutableList(COLONNES_DETAIL) { XlsxWriter.Cellule() }
        total[0] = entete("TOTAL")
        total[17] = XlsxWriter.Cellule(nombre = totalEur, style = XlsxWriter.GRAS_MONTANT)
        total[20] = XlsxWriter.Cellule(nombre = totalTva, style = XlsxWriter.GRAS_MONTANT)
        total[21] = XlsxWriter.Cellule(nombre = totalKm.toDouble())
        total[23] = XlsxWriter.Cellule(texte = "$nbJustificatifs justificatif(s) pour ${plan.size} dépense(s)")
        detail.ligne(total)

        val synthese = XlsxWriter.Feuille("Synthèse")
        synthese.largeurs.addAll(listOf(24, 10, 14, 12))
        synthese.ligne(entete("Projet"), XlsxWriter.Cellule(texte = projet.nom))
        val societe = profil?.nom?.takeIf { it.isNotBlank() } ?: parametres.societe
        if (societe.isNotBlank()) {
            synthese.ligne(entete("Société"), XlsxWriter.Cellule(texte = societe))
        }
        val siret = profil?.siret?.takeIf { it.isNotBlank() } ?: parametres.siret
        if (siret.isNotBlank()) {
            synthese.ligne(entete("SIRET"), XlsxWriter.Cellule(texte = siret))
        }
        val adresse = profil?.adresse?.takeIf { it.isNotBlank() } ?: parametres.adresse
        if (adresse.isNotBlank()) {
            synthese.ligne(entete("Adresse"), XlsxWriter.Cellule(texte = adresse))
        }
        if (parametres.identite.isNotBlank()) {
            synthese.ligne(entete("Titulaire"), XlsxWriter.Cellule(texte = parametres.identite))
        }
        synthese.ligne(entete("Dépenses"), XlsxWriter.Cellule(nombre = plan.size.toDouble()))
        synthese.ligne(entete("Justificatifs"), XlsxWriter.Cellule(nombre = nbJustificatifs.toDouble()))
        // Combien de dépenses partent sans aucune pièce jointe : c'est ce que
        // la comptabilité va réclamer, autant le voir écrit noir sur blanc.
        synthese.ligne(entete("Dont sans justificatif"), XlsxWriter.Cellule(nombre = (plan.size - nbJustificatifs).toDouble()))
        synthese.ligne(entete("Total EUR"), XlsxWriter.Cellule(nombre = totalEur, style = XlsxWriter.GRAS_MONTANT))
        synthese.ligne(entete("Dont TVA"), XlsxWriter.Cellule(nombre = totalTva, style = XlsxWriter.MONTANT))
        synthese.ligne(entete("Édité le"), XlsxWriter.Cellule(texte = LocalDate.now().toString()))
        if (totalRefacturable > 0.0) {
            synthese.ligne(entete("Dont refacturable"), XlsxWriter.Cellule(nombre = totalRefacturable, style = XlsxWriter.MONTANT))
        }
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

        // Which card was charged: the statement total by card.
        synthese.ligne()
        synthese.ligne(entete("Paiement"), entete("Nombre"), entete("Total EUR"))
        for ((moyen, valeurs) in parPaiement) {
            synthese.ligne(
                XlsxWriter.Cellule(texte = moyen),
                XlsxWriter.Cellule(nombre = valeurs.first.toDouble()),
                XlsxWriter.Cellule(nombre = valeurs.second, style = XlsxWriter.MONTANT),
            )
        }

        return listOf(detail, synthese)
    }

    private fun entete(texte: String) = XlsxWriter.Cellule(texte = texte, style = XlsxWriter.GRAS)
}
