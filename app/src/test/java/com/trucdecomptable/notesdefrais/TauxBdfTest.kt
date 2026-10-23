package com.trucdecomptable.notesdefrais

import com.trucdecomptable.notesdefrais.data.TauxBdf
import com.trucdecomptable.notesdefrais.data.convertirEnEuros
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Rates come from trucdecomptable.fr (Banque de France / BCE series):
 * parity = how many units of the currency 1 EUR buys.
 */
class TauxBdfTest {

    private val parDate = mapOf(
        "2026-10-22" to mapOf("USD" to 1.1205, "GBP" to 0.8377, "CHF" to 0.9211),
        "2026-10-21" to mapOf("USD" to 1.1234, "GBP" to 0.83825),
        // Friday, then the weekend has no publication at all.
        "2026-10-16" to mapOf("USD" to 1.11, "GBP" to 0.84),
        "2026-10-15" to mapOf("USD" to 1.105, "GBP" to 0.839),
    )

    private val json = """
        {
          "2026-10-22": {"USD": 1.1205, "GBP": 0.8377, "CHF": 0.9211},
          "2026-10-21": {"USD": 1.1234, "GBP": 0.83825}
        }
    """.trimIndent()

    @Test
    fun `le fichier publie par le site est lu`() {
        val lues = TauxBdf.parser(json)
        assertEquals(2, lues.size)
        assertEquals(1.1205, lues.getValue("2026-10-22").getValue("USD"), 0.000001)
        assertEquals(0.8377, lues.getValue("2026-10-22").getValue("GBP"), 0.000001)
    }

    @Test
    fun `JSON invalide ne casse rien`() {
        assertTrue(TauxBdf.parser("<html>404</html>").isEmpty())
        assertTrue(TauxBdf.parser("").isEmpty())
    }

    @Test
    fun `la parite du jour exact est retenue`() {
        val taux = TauxBdf.chercher(parDate, "2026-10-22", "USD")
        assertEquals(1.1205, taux!!.valeur, 0.000001)
        assertEquals("2026-10-22", taux.date)
    }

    @Test
    fun `depense du dimanche - parite du vendredi`() {
        // 2026-10-18 is a Sunday; the last publication is Friday 16 October.
        val taux = TauxBdf.chercher(parDate, "2026-10-18", "USD")
        assertEquals("2026-10-16", taux!!.date)
        assertEquals(1.11, taux.valeur, 0.000001)
        assertEquals(2L, taux.ecartJours("2026-10-18"))
    }

    @Test
    fun `aucune parite avant la fenetre - on prefere rien a un taux perime`() {
        assertNull(TauxBdf.chercher(parDate, "2026-11-20", "USD", fenetreJours = 7))
        // Within a wider window the old rate is still there.
        assertEquals("2026-10-22", TauxBdf.chercher(parDate, "2026-11-20", "USD", fenetreJours = 60)!!.date)
    }

    @Test
    fun `devise absente du fichier`() {
        assertNull(TauxBdf.chercher(parDate, "2026-10-18", "JPY"))
    }

    @Test
    fun `l euro vaut toujours 1`() {
        val taux = TauxBdf.chercher(parDate, "2026-10-18", "EUR")
        assertEquals(1.0, taux!!.valeur, 0.0)
    }

    @Test
    fun `conversion d un montant etranger`() {
        val taux = TauxBdf.chercher(parDate, "2026-10-22", "USD")!!
        // 312,40 USD at 1.1205 = 278,80 EUR
        assertEquals(27880L, convertirEnEuros(31240, taux.valeur))
    }

    @Test
    fun `libelle lisible pour l utilisateur`() {
        val taux = TauxBdf.Taux(1.1205, "2026-10-22", "USD")
        assertEquals("1 € = 1,1205 USD (parité du 22/10/2026)", taux.libelle())
    }
}
