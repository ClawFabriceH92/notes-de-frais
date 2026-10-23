package com.trucdecomptable.notesdefrais.data

import java.math.BigDecimal
import java.math.RoundingMode

enum class CategorieVehicule(val label: String) {
    VOITURE("Voiture"),
    MOTO("Moto / scooter"),
    CYCLO("Cyclomoteur"),
}

/**
 * Barème kilométrique fiscal, valeur 2026 (inchangé depuis 2023, source
 * economie.gouv.fr et URSSAF, article 6 B de l'annexe IV au CGI).
 *
 * The rate depends on the distance driven over the WHOLE year, not on one trip,
 * so the amount of a trip is the marginal difference between the yearly total
 * before and after it. That avoids the jump an "apply the bracket rate" version
 * produces when a trip crosses the 5 000 km line.
 *
 * Véhicules électriques : majoration de 20 % appliquée par le barème officiel
 * (les coefficients ci-dessous sont déjà les coefficients majorés).
 */
object BaremeKm {

    const val ANNEE = 2026

    /**
     * Three brackets: up to [borne1] km, up to [borne2] km, then beyond.
     * Each bracket is `coef * d + forfait`.
     */
    data class Grille(
        val borne1: Int,
        val borne2: Int,
        val coef1: Double,
        val forfait1: Double,
        val coef2: Double,
        val forfait2: Double,
        val coef3: Double,
        val forfait3: Double,
    ) {
        fun montantEuros(distance: Int): Double = when {
            distance <= 0 -> 0.0
            distance <= borne1 -> coef1 * distance + forfait1
            distance <= borne2 -> coef2 * distance + forfait2
            else -> coef3 * distance + forfait3
        }
    }

    /** One row of the official table: the CV ceiling, then the three brackets. */
    data class Ligne(
        val cvMax: Int,
        val coef1: Double,
        val coef2: Double,
        val forfait2: Double,
        val coef3: Double,
    )

    private val voitureThermique = listOf(
        Ligne(3, 0.529, 0.316, 1065.0, 0.370),
        Ligne(4, 0.606, 0.340, 1330.0, 0.407),
        Ligne(5, 0.636, 0.357, 1395.0, 0.427),
        Ligne(6, 0.665, 0.374, 1457.0, 0.447),
        Ligne(Int.MAX_VALUE, 0.697, 0.394, 1515.0, 0.470),
    )

    private val voitureElectrique = listOf(
        Ligne(3, 0.635, 0.379, 1278.0, 0.444),
        Ligne(4, 0.727, 0.408, 1596.0, 0.488),
        Ligne(5, 0.763, 0.428, 1674.0, 0.512),
        Ligne(6, 0.798, 0.449, 1748.0, 0.536),
        Ligne(Int.MAX_VALUE, 0.836, 0.473, 1818.0, 0.564),
    )

    private val motoThermique = listOf(
        Ligne(2, 0.395, 0.099, 891.0, 0.248),
        Ligne(5, 0.468, 0.082, 1158.0, 0.275),
        Ligne(Int.MAX_VALUE, 0.606, 0.079, 1583.0, 0.343),
    )

    private val motoElectrique = listOf(
        Ligne(2, 0.474, 0.119, 1069.0, 0.298),
        Ligne(5, 0.562, 0.098, 1390.0, 0.330),
        Ligne(Int.MAX_VALUE, 0.727, 0.095, 1900.0, 0.412),
    )

    private const val CYCLO_COEF1 = 0.315
    private const val CYCLO_COEF2 = 0.079
    private const val CYCLO_FORFAIT2 = 711.0
    private const val CYCLO_COEF3 = 0.198

    private const val CYCLO_ELEC_COEF1 = 0.378
    private const val CYCLO_ELEC_COEF2 = 0.095
    private const val CYCLO_ELEC_FORFAIT2 = 853.0
    private const val CYCLO_ELEC_COEF3 = 0.238

