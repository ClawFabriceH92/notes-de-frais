package com.trucdecomptable.notesdefrais.util

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Les dates sont stockées en ISO (yyyy-MM-dd, tri naturel, noms de fichiers) mais
 * saisies et affichées en français (jj/mm/aaaa), comme Fabrice les lit.
 */
object Dates {

    private val FR: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private val FR_COURT: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yy")
    private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE

    /** "2026-10-12" -> "12/10/2026", laisse intact ce qui n'est pas une date ISO. */
    fun isoVersFr(iso: String): String =
        runCatching { LocalDate.parse(iso.trim(), ISO).format(FR) }.getOrDefault(iso)

    fun aujourdhuiFr(): String = LocalDate.now().format(FR)

    fun aujourdhuiIso(): String = LocalDate.now().format(ISO)

    /**
     * Saisie libre -> ISO. Accepte jj/mm/aaaa, jj-mm-aaaa, jj.mm.aaaa,
     * jj/mm/aa et l'ISO. Rend null si la date est incomplète ou impossible
     * (31/02), pour que le formulaire puisse refuser d'enregistrer.
     */
    fun frVersIso(texte: String): String? {
        val propre = texte.trim()
        if (propre.isEmpty()) return null
        runCatching { return LocalDate.parse(propre, ISO).format(ISO) }
        val morceaux = propre.split('/', '-', '.', ' ').filter { it.isNotBlank() }
        if (morceaux.size != 3) return null
        val jour = morceaux[0].toIntOrNull() ?: return null
        val mois = morceaux[1].toIntOrNull() ?: return null
        var annee = morceaux[2].toIntOrNull() ?: return null
        if (annee < 100) annee += 2000
        return runCatching { LocalDate.of(annee, mois, jour).format(ISO) }.getOrNull()
    }

    /** Vrai dès que le texte ressemble à une date complète et valide. */
    fun estValide(texte: String): Boolean = frVersIso(texte) != null

    /** Pour les tris et les filtres de recherche : la date affichée, tolérante. */
    fun affichage(dateStockee: String): String =
        if (dateStockee.matches(Regex("""\d{4}-\d{2}-\d{2}"""))) isoVersFr(dateStockee) else dateStockee
}
