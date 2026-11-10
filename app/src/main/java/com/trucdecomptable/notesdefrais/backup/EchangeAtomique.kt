package com.trucdecomptable.notesdefrais.backup

import java.io.File
import java.io.IOException

/**
 * Bascule par renommages, avec retour arrière complet.
 *
 * Principe : tout ce qui doit remplacer l'état courant est déjà écrit et
 * validé dans un dossier temporaire. Pour chaque emplacement, l'ancien contenu
 * est d'abord rangé de côté (renommage), puis le nouveau est mis à sa place
 * (renommage). Rien n'est supprimé pendant l'échange. Si un renommage échoue,
 * les précédents sont défaits en ordre inverse : l'état d'origine revient tel
 * quel, justificatifs compris. L'ancien contenu n'est détruit que par
 * [terminer], une fois tout réussi.
 *
 * Un journal est tenu dans le dossier temporaire : si le processus meurt en
 * plein milieu, [recuperer] défait ce qui avait été fait au prochain démarrage.
 *
 * Aucune dépendance Android : testable sur la JVM, y compris l'échec simulé
 * d'un renommage.
 */
class EchangeAtomique(
    private val racine: File,
    private val renommer: (File, File) -> Boolean = { source, cible -> source.renameTo(cible) },
) {

    /** Un emplacement vivant [vivant] remplacé par [nouveau] (null : simplement retiré). */
    data class Remplacement(val vivant: File, val nouveau: File?)

    private data class Deplacement(val source: File, val destination: File)

    private val faits = ArrayList<Deplacement>()
    private val dossierAncien = File(racine, ANCIEN)
    private val journal = File(racine, JOURNAL)

    /**
     * Effectue l'échange. En cas d'exception, tout a déjà été défait quand elle
     * remonte (voir [annuler] pour les rares renommages de retour qui échoueraient).
     */
    fun echanger(remplacements: List<Remplacement>) {
        dossierAncien.mkdirs()
        try {
            remplacements.forEachIndexed { rang, r ->
                if (r.vivant.exists()) {
                    deplacer(r.vivant, File(dossierAncien, "$rang-${r.vivant.name}"))
                }
                if (r.nouveau != null) deplacer(r.nouveau, r.vivant)
            }
        } catch (e: Throwable) {
            val echecs = annuler()
            if (echecs.isNotEmpty()) {
                throw IOException(
                    "Échange annulé, mais ${echecs.size} fichier(s) n'ont pas pu être remis en place " +
                        "(ils restent dans ${dossierAncien.path}) : ${e.message}",
                    e,
                )
            }
            throw e
        }
    }

    /** Marque l'échange comme abouti : plus aucun retour arrière au prochain démarrage. */
    fun valider() {
        journal.appendText("$FIN\n")
    }

    /**
     * Défait les renommages faits, en ordre inverse. Retourne les chemins qui
     * n'ont pas pu être remis en place (liste vide : retour complet).
     */
    fun annuler(): List<String> {
        val echecs = ArrayList<String>()
        for (d in faits.asReversed()) {
            if (d.destination.exists() && !d.source.exists()) {
                if (!d.destination.renameTo(d.source)) echecs += d.destination.path
            }
        }
        faits.clear()
        return echecs
    }

    private fun deplacer(source: File, destination: File) {
        destination.parentFile?.mkdirs()
        // Le journal est écrit AVANT le renommage : un crash entre les deux est
        // sans effet (la récupération ne défait que ce qui existe réellement).
        journal.appendText("$ETAPE\t${source.path}\t${destination.path}\n")
        if (!renommer(source, destination)) {
            throw IOException("Renommage impossible : ${source.path} vers ${destination.path}")
        }
        faits += Deplacement(source, destination)
    }

    companion object {
        const val ANCIEN = "ancien"
        const val NOUVEAU = "nouveau"
        const val JOURNAL = "journal.txt"
        private const val ETAPE = "RENOMMER"
        private const val FIN = "TERMINE"

        /**
         * À appeler au démarrage, avant toute ouverture de la base. S'il reste
         * un dossier de restauration : si le journal ne dit pas « TERMINE »,
         * les renommages sont défaits en ordre inverse (l'état d'origine
         * revient) ; dans tous les cas le dossier est ensuite supprimé.
         * Retourne un texte de diagnostic, ou null s'il n'y avait rien.
         */
        fun recuperer(racine: File): String? {
            if (!racine.exists()) return null
            val lignes = File(racine, JOURNAL).takeIf { it.exists() }?.readLines().orEmpty()
            val termine = lignes.any { it == FIN }
            var defaits = 0
            var echecs = 0
            if (!termine) {
                for (ligne in lignes.asReversed()) {
                    val morceaux = ligne.split('\t')
                    if (morceaux.size != 3 || morceaux[0] != ETAPE) continue
                    val source = File(morceaux[1])
                    val destination = File(morceaux[2])
                    if (destination.exists() && !source.exists()) {
                        if (destination.renameTo(source)) defaits++ else echecs++
                    }
                }
            }
            if (echecs > 0) {
                // On garde le dossier : il contient encore des données de l'utilisateur.
                return "récupération incomplète : $echecs élément(s) à remettre en place à la main dans ${racine.path}"
            }
            racine.deleteRecursively()
            return when {
                termine -> "restauration terminée, restes supprimés"
                defaits > 0 -> "restauration interrompue : $defaits renommage(s) défait(s), état d'origine rétabli"
                else -> "restauration interrompue avant la bascule : dossier temporaire supprimé"
            }
        }
    }
}
