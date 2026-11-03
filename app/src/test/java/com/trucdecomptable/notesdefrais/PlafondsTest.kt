package com.trucdecomptable.notesdefrais

import com.trucdecomptable.notesdefrais.data.Depense
import com.trucdecomptable.notesdefrais.data.Doublons
import com.trucdecomptable.notesdefrais.data.Plafonds
import com.trucdecomptable.notesdefrais.data.SituationRepas
import com.trucdecomptable.notesdefrais.data.TypeDepense
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plafonds d'exonération : le même repas ne vaut pas le même plafond selon la
 * situation (lieu de travail 7,50 €, hors déplacement 10,40 €, grand
 * déplacement 21,40 €), et le numéro de facture sert de doublon fiable.
 */
class PlafondsTest {

    @Test
    fun `repas au lieu de travail - plafond de 7,50 euros`() {
        assertEquals(
            750L,
            Plafonds.plafondCentimes(TypeDepense.RESTAURANT_MIDI_SEUL, false, SituationRepas.LIEU_TRAVAIL),
        )
    }

    @Test
    fun `repas hors deplacement - plafond de 10,40 euros`() {
        assertEquals(
            1040L,
            Plafonds.plafondCentimes(TypeDepense.RESTAURANT_MIDI_SEUL, false, SituationRepas.HORS_DEPLACEMENT),
        )
    }

    @Test
    fun `repas en grand deplacement - plafond de 21,40 euros`() {
        assertEquals(
            2140L,
            Plafonds.plafondCentimes(TypeDepense.RESTAURANT_MIDI_SEUL, false, SituationRepas.GRAND_DEPLACEMENT),
        )
    }

    @Test
    fun `le petit dejeuner garde son plafond quelle que soit la situation`() {
        assertEquals(2140L, Plafonds.plafondCentimes(TypeDepense.PETIT_DEJEUNER, false, SituationRepas.LIEU_TRAVAIL))
        assertEquals(2140L, Plafonds.plafondCentimes(TypeDepense.PETIT_DEJEUNER, true, SituationRepas.HORS_DEPLACEMENT))
    }

    @Test
    fun `l hotel ne depend pas de la situation du repas`() {
        assertEquals(7660L, Plafonds.plafondCentimes(TypeDepense.HOTEL, true, SituationRepas.LIEU_TRAVAIL))
        assertEquals(5680L, Plafonds.plafondCentimes(TypeDepense.HOTEL, false, SituationRepas.HORS_DEPLACEMENT))
    }

    @Test
    fun `un repas de 15 euros au lieu de travail depasse le plafond de 7,50`() {
        val depense = Depense(
            projetId = 1,
            date = "2026-10-10",
            montantCentimes = 1500,
            type = TypeDepense.RESTAURANT_MIDI_SEUL.name,
            situationRepas = SituationRepas.LIEU_TRAVAIL.name,
        )
        val alerte = Plafonds.alerte(depense, false)
        assertNotNull(alerte)
        assertTrue(alerte!!.contains("7,50"))
    }

    @Test
    fun `le meme repas de 15 euros en grand deplacement ne declenche rien`() {
        val depense = Depense(
            projetId = 1,
            date = "2026-10-10",
            montantCentimes = 1500,
            type = TypeDepense.RESTAURANT_MIDI_SEUL.name,
        )
        assertNull(Plafonds.alerte(depense, false))
    }

    @Test
    fun `meme numero de facture sur le meme projet - doublon signale`() {
        val candidate = Depense(
            id = 1,
            projetId = 1,
            date = "2026-10-10",
            montantCentimes = 1000,
            numFacture = " FA-123 ",
        )
        val existante = Depense(
            id = 2,
            projetId = 1,
            date = "2026-10-11",
            montantCentimes = 2000,
            numFacture = "fa-123",
        )
        // Comparaison insensible à la casse et aux espaces de bord.
        assertEquals(
            "Facture n° fa-123 déjà enregistrée le 2026-10-11",
            Doublons.alerteNumero(candidate, listOf(existante)),
        )
    }

    @Test
    fun `numero vide ou projet different - pas de doublon`() {
        val vide = Depense(id = 1, projetId = 1, date = "2026-10-10", montantCentimes = 1000, numFacture = "")
        val existanteVide = Depense(id = 2, projetId = 1, date = "2026-10-11", montantCentimes = 2000, numFacture = "")
        assertNull(Doublons.alerteNumero(vide, listOf(existanteVide)))

        val autreProjet = Depense(id = 1, projetId = 1, date = "2026-10-10", montantCentimes = 1000, numFacture = "FA-1")
        val existanteAutre = Depense(id = 2, projetId = 2, date = "2026-10-11", montantCentimes = 2000, numFacture = "FA-1")
        assertNull(Doublons.alerteNumero(autreProjet, listOf(existanteAutre)))
    }
}
