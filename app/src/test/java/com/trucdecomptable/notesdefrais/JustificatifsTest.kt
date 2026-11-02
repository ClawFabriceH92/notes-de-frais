package com.trucdecomptable.notesdefrais

import com.trucdecomptable.notesdefrais.data.Depense
import com.trucdecomptable.notesdefrais.data.Justificatifs
import com.trucdecomptable.notesdefrais.data.TypeDepense
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le seuil de 150 € HT : au-delà, un repas au restaurant ne se justifie plus
 * par un ticket mais par une facture nominative.
 */
class JustificatifsTest {

    private fun repas(
        montantCentimes: Long,
        type: TypeDepense = TypeDepense.RESTAURANT_MIDI_INVITE,
        tvaTauxBp: Int? = null,
        devise: String = "EUR",
        tauxChange: Double? = null,
    ) = Depense(
        projetId = 1,
        date = "2026-11-02",
        montantCentimes = montantCentimes,
        devise = devise,
        tauxChange = tauxChange,
        type = type.name,
        tvaTauxBp = tvaTauxBp,
    )

    @Test
    fun `sous le seuil avec TVA connue, le ticket suffit`() {
        // 120,00 € TTC à 10 % = 109,09 € HT
        val d = repas(12000, tvaTauxBp = 1000)
        assertFalse(Justificatifs.factureObligatoire(d))
        assertNull(Justificatifs.alerte(d))
    }

    @Test
    fun `juste au-dessus de 150 euros HT, la facture est exigee`() {
        // 180,00 € TTC à 10 % = 163,64 € HT
        val d = repas(18000, tvaTauxBp = 1000)
        assertTrue(Justificatifs.factureObligatoire(d))
        assertNotNull(Justificatifs.alerte(d))
    }

    @Test
    fun `le seuil se lit hors taxes, pas sur le TTC`() {
        // 160,00 € TTC à 20 % = 133,33 € HT : reste sous le seuil
        val d = repas(16000, tvaTauxBp = 2000)
        assertEquals(13333L, Justificatifs.montantHtEurCentimes(d))
        assertFalse(Justificatifs.factureObligatoire(d))
    }

    @Test
    fun `pile 150 euros HT reste sous le seuil`() {
        // 180,00 € TTC à 20 % = 150,00 € HT : le seuil n'est pas dépassé
        val d = repas(18000, tvaTauxBp = 2000)
        assertEquals(15000L, Justificatifs.montantHtEurCentimes(d))
        assertFalse(Justificatifs.factureObligatoire(d))
    }

    @Test
    fun `sans ventilation de TVA, le TTC est retenu`() {
        // Pas de taux saisi : on compare le TTC, donc l'alerte arrive plus tôt
        val d = repas(16000)
        assertEquals(16000L, Justificatifs.montantHtEurCentimes(d))
        assertTrue(Justificatifs.factureObligatoire(d))
    }

    @Test
    fun `un autre type de depense n'est jamais concerne`() {
        val hotel = repas(45000, type = TypeDepense.HOTEL, tvaTauxBp = 1000)
        assertFalse(Justificatifs.factureObligatoire(hotel))
        assertNull(Justificatifs.alerte(hotel))
    }

    @Test
    fun `les trois types de repas sont couverts`() {
        TypeDepense.entries
            .filter { it.slug.startsWith("repas") || it == TypeDepense.RESTAURANT_SOIR }
            .forEach { type ->
                assertTrue(
                    "$type devrait déclencher la facture",
                    Justificatifs.factureObligatoire(repas(30000, type = type)),
                )
            }
    }

    @Test
    fun `une depense en devise est ramenee en euros`() {
        // 200,00 USD à 1,10 USD pour 1 € = 181,82 € TTC, sans TVA saisie
        val d = repas(20000, devise = "USD", tauxChange = 1.10)
        assertEquals(18182L, Justificatifs.montantHtEurCentimes(d))
        assertTrue(Justificatifs.factureObligatoire(d))
    }

    @Test
    fun `sans parite, le seuil ne peut pas etre apprecie`() {
        val d = repas(20000, devise = "USD", tauxChange = null)
        assertNull(Justificatifs.montantHtEurCentimes(d))
        assertFalse(Justificatifs.factureObligatoire(d))
    }

    @Test
    fun `le message cite le montant HT et le seuil`() {
        val message = Justificatifs.alerte(repas(18000, tvaTauxBp = 1000))
        assertNotNull(message)
        assertTrue(message!!.contains("163,64 € HT"))
        assertTrue(message.contains("150 € HT"))
        assertTrue(message.contains("facture"))
    }
}
