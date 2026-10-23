package com.trucdecomptable.notesdefrais

import com.trucdecomptable.notesdefrais.data.TypeDepense
import com.trucdecomptable.notesdefrais.ocr.ReceiptParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

class ReceiptParserTest {

    private val jourTicket = LocalDate.now().minusDays(3)
    private val jour = "%02d".format(jourTicket.dayOfMonth)
    private val mois = "%02d".format(jourTicket.monthValue)
    private val annee = jourTicket.year
    private val recent = jourTicket.toString()

    @Test
    fun `restaurant du midi - total ttc et heure`() {
        val texte = """
            BRASSERIE DU MARCHE
            12 rue des Halles
            Ticket 4521
            le $jour/$mois/$annee a 12:34
            Menu du jour      18,50
            Cafe               2,50
            TOTAL TTC         21,00
            TVA 10%            1,91
            Especes           30,00
            Rendu              9,00
        """.trimIndent()

        val champs = ReceiptParser.parse(texte)

        assertEquals(2100L, champs.montantCentimes)
        assertEquals(recent, champs.date)
        assertEquals(TypeDepense.RESTAURANT_MIDI, champs.type)
        assertEquals(12, champs.heure)
        assertEquals(1000, champs.tvaTauxBp)
        assertEquals("EUR", champs.devise)
        assertTrue(champs.complet)
    }

    @Test
    fun `restaurant du soir - heure tardive`() {
        val texte = """
            LE BISTROT
            $jour/$mois/$annee 20:15
            Plat du jour      22,00
            TOTAL             22,00
        """.trimIndent()

        val champs = ReceiptParser.parse(texte)

        assertEquals(TypeDepense.RESTAURANT_SOIR, champs.type)
        assertEquals(2200L, champs.montantCentimes)
    }

    @Test
    fun `hotel - montant avec separateur de milliers`() {
        val texte = """
            IBIS PARIS BERCY
            Chambre 412
            Date arrivee $jour/$mois/$annee
            2 nuitees
            TOTAL A PAYER  1 248,50
        """.trimIndent()

        val champs = ReceiptParser.parse(texte)

        assertEquals(124850L, champs.montantCentimes)
        assertEquals(TypeDepense.HOTEL, champs.type)
    }

    @Test
    fun `train SNCF - montant ttc et tva`() {
        val texte = """
            SNCF CONNECT
            Billet TGV Paris - Lyon
            $jour/$mois/$annee
            Voiture 12 - Place 45
            MONTANT TTC        89,00
            TVA 10 %            8,09
        """.trimIndent()

        val champs = ReceiptParser.parse(texte)

        assertEquals(8900L, champs.montantCentimes)
        assertEquals(TypeDepense.TRAIN, champs.type)
        assertEquals(1000, champs.tvaTauxBp)
    }

    @Test
    fun `avion - carte d embarquement`() {
        val texte = """
            AIR FRANCE
            BOARDING PASS
            Flight AF1234
            $jour/$mois/$annee
            TOTAL EUR       245,60
        """.trimIndent()

        val champs = ReceiptParser.parse(texte)

        assertEquals(24560L, champs.montantCentimes)
        assertEquals(TypeDepense.AVION, champs.type)
    }

    @Test
    fun `taxi - montant avec point decimal`() {
        val texte = """
            UBER
            $jour/$mois/$annee 08:12
            Course 1 : 14.90
            TOTAL 14.90
        """.trimIndent()

        val champs = ReceiptParser.parse(texte)

        assertEquals(1490L, champs.montantCentimes)
        assertEquals(TypeDepense.TAXI, champs.type)
        assertEquals("EUR", champs.devise)
    }

    @Test
    fun `devise etrangere detectee`() {
        val texte = """
            HOTEL ZURICH
            $jour/$mois/$annee
            TOTAL CHF 312,40
        """.trimIndent()

        val champs = ReceiptParser.parse(texte)

        assertEquals(31240L, champs.montantCentimes)
        assertEquals("CHF", champs.devise)
        assertEquals(TypeDepense.HOTEL, champs.type)
    }

    @Test
    fun `ticket illisible - ni date ni montant`() {
        val champs = ReceiptParser.parse("photo floue sans texte exploitable")

        assertNull(champs.montantCentimes)
        assertNull(champs.date)
        assertTrue(!champs.complet)
    }

    @Test
    fun `le plus gros montant gagne quand rien n est libelle`() {
        val texte = """
            QUIOSQUE
            $jour/$mois/$annee
            Boisson 3,50
            Sandwich 7,90
        """.trimIndent()

        val champs = ReceiptParser.parse(texte)

        assertEquals(790L, champs.montantCentimes)
        assertNotNull(champs.date)
    }

    @Test
    fun `ligne total tva ne remplace pas le total`() {
        val texte = """
            RESTAURANT LE ROI
            $jour/$mois/$annee
            Plats              87,50
            TOTAL TVA           8,75
            TOTAL              96,25
        """.trimIndent()

        val champs = ReceiptParser.parse(texte)

        assertEquals(9625L, champs.montantCentimes)
    }

    @Test
    fun `date au format texte francais`() {
        val texte = """
            $jour octobre $annee
            RESTAURANT
            TOTAL 45,00
        """.trimIndent()

        val champs = ReceiptParser.parse(texte)

        assertEquals(10, ReceiptParser.moisDe(champs.date ?: "")?.monthValue ?: -1)
        assertEquals(4500L, champs.montantCentimes)
    }

    @Test
    fun `TVA absente reste nulle`() {
        val champs = ReceiptParser.parse("TOTAL 12,00")
        assertNull(champs.tvaTauxBp)
    }

    @Test
    fun `montants divers se lisent correctement`() {
        assertEquals(2450L, ReceiptParser.montantDe("24,50"))
        assertEquals(2450L, ReceiptParser.montantDe("24.50"))
        assertEquals(124850L, ReceiptParser.montantDe("1 248,50"))
        assertEquals(124850L, ReceiptParser.montantDe("1.248,50"))
        assertEquals(124850L, ReceiptParser.montantDe("1,248.50"))
        assertNull(ReceiptParser.montantDe("12/10/2026"))
    }
}
