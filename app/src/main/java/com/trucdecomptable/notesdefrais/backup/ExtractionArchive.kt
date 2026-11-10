package com.trucdecomptable.notesdefrais.backup

import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.zip.ZipInputStream

/**
 * Lecture d'une archive en flux, entrée par entrée, sans jamais la charger en
 * mémoire : seul un tampon de 64 Ko est alloué, quelle que soit la taille des
 * pièces. Aucune dépendance Android, donc testable sur la JVM.
 */
object ExtractionArchive {

    private const val TAMPON = 64 * 1024

    /** Ce que le parcours a vu : le strict nécessaire pour décider de continuer. */
    data class Apercu(val meta: MetaArchive?, val baseVue: Boolean)

    /** Ce qui a été écrit dans le dossier de destination. */
    data class Extrait(
        val meta: MetaArchive?,
        val baseVue: Boolean,
        val justificatifs: Int,
        val octets: Long,
        val base: File?,
        val parametres: File?,
    )

    /** Noms fixes dans le dossier de destination. */
    const val SOUS_DOSSIER_BASE = "db"
    const val SOUS_DOSSIER_JUSTIFICATIFS = "justificatifs"

    /**
     * Parcourt l'archive pour lire meta.json et savoir si la base y figure,
     * en s'arrêtant dès que les deux sont connus. Les archives écrites par
     * cette version placent meta.json puis la base en tête : seuls deux
     * en-têtes sont lus. Les anciennes, où meta.json est en queue, sont
     * parcourues en entier (toujours sans rien garder en mémoire).
     */
    fun apercu(flux: InputStream, annule: () -> Unit = {}): Apercu {
        var meta: MetaArchive? = null
        var metaVue = false
        var baseVue = false
        ZipInputStream(flux.buffered(TAMPON)).use { zip ->
            while (true) {
                annule()
                val entree = zip.nextEntry ?: break
                when (val cible = ReglesRestauration.classer(entree.name, entree.isDirectory)) {
                    is CibleEntree.Suspecte -> throw RestaurationRefusee(
                        "Archive refusée : l'entrée « ${cible.nom} » sort du dossier de l'application.",
                    )
                    is CibleEntree.Base -> if (cible.suffixe.isEmpty()) baseVue = true
                    is CibleEntree.Meta -> {
                        meta = ReglesRestauration.lireMeta(
                            lireTexteBorne(zip, ReglesRestauration.TAILLE_MAX_META).orEmpty(),
                        )
                        metaVue = true
                    }
                    else -> Unit
                }
                if (metaVue && baseVue) break
            }
        }
        return Apercu(meta, baseVue)
    }

