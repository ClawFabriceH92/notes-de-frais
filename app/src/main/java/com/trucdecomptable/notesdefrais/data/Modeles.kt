package com.trucdecomptable.notesdefrais.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.math.BigDecimal
import java.math.RoundingMode

/** The seven expense types Fabrice asked for, each with a slug used in file names. */
enum class TypeDepense(val label: String, val slug: String) {
    AVION("Avion", "avion"),
    HOTEL("Hôtel", "hotel"),
    TRAIN("Train", "train"),
    TAXI("Taxi", "taxi"),
    RESTAURANT_MIDI("Restaurant midi", "restaurant-midi"),
    RESTAURANT_SOIR("Restaurant soir", "restaurant-soir"),
    AUTRE("Autres", "autres");

    companion object {
        fun fromName(name: String): TypeDepense = entries.firstOrNull { it.name == name } ?: AUTRE
        fun fromSlug(slug: String): TypeDepense = entries.firstOrNull { it.slug == slug } ?: AUTRE
    }
}

@Entity(tableName = "projets")
data class Projet(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nom: String,
    val creeLe: Long = System.currentTimeMillis(),
)

/**
 * One expense. Amounts are stored in cents so no rounding drifts into the
 * export; [montantCentimes] is expressed in [devise].
 */
@Entity(
    tableName = "depenses",
    indices = [Index("projetId")],
)
data class Depense(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val projetId: Long,
    /** ISO yyyy-MM-dd. */
    val date: String,
    val montantCentimes: Long,
    val devise: String = "EUR",
    /** 1 EUR = [tauxChange] units of [devise]. Null when unknown. */
    val tauxChange: Double? = null,
    /** [TypeDepense] name, stored as text to stay readable in the database. */
    val type: String = TypeDepense.AUTRE.name,
    val libelle: String = "",
    /** VAT rate in basis points (2000 = 20 %). Null when not asked. */
    val tvaTauxBp: Int? = null,
    /** File name inside filesDir/justificatifs. */
    val fichier: String? = null,
    val texteOcr: String = "",
    val creeLe: Long = System.currentTimeMillis(),
) {
    val typeDepense: TypeDepense get() = TypeDepense.fromName(type)

    /** Amount in euros, or null when a foreign amount has no exchange rate. */
    val montantEurCentimes: Long?
        get() = when {
            devise.equals("EUR", ignoreCase = true) -> montantCentimes
            tauxChange != null && tauxChange > 0.0 -> convertirEnEuros(montantCentimes, tauxChange)
            else -> null
        }

    val montantTtc: BigDecimal get() = BigDecimal(montantCentimes).movePointLeft(2)

    /** Share of the TTC that is VAT, null when no rate was recorded. */
    val tvaCentimes: Long?
        get() {
            val bp = tvaTauxBp ?: return null
            if (bp <= 0) return 0L
            val taux = BigDecimal(bp).movePointLeft(4)
            val ttc = BigDecimal(montantCentimes)
            return ttc.multiply(taux).divide(BigDecimal.ONE.add(taux), 0, RoundingMode.HALF_UP).toLong()
        }

    val htCentimes: Long?
        get() {
            val tva = tvaCentimes ?: return null
            return montantCentimes - tva
        }
}

/** Rounded conversion, kept pure so it can be unit tested. */
fun convertirEnEuros(centimes: Long, tauxChange: Double): Long {
    if (tauxChange <= 0.0) return centimes
    return BigDecimal(centimes)
        .divide(BigDecimal.valueOf(tauxChange), 0, RoundingMode.HALF_UP)
        .toLong()
}
