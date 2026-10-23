package com.trucdecomptable.notesdefrais

import com.trucdecomptable.notesdefrais.data.Depense
import com.trucdecomptable.notesdefrais.data.Doublons
import com.trucdecomptable.notesdefrais.data.Plafonds
import com.trucdecomptable.notesdefrais.data.TypeDepense
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Plafonds d'exonération 2026 (page « Frais professionnels » de l'URSSAF) et
 * détection de doublons.
 */
class PlafondsDoublonsTest {

    private fun depense(
        id: Long = 0,
        montant: Long = 2000,
        type: TypeDepense = TypeDepense.RESTAURANT_MIDI,
        date: String = "2026-10-12",
        libelle: String = "",
        projetId: Long = 1,
    ) = Depense(
        id = id,
        projetId = projetId,
        date = date,
        montantCentimes = montant,
        type = type.name,
        libelle = libelle,
    )

    @Test
    fun `plafonds 2026 des repas et de la nuitée`() {
        assertEquals(2140L, Plafonds.plafondCentimes(TypeDepense.RESTAURANT_MIDI, false))
        assertEquals(2140L, Plafonds.plafondCentimes(TypeDepense.RESTAURANT_SOIR, true))
        assertEquals(5680L, Plafonds.plafondCentimes(TypeDepense.HOTEL, false))
        assertEquals(7660L, Plafonds.plafondCentimes(TypeDepense.HOTEL, true))
    }

    @Test
    fun `pas de plafond pour les transports`() {
        assertNull(Plafonds.plafondCentimes(TypeDepense.TRAIN, false))
        assertNull(Plafonds.plafondCentimes(TypeDepense.AVION, false))
        assertNull(Plafonds.plafondCentimes(TypeDepense.INDENNITE_KM, false))
    }

    @Test
    fun `repas sous le plafond ne declenche rien`() {
        assertNull(Plafonds.alerte(depense(montant = 1850), zoneParis = false))
    }

    @Test
    fun `repas au dessus du plafond signale l excedent`() {
        val alerte = Plafonds.alerte(depense(montant = 2600), zoneParis = false)
        assertNotNull(alerte)
        assertTrue(alerte!!.contains("21,40 €"))
        assertTrue(alerte.contains("4,60 €"))
    }

    @Test
    fun `hotel paris plus permissif qu en province`() {
        val note = depense(montant = 6000, type = TypeDepense.HOTEL)
        assertNotNull(Plafonds.alerte(note, zoneParis = false))
        assertNull(Plafonds.alerte(note, zoneParis = true))
    }

    @Test
    fun `hotel de deux nuits - plafond multiplie`() {
        val note = depense(montant = 10000, type = TypeDepense.HOTEL)
        assertNotNull(Plafonds.alerte(note, zoneParis = false, parNuit = 1))
        assertNull(Plafonds.alerte(note, zoneParis = false, parNuit = 2))
    }

    @Test
    fun `doublon exact detecte`() {
        val existante = depense(id = 5, montant = 2450, date = "2026-10-12")
        val candidate = depense(montant = 2450, date = "2026-10-12")
        assertNotNull(Doublons.chercher(candidate, listOf(existante)))
    }

    @Test
    fun `meme montant mais autre jour n est pas un doublon`() {
        val existante = depense(id = 5, montant = 2450, date = "2026-10-11")
        val candidate = depense(montant = 2450, date = "2026-10-12")
        assertNull(Doublons.chercher(candidate, listOf(existante)))
    }

    @Test
    fun `la depense elle meme n est pas son propre doublon`() {
        val d = depense(id = 7, montant = 2450)
        assertNull(Doublons.chercher(d, listOf(d)))
    }

    @Test
    fun `libelle identique sur un autre type reste un doublon probable`() {
        val existante = depense(id = 5, montant = 2450, type = TypeDepense.AUTRE, libelle = "Taxi G7")
        val candidate = depense(montant = 2450, type = TypeDepense.TAXI, libelle = "taxi g7")
        assertNotNull(Doublons.chercher(candidate, listOf(existante)))
    }

    @Test
    fun `montant different n est pas un doublon`() {
        val existante = depense(id = 5, montant = 2450)
        val candidate = depense(montant = 2451)
        assertNull(Doublons.chercher(candidate, listOf(existante)))
    }
}
