package com.trucdecomptable.notesdefrais.ocr

import com.trucdecomptable.notesdefrais.data.TypeDepense
import java.time.LocalDate
import java.time.YearMonth
import kotlin.math.abs

/**
 * Turns raw ML Kit text into the four fields the app cares about: date, TTC
 * amount, expense type, and (when printed) the VAT rate.
 *
 * Everything here is pure Kotlin so it can be unit tested without a phone, and
 * so a mis-read ticket is reproducible from the log instead of a mystery.
 */
object ReceiptParser {

    data class Champs(
        /** ISO yyyy-MM-dd. */
        val date: String? = null,
        val montantCentimes: Long? = null,
        val devise: String = "EUR",
        val type: TypeDepense? = null,
        /** 0..23 when a time is printed on the ticket. */
        val heure: Int? = null,
        /** VAT in basis points, 2000 = 20 %. */
        val tvaTauxBp: Int? = null,
        val libelle: String = "",
        /** Gares lues sur un billet de train ou d'avion (vides sinon). */
        val gareDepart: String = "",
        val gareArrivee: String = "",
    ) {
        /** True when the app can save without asking anything. */
        val complet: Boolean get() = date != null && montantCentimes != null
    }

    private const val MAX_AGE_DAYS = 365L * 6

    /** Amounts such as 12,50 / 1 234,56 / 1.234,56 / 1,234.56 / 12.50 */
    private val MONTANT = Regex("""(?<![\d/.-])(\d{1,3}(?:[ .\u00a0]\d{3})+|\d+)\s*[.,](\d{2})(?![\d/])""")

    private val DATE_NUM = Regex("""(?<!\d)(\d{1,2})\s*[/.\-]\s*(\d{1,2})\s*[/.\-]\s*(\d{2,4})(?!\d)""")
    private val DATE_ISO = Regex("""(?<!\d)(\d{4})-(\d{2})-(\d{2})(?!\d)""")
    private val DATE_TEXTE = Regex(
        """(?<!\d)(\d{1,2})\s*(janv|fevr|févr|mars|avr|mai|juin|juil|aout|août|sept|oct|nov|dec|déc)[a-zéû.]*\s*(\d{2,4})(?!\d)""",
        RegexOption.IGNORE_CASE,
    )

    private val HEURE = Regex("""(?<!\d)([01]?\d|2[0-3])\s*[:hH]\s*(\d{2})(?!\d)""")
    private val TVA = Regex("""T\.?V\.?A[^0-9%]{0,12}(\d{1,2}(?:[.,]\d{1,2})?)\s*%""", RegexOption.IGNORE_CASE)

    /** Lines carrying the amount actually paid, most trustworthy first. */
    private val TOTAUX = listOf(
        Regex("""NET\s*(?:A|À)\s*PAYER""") to 100,
        Regex("""TOTAL\s*T\.?T\.?C""") to 98,
        Regex("""MONTANT\s*T\.?T\.?C""") to 96,
        Regex("""TOTAL\s*(?:A|À)\s*PAYER""") to 95,
        Regex("""(?:A|À)\s*REGLER""") to 90,
        Regex("""TOTAL""") to 80,
        Regex("""(?:A|À)\s*PAYER""") to 70,
        Regex("""MONTANT""") to 60,
        Regex("""SOMME""") to 50,
    )

    /** Lines that carry a partial amount, never the one paid. */
    private val PARTIELS = Regex("""\b(HT|TVA|T\.V\.A|H\.T|REMISE|RENDU|ESPECES|ESPÈCES|MONNAIE|POURBOIRE|SERVICE)\b""")

    fun parse(texte: String): Champs {
        val brut = texte.replace('\u00a0', ' ')
        val haut = sansAccents(brut).uppercase()

        val heure = heure(haut)
        val type = type(haut, heure)
        val (gareDepart, gareArrivee) = gares(brut, type)
        return Champs(
            date = date(brut)?.toString(),
            montantCentimes = montant(haut, brut),
            devise = devise(haut),
            type = type,
            heure = heure,
            tvaTauxBp = tva(haut),
            libelle = libelle(brut, type),
            gareDepart = gareDepart,
            gareArrivee = gareArrivee,
        )
    }

    // ---------------------------------------------------------------- montant

