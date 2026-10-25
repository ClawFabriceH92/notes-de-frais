package com.trucdecomptable.notesdefrais

import com.trucdecomptable.notesdefrais.data.AnnuaireEntreprises
import com.trucdecomptable.notesdefrais.data.Depense
import com.trucdecomptable.notesdefrais.data.Parametres
import com.trucdecomptable.notesdefrais.data.Profil
import com.trucdecomptable.notesdefrais.data.Projet
import com.trucdecomptable.notesdefrais.data.SensTrajet
import com.trucdecomptable.notesdefrais.data.TypeDepense
import com.trucdecomptable.notesdefrais.export.ExportManager
import com.trucdecomptable.notesdefrais.export.XlsxWriter
import com.trucdecomptable.notesdefrais.util.Dates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * Les ajouts de la v1.4 : dates françaises, aller/retour, invités d'un repas,
 * profil de société et contrôle du SIRET.
 */
class DatesEtProfilsTest {

    @Test
    fun `la date s affiche en jj mm aaaa`() {
        assertEquals("12/10/2026", Dates.isoVersFr("2026-10-12"))
        assertEquals("12/10/2026", Dates.affichage("2026-10-12"))
        // Ce qui n'est pas une date ISO n'est pas maquillé.
        assertEquals("brouillon", Dates.isoVersFr("brouillon"))
    }

    @Test
    fun `la saisie accepte les formats francais`() {
        assertEquals("2026-10-12", Dates.frVersIso("12/10/2026"))
        assertEquals("2026-10-12", Dates.frVersIso("12-10-2026"))
        assertEquals("2026-10-12", Dates.frVersIso("12.10.2026"))
        assertEquals("2026-10-12", Dates.frVersIso("12/10/26"))
        assertEquals("2026-10-12", Dates.frVersIso("2026-10-12"))
        assertNull(Dates.frVersIso("31/02/2026"))
        assertNull(Dates.frVersIso("12/10"))
        assertFalse(Dates.estValide(""))
    }

    @Test
    fun `le sens du trajet ne concerne que le train et l avion`() {
        val train = Depense(projetId = 1, date = "2026-10-12", montantCentimes = 9000, type = TypeDepense.TRAIN.name)
        val avion = train.copy(type = TypeDepense.AVION.name)
        val taxi = train.copy(type = TypeDepense.TAXI.name)

        assertTrue(train.trajetDemande)
        assertTrue(avion.trajetDemande)
        assertFalse(taxi.trajetDemande)
        assertEquals(SensTrajet.ALLER_RETOUR, train.copy(sensTrajet = "ALLER_RETOUR").sens)
        assertNull(train.sens)
    }

    @Test
    fun `les invites d un repas ne concernent que les restaurants`() {
        val repas = Depense(
            projetId = 1,
            date = "2026-10-12",
            montantCentimes = 6500,
            type = TypeDepense.RESTAURANT_MIDI.name,
            participants = "Jean Dupont",
            societeParticipant = "Client SARL",
            motifRepas = "Revue des comptes annuels",
        )
        assertTrue(repas.repasDemande)
        assertFalse(repas.copy(type = TypeDepense.HOTEL.name).repasDemande)
    }

    @Test
    fun `le classeur porte trajet invites et societe du profil`() {
        val projet = Projet(id = 1, nom = "Mission Lyon", profilId = 7)
        val profil = Profil(id = 7, nom = "FAR Applications", siret = "98830048900016", adresse = "7 rue Mortinat 92600 Asnières")
        val depenses = listOf(
            Depense(
                projetId = 1,
                date = "2026-10-12",
                montantCentimes = 12000,
                type = TypeDepense.RESTAURANT_MIDI.name,
                participants = "Jean Dupont",
                societeParticipant = "Client SARL",
                motifRepas = "Revue des comptes",
            ),
            Depense(
                projetId = 1,
                date = "2026-10-13",
                montantCentimes = 9000,
                type = TypeDepense.TRAIN.name,
                sensTrajet = SensTrajet.ALLER_RETOUR.name,
            ),
        )
        val sortie = ByteArrayOutputStream()
        XlsxWriter().ecrire(
            ExportManager.feuilles(projet, depenses, Parametres(), profil = profil),
            sortie,
        )
        val xml = unzip(sortie.toByteArray())
        val detail = xml["xl/worksheets/sheet1.xml"].orEmpty()
        val synthese = xml["xl/worksheets/sheet2.xml"].orEmpty()

        assertTrue(detail.contains("Trajet"))
        assertTrue(detail.contains("Invités"))
        assertTrue(detail.contains("Société invités"))
        assertTrue(detail.contains("Motif"))
        assertTrue(detail.contains("Aller-retour"))
        assertTrue(detail.contains("Jean Dupont"))
        assertTrue(detail.contains("Client SARL"))
        assertTrue(detail.contains("Revue des comptes"))
        // La société vient du profil du projet, pas du paramétrage global.
        assertTrue(synthese.contains("FAR Applications"))
        assertTrue(synthese.contains("98830048900016"))
    }

    @Test
    fun `le SIRET est verifie avant d interroger l annuaire`() {
        assertTrue(AnnuaireEntreprises.valide("98830048900016"))
        assertFalse(AnnuaireEntreprises.valide("98830048900017"))
        assertFalse(AnnuaireEntreprises.valide("12345"))
    }

    private fun unzip(octets: ByteArray): Map<String, String> {
        val entrees = mutableMapOf<String, String>()
        ZipInputStream(octets.inputStream()).use { zip ->
            var entree = zip.nextEntry
            while (entree != null) {
                entrees[entree.name] = zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
                entree = zip.nextEntry
            }
        }
        return entrees
    }
}
