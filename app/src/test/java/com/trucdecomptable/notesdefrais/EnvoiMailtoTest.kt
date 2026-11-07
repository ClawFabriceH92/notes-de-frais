package com.trucdecomptable.notesdefrais

import com.trucdecomptable.notesdefrais.export.EnvoiMailto
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** Envoi rapide par e-mail : validation d'adresse, objet et corps du message. */
class EnvoiMailtoTest {

    @Test
    fun `adresse vide ou malformee refusee`() {
        assertFalse(EnvoiMailto.adresseValide(null))
        assertFalse(EnvoiMailto.adresseValide(""))
        assertFalse(EnvoiMailto.adresseValide("   "))
        assertFalse(EnvoiMailto.adresseValide("comptable"))
        assertFalse(EnvoiMailto.adresseValide("@domaine.fr"))
        assertFalse(EnvoiMailto.adresseValide("contact@domaine"))
        assertFalse(EnvoiMailto.adresseValide("contact@.fr"))
        assertFalse(EnvoiMailto.adresseValide("contact@domaine."))
        assertFalse(EnvoiMailto.adresseValide("deux@arobase@domaine.fr"))
        assertFalse(EnvoiMailto.adresseValide("con tact@domaine.fr"))
    }

    @Test
    fun `adresse correcte acceptee`() {
        assertTrue(EnvoiMailto.adresseValide("compta@cabinet.fr"))
        assertTrue(EnvoiMailto.adresseValide("  fh@fh-ec.fr  "))
        assertTrue(EnvoiMailto.adresseValide("nom.prenom+ndf@cabinet.co.uk"))
    }

    @Test
    fun `objet reprend la societe et la date`() {
        val sujet = EnvoiMailto.sujet("FAR Applications", LocalDate.of(2026, 11, 6))
        assertEquals("Notes de frais – FAR Applications – 06/11/2026", sujet)
    }

    @Test
    fun `objet sans societe reste lisible`() {
        val sujet = EnvoiMailto.sujet("   ", LocalDate.of(2026, 1, 2))
        assertEquals("Notes de frais – sans société – 02/01/2026", sujet)
    }

    @Test
    fun `corps annonce le nombre de justificatifs`() {
        assertTrue(EnvoiMailto.corps("Cabinet", 1).contains("1 justificatif)"))
        assertTrue(EnvoiMailto.corps("Cabinet", 3).contains("3 justificatifs)"))
        assertTrue(EnvoiMailto.corps("Cabinet", 0).contains("aucun justificatif"))
    }

    @Test
    fun `corps rappelle qu aucune note de frais n a ete remplie`() {
        assertTrue(EnvoiMailto.corps("Cabinet", 2).contains("aucune note de frais"))
    }

    @Test
    fun `objet precise le projet quand l envoi est limite`() {
        val sujet = EnvoiMailto.sujet("Cabinet", LocalDate.of(2026, 11, 6), nomProjet = "Mission Lyon")
        assertEquals("Notes de frais – Cabinet – Mission Lyon – 06/11/2026", sujet)
    }

    @Test
    fun `projet vide ou blanc ne pollue pas l objet`() {
        val jour = LocalDate.of(2026, 11, 6)
        assertEquals(
            EnvoiMailto.sujet("Cabinet", jour, nomProjet = null),
            EnvoiMailto.sujet("Cabinet", jour, nomProjet = "   "),
        )
    }

    @Test
    fun `corps mentionne le projet quand il y en a un`() {
        assertTrue(EnvoiMailto.corps("Cabinet", 2, nomProjet = "Mission Lyon").contains("Cabinet, projet Mission Lyon"))
        assertTrue(EnvoiMailto.corps("Cabinet", 2).contains("justificatifs de Cabinet,"))
    }
}