    private fun montant(haut: String, brut: String): Long? {
        var meilleur: Pair<Int, Long>? = null
        for (ligne in brut.lines()) {
            val h = sansAccents(ligne).uppercase()
            val score = TOTAUX.firstOrNull { it.first.containsMatchIn(h) }?.second ?: continue
            // "TOTAL TVA" / "TOTAL HT" print a slice of the bill, not the bill.
            val ttc = h.contains("TTC") || h.contains("T.T.C")
            if (!ttc && PARTIELS.containsMatchIn(h)) continue
            val valeur = dernierMontant(ligne) ?: continue
            if (meilleur == null || score > meilleur.first) meilleur = score to valeur
        }
        if (meilleur != null) return meilleur.second

        // Nothing labelled: the largest amount on the ticket is the best guess.
        val tous = MONTANT.findAll(brut).mapNotNull { montantDe(it.value) }.toList()
        return tous.maxOrNull()
    }

    private fun dernierMontant(ligne: String): Long? =
        MONTANT.findAll(ligne).mapNotNull { montantDe(it.value) }.lastOrNull()

    /**
     * Reads one amount token. The decimal separator is the LAST separator in
     * the token, which is what both "1.234,56" and "1,234.56" obey.
     */
    fun montantDe(token: String): Long? {
        val propre = token.replace(" ", "").replace("\u00a0", "").trim()
        val dernierPoint = maxOf(propre.lastIndexOf('.'), propre.lastIndexOf(','))
        if (dernierPoint < 0) return null
        val entier = propre.substring(0, dernierPoint).filter { it.isDigit() }
        val decimales = propre.substring(dernierPoint + 1).filter { it.isDigit() }
        if (decimales.length != 2 || entier.isEmpty()) return null
        val euros = entier.toLongOrNull() ?: return null
        val cents = decimales.toLongOrNull() ?: return null
        return euros * 100 + cents
    }

    // ------------------------------------------------------------------- date

    private fun date(brut: String): LocalDate? {
        val candidats = mutableListOf<LocalDate>()

        DATE_ISO.findAll(brut).forEach { m ->
            val (a, mo, j) = m.destructured
            candidats += LocalDate.of(a.toInt(), mo.toInt(), j.toInt())
        }
        DATE_NUM.findAll(brut).forEach { m ->
            val (j, mo, a) = m.destructured
            val annee = quandAnnee(a.toInt())
            val jour = j.toInt()
            val mois = mo.toInt()
            // French tickets are day-first; a "month" above 12 proves it.
            if (mois in 1..12 && jour in 1..31) candidats += LocalDate.of(annee, mois, jour)
            else if (jour in 1..12 && mois in 1..31) candidats += LocalDate.of(annee, jour, mois)
        }
        DATE_TEXTE.findAll(brut).forEach { m ->
            val (j, nomMois, a) = m.destructured
            val mois = numeroMois(nomMois) ?: return@forEach
            val annee = quandAnnee(a.toInt())
            candidats += LocalDate.of(annee, mois, j.toInt())
        }

        val aujourdhui = LocalDate.now()
        val plausibles = candidats.filter { !it.isAfter(aujourdhui.plusDays(1)) && it.isAfter(aujourdhui.minusDays(MAX_AGE_DAYS)) }
        // A recent ticket is what the user is photographing: take the most recent.
        return (plausibles.ifEmpty { candidats }).maxOrNull()
    }

    private fun quandAnnee(valeur: Int): Int = when {
        valeur > 999 -> valeur
        valeur >= 70 -> 1900 + valeur
        else -> 2000 + valeur
    }

    private fun numeroMois(nom: String): Int? {
        val n = sansAccents(nom).lowercase().take(4)
        return when {
            n.startsWith("janv") -> 1
            n.startsWith("fevr") -> 2
            n.startsWith("mars") -> 3
            n.startsWith("avri") -> 4
            n.startsWith("mai") -> 5
            n.startsWith("juin") -> 6
            n.startsWith("juil") -> 7
            n.startsWith("aout") -> 8
            n.startsWith("sept") -> 9
            n.startsWith("octo") || n.startsWith("oct") -> 10
            n.startsWith("nove") -> 11
            n.startsWith("dece") -> 12
            else -> null
        }
    }

    // ------------------------------------------------------------------ heure

    private fun heure(haut: String): Int? =
        HEURE.findAll(haut).mapNotNull { m ->
            val h = m.groupValues[1].toIntOrNull() ?: return@mapNotNull null
            val mn = m.groupValues[2].toIntOrNull() ?: return@mapNotNull null
            if (mn in 0..59 && h in 0..23) h else null
        }.firstOrNull { it in 6..23 }

