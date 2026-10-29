package com.trucdecomptable.notesdefrais.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow
import java.math.BigDecimal
import java.math.RoundingMode

/** The expense types Fabrice asked for, each with a slug used in file names. */
enum class TypeDepense(val label: String, val slug: String) {
    AVION("Avion", "avion"),
    TRAIN("Train", "train"),
    TAXI("Taxi", "taxi"),
    LOCATION_VOITURE("Location voiture", "location-voiture"),
    ESSENCE("Essence", "essence"),
    PEAGE("Péage", "peage"),
    HOTEL("Hôtel", "hotel"),
    RESTAURANT_MIDI_SEUL("Repas midi seul", "repas-midi-seul"),
    RESTAURANT_MIDI_INVITE("Repas midi avec invité", "repas-midi-invite"),
    RESTAURANT_SOIR("Restaurant soir", "restaurant-soir"),
    INDENNITE_KM("Indemnité km", "indemnite-km"),
    AUTRE("Autres", "autres");

    companion object {
        fun fromName(name: String): TypeDepense = entries.firstOrNull { it.name == name } ?: AUTRE
        fun fromSlug(slug: String): TypeDepense = entries.firstOrNull { it.slug == slug } ?: AUTRE
    }
}

/** One way or a return trip, asked for trains and planes. */
enum class SensTrajet(val label: String, val slug: String) {
    ALLER("Aller", "aller"),
    ALLER_RETOUR("Aller-retour", "aller-retour");

    companion object {
        fun fromName(name: String): SensTrajet? = entries.firstOrNull { it.name == name }
    }
}

/**
 * Une société du cabinet (deux sociétés = deux jeux de projets). Le titulaire,
 * lui, reste dans le paramétrage : c'est la même personne qui engage les frais.
 */
@Entity(tableName = "profils")
data class Profil(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** Raison sociale, telle qu'elle doit apparaître sur l'export. */
    val nom: String,
    val siret: String = "",
    val adresse: String = "",
    /** Destinataire des exports pour cette société ; vide = celui du paramétrage. */
    val emailDestinataire: String = "",
    /**
     * Moyen de paiement proposé par défaut sur les dépenses de cette société ;
     * vide = celui du paramétrage général.
     */
    val moyenPaiementDefaut: String = "",
    /**
     * Moyens de paiement propres à cette société, séparés par '|'. Les moyens
     * communs du paramétrage restent toujours disponibles et ne sont pas
     * supprimables ici.
     */
    val moyensPaiement: String = "",
    val creeLe: Long = System.currentTimeMillis(),
) {
    /** Moyens propres à la société, sans doublon ni vide. */
    val moyens: List<String>
        get() = moyensPaiement.split('|').map { it.trim() }.filter { it.isNotBlank() }.distinct()

    /** Tout ce qu'on peut choisir sur une dépense de cette société. */
    fun moyensAvec(communs: List<String>): List<String> = (communs + moyens).distinct()
}

@Dao
interface ProfilsDao {
    @Query("SELECT * FROM profils ORDER BY nom COLLATE NOCASE")
    fun profils(): Flow<List<Profil>>

    @Query("SELECT * FROM profils ORDER BY nom COLLATE NOCASE")
    suspend fun tousLesProfils(): List<Profil>

    @Query("SELECT * FROM profils WHERE id = :id")
    suspend fun profil(id: Long): Profil?

    @Insert
    suspend fun ajouter(profil: Profil): Long

    @Update
    suspend fun maj(profil: Profil)

    @Query("DELETE FROM profils WHERE id = :id")
    suspend fun supprimer(id: Long)

    @Query("UPDATE projets SET profilId = :profilId WHERE profilId = :ancien")
    suspend fun transfererProjets(ancien: Long, profilId: Long)

    /** Migration en masse : tous les projets encore sans société. */
    @Query("UPDATE projets SET profilId = :profilId WHERE profilId = 0")
    suspend fun affecterProjetsSansSociete(profilId: Long): Int
}

