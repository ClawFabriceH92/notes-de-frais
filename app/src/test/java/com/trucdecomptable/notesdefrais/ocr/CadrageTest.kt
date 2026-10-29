package com.trucdecomptable.notesdefrais.ocr

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Le cadrage d'un ticket : enveloppe des blocs de texte, marge, et refus quand
 * la détection est douteuse. Ces tests tournent sur JVM (aucune dépendance
 * Android) parce que la géométrie est isolée dans [Cadrage].
 */
class CadrageTest {

    /** Photo 1000 × 2000 avec un ticket occupant le centre. */
    private val largeur = 1000
    private val hauteur = 2000

    private fun ticket(decalage: Int = 0) = listOf(
        Zone(300 + decalage, 500, 700 + decalage, 540),
        Zone(300 + decalage, 560, 690 + decalage, 600),
        Zone(310 + decalage, 900, 700 + decalage, 960),
    )

    @Test
    fun `enveloppe des blocs`() {
        val union = Cadrage.union(ticket())
        assertNotNull(union)
        assertEquals(300, union!!.left)
        assertEquals(500, union.top)
        assertEquals(700, union.right)
        assertEquals(960, union.bottom)
    }

    @Test
    fun `union ignore les zones vides`() {
        val union = Cadrage.union(listOf(Zone(10, 10, 10, 10), Zone(20, 30, 40, 50)))
        assertEquals(Zone(20, 30, 40, 50), union)
        assertNull(Cadrage.union(emptyList()))
    }

    @Test
    fun `un ticket au centre est recadre avec marge`() {
        val zone = Cadrage.zoneDuTicket(ticket(), largeur, hauteur)
        assertNotNull(zone)
        // Marge = 5 % du plus grand côté du ticket (460 px) -> 23 px.
        assertEquals(300 - 23, zone!!.left)
        assertEquals(700 + 23, zone.right)
        assertEquals(500 - 23, zone.top)
        assertEquals(960 + 23, zone.bottom)
        assertTrue(zone.largeur < largeur)
        assertTrue(zone.hauteur < hauteur)
    }

    @Test
    fun `une seule ligne lue ne suffit pas`() {
        assertNull(Cadrage.zoneDuTicket(listOf(Zone(100, 100, 900, 140)), largeur, hauteur))
    }

    @Test
    fun `texte minuscule dans l'image ne coupe pas`() {
        val minuscule = listOf(Zone(480, 980, 520, 1000), Zone(480, 1010, 515, 1030))
        assertNull(Cadrage.zoneDuTicket(minuscule, largeur, hauteur))
    }

    @Test
    fun `ticket qui remplit deja la photo reste tel quel`() {
        val pleine = listOf(
            Zone(20, 20, 980, 1980),
            Zone(20, 100, 980, 1900),
        )
        assertNull(Cadrage.zoneDuTicket(pleine, largeur, hauteur))
    }

    @Test
    fun `marge bornee aux bords de l'image`() {
        val colle = listOf(
            Zone(0, 0, 400, 300),
            Zone(0, 320, 380, 600),
        )
        val zone = Cadrage.zoneDuTicket(colle, largeur, hauteur)
        assertNotNull(zone)
        assertEquals(0, zone!!.left)
        assertEquals(0, zone.top)
    }

    @Test
    fun `image invalide`() {
        assertNull(Cadrage.zoneDuTicket(ticket(), 0, 0))
        assertNull(Cadrage.zoneDuTicket(ticket(), 4, 4))
        assertNull(Cadrage.zoneDuTicket(emptyList(), largeur, hauteur))
    }

    @Test
    fun `etirement de la marge`() {
        val zone = Zone(100, 100, 200, 200).etendre(10, 500, 500)
        assertEquals(Zone(90, 90, 210, 210), zone)
        val bord = Zone(0, 0, 10, 10).etendre(50, 500, 500)
        assertEquals(0, bord.left)
        assertEquals(0, bord.top)
        assertEquals(60, bord.right)
    }
}
