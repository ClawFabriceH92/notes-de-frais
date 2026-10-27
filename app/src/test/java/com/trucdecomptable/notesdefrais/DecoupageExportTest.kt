package com.trucdecomptable.notesdefrais

import com.trucdecomptable.notesdefrais.data.Depense
import com.trucdecomptable.notesdefrais.export.ExportManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DecoupageExportTest {

    private fun justificatif(id: Long): ExportManager.Justificatif = ExportManager.Justificatif(
        depense = Depense(id = id, projetId = 1, date = "2026-10-12", montantCentimes = 1000 * id),
        numero = id.toInt(),
        noms = listOf("piece-$id.pdf"),
    )

    @Test
    fun `deux tas de poids comparable`() {
        val pieces = (1L..4L).map { justificatif(it) }
        // 4 pièces de 1, 2, 3 et 4 Mo : la première moitié doit faire 5 Mo.
        val poids = pieces.associateWith { it.depense.id * 1024L * 1024L }
        val (premiere, seconde) = ExportManager.repartirEnDeux(pieces, poids)

        assertEquals(4, premiere.size + seconde.size)
        val totalPremiere = premiere.sumOf { poids[it] ?: 0L }
        val totalSeconde = seconde.sumOf { poids[it] ?: 0L }
        assertEquals(10L * 1024 * 1024, totalPremiere + totalSeconde)
        // Écart maximal accepté : un quart de la pièce la plus lourde.
        assertTrue(
            "première=$totalPremiere seconde=$totalSeconde",
            kotlin.math.abs(totalPremiere - totalSeconde) <= 1L * 1024 * 1024,
        )
        assertTrue(premiere.isNotEmpty())
        assertTrue(seconde.isNotEmpty())
    }

    @Test
    fun `une seule piece tient dans la premiere partie`() {
        val pieces = listOf(justificatif(1))
        val (premiere, seconde) = ExportManager.repartirEnDeux(pieces, mapOf(pieces[0] to 5_000_000L))
        assertEquals(1, premiere.size)
        assertTrue(seconde.isEmpty())
    }

    @Test
    fun `le seuil de decoupage est bien 10 Mo`() {
        assertEquals(10L * 1024 * 1024, ExportManager.SEUIL_DECOUPAGE)
    }
}