    /** The grid that applies to a vehicle. */
    fun grille(categorie: CategorieVehicule, puissanceFiscale: Int, electrique: Boolean): Grille {
        val cv = puissanceFiscale.coerceAtLeast(1)
        return when (categorie) {
            CategorieVehicule.VOITURE -> {
                val table = if (electrique) voitureElectrique else voitureThermique
                val ligne = table.first { cv <= it.cvMax }
                Grille(
                    borne1 = 5000,
                    borne2 = 20000,
                    coef1 = ligne.coef1,
                    forfait1 = 0.0,
                    coef2 = ligne.coef2,
                    forfait2 = ligne.forfait2,
                    coef3 = ligne.coef3,
                    forfait3 = 0.0,
                )
            }

            CategorieVehicule.MOTO -> {
                val table = if (electrique) motoElectrique else motoThermique
                val ligne = table.first { cv <= it.cvMax }
                Grille(
                    borne1 = 3000,
                    borne2 = 6000,
                    coef1 = ligne.coef1,
                    forfait1 = 0.0,
                    coef2 = ligne.coef2,
                    forfait2 = ligne.forfait2,
                    coef3 = ligne.coef3,
                    forfait3 = 0.0,
                )
            }

            CategorieVehicule.CYCLO -> if (electrique) {
                Grille(3000, 6000, CYCLO_ELEC_COEF1, 0.0, CYCLO_ELEC_COEF2, CYCLO_ELEC_FORFAIT2, CYCLO_ELEC_COEF3, 0.0)
            } else {
                Grille(3000, 6000, CYCLO_COEF1, 0.0, CYCLO_COEF2, CYCLO_FORFAIT2, CYCLO_COEF3, 0.0)
            }
        }
    }

    /**
     * Allowance for one trip, in cents.
     *
     * @param cumulAnnuelKm kilometres already claimed this year before this trip
     */
    fun indemniteCentimes(
        distanceKm: Int,
        cumulAnnuelKm: Int,
        categorie: CategorieVehicule,
        puissanceFiscale: Int,
        electrique: Boolean,
    ): Long {
        if (distanceKm <= 0) return 0L
        val grille = grille(categorie, puissanceFiscale, electrique)
        // Round each total to the cent before subtracting: 0.470 × 25 000 lands
        // on 11 749,999… in binary floating point, which would lose a cent.
        val avant = enCentimes(grille.montantEuros(cumulAnnuelKm.coerceAtLeast(0)))
        val apres = enCentimes(grille.montantEuros(cumulAnnuelKm.coerceAtLeast(0) + distanceKm))
        return (apres - avant).coerceAtLeast(0L)
    }

    private fun enCentimes(euros: Double): Long =
        BigDecimal.valueOf(euros).movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong()

    /** Human readable explanation, printed on the feuille de route. */
    fun explication(
        distanceKm: Int,
        cumulAnnuelKm: Int,
        categorie: CategorieVehicule,
        puissanceFiscale: Int,
        electrique: Boolean,
    ): String {
        val grille = grille(categorie, puissanceFiscale, electrique)
        val apres = cumulAnnuelKm + distanceKm
        val libelle = when (categorie) {
            CategorieVehicule.VOITURE -> "voiture ${puissanceFiscale.coerceAtLeast(1)} CV"
            CategorieVehicule.MOTO -> "moto ${puissanceFiscale.coerceAtLeast(1)} CV"
            CategorieVehicule.CYCLO -> "cyclomoteur"
        } + if (electrique) " électrique" else ""
        val formule = when {
            apres <= grille.borne1 -> "${pourcent(grille.coef1)} × distance"
            apres <= grille.borne2 -> "(${pourcent(grille.coef2)} × distance) + ${euros(grille.forfait2)}"
            else -> "${pourcent(grille.coef3)} × distance"
        }
        return "Barème $ANNEE · $libelle · $distanceKm km (cumul annuel $apres km) · $formule"
    }

    private fun pourcent(coef: Double): String =
        String.format(java.util.Locale.FRANCE, "%.3f €/km", coef)

    private fun euros(valeur: Double): String =
        String.format(java.util.Locale.FRANCE, "%.0f €", valeur)
}