    /**
     * Écrit l'archive dans [destination] (dossier vide créé par l'appelant) :
     * db/<base> et db/<base>-wal, justificatifs/<nom>, parametres.json,
     * meta.json. [budget] borne le total écrit (garde contre une archive qui
     * se décompresse sans fin et contre le disque plein). Une entrée aux
     * chemins suspects fait refuser toute l'archive.
     */
    fun extraire(
        flux: InputStream,
        destination: File,
        budget: Long,
        annule: () -> Unit = {},
    ): Extrait {
        val dossierBase = File(destination, SOUS_DOSSIER_BASE).apply { mkdirs() }
        val dossierPieces = File(destination, SOUS_DOSSIER_JUSTIFICATIFS).apply { mkdirs() }
        var meta: MetaArchive? = null
        var baseVue = false
        var parametres: File? = null
        var pieces = 0
        var total = 0L
        val tampon = ByteArray(TAMPON)

        fun copier(zip: ZipInputStream, cible: File, plafond: Long) {
            cible.outputStream().buffered(TAMPON).use { sortie ->
                total += copierBorne(zip, sortie, tampon, plafond, budget - total, annule)
            }
        }

        ZipInputStream(flux.buffered(TAMPON)).use { zip ->
            while (true) {
                annule()
                val entree = zip.nextEntry ?: break
                when (val cible = ReglesRestauration.classer(entree.name, entree.isDirectory)) {
                    is CibleEntree.Suspecte -> throw RestaurationRefusee(
                        "Archive refusée : l'entrée « ${cible.nom} » sort du dossier de l'application.",
                    )
                    is CibleEntree.Base -> {
                        copier(zip, File(dossierBase, ReglesRestauration.NOM_DB + cible.suffixe), Long.MAX_VALUE)
                        if (cible.suffixe.isEmpty()) baseVue = true
                    }
                    is CibleEntree.Justificatif -> {
                        copier(zip, File(dossierPieces, cible.nom), Long.MAX_VALUE)
                        pieces++
                    }
                    is CibleEntree.Parametres -> {
                        val fichier = File(destination, ReglesRestauration.ENTREE_PARAMETRES)
                        copier(zip, fichier, ReglesRestauration.TAILLE_MAX_PARAMETRES)
                        parametres = fichier
                    }
                    is CibleEntree.Meta -> {
                        val fichier = File(destination, ReglesRestauration.ENTREE_META)
                        copier(zip, fichier, ReglesRestauration.TAILLE_MAX_META)
                        meta = ReglesRestauration.lireMeta(fichier.readText(Charsets.UTF_8))
                    }
                    is CibleEntree.Ignoree -> Unit
                }
            }
        }
        val base = File(dossierBase, ReglesRestauration.NOM_DB).takeIf { baseVue && it.exists() }
        return Extrait(meta, baseVue, pieces, total, base, parametres)
    }

    /** Lit une petite entrée en texte, avec un plafond. Null si elle le dépasse. */
    private fun lireTexteBorne(zip: ZipInputStream, plafond: Long): String? {
        val sortie = java.io.ByteArrayOutputStream()
        val tampon = ByteArray(8 * 1024)
        var lu = 0L
        while (true) {
            val n = zip.read(tampon)
            if (n < 0) break
            lu += n
            if (lu > plafond) return null
            sortie.write(tampon, 0, n)
        }
        return sortie.toString(Charsets.UTF_8.name())
    }

    /**
     * Copie en flux avec deux garde-fous : [plafondEntree] (entrée trop grosse
     * pour ce qu'elle est censée être) et [budgetRestant] (espace disponible).
     */
    private fun copierBorne(
        entree: InputStream,
        sortie: OutputStream,
        tampon: ByteArray,
        plafondEntree: Long,
        budgetRestant: Long,
        annule: () -> Unit,
    ): Long {
        var copie = 0L
        while (true) {
            val n = entree.read(tampon)
            if (n < 0) break
            copie += n
            if (copie > plafondEntree) {
                throw RestaurationRefusee("Archive refusée : une entrée de réglages est anormalement volumineuse.")
            }
            if (copie > budgetRestant) {
                throw RestaurationRefusee(
                    "Espace insuffisant pendant la lecture de l'archive. Rien n'a été modifié : libérez de la place puis recommencez.",
                )
            }
            sortie.write(tampon, 0, n)
            annule()
        }
        return copie
    }

    /** Les 100 premiers octets d'un fichier (ou moins s'il est plus court). */
    fun lireEntete(fichier: File, taille: Int = 100): ByteArray {
        fichier.inputStream().use { flux ->
            val tampon = ByteArray(taille)
            var lu = 0
            while (lu < taille) {
                val n = flux.read(tampon, lu, taille - lu)
                if (n < 0) break
                lu += n
            }
            return tampon.copyOf(lu)
        }
    }

    /** Taille cumulée d'un fichier ou d'un dossier (0 s'il n'existe pas). */
    fun taille(fichier: File?): Long {
        if (fichier == null || !fichier.exists()) return 0
        if (fichier.isFile) return fichier.length()
        return fichier.walkTopDown().filter { it.isFile }.sumOf { it.length() }
    }
}