    // ------------------------------------------------------------------- type

    private fun type(haut: String, heure: Int?): TypeDepense? {
        fun contient(vararg mots: String) = mots.any { haut.contains(it) }

        return when {
            contient("BOARDING", "EMBARQUEMENT", "AIR FRANCE", "EASYJET", "RYANAIR", "TRANSAVIA",
                "LUFTHANSA", "AIRLINE", "FLIGHT", "AEROPORT", "AÉROPORT", "BAGAGE", "GATE ")
                -> TypeDepense.AVION

            contient("HOTEL", "HÔTEL", "NUITEE", "NUITÉE", "BOOKING", "CHAMBRE", "SEJOUR", "SÉJOUR",
                "IBIS", "NOVOTEL", "MERCURE", "AIRBNB", "PENSION", "CHECK OUT", "CHECK-OUT")
                -> TypeDepense.HOTEL

            contient("SNCF", "TGV", "INOUI", "OUIGO", "EUROSTAR", "THALYS", "ITINERIS",
                "BILLET DE TRAIN", "GARE", "VOITURE ", "PLACE NUMERO", "PLACE NUMÉRO")
                -> TypeDepense.TRAIN

            contient("TAXI", "UBER", "BOLT", "VTC", "G7 ", "CHAUFFEUR", "COURSE ", "MARCEL ")
                -> TypeDepense.TAXI

            contient("RESTAURANT", "BRASSERIE", "BISTROT", "RESTO", "PIZZERIA", "SUSHI", "MENU",
                "REPAS", "RÉPAS", "COUVERT", "SERVEUR", "TABLE ", "ENTRECOTE", "ENTRECÔTE", "PLAT ",
                "DESSERT", "CAFE ", "CAFÉ ")
                -> if (heure != null && heure >= 16) TypeDepense.RESTAURANT_SOIR else TypeDepense.RESTAURANT_MIDI_SEUL

            // A plain "TRAIN"/"AVION" mention is weaker evidence but still evidence.
            contient("TRAIN", "BILLET") -> TypeDepense.TRAIN
            contient("AVION", "VOL ") -> TypeDepense.AVION
            else -> null
        }
    }

    // ------------------------------------------------------------------ gares

    /** Ce qu'un billet imprime entre les deux villes d'un trajet. */
    private val FLECHES = listOf("→", "⟶", "->", "=>", "»", "›", " > ")

    /** Libellés qui introduisent une gare, du plus explicite au plus vague. */
    private val MOTS_DEPART = listOf("GARE DE DEPART", "GARE DEPART", "DEPART", "ORIGINE")
    private val MOTS_ARRIVEE = listOf("GARE D'ARRIVEE", "GARE ARRIVEE", "ARRIVEE", "DESTINATION")

    /** Mots d'un billet qui ne sont jamais un nom de gare. */
    private val BRUIT_GARE = Regex(
        """(?i)\b(billet|sncf|tgv|inoui|ouigo|eurostar|thalys|itineris|ter|place|voiture|classe|prix|total|montant|eur|euros|date|heure|barcode|reference|commande|dossier|aller|retour|simple|aller-retour)\b""",
    )

    /**
     * Gares d'un billet de train (aéroports d'un billet d'avion). Seuls deux
     * cas sont reconnus, parce qu'ils sont sans ambiguïté : « A → B » sur une
     * ligne, ou un libellé DÉPART/ARRIVÉE. Le reste est laissé vide : une gare
     * inventée se retrouverait dans l'export comptable, une gare vide se saisit
     * en trois secondes.
     */
    private fun gares(brut: String, type: TypeDepense?): Pair<String, String> {
        if (type != TypeDepense.TRAIN && type != TypeDepense.AVION) return "" to ""
        val lignes = brut.lines().map { it.trim() }.filter { it.isNotEmpty() }

        for (ligne in lignes) {
            for (fleche in FLECHES) {
                val i = ligne.indexOf(fleche)
                if (i <= 0) continue
                val depart = garePropre(ligne.substring(0, i))
                val arrivee = garePropre(ligne.substring(i + fleche.length))
                if (depart.isNotBlank() && arrivee.isNotBlank()) return depart to arrivee
            }
        }

        var depart = ""
        var arrivee = ""
        for (ligne in lignes) {
            val haut = sansAccents(ligne).uppercase()
            if (depart.isBlank()) {
                depart = valeurApresLibelle(haut, MOTS_DEPART)
            }
            if (arrivee.isBlank()) {
                arrivee = valeurApresLibelle(haut, MOTS_ARRIVEE)
            }
        }
        // Une moitié de trajet ne sert à rien : on rend les deux ou rien.
        return if (depart.isNotBlank() && arrivee.isNotBlank()) depart to arrivee else "" to ""
    }

