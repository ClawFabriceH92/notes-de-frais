package com.trucdecomptable.notesdefrais.data

import android.content.Context
import com.trucdecomptable.notesdefrais.util.Diag
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/**
 * Exchange rates from the Banque de France (BCE series), published by Fabrice's
 * own site: https://trucdecomptable.fr/taux-devises-bdf.json
 *
 * Parité = how many units of the currency 1 EUR buys, so
 * montant en euros = montant en devise / parité.
 *
 * The parser and the date logic are pure so they can be tested off-device.
 */
object TauxBdf {

    const val URL = "https://trucdecomptable.fr/taux-devises-bdf.json"
    const val SOURCE = "Banque de France"

    /** A rate found for (or just before) a given day. */
    data class Taux(
        val valeur: Double,
        /** Day the rate is published for, ISO. */
        val date: String,
        val devise: String,
    ) {
        /** How many days before the expense the rate had to be taken. */
        fun ecartJours(dateDepense: String): Long? = runCatching {
            abs(ChronoUnit.DAYS.between(LocalDate.parse(date), LocalDate.parse(dateDepense)))
        }.getOrNull()

        /** "1 € = 1,1205 USD (parité du 22/10/2026)". */
        fun libelle(): String {
            val valeur = String.format(java.util.Locale.FRANCE, "%.4f", this.valeur).trimEnd('0').trimEnd(',')
            val jour = runCatching {
                LocalDate.parse(date).format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))
            }.getOrDefault(date)
            return "1 € = $valeur $devise (parité du $jour)"
        }
    }

    /** Reads {date: {DEVISE: parité}} into a map. Empty on malformed input. */
    fun parser(json: String): Map<String, Map<String, Double>> {
        val racine = runCatching { JSONObject(json) }.getOrNull() ?: return emptyMap()
        val resultat = HashMap<String, Map<String, Double>>(racine.length())
        val dates = racine.keys()
        while (dates.hasNext()) {
            val jour = dates.next()
            val valeurs = racine.optJSONObject(jour) ?: continue
            val devises = HashMap<String, Double>(valeurs.length())
            val codes = valeurs.keys()
            while (codes.hasNext()) {
                val code = codes.next()
                val valeur = valeurs.optDouble(code, Double.NaN)
                if (!valeur.isNaN() && valeur > 0.0) devises[code.uppercase()] = valeur
            }
            if (devises.isNotEmpty()) resultat[jour] = devises
        }
        return resultat
    }

    /**
     * Rate for [dateIso], falling back to the last published day before it.
     * A weekend expense takes Friday's rate, which is what the Banque de France
     * itself prescribes; beyond [fenetreJours] we prefer no rate to a stale one.
     */
    fun chercher(
        parDate: Map<String, Map<String, Double>>,
        dateIso: String,
        devise: String,
        fenetreJours: Int = 7,
    ): Taux? {
        if (devise.equals("EUR", ignoreCase = true)) return Taux(1.0, dateIso, "EUR")
        val cible = runCatching { LocalDate.parse(dateIso) }.getOrNull() ?: return null
        val code = devise.uppercase()
        val limite = cible.minusDays(fenetreJours.toLong())

        var meilleur: Taux? = null
        for (jour in parDate.keys) {
            val d = runCatching { LocalDate.parse(jour) }.getOrNull() ?: continue
            if (d.isAfter(cible) || d.isBefore(limite)) continue
            val valeur = parDate[jour]?.get(code) ?: continue
            if (meilleur == null || jour > meilleur.date) meilleur = Taux(valeur, jour, code)
        }
        return meilleur
    }
}

/**
 * Downloads the rate file once and keeps it on disk. The phone may be offline
 * for days; a rate older than [MAX_AGE_HEURES] is refreshed on the next launch
 * but never blocks the app.
 */
class TauxBdfClient(private val context: Context) {

    companion object {
        const val MAX_AGE_HEURES = 20L
        private const val DOSSIER = "taux"
        private const val FICHIER = "taux-bdf.json"

        @Volatile
        private var cache: Map<String, Map<String, Double>>? = null
    }

    private fun fichier(): File = File(File(context.filesDir, DOSSIER).apply { mkdirs() }, FICHIER)

    /** True when the local copy is missing or older than [MAX_AGE_HEURES]. */
    fun perime(): Boolean {
        val f = fichier()
        if (!f.exists() || f.length() == 0L) return true
        val age = System.currentTimeMillis() - f.lastModified()
        return age > MAX_AGE_HEURES * 3_600_000L
    }

    suspend fun rafraichir(force: Boolean = false): Boolean = withContext(Dispatchers.IO) {
        if (!force && !perime()) return@withContext false
        val destination = fichier()
        val temporaire = File(destination.parentFile, "$FICHIER.tmp")
        runCatching {
            val connexion = URL(TauxBdf.URL).openConnection() as HttpURLConnection
            try {
                connexion.connectTimeout = 15_000
                connexion.readTimeout = 45_000
                connexion.instanceFollowRedirects = true
                connexion.setRequestProperty("User-Agent", "NotesDeFrais/taux")
                if (connexion.responseCode != HttpURLConnection.HTTP_OK) {
                    error("HTTP ${connexion.responseCode}")
                }
                connexion.inputStream.use { entree ->
                    temporaire.outputStream().use { sortie -> entree.copyTo(sortie) }
                }
            } finally {
                connexion.disconnect()
            }
            if (temporaire.length() < 1000L) error("fichier de taux trop petit")
            if (destination.exists()) destination.delete()
            if (!temporaire.renameTo(destination)) error("renommage impossible")
            cache = null
            Diag.record("taux", "parités Banque de France mises à jour (${destination.length() / 1024} Ko)")
            true
        }.getOrElse { e ->
            Diag.record("taux", e)
            temporaire.delete()
            false
        }
    }

    /** In-memory, loaded once from disk; empty when nothing usable is cached. */
    fun parDate(): Map<String, Map<String, Double>> {
        cache?.let { return it }
        val f = fichier()
        if (!f.exists()) return emptyMap()
        val charge = runCatching { TauxBdf.parser(f.readText()) }.getOrDefault(emptyMap())
        cache = charge
        return charge
    }

    fun taux(dateIso: String, devise: String): TauxBdf.Taux? {
        if (devise.equals("EUR", ignoreCase = true)) return TauxBdf.Taux(1.0, dateIso, "EUR")
        return TauxBdf.chercher(parDate(), dateIso, devise)
    }

    /** Oldest and newest published day, for the settings line. */
    fun couverture(): Pair<String, String>? {
        val jours = parDate().keys
        val minimum = jours.minOrNull() ?: return null
        val maximum = jours.maxOrNull() ?: return null
        return minimum to maximum
    }
}
