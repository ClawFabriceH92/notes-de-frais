package com.trucdecomptable.notesdefrais

import com.trucdecomptable.notesdefrais.data.Depense
import org.junit.Assert.assertEquals
import org.junit.Test

class DepenseAvecPieceTest {

    private fun depense(fichier: String? = null, annexes: String = "") =
        Depense(projetId = 1, date = "2026-11-01", montantCentimes = 1000, fichier = fichier, fichiersSupplementaires = annexes)

    @Test
    fun `sans piece principale la nouvelle piece devient la principale`() {
        val maj = depense(fichier = null).avecPiece("x.jpg")

        assertEquals(listOf("x.jpg"), maj.tousLesFichiers)
        assertEquals("x.jpg", maj.fichier)
    }

    @Test
    fun `sans piece principale les annexes existantes sont conservees`() {
        val maj = depense(fichier = null, annexes = "a.pdf").avecPiece("x.jpg")

        assertEquals(listOf("x.jpg", "a.pdf"), maj.tousLesFichiers)
    }

    @Test
    fun `avec piece principale elle reste inchangee et la nouvelle rejoint les annexes`() {
        val maj = depense(fichier = "p.jpg", annexes = "a.pdf").avecPiece("x.jpg")

        assertEquals("p.jpg", maj.fichier)
        assertEquals(listOf("p.jpg", "a.pdf", "x.jpg"), maj.tousLesFichiers)
    }
}