@Entity(tableName = "projets")
data class Projet(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nom: String,
    val creeLe: Long = System.currentTimeMillis(),
    /** A closed project is read-only: the report has been handed over. */
    val cloture: Boolean = false,
    val clotureLe: Long? = null,
    /** Société propriétaire du projet ; 0 = pas de profil (société du paramétrage). */
    val profilId: Long = 0,
    /**
     * Archivé = rangé, hors de l'accueil mais intact : ni les dépenses ni les
     * pièces jointes ne bougent, et la restauration le remet dans la liste.
     * Différent de [cloture], qui rend le projet en lecture seule mais visible.
     */
    val archiveLe: Long? = null,
    /**
     * Date du dernier envoi des justificatifs en PDF à la comptabilité. Nulle
     * tant qu'ils ne sont jamais partis : c'est ce qui déclenche la proposition
     * d'envoi au moment d'archiver.
     */
    val justificatifsEnvoyesLe: Long? = null,
) {
    val archive: Boolean get() = archiveLe != null
    val justificatifsEnvoyes: Boolean get() = justificatifsEnvoyesLe != null
}

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
    /** Kilometres of a mileage allowance trip, null for an ordinary expense. */
    val distanceKm: Int? = null,
    /** "Asnières → Rouen" for a mileage allowance. */
    val trajet: String = "",
    /** The mileage part alone; [montantCentimes] adds the tolls and parking. */
    val indemniteCentimes: Long? = null,
    /** Tolls and parking added to a mileage allowance, in [devise]. */
    val fraisAnnexesCentimes: Long? = null,
    /** Whether the expense is to be re-invoiced to the client. */
    val refacturable: Boolean = false,
    /** Extra attachments (invoice, back of the receipt), names joined by '|'. */
    val fichiersSupplementaires: String = "",
    /**
     * How it was paid ("CB pro", "Espèces"…). Stores the label itself rather
     * than an index into the settings list, so renaming a card never rewrites
     * past expenses.
     */
    val moyenPaiement: String = "",
    /**
     * Aller simple ou aller-retour, pour le train et l'avion. Vide quand la
     * question ne se pose pas.
     */
    val sensTrajet: String = "",
    /** Repas : qui était là (nom et prénom), pour qui, et pourquoi. */
    val participants: String = "",
    val societeParticipant: String = "",
    val motifRepas: String = "",
    /**
     * Quand la dépense a été mise à la corbeille (null = dépense active). Une
     * dépense à la corbeille n'est ni listée, ni totalisée, ni exportée, mais
     * sa pièce jointe reste sur le téléphone jusqu'à la suppression définitive.
     */
    val corbeilleLe: Long? = null,
) {
    val dansCorbeille: Boolean get() = corbeilleLe != null

    val typeDepense: TypeDepense get() = TypeDepense.fromName(type)

    /** The trip question only means something for a train or a plane. */
    val trajetDemande: Boolean
        get() = typeDepense == TypeDepense.TRAIN || typeDepense == TypeDepense.AVION

    /** A meal needs its guests and its purpose to be deductible. */
    val repasDemande: Boolean
        get() = typeDepense == TypeDepense.RESTAURANT_MIDI_SEUL ||
            typeDepense == TypeDepense.RESTAURANT_MIDI_INVITE ||
            typeDepense == TypeDepense.RESTAURANT_SOIR

    val sens: SensTrajet? get() = SensTrajet.fromName(sensTrajet)

    val estIndemniteKm: Boolean get() = typeDepense == TypeDepense.INDENNITE_KM

    /** Every attachment of the expense, the main one first. */
    val tousLesFichiers: List<String>
        get() = buildList {
            fichier?.takeIf { it.isNotBlank() }?.let { add(it) }
            fichiersSupplementaires.split('|').map { it.trim() }.filter { it.isNotBlank() }.let { addAll(it) }
        }

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
