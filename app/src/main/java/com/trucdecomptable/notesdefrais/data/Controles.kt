package com.trucdecomptable.notesdefrais.data

import com.trucdecomptable.notesdefrais.util.Dates
import java.time.LocalDate
import java.util.Locale

/**
 * Contrôles de cohérence d'une dépense, au moment de la saisie et avant
 * l'export.
 *
 * Règle de maison : un contrôle **avertit**, il ne refuse jamais
 * l'enregistrement. La dépense reste réelle, mais l'app la signale pour éviter
 * un aller-retour avec la comptabilité (date à venir, taux de TVA improbable,
 * repas non justifié) ou un rejet de l'export (justificatif manquant).
 */
object Controles {

    /**
     * Une date postérieure à aujourd'hui est presque toujours une erreur de
     * frappe (année inversée, mois glissé) : la comptabilité la refuserait.
     * On signale, mais on laisse enregistrer : un billet daté de demain peut
     * être réel.
     */
    fun alerteDateFuture(depense: Depense, aujourdhui: LocalDate = LocalDate.now()): String? {
        val date = runCatching { LocalDate.parse(depense.date.trim()) }.getOrNull() ?: return null
        if (!date.isAfter(aujourdhui)) return null
        return "Date postérieure à aujourd'hui (${Dates.isoVersFr(depense.date)}) : à vérifier"
    }

    /**
     * Compare le taux de TVA saisi à ceux que ce type de dépense peut porter.
     * Un taux inhabituel (5,5 % sur un repas, 20 % sur un hôtel) sent la
     * saisie erronée ou le justificatif mal classé : mieux vaut le signaler
     * que de l'exporter tel quel.
     *
     * Les taux sont en points de base (2000 = 20 %). « autres » et l'indemnité
     * kilométrique ne sont pas contrôlés : leur TVA dépend du cas.
     */
    fun alerteTauxTva(depense: Depense): String? {
        val bp = depense.tvaTauxBp ?: return null
        val attendus: List<Int> = when (depense.typeDepense) {
            // Repas : 10 %, et 20 % toléré pour les boissons alcoolisées.
            TypeDepense.RESTAURANT_MIDI_SEUL,
            TypeDepense.RESTAURANT_MIDI_INVITE,
            TypeDepense.RESTAURANT_SOIR,
            -> listOf(1000, 2000)

            // Hôtel et petit-déjeuner : 10 % seulement.
            TypeDepense.HOTEL, TypeDepense.PETIT_DEJEUNER -> listOf(1000)

            // Train / avion : 10 %, 5,5 %, ou 0 % pour l'international.
            TypeDepense.TRAIN, TypeDepense.AVION -> listOf(1000, 550, 0)

            // Essence, péage, taxi, location : 20 %.
            TypeDepense.ESSENCE, TypeDepense.PEAGE, TypeDepense.TAXI, TypeDepense.LOCATION_VOITURE -> listOf(2000)

            // Indemnité km et autres : pas de taux attendu.
            else -> return null
        }
        if (bp in attendus) return null
        val attendusTexte = attendus.joinToString(" ou ") { pourcent(it) }
        return "Taux de TVA ${pourcent(bp)} inhabituel pour ${depense.typeDepense.label} " +
            "(attendu : $attendusTexte)."
    }

    /**
     * Un repas doit porter la preuve de sa déductibilité : qui était invité
     * (et pourquoi). Sans le nom des participants, un « repas midi avec
     * invité » n'est pas déductible ; sans motif, tout repas est plus fragile
     * à justifier. On distingue les deux niveaux.
     */
    fun alerteRepasSansInvite(depense: Depense): String? {
        if (!depense.repasDemande) return null
        if (depense.typeDepense == TypeDepense.RESTAURANT_MIDI_INVITE && depense.participants.isBlank()) {
            return "Repas midi avec invité sans le nom des participants : sans eux, " +
                "la dépense n'est pas déductible."
        }
        if (depense.motifRepas.isBlank()) {
            return "Repas sans motif : précise la raison du repas pour justifier sa déductibilité."
        }
        return null
    }

    /**
     * Ce qui manquerait à l'export : les dépenses sans aucune pièce jointe, et
     * les repas de plus de 150 € HT dont le justificatif n'est pas une pièce
     * supplémentaire (une seule pièce = le ticket, donc la facture nominative
     * est probablement absente). La première ligne, quand il y a des pièces
     * manquantes, est un en-tête chiffré. Rien n'est bloqué : c'est l'UI qui
     * décide « Exporter quand même » ou « Annuler ».
     */
    fun controleAvantExport(depenses: List<Depense>): List<String> {
        val lignes = mutableListOf<String>()
        var sansJustificatif = 0
        for (depense in depenses) {
            if (depense.tousLesFichiers.isEmpty()) {
                sansJustificatif++
                lignes += "Sans justificatif : ${description(depense)}"
                continue // Sans aucune pièce, la facture manque forcément aussi.
            }
            // Repas au-delà du seuil avec seulement le ticket : la facture
            // nominative, attendue en pièce supplémentaire, est probablement absente.
            if (Justificatifs.factureObligatoire(depense) && depense.fichiersSupplementaires.isBlank()) {
                lignes += "Facture probablement absente : ${description(depense)}"
            }
        }
        if (sansJustificatif > 0) {
            val mot = if (sansJustificatif > 1) "dépenses" else "dépense"
            lignes.add(0, "$sansJustificatif $mot sans justificatif sur ${depenses.size}")
        }
        return lignes
    }

    /** Toutes les alertes d'une saisie, dans l'ordre d'affichage. */
    fun alertesSaisie(depense: Depense): List<String> = listOfNotNull(
        alerteDateFuture(depense),
        alerteTauxTva(depense),
        alerteRepasSansInvite(depense),
    )

    /** « nom, date, montant », pour identifier la dépense dans une ligne d'alerte. */
    private fun description(depense: Depense): String {
        val nom = depense.libelle.ifBlank { depense.typeDepense.label }
        val montant = String.format(Locale.FRANCE, "%.2f €", depense.montantCentimes / 100.0)
        return "$nom, ${Dates.isoVersFr(depense.date)}, $montant"
    }

    /** Points de base vers « 10 % », « 5,5 % », « 0 % ». */
    private fun pourcent(bp: Int): String {
        val entier = bp / 100
        val centièmes = bp % 100
        return if (centièmes == 0) "$entier %"
        else "$entier,${centièmes.toString().padStart(2, '0').trimEnd('0')} %"
    }
}
