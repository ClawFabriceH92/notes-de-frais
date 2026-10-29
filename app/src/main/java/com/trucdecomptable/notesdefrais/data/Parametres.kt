package com.trucdecomptable.notesdefrais.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/**
 * Everything the app remembers about its owner and their vehicle. Kept in
 * SharedPreferences as one JSON blob: one read at startup, no database round
 * trip, and a plain-text file that can be inspected or backed up.
 */
data class Parametres(
    val nom: String = "",
    val prenom: String = "",
    val societe: String = "",
    val adresse: String = "",
    val siret: String = "",
    /** Where the export ZIP is mailed by default. Empty means "ask each time". */
    val emailDestinataire: String = "",
    val plaque: String = "",
    val categorieVehicule: String = CategorieVehicule.VOITURE.name,
    val puissanceFiscale: Int = 5,
    val electrique: Boolean = false,
    /** Kilometres already driven this year for business: picks the rate bracket. */
    val kmAnnuels: Int = 0,
    val deviseDefaut: String = "EUR",
    /** 2000 = 20 %. */
    val tvaDefautBp: Int = 2000,
    /** Paris and the 92/93/94 departments have higher accommodation ceilings. */
    val zoneParis: Boolean = false,
    /** SHA-256 of the access code, empty when the app is not locked. */
    val codeAccesHash: String = "",
    /** Cards and other ways to pay, as Fabrice names them ("CB BNP", "CB perso"). */
    val moyensPaiement: List<String> = MOYENS_PAIEMENT_DEFAUT,
    /** Pre-selected in the expense form; empty means "nothing pre-selected". */
    val moyenPaiementDefaut: String = MOYENS_PAIEMENT_DEFAUT.first(),
    /** Set once the expenses recorded before the feature got the default card. */
    val paiementRempliAuto: Boolean = false,
    /** Open the attachment full screen as soon as an expense is opened. */
    val pieceEnGrand: Boolean = true,
    /** Set once the attachment-first default was pushed to an existing install. */
    val pieceEnGrandV2: Boolean = false,
    /** Société (profil) sur laquelle on travaille : ses projets, ses exports. */
    val profilCourant: Long = 0,
    /** Set once the "payé par un tiers" default was merged into an old list. */
    val moyensPaiementV2: Boolean = false,
    /**
     * Coupe le fond des photos de justificatif : seule la zone du ticket est
     * conservée. Désactivable dans le paramétrage.
     */
    val recadrageAuto: Boolean = true,
) {
    val verrouillageActif: Boolean get() = codeAccesHash.isNotBlank()

    val categorie: CategorieVehicule get() = runCatching { CategorieVehicule.valueOf(categorieVehicule) }
        .getOrDefault(CategorieVehicule.VOITURE)

    /** "Fabrice Heuvrard" or the company when there is one. */
    val identite: String
        get() = listOf(prenom, nom).filter { it.isNotBlank() }.joinToString(" ")

    val mention: String
        get() = when {
            societe.isNotBlank() && identite.isNotBlank() -> "$societe · $identite"
            societe.isNotBlank() -> societe
            identite.isNotBlank() -> identite
            else -> "Notes de frais"
        }

    fun toJson(): JSONObject = JSONObject().apply {
        put("nom", nom)
        put("prenom", prenom)
        put("societe", societe)
        put("adresse", adresse)
        put("siret", siret)
        put("emailDestinataire", emailDestinataire)
        put("plaque", plaque)
        put("categorieVehicule", categorieVehicule)
        put("puissanceFiscale", puissanceFiscale)
        put("electrique", electrique)
        put("kmAnnuels", kmAnnuels)
        put("deviseDefaut", deviseDefaut)
        put("tvaDefautBp", tvaDefautBp)
        put("zoneParis", zoneParis)
        put("codeAccesHash", codeAccesHash)
        put("moyensPaiement", JSONArray(moyensPaiement))
        put("moyenPaiementDefaut", moyenPaiementDefaut)
        put("paiementRempliAuto", paiementRempliAuto)
        put("pieceEnGrand", pieceEnGrand)
        put("pieceEnGrandV2", pieceEnGrandV2)
        put("profilCourant", profilCourant)
        put("moyensPaiementV2", moyensPaiementV2)
        put("recadrageAuto", recadrageAuto)
    }

    companion object {
        /** Enough to be usable on the first launch; renamed in the settings. */
        val MOYENS_PAIEMENT_DEFAUT = listOf("CB", "Espèces", "Payé par un tiers")

        fun depuisJson(json: JSONObject): Parametres = Parametres(
            nom = json.optString("nom", ""),
            prenom = json.optString("prenom", ""),
            societe = json.optString("societe", ""),
            adresse = json.optString("adresse", ""),
            siret = json.optString("siret", ""),
            emailDestinataire = json.optString("emailDestinataire", ""),
            plaque = json.optString("plaque", ""),
            categorieVehicule = json.optString("categorieVehicule", CategorieVehicule.VOITURE.name),
            puissanceFiscale = json.optInt("puissanceFiscale", 5),
            electrique = json.optBoolean("electrique", false),
            kmAnnuels = json.optInt("kmAnnuels", 0),
            deviseDefaut = json.optString("deviseDefaut", "EUR"),
            tvaDefautBp = json.optInt("tvaDefautBp", 2000),
            zoneParis = json.optBoolean("zoneParis", false),
            codeAccesHash = json.optString("codeAccesHash", ""),
            moyensPaiement = json.optJSONArray("moyensPaiement")?.let { tableau ->
                (0 until tableau.length())
                    .mapNotNull { tableau.optString(it).trim().takeIf(String::isNotEmpty) }
                    .distinct()
            } ?: MOYENS_PAIEMENT_DEFAUT,
            moyenPaiementDefaut = json.optString("moyenPaiementDefaut", MOYENS_PAIEMENT_DEFAUT.first()),
            paiementRempliAuto = json.optBoolean("paiementRempliAuto", false),
            pieceEnGrand = json.optBoolean("pieceEnGrand", true),
            pieceEnGrandV2 = json.optBoolean("pieceEnGrandV2", false),
            profilCourant = json.optLong("profilCourant", 0L),
            moyensPaiementV2 = json.optBoolean("moyensPaiementV2", false),
            recadrageAuto = json.optBoolean("recadrageAuto", true),
        )
    }
}

class ParametresStore(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences(FICHIER, Context.MODE_PRIVATE)

    fun lire(): Parametres {
        val brut = prefs.getString(CLE, null) ?: return Parametres()
        return runCatching { Parametres.depuisJson(JSONObject(brut)) }.getOrDefault(Parametres())
    }

    fun ecrire(parametres: Parametres) {
        prefs.edit().putString(CLE, parametres.toJson().toString()).apply()
    }

    /** Used by the restore path, which replaces the whole settings blob. */
    fun ecrireJson(brut: String) {
        prefs.edit().putString(CLE, brut).apply()
    }

    fun lireJson(): String? = prefs.getString(CLE, null)

    companion object {
        private const val FICHIER = "parametres"
        private const val CLE = "parametres"
    }
}
