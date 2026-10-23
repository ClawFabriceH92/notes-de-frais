package com.trucdecomptable.notesdefrais.data

import android.content.Context
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
    }

    companion object {
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
