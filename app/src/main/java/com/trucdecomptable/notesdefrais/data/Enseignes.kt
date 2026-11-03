package com.trucdecomptable.notesdefrais.data

import java.text.Normalizer
import java.util.Locale

/**
 * Enseignes qui proposent un portail « facture en ligne » pour transformer un
 * ticket de caisse en facture.
 *
 * Les URL changent régulièrement chez les enseignes : elles sont centralisées
 * ici (vérifiées le 03/11/2026) pour qu'une correction tienne en une ligne.
 *
 * E.Leclerc et Monoprix n'ont pas de formulaire ticket → facture : tout passe
 * par l'espace client, on propose seulement le lien (formulaireTicket = false).
 * Intermarché n'a aucun service trouvé, donc pas d'entrée.
 */
data class Enseigne(
    val nom: String,              // « Auchan »
    val urlFacture: String,       // portail « facture en ligne »
    val motsCles: List<String>,   // détection sur le texte OCR, en majuscules sans accents
    val champDemande: String,     // « N° du code-barres du ticket (22 chiffres) »
    val delaiJours: Int?,         // 30 pour Auchan, null quand inconnu
    val note: String = "",        // « espace client », « formulaire ticket », etc.
    val formulaireTicket: Boolean, // true = on peut générer depuis le ticket
)

object Enseignes {

    val toutes: List<Enseigne> = listOf(
        Enseigne(
            nom = "Carrefour",
            urlFacture = "https://www.carrefour.fr/services/facture",
            // Le nom précis prime sur le générique : « CARREFOUR CITY » est
            // testé avant « CARREFOUR ».
            motsCles = listOf(
                "CARREFOUR CITY",
                "CARREFOUR MARKET",
                "CARREFOUR CONTACT",
                "CARREFOUR EXPRESS",
                "CARREFOUR",
            ),
            champDemande = "N° du ticket de caisse",
            delaiJours = null,
            note = "formulaire ticket",
            formulaireTicket = true,
        ),
        Enseigne(
            nom = "Auchan",
            urlFacture = "https://www.auchan.fr/facture",
            motsCles = listOf("AUCHAN"),
            champDemande = "N° du code-barres du ticket (22 chiffres)",
            delaiJours = 30,
            note = "formulaire ticket, refusé au-delà de 30 jours",
            formulaireTicket = true,
        ),
        Enseigne(
            nom = "Lidl",
            urlFacture = "https://www.lidl.fr/c/facture-en-ligne/s10019836",
            motsCles = listOf("LIDL"),
            champDemande = "N° du ticket de caisse",
            delaiJours = null,
            note = "formulaire ticket",
            formulaireTicket = true,
        ),
        Enseigne(
            nom = "Magasins U",
            urlFacture = "https://www.magasins-u.com/ma-facture-u",
            // « U » seul est trop large (il apparaît aussi dans un mot) : on
            // exige une enseigne précise de la famille U.
            motsCles = listOf("SUPER U", "HYPER U", "MAGASINS U", "U EXPRESS", "UTILE"),
            champDemande = "N° du ticket de caisse",
            delaiJours = null,
            note = "formulaire ticket",
            formulaireTicket = true,
        ),
        Enseigne(
            nom = "E.Leclerc",
            urlFacture = "https://www.e.leclerc/espace-client/mon-compte",
            motsCles = listOf("E.LECLERC", "LECLERC"),
            champDemande = "",
            delaiJours = null,
            note = "espace client, pas de formulaire ticket",
            formulaireTicket = false,
        ),
        Enseigne(
            nom = "Monoprix",
            urlFacture = "https://client.monoprix.fr/monoprix-plus",
            motsCles = listOf("MONOPRIX"),
            champDemande = "",
            delaiJours = null,
            note = "espace client, pas de formulaire ticket",
            formulaireTicket = false,
        ),
    )

    /**
     * Reconnaît l'enseigne d'un ticket à partir de son texte OCR. Insensible à
     * la casse et aux accents. Les mots-clés sont testés dans l'ordre de la
     * liste, les plus précis d'abord, donc un « CARREFOUR CITY » est reconnu
     * avant un simple « CARREFOUR ».
     */
    fun detecter(texteOcr: String): Enseigne? {
        val texte = normaliser(texteOcr)
        if (texte.isBlank()) return null
        for (enseigne in toutes) {
            for (motCle in enseigne.motsCles) {
                if (contient(texte, normaliser(motCle))) return enseigne
            }
        }
        return null
    }

    /** Retrouve une enseigne par son nom exact (casse ignorée). */
    fun parNom(nom: String): Enseigne? =
        toutes.firstOrNull { it.nom.equals(nom.trim(), ignoreCase = true) }

    /** Majuscules sans accents, pour une comparaison stable sur du texte OCR. */
    private fun normaliser(texte: String): String {
        val sansAccents = Normalizer.normalize(texte, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
        return sansAccents.uppercase(Locale.ROOT)
    }

    /**
     * Présence du mot-clé avec frontières de mots : « U » ne doit pas matcher
     * dans « PLUS », ni « UTILE » dans « INUTILE ».
     */
    private fun contient(texte: String, motCle: String): Boolean {
        val frontiere = Regex("(?<![A-Z0-9])${Regex.escape(motCle)}(?![A-Z0-9])")
        return frontiere.containsMatchIn(texte)
    }
}
