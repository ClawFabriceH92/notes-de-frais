package com.trucdecomptable.notesdefrais.data

import java.math.BigDecimal
import java.util.Locale

/**
 * Ce que la dépense doit avoir comme pièce justificative.
 *
 * Un repas pris au restaurant peut se justifier par la seule addition tant que
 * le montant reste modeste ; au-delà de 150 € HT, seule une facture nominative
 * (identité du client, détail des consommations, TVA) permet de justifier la
 * charge et de déduire la TVA. Le ticket de caisse ne suffit plus.
 *
 * Le seuil s'apprécie hors taxes. Quand la dépense n'est pas ventilée, l'app
 * retient le TTC : le seuil est alors atteint plus tôt, ce qui déclenche
 * l'alerte avant de la manquer plutôt qu'après.
 */
object Justificatifs {

    /** Seuil en centimes HT au-delà duquel une facture est exigée pour un repas. */
    const val SEUIL_FACTURE_RESTAURANT_HT = 15000L

    /**
     * Montant HT de la dépense, ramené en euros. Null pour une dépense en
     * devise sans parité : on ne peut alors pas apprécier le seuil.
     */
    fun montantHtEurCentimes(depense: Depense): Long? {
        // Pas de ventilation : on retient le TTC, plus prudent que de supposer
        // une TVA qui n'a pas été saisie.
        val ht = depense.htCentimes ?: depense.montantCentimes
        return if (depense.devise.equals("EUR", ignoreCase = true)) {
            ht
        } else {
            depense.tauxChange?.takeIf { it > 0.0 }?.let { convertirEnEuros(ht, it) }
        }
    }

    /** Vrai quand le repas dépasse le seuil et exige donc une facture. */
    fun factureObligatoire(depense: Depense): Boolean {
        if (!depense.repasDemande) return false
        val ht = montantHtEurCentimes(depense) ?: return false
        return ht > SEUIL_FACTURE_RESTAURANT_HT
    }

    /** Seuil lisible, pour l'aide affichée dans le formulaire. */
    val seuilLisible: String
        get() = String.format(Locale.FRANCE, "%.0f € HT", SEUIL_FACTURE_RESTAURANT_HT / 100.0)

    /**
     * Message à afficher, null quand aucune facture n'est exigée. Le montant
     * cité est le HT effectivement comparé au seuil.
     */
    fun alerte(depense: Depense): String? {
        if (!factureObligatoire(depense)) return null
        val ht = montantHtEurCentimes(depense) ?: return null
        val texte = String.format(Locale.FRANCE, "%.2f € HT", ht / 100.0)
        return "Repas de $texte : facture nominative obligatoire au-delà de $seuilLisible, " +
            "le ticket de caisse ne suffit pas."
    }

    /** Bloc d'explication affiché dans la fenêtre d'avertissement. */
    fun explication(depense: Depense): String {
        val ht = montantHtEurCentimes(depense)
        val constat = if (ht == null) {
            "Ce repas dépasse $seuilLisible."
        } else {
            String.format(Locale.FRANCE, "Ce repas atteint %.2f € HT, au-delà de $seuilLisible.", ht / 100.0)
        }
        return "$constat Il faut une facture nominative (nom du client, détail des consommations, " +
            "montant de TVA) et non un simple ticket : c'est elle qui justifie la charge et permet " +
            "de récupérer la TVA. Pense à la joindre avant l'export des justificatifs."
    }

    /** Part de TVA récupérable, rappelée dans l'avertissement (0 quand inconnue). */
    fun tvaRecuperableCentimes(depense: Depense): Long = depense.tvaCentimes ?: 0L

    /** Formatage centralisé des centimes, pour ne pas diverger entre écrans. */
    fun euros(centimes: Long): BigDecimal = BigDecimal(centimes).movePointLeft(2)
}
