package com.trucdecomptable.notesdefrais

import com.trucdecomptable.notesdefrais.data.Controles
import com.trucdecomptable.notesdefrais.data.Depense
import com.trucdecomptable.notesdefrais.data.TypeDepense
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/**
 * Contrôles de cohérence : date future, taux de TVA par type, repas avec
 * invité incomplet, et contrôle avant export (pièces jointes manquantes).
 */
class ControlesTest {

    private fun depense(
        type: TypeDepense = TypeDepense.AUTRE,
        tvaTauxBp: Int? = null,
        participants: String = "",
        motifRepas: String = "",
    ): Depense = Depense(
        projetId = 1,
        date = "2026-10-10",
        montantCentimes = 1000,
        type = type.name,
        tvaTauxBp = tvaTauxBp,
        participants = participants,
        motifRepas = motifRepas,
    )

    // ------------------------------------------------------------- date future

    @Test
    fun `une date posterieure a aujourd hui est signalee`() {
        val demain = LocalDate.now().plusDays(1).toString()
        val alerte = Controles.alerteDateFuture(depense().copy(date = demain))
        assertNotNull(alerte)
        assertTrue(alerte!!.contains("Date postérieure à aujourd'hui"))
    }

    @Test
    fun `une date passee ou du jour ne declenche rien`() {
        val hier = LocalDate.now().minusDays(1).toString()
        assertNull(Controles.alerteDateFuture(depense().copy(date = hier)))
        assertNull(Controles.alerteDateFuture(depense().copy(date = LocalDate.now().toString())))
    }

    // ------------------------------------------------------------ taux de TVA

    @Test
    fun `un repas a 5,5 pour cent est signale`() {
        assertNotNull(Controles.alerteTauxTva(depense(TypeDepense.RESTAURANT_MIDI_SEUL, tvaTauxBp = 550)))
    }

    @Test
    fun `un repas a 10 ou 20 pour cent passe`() {
        assertNull(Controles.alerteTauxTva(depense(TypeDepense.RESTAURANT_MIDI_SEUL, tvaTauxBp = 1000)))
        // 20 % toléré pour les boissons alcoolisées.
        assertNull(Controles.alerteTauxTva(depense(TypeDepense.RESTAURANT_MIDI_SEUL, tvaTauxBp = 2000)))
    }

    @Test
    fun `un hotel a 20 ou 5,5 pour cent est signale`() {
        assertNotNull(Controles.alerteTauxTva(depense(TypeDepense.HOTEL, tvaTauxBp = 2000)))
        assertNotNull(Controles.alerteTauxTva(depense(TypeDepense.HOTEL, tvaTauxBp = 550)))
        assertNull(Controles.alerteTauxTva(depense(TypeDepense.HOTEL, tvaTauxBp = 1000)))
    }

    @Test
    fun `un train ou un avion accepte 10, 5,5 et 0 pour cent`() {
        assertNull(Controles.alerteTauxTva(depense(TypeDepense.TRAIN, tvaTauxBp = 1000)))
        assertNull(Controles.alerteTauxTva(depense(TypeDepense.TRAIN, tvaTauxBp = 550)))
        assertNull(Controles.alerteTauxTva(depense(TypeDepense.AVION, tvaTauxBp = 0)))
        assertNotNull(Controles.alerteTauxTva(depense(TypeDepense.AVION, tvaTauxBp = 2000)))
    }

    @Test
    fun `essence peage taxi et location exigent 20 pour cent`() {
        assertNull(Controles.alerteTauxTva(depense(TypeDepense.ESSENCE, tvaTauxBp = 2000)))
        assertNotNull(Controles.alerteTauxTva(depense(TypeDepense.PEAGE, tvaTauxBp = 1000)))
        assertNotNull(Controles.alerteTauxTva(depense(TypeDepense.TAXI, tvaTauxBp = 550)))
        assertNotNull(Controles.alerteTauxTva(depense(TypeDepense.LOCATION_VOITURE, tvaTauxBp = 0)))
    }

    @Test
    fun `indemnite km et autres ne sont pas controles`() {
        assertNull(Controles.alerteTauxTva(depense(TypeDepense.INDENNITE_KM, tvaTauxBp = 0)))
        assertNull(Controles.alerteTauxTva(depense(TypeDepense.AUTRE, tvaTauxBp = 550)))
    }

