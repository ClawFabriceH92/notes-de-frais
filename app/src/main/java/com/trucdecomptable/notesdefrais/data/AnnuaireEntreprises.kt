package com.trucdecomptable.notesdefrais.data

import com.trucdecomptable.notesdefrais.util.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Annuaire des entreprises (API publique de l'État, sans clé) : à partir d'un
 * SIRET saisi, retrouver la raison sociale et l'adresse du siège, plutôt que de
 * les faire taper à la main.
 */
object AnnuaireEntreprises {

    private const val BASE = "https://recherche-entreprises.api.gouv.fr/search?q="

    data class Info(val nom: String, val adresse: String, val siret: String)

    private val client by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()
    }

    /** 14 chiffres et clé de Luhn valide : inutile d'interroger l'API sinon. */
    fun valide(siret: String): Boolean {
        val chiffres = siret.filter { it.isDigit() }
        if (chiffres.length != 14) return false
        var somme = 0
        chiffres.forEachIndexed { index, caractere ->
            var valeur = caractere - '0'
            // Position paire en partant de la droite (index impair depuis la gauche).
            if ((chiffres.length - index) % 2 == 0) {
                valeur *= 2
                if (valeur > 9) valeur -= 9
            }
            somme += valeur
        }
        return somme % 10 == 0
    }

    suspend fun chercher(siret: String): Info? = withContext(Dispatchers.IO) {
        val chiffres = siret.filter { it.isDigit() }
        if (chiffres.length !in 9..14) return@withContext null
        runCatching {
            // Le SIRET exact d'abord ; à défaut le SIREN, puis on garde le siège.
            lire(chiffres) ?: if (chiffres.length == 14) lire(chiffres.take(9)) else null
        }.onFailure { Diag.record("annuaire", it) }.getOrNull()
    }

    private fun lire(recherche: String): Info? {
        val requete = Request.Builder().url(BASE + recherche).build()
        client.newCall(requete).execute().use { reponse ->
            if (!reponse.isSuccessful) return null
            val corps = reponse.body?.string().orEmpty()
            if (corps.isBlank()) return null
            val resultats = JSONObject(corps).optJSONArray("results") ?: return null
            if (resultats.length() == 0) return null
            val premier = resultats.getJSONObject(0)
            val nom = premier.optString("nom_complet").ifBlank {
                premier.optString("nom_raison_sociale")
            }
            val siege = premier.optJSONObject("siege")
            val adresse = siege?.optString("adresse").orEmpty()
            val siretTrouve = siege?.optString("siret").orEmpty()
            if (nom.isBlank()) return null
            return Info(nom = nom, adresse = adresse, siret = siretTrouve)
        }
    }
}
