package com.trucdecomptable.notesdefrais.data

/**
 * Plafonds d'exonération de frais professionnels, montants 2026 publiés par
 * l'URSSAF (page « Frais professionnels »).
 *
 * Ces plafonds ne bloquent rien : une dépense au-delà reste une dépense réelle,
 * mais la part excédentaire peut être réintégrée dans le revenu imposable, donc
 * l'app le signale au moment de l'enregistrement plutôt que de le laisser
 * découvrir au contrôle.
 */
object Plafonds {

    const val ANNEE = 2026

    /** Repas pris au restaurant lors d'un déplacement professionnel. */
    const val REPAS_RESTAURANT = 2140L

    /**
     * Petit déjeuner. L'URSSAF ne publie pas de plafond propre : le
     * petit-déjeuner n'apparaît que dans la ligne « logement et petit
     * déjeuner » du grand déplacement. Facturé à part (hôtel sans formule ou
     * café le matin), il relève des frais de repas : c'est donc le plafond
     * repas au restaurant qui sert de repère.
     */
    const val PETIT_DEJEUNER = REPAS_RESTAURANT

    /** Repas sur le lieu de travail. */
    const val REPAS_LIEU_TRAVAIL = 750L

    /** Repas au restaurant hors situation de déplacement contraint. */
    const val REPAS_NON_CONTRAINT = 1040L

    /** Nuitée + petit-déjeuner, Paris et départements 92, 93, 94 (3 premiers mois). */
    const val NUITEE_PARIS = 7660L

    /** Nuitée + petit-déjeuner, autres départements (3 premiers mois). */
    const val NUITEE_PROVINCE = 5680L

    /** Ceiling that applies to an expense, null when it has none. */
    fun plafondCentimes(
        type: TypeDepense,
        zoneParis: Boolean,
        situation: SituationRepas = SituationRepas.GRAND_DEPLACEMENT,
    ): Long? = when (type) {
        TypeDepense.RESTAURANT_MIDI_SEUL,
        TypeDepense.RESTAURANT_MIDI_INVITE,
        TypeDepense.RESTAURANT_SOIR,
        // Le même repas n'ouvre pas le même plafond selon où il a été pris :
        // sur le lieu de travail c'est 7,50 €, au restaurant sans déplacement
        // contraint 10,40 €, et en grand déplacement 21,40 €.
        -> when (situation) {
            SituationRepas.LIEU_TRAVAIL -> REPAS_LIEU_TRAVAIL
            SituationRepas.HORS_DEPLACEMENT -> REPAS_NON_CONTRAINT
            SituationRepas.GRAND_DEPLACEMENT -> REPAS_RESTAURANT
        }

        TypeDepense.PETIT_DEJEUNER -> PETIT_DEJEUNER

        TypeDepense.HOTEL -> if (zoneParis) NUITEE_PARIS else NUITEE_PROVINCE
        // Transport, taxi, train, avion, essence, péage, location, indemnités :
        // frais réels sur justificatif, sans plafond d'exonération.
        else -> null
    }

    /**
     * Warning to show when the amount is above the ceiling, null otherwise.
     * [parNuit] lets a hotel bill of several nights through.
     */
    fun alerte(
        depense: Depense,
        zoneParis: Boolean,
        parNuit: Int = 1,
        situation: SituationRepas = depense.situation,
    ): String? {
        val plafond = plafondCentimes(depense.typeDepense, zoneParis, situation) ?: return null
        val plafondTotal = plafond * parNuit.coerceAtLeast(1)
        if (depense.montantCentimes <= plafondTotal) return null
        val exces = depense.montantCentimes - plafondTotal
        return "Au-delà du plafond d'exonération $ANNEE (${montant(plafondTotal)}) : " +
            "${montant(exces)} peuvent être réintégrés."
    }

    fun libellePlafond(
        type: TypeDepense,
        zoneParis: Boolean,
        situation: SituationRepas = SituationRepas.GRAND_DEPLACEMENT,
    ): String? =
        plafondCentimes(type, zoneParis, situation)?.let { "Plafond $ANNEE : ${montant(it)}" }

    private fun montant(centimes: Long): String =
        String.format(java.util.Locale.FRANCE, "%.2f €", centimes / 100.0)
}

/**
 * Deux fois la même dépense arrive vite : le ticket photographié deux fois, ou
 * la saisie qui complète l'enregistrement automatique. Better to ask than to
 * inflate the report.
 */
object Doublons {

    /** An existing expense that looks like [candidate], null when there is none. */
    fun chercher(candidate: Depense, existantes: List<Depense>): Depense? = existantes.firstOrNull { autre ->
        autre.id != candidate.id &&
            autre.projetId == candidate.projetId &&
            autre.montantCentimes == candidate.montantCentimes &&
            autre.date == candidate.date &&
            (autre.devise == candidate.devise) &&
            (
                autre.type == candidate.type ||
                    (candidate.libelle.isNotBlank() &&
                        autre.libelle.trim().equals(candidate.libelle.trim(), ignoreCase = true))
                )
    }

    /** Human readable warning, null when the expense is unique. */
    fun alerte(candidate: Depense, existantes: List<Depense>): String? {
        val doublon = chercher(candidate, existantes) ?: return null
        val montant = String.format(java.util.Locale.FRANCE, "%.2f €", doublon.montantCentimes / 100.0)
        return "Dépense identique déjà enregistrée le ${doublon.date} : $montant ${doublon.typeDepense.label}"
    }

    /**
     * Deux dépenses d'un même projet portant le même numéro de facture (non
     * vide) sont presque sûrement la même facture : le numéro est un repère
     * plus fiable que le montant ou la date, qui peuvent coïncider par hasard.
     * La comparaison ignore la casse et les espaces de bord, mais garde la
     * ponctuation interne (un tiret en plus = un autre numéro).
     */
    fun alerteNumero(candidate: Depense, existantes: List<Depense>): String? {
        val numero = candidate.numFacture.trim()
        if (numero.isBlank()) return null
        val doublon = existantes.firstOrNull { autre ->
            autre.id != candidate.id &&
                autre.projetId == candidate.projetId &&
                autre.numFacture.trim().equals(numero, ignoreCase = true)
        } ?: return null
        return "Facture n° ${doublon.numFacture.trim()} déjà enregistrée le ${doublon.date}"
    }
}