    @Test
    fun `taux non renseigne - pas de controle`() {
        assertNull(Controles.alerteTauxTva(depense(TypeDepense.RESTAURANT_MIDI_SEUL, tvaTauxBp = null)))
    }

    // --------------------------------------------------------- repas incomplet

    @Test
    fun `repas midi avec invite sans participants est signale`() {
        val alerte = Controles.alerteRepasSansInvite(depense(TypeDepense.RESTAURANT_MIDI_INVITE))
        assertNotNull(alerte)
        assertTrue(alerte!!.contains("n'est pas déductible"))
    }

    @Test
    fun `tout repas sans motif est signale`() {
        val alerte = Controles.alerteRepasSansInvite(depense(TypeDepense.RESTAURANT_MIDI_SEUL))
        assertNotNull(alerte)
        assertTrue(alerte!!.contains("motif"))
    }

    @Test
    fun `repas complet - pas d alerte`() {
        val d = depense(TypeDepense.RESTAURANT_MIDI_INVITE, participants = "Jean Dupont", motifRepas = "Client X")
        assertNull(Controles.alerteRepasSansInvite(d))
    }

    @Test
    fun `une depense qui n est pas un repas ne declenche pas l alerte repas`() {
        assertNull(Controles.alerteRepasSansInvite(depense(TypeDepense.HOTEL)))
    }

    // -------------------------------------------------------- avant l'export

    @Test
    fun `depense sans piece jointe est signalee avant export`() {
        val d = Depense(
            projetId = 1,
            date = "2026-10-10",
            montantCentimes = 1000,
            type = TypeDepense.RESTAURANT_MIDI_SEUL.name,
            libelle = "Restaurant Le Central",
        )
        val lignes = Controles.controleAvantExport(listOf(d))
        assertEquals(2, lignes.size)
        assertEquals("1 dépense sans justificatif sur 1", lignes[0])
        assertTrue(lignes[1].contains("Restaurant Le Central"))
    }

    @Test
    fun `depense avec piece jointe - pas de signalement`() {
        val d = Depense(
            projetId = 1,
            date = "2026-10-10",
            montantCentimes = 1000,
            type = TypeDepense.RESTAURANT_MIDI_SEUL.name,
            fichier = "ticket.jpg",
        )
        assertTrue(Controles.controleAvantExport(listOf(d)).isEmpty())
    }

    @Test
    fun `repas au dela de 150 euros HT avec seulement le ticket - facture probablement absente`() {
        val d = Depense(
            projetId = 1,
            date = "2026-10-10",
            montantCentimes = 20000,
            type = TypeDepense.RESTAURANT_MIDI_SEUL.name,
            fichier = "ticket.jpg",
        )
        val lignes = Controles.controleAvantExport(listOf(d))
        assertEquals(1, lignes.size)
        assertTrue(lignes[0].contains("Facture probablement absente"))
    }

    @Test
    fun `depense en devise sans taux est signalee avant export`() {
        val d = Depense(
            projetId = 1,
            date = "2026-10-10",
            montantCentimes = 5000,
            devise = "USD",
            type = TypeDepense.TRAIN.name,
            fichier = "ticket.jpg",
        )
        val lignes = Controles.controleAvantExport(listOf(d))
        assertEquals(2, lignes.size)
        assertEquals("1 dépense en devise sans taux de change : total EUR incomplet", lignes[0])
        assertTrue(lignes[1].contains("USD"))
    }

    @Test
    fun `depense en devise avec taux - pas de signalement de taux`() {
        val d = Depense(
            projetId = 1,
            date = "2026-10-10",
            montantCentimes = 5000,
            devise = "USD",
            tauxChange = 1.1205,
            type = TypeDepense.TRAIN.name,
            fichier = "ticket.jpg",
        )
        assertTrue(Controles.controleAvantExport(listOf(d)).isEmpty())
    }

    // -------------------------------------------------------------- synthèse

    @Test
    fun `alertesSaisie regroupe date future, TVA et repas incomplet`() {
        val demain = LocalDate.now().plusDays(1).toString()
        val d = depense(TypeDepense.RESTAURANT_MIDI_SEUL, tvaTauxBp = 550).copy(date = demain)
        // date future + TVA 5,5 % + repas sans motif.
        assertEquals(3, Controles.alertesSaisie(d).size)
    }
}
