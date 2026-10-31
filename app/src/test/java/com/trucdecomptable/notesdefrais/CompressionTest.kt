package com.trucdecomptable.notesdefrais

import com.trucdecomptable.notesdefrais.util.Compression
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * La règle d'allègement des justificatifs : au-delà de 5 Mo une photo est
 * ré-encodée, un PDF jamais.
 */
class CompressionTest {

    private val cinqMo = 5L * 1024 * 1024

    @Test
    fun `une photo au-dessus de 5 Mo est compressee`() {
        assertTrue(Compression.fautCompresser("photo.jpg", cinqMo + 1))
        assertTrue(Compression.fautCompresser("photo.jpg", 12L * 1024 * 1024))
    }

    @Test
    fun `une photo sous le seuil est laissee telle quelle`() {
        assertFalse(Compression.fautCompresser("photo.jpg", cinqMo))
        assertFalse(Compression.fautCompresser("photo.jpg", 800_000L))
    }

    @Test
    fun `un PDF n est jamais recompresse, meme lourd`() {
        assertFalse(Compression.fautCompresser("facture.pdf", 9L * 1024 * 1024))
        assertFalse(Compression.fautCompresser("FACTURE.PDF", 9L * 1024 * 1024))
    }

    @Test
    fun `les tailles s affichent en Mo`() {
        assertEquals("5,0 Mo", Compression.mo(cinqMo))
        assertEquals("22,1 Mo", Compression.mo(23_200_000L))
    }
}
