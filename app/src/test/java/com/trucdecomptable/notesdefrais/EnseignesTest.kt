package com.trucdecomptable.notesdefrais

import com.trucdecomptable.notesdefrais.data.Enseignes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reconnaissance d'enseigne sur le texte OCR : chaque enseigne est détectée,
 * un « U » isolé ne déclenche rien, le nom exact retrouve l'enseigne, et la
 * forme la plus précise prime sur la forme générique.
 */
class EnseignesTest {

    // ------------------------------------------------------- chaque enseigne

    @Test
    fun `carrefour est detecte`() {
        assertEquals("Carrefour", Enseignes.detecter("HYPERMARCHE CARREFOUR")?.nom)
    }

    @Test
    fun `auchan est detecte`() {
        assertEquals("Auchan", Enseignes.detecter("HYPERMARCHE AUCHAN")?.nom)
    }

    @Test
    fun `lidl est detecte`() {
        assertEquals("Lidl", Enseignes.detecter("LIDL")?.nom)
    }

    @Test
    fun `magasins u est detecte via super u`() {
        assertEquals("Magasins U", Enseignes.detecter("SUPER U")?.nom)
    }

    @Test
    fun `magasins u est detecte via u express`() {
        assertEquals("Magasins U", Enseignes.detecter("U EXPRESS")?.nom)
    }

    @Test
    fun `e leclerc est detecte`() {
        assertEquals("E.Leclerc", Enseignes.detecter("E.LECLERC")?.nom)
    }

    @Test
    fun `monoprix est detecte`() {
        assertEquals("Monoprix", Enseignes.detecter("MONOPRIX")?.nom)
    }

    // ------------------------------------------- casse et accents ignorés

    @Test
    fun `la detection ignore la casse`() {
        assertEquals("Carrefour", Enseignes.detecter("carrefour market")?.nom)
        assertEquals("E.Leclerc", Enseignes.detecter("e.leclerc")?.nom)
    }

    @Test
    fun `la detection ignore les accents`() {
        // L'OCR accentue parfois à tort : « SUPÉR » se lit comme « SUPER ».
        assertEquals("Magasins U", Enseignes.detecter("SUPÉR U")?.nom)
    }

    // ------------------------------------------------- faux positif « U »

    @Test
    fun `un u isole ne detecte pas magasins u`() {
        assertNull(Enseignes.detecter("U"))
        assertNull(Enseignes.detecter("TOTAL U 12,50"))
        assertNull(Enseignes.detecter("PRIX U LA CAISSE"))
    }

    @Test
    fun `un texte sans enseigne ne renvoie rien`() {
        assertNull(Enseignes.detecter(""))
        assertNull(Enseignes.detecter("SUPERMARCHE DU CENTRE"))
    }

    // ---------------------------------------------------------- parNom

    @Test
    fun `parNom retrouve une enseigne par son nom`() {
        assertEquals("Carrefour", Enseignes.parNom("Carrefour")?.nom)
        // Casse ignorée.
        assertEquals("Auchan", Enseignes.parNom("auchan")?.nom)
        assertNull(Enseignes.parNom("Intermarché"))
        assertNull(Enseignes.parNom("Inconnu"))
    }

    // --------------------------------------- priorité CARREFOUR CITY

    @Test
    fun `carrefour city prime sur carrefour`() {
        val carrefour = Enseignes.parNom("Carrefour")!!
        // Le nom précis est testé avant le nom générique dans les mots-clés.
        assertTrue(
            carrefour.motsCles.indexOf("CARREFOUR CITY") <
                carrefour.motsCles.indexOf("CARREFOUR"),
        )
        // Et la détection retourne Carrefour dans les deux cas.
        assertEquals("Carrefour", Enseignes.detecter("CARREFOUR CITY")?.nom)
        assertEquals("Carrefour", Enseignes.detecter("CARREFOUR")?.nom)
    }
}