    private fun valeurApresLibelle(haut: String, libelles: List<String>): String {
        val libelle = libelles.firstOrNull { haut.contains(it) } ?: return ""
        val apres = haut.substring(haut.indexOf(libelle) + libelle.length)
        return garePropre(apres)
    }

    /**
     * Nettoie un morceau de ligne : heures, numéros de voiture et de place,
     * ponctuation. Rend une chaîne vide quand ce qui reste ne ressemble pas à un
     * nom de lieu, ce qui écarte les lignes de montant ou de code-barres.
     */
    fun garePropre(brut: String): String {
        var texte = brut.replace(Regex("""(?<!\d)[0-2]?\d\s*[:hH]\s*\d{2}(?!\d)"""), " ")
        texte = texte.replace(Regex("""\b\d+\b"""), " ")
        texte = texte.replace(Regex("""[^\p{L}'’\-. ]"""), " ")
        texte = texte.replace(Regex("""\s+"""), " ").trim(' ', '.', '-', '\'')
        if (texte.length < 4) return ""
        val lettres = texte.count { it.isLetter() }
        if (lettres < 4 || lettres * 10 < texte.length * 6) return ""
        if (BRUIT_GARE.containsMatchIn(texte)) return ""
        return texte.take(40).trim()
    }

    // ---------------------------------------------------------------- divers

    private fun devise(haut: String): String = when {
        haut.contains("CHF") -> "CHF"
        haut.contains("GBP") || haut.contains("£") -> "GBP"
        haut.contains("USD") || haut.contains("$") -> "USD"
        else -> "EUR"
    }

    private fun tva(haut: String): Int? {
        val m = TVA.find(haut) ?: return null
        val valeur = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        if (valeur <= 0.0 || valeur > 30.0) return null
        return (valeur * 100).toInt()
    }

    /** Issuer name: the first line that looks like a shop name, not boilerplate. */
    private fun libelle(brut: String, type: TypeDepense?): String {
        val bruit = Regex("""(?i)\b(ticket|facture|recu|reçu|justificatif|date|heure|table|caisse|addition|duplicata)\b""")
        val candidat = brut.lines()
            .map { it.trim() }
            .firstOrNull { ligne ->
                ligne.length in 3..60 &&
                    ligne.count { it.isLetter() } >= 3 &&
                    !bruit.containsMatchIn(ligne) &&
                    !ligne.matches(Regex("""[\d .,/:-]+"""))
            }
        return candidat?.take(60) ?: type?.label.orEmpty()
    }

    private fun sansAccents(texte: String): String = buildString {
        for (c in texte) {
            append(
                when (c) {
                    'à', 'â', 'ä', 'á', 'ã', 'å' -> 'a'
                    'é', 'è', 'ê', 'ë' -> 'e'
                    'î', 'ï', 'í', 'ì' -> 'i'
                    'ô', 'ö', 'ó', 'ò', 'õ' -> 'o'
                    'ù', 'û', 'ü', 'ú' -> 'u'
                    'ç' -> 'c'
                    'À', 'Â', 'Ä', 'Á', 'Ã', 'Å' -> 'A'
                    'É', 'È', 'Ê', 'Ë' -> 'E'
                    'Î', 'Ï', 'Í', 'Ì' -> 'I'
                    'Ô', 'Ö', 'Ó', 'Ò', 'Õ' -> 'O'
                    'Ù', 'Û', 'Ü', 'Ú' -> 'U'
                    'Ç' -> 'C'
                    'æ' -> 'a'
                    else -> c
                },
            )
        }
    }

    /** Age of a ticket in days, used to warn about photographs of old receipts. */
    fun ecartJours(dateIso: String, aujourdhui: LocalDate = LocalDate.now()): Long? {
        val d = runCatching { LocalDate.parse(dateIso) }.getOrNull() ?: return null
        return abs(java.time.temporal.ChronoUnit.DAYS.between(d, aujourdhui))
    }

    fun moisDe(dateIso: String): YearMonth? =
        runCatching { YearMonth.from(LocalDate.parse(dateIso)) }.getOrNull()
}
