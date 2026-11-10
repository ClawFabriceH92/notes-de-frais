package com.trucdecomptable.notesdefrais.backup

import org.json.JSONObject
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Les règles décidables de la restauration, sans aucune dépendance Android :
 * tout ce qui peut se calculer à partir de valeurs simples vit ici, pour être
 * testé sur la JVM. Le code qui touche aux fichiers de l'application
 * (Sauvegarde.restaurer) ne fait qu'appliquer ces décisions.
 */

/** Refus net, avant toute écriture dans l'état courant : rien n'a bougé. */
class RestaurationRefusee(message: String) : Exception(message)

/**
 * Échec survenu après la fermeture de la base. Les fichiers d'origine ont été
 * remis en place, mais les accès Room ouverts avant sont morts : il faut
 * redémarrer le processus.
 */
class RestaurationInterrompue(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Ce que dit meta.json d'une archive. Chaque champ peut manquer. */
data class MetaArchive(
    val version: String?,
    val date: String?,
    val motif: String?,
    val projets: Int?,
    val depenses: Int?,
    val justificatifs: Int?,
)

/** Résultat de la décision prise sur une archive avant toute écriture. */
sealed interface Decision {
    data class Autorisee(val avertissements: List<String>) : Decision
    data class Refusee(val raison: String) : Decision
}

/** Rôle d'une entrée du ZIP. */
sealed interface CibleEntree {
    data object Meta : CibleEntree
    data object Parametres : CibleEntree
    data class Base(val suffixe: String) : CibleEntree
    data class Justificatif(val nom: String) : CibleEntree
    data object Ignoree : CibleEntree
    data class Suspecte(val nom: String) : CibleEntree
}

object ReglesRestauration {

    const val NOM_DB = "notes-de-frais.db"
    const val DOSSIER_JUSTIFICATIFS = "justificatifs"
    const val ENTREE_PARAMETRES = "parametres.json"
    const val ENTREE_META = "meta.json"
    const val MOTIF_AVANT_RESTAURATION = "avant-restauration"

    /** Plafonds de lecture des petites entrées (meta.json, parametres.json). */
    const val TAILLE_MAX_META = 1L * 1024 * 1024
    const val TAILLE_MAX_PARAMETRES = 5L * 1024 * 1024

    /** Réserve laissée libre en plus de ce que la restauration écrit. */
    const val MARGE_ESPACE = 32L * 1024 * 1024
    private const val ESTIMATION_MINIMALE = 64L * 1024 * 1024

    /** Combien d'archives « avant restauration » on garde, au minimum 1. */
    const val GARDER_AVANT_RESTAURATION = 3
    const val GARDER_GENERALES = 10
    private const val AGE_MAX_PARTIEL_MS = 60L * 60 * 1000

    /** « SQLite format 3 » suivi d'un octet nul : les 16 premiers octets d'une base. */
    private val SIGNATURE_SQLITE = "SQLite format 3\u0000".toByteArray(Charsets.ISO_8859_1)
    private const val TAILLE_ENTETE_SQLITE = 100

    private val TABLES_ATTENDUES = setOf("projets", "depenses")

    // ------------------------------------------------------------ meta.json

    /** Lit meta.json. Null si le texte manque ou n'est pas un objet JSON. */
    fun lireMeta(json: String?): MetaArchive? {
        if (json.isNullOrBlank()) return null
        val racine = runCatching { JSONObject(json) }.getOrNull() ?: return null
        fun entier(cle: String): Int? =
            if (racine.has(cle)) racine.optInt(cle, -1).takeIf { it >= 0 } else null
        fun texte(cle: String): String? =
            if (racine.has(cle)) racine.optString(cle).trim().takeIf { it.isNotEmpty() } else null
        return MetaArchive(
            version = texte("version"),
            date = texte("date"),
            motif = texte("motif"),
            projets = entier("projets"),
            depenses = entier("depenses"),
            justificatifs = entier("justificatifs"),
        )
    }

    /** « 2026-11-08T10:15:30.123 » -> « 08/11/2026 à 10:15 ». Texte brut si illisible. */
    fun formaterDate(iso: String?): String {
        if (iso.isNullOrBlank()) return "date inconnue"
        val date = runCatching { LocalDateTime.parse(iso) }.getOrNull() ?: return iso
        return date.format(DateTimeFormatter.ofPattern("dd/MM/yyyy 'à' HH:mm"))
    }

    /** Compare deux numéros de version « 1.20.1 ». Négatif si [a] est plus ancienne. */
    fun comparerVersions(a: String, b: String): Int {
        fun segments(v: String) = v.trim().split('.', '-').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
        val x = segments(a)
        val y = segments(b)
        for (i in 0 until maxOf(x.size, y.size)) {
            val delta = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
            if (delta != 0) return delta
        }
        return 0
    }

    /**
     * Décision prise sur ce qu'on sait de l'archive avant d'écrire quoi que ce
     * soit. [baseVue] est faux si le parcours de l'archive n'a pas trouvé la
     * base. La base elle-même est contrôlée plus tard (verifierBase).
     */
    fun decider(meta: MetaArchive?, baseVue: Boolean, versionApp: String): Decision {
        if (!baseVue) return Decision.Refusee("Cette archive ne contient pas de base Notes de frais.")
        val avertissements = mutableListOf<String>()
        if (meta == null) {
            avertissements += "Cette archive n'a pas de meta.json lisible : son contenu ne peut pas être annoncé avant la restauration."
        } else {
            val version = meta.version
            if (version != null && comparerVersions(version, versionApp) > 0) {
                avertissements += "Archive créée par une version plus récente ($version) que celle installée ($versionApp)."
            }
            if (meta.depenses == 0 && meta.projets == 0 && meta.justificatifs == 0) {
                avertissements += "Cette archive est vide : vos données actuelles seraient remplacées par rien."
            }
        }
        return Decision.Autorisee(avertissements)
    }

    /** Texte du dialogue de confirmation : ce que contient l'archive, ce qui sera écrasé. */
    fun lignesConfirmation(
        meta: MetaArchive?,
        avertissements: List<String>,
        actuelProjets: Int,
        actuelDepenses: Int,
        actuelJustificatifs: Int,
    ): List<String> {
        val lignes = mutableListOf<String>()
        if (meta != null) {
            lignes += "Archive du ${formaterDate(meta.date)}, version ${meta.version ?: "inconnue"}" +
                (meta.motif?.let { ", motif « $it »" } ?: "") + "."
            fun nombre(valeur: Int?) = valeur?.toString() ?: "?"
            lignes += "Elle contient ${nombre(meta.depenses)} dépense(s), ${nombre(meta.projets)} projet(s) " +
                "et ${nombre(meta.justificatifs)} pièce(s)."
        }
        lignes += "Sera écrasé : toute la base actuelle ($actuelDepenses dépense(s), $actuelProjets projet(s)), " +
            "les $actuelJustificatifs justificatif(s) enregistrés et le paramétrage."
        lignes += "Une sauvegarde de l'état actuel est écrite avant toute modification, " +
            "puis l'application redémarre."
        lignes += avertissements
        return lignes
    }

    // ------------------------------------------------------------ entrées du ZIP

    /** Classe une entrée du ZIP. Un chemin qui sort du dossier est « suspect ». */
    fun classer(nomEntree: String, estDossier: Boolean): CibleEntree {
        val nom = nomEntree.replace('\\', '/')
        val segments = nom.split('/')
        if (nom.startsWith("/") || segments.any { it == ".." } || nom.contains('\u0000')) {
            return CibleEntree.Suspecte(nomEntree)
        }
        if (estDossier) return CibleEntree.Ignoree
        return when {
            nom == ENTREE_META -> CibleEntree.Meta
            nom == ENTREE_PARAMETRES -> CibleEntree.Parametres
            nom == NOM_DB -> CibleEntree.Base("")
            nom == "$NOM_DB-wal" -> CibleEntree.Base("-wal")
            nom == "$NOM_DB-shm" -> CibleEntree.Ignoree
            nom.startsWith("$DOSSIER_JUSTIFICATIFS/") -> {
                val reste = nom.removePrefix("$DOSSIER_JUSTIFICATIFS/")
                if (reste.isEmpty() || reste.contains('/') || reste == ".") CibleEntree.Ignoree
                else CibleEntree.Justificatif(reste)
            }
            else -> CibleEntree.Ignoree
        }
    }

    // ------------------------------------------------------------ espace

    /**
     * Espace libre à exiger avant de commencer. [tailleArchive] est la taille
     * compressée, ou une valeur <= 0 si elle est inconnue. On prévoit deux fois
     * l'archive (la base se tasse bien à la compression), plus l'état courant
     * que la sauvegarde de sécurité va copier, plus une marge.
     */
    fun espaceNecessaire(tailleArchive: Long, tailleEtatCourant: Long): Long {
        val etat = tailleEtatCourant.coerceAtLeast(0)
        val archive = if (tailleArchive > 0) tailleArchive else maxOf(etat * 2, ESTIMATION_MINIMALE)
        return archive * 2 + etat + MARGE_ESPACE
    }

    fun espaceSuffisant(libre: Long, tailleArchive: Long, tailleEtatCourant: Long): Boolean =
        libre >= espaceNecessaire(tailleArchive, tailleEtatCourant)

    /**
     * Octets qu'on peut écrire en dossier temporaire sans empêcher la
     * sauvegarde de sécurité (taille de l'état courant) ni la marge. Sert aussi
     * de garde contre une archive qui se décompresse sans fin.
     */
    fun budgetExtraction(libre: Long, tailleEtatCourant: Long): Long =
        (libre - tailleEtatCourant.coerceAtLeast(0) - MARGE_ESPACE).coerceAtLeast(0)

    fun enMo(octets: Long): String = "${(octets + 1024 * 1024 - 1) / (1024 * 1024)} Mo"

    fun messageEspaceInsuffisant(libre: Long, necessaire: Long): String =
        "Espace insuffisant pour restaurer en sécurité : il faut environ ${enMo(necessaire)} libres, " +
            "il en reste ${enMo(libre)}. Libérez de la place puis recommencez : rien n'a été modifié."

    // ------------------------------------------------------------ base SQLite

    /** Vrai si les octets de tête sont ceux d'une base SQLite complète (>= 100 octets). */
    fun enteteSqliteValide(debut: ByteArray, tailleFichier: Long): Boolean {
        if (tailleFichier < TAILLE_ENTETE_SQLITE || debut.size < SIGNATURE_SQLITE.size) return false
        return SIGNATURE_SQLITE.indices.all { debut[it] == SIGNATURE_SQLITE[it] }
    }

    /**
     * Verdict sur la base de l'archive, ou null si elle est acceptable.
     * [versionArchive] vient de PRAGMA user_version, [versionApp] de la base
     * ouverte de l'application, [quickCheck] des lignes de PRAGMA quick_check.
     */
    fun verifierBase(
        versionArchive: Int,
        versionApp: Int,
        quickCheck: List<String>,
        tables: Set<String>,
        identiteArchive: String? = null,
        identiteApp: String? = null,
    ): String? {
        if (versionArchive <= 0) {
            return "La base de l'archive n'est pas une base Notes de frais (version $versionArchive)."
        }
        if (versionArchive > versionApp) {
            return "La base de l'archive est plus récente (version $versionArchive) que celle de l'application " +
                "(version $versionApp). Mettez l'application à jour avant de restaurer."
        }
        if (quickCheck.size != 1 || !quickCheck.first().equals("ok", ignoreCase = true)) {
            val detail = quickCheck.take(3).joinToString(" ; ").ifBlank { "aucun résultat" }
            return "La base de l'archive est corrompue (contrôle d'intégrité : $detail)."
        }
        val manquantes = TABLES_ATTENDUES - tables.map { it.lowercase() }.toSet()
        if (manquantes.isNotEmpty()) {
            return "La base de l'archive ne contient pas les tables attendues (${manquantes.sorted().joinToString(", ")})."
        }
        // Même version mais empreinte de schéma Room différente (build de développement,
        // schéma modifié) : Room refuserait d'ouvrir la base au démarrage.
        if (versionArchive == versionApp && identiteArchive != null && identiteApp != null &&
            identiteArchive != identiteApp
        ) {
            return "La base de l'archive a le même numéro de version que celle de l'application mais pas le même schéma."
        }
        return null
    }

    // ------------------------------------------------------------ rétention

    /** Un fichier du dossier des sauvegardes, pour la politique de rétention. */
    data class ArchiveFichier(val nom: String, val modifie: Long)

    fun estAvantRestauration(nom: String): Boolean =
        nom.startsWith("notes-de-frais-$MOTIF_AVANT_RESTAURATION-") && nom.endsWith(".zip")

    /**
     * Noms à supprimer. Les archives « avant restauration » ont leur propre
     * quota (la dernière est le filet de sécurité de l'utilisateur : la
     * rotation des autres sauvegardes ne doit jamais l'emporter). Les autres
     * fichiers suivent l'ancienne règle des [garderGenerales] plus récents. Les
     * écritures partielles (.tmp) abandonnées depuis plus d'une heure partent.
     */
    fun aSupprimer(
        fichiers: List<ArchiveFichier>,
        maintenant: Long,
        garderGenerales: Int = GARDER_GENERALES,
        garderAvantRestauration: Int = GARDER_AVANT_RESTAURATION,
    ): List<String> {
        val ordre = compareByDescending<ArchiveFichier> { it.modifie }.thenByDescending { it.nom }
        val partiels = fichiers.filter { it.nom.endsWith(".tmp") }
        val propres = fichiers - partiels.toSet()
        val avant = propres.filter { estAvantRestauration(it.nom) }.sortedWith(ordre)
        val autres = (propres - avant.toSet()).sortedWith(ordre)
        return buildList {
            addAll(partiels.filter { maintenant - it.modifie > AGE_MAX_PARTIEL_MS }.map { it.nom })
            addAll(avant.drop(garderAvantRestauration.coerceAtLeast(1)).map { it.nom })
            addAll(autres.drop(garderGenerales.coerceAtLeast(0)).map { it.nom })
        }
    }
}
