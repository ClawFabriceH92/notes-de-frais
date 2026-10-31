package com.trucdecomptable.notesdefrais

import com.trucdecomptable.notesdefrais.data.Depense
import com.trucdecomptable.notesdefrais.data.Parametres
import com.trucdecomptable.notesdefrais.data.Projet
import com.trucdecomptable.notesdefrais.data.TypeDepense
import com.trucdecomptable.notesdefrais.export.ExportManager
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Organisme de formation d'une dépense : FOROM, CNCC Formation, ou un nom libre.
 *
 * Ce qui compte ici : le nom est écrit sur la dépense, pas stocké comme un
 * renvoi vers la liste du paramétrage. Retirer un organisme de la liste laisse
 * donc intactes les dépenses déjà enregistrées, et un nom retiré continue de
 * s'afficher sur sa dépense.
 */
class OrganismeTest {

    private fun depense(organisme: String) = Depense(
        projetId = 1,
        date = "2026-10-12",
        montantCentimes = 45000,
        type = TypeDepense.AUTRE.name,
        libelle = "Inscription formation",
        organisme = organisme,
    )

    @Test
    fun `la liste permanente propose FOROM et CNCC Formation par defaut`() {
        assertEquals(listOf("FOROM", "CNCC Formation"), Parametres().organismes)
        assertEquals(Parametres.ORGANISMES_DEFAUT, Parametres.ORGANISMES_DEFAUT)
    }

    @Test
    fun `la liste des organismes survit a un aller-retour en parametrage JSON`() {
        val initial = Parametres().copy(organismes = listOf("FOROM", "CNCC Formation", "IFEC"))

        val relu = Parametres.depuisJson(JSONObject(initial.toJson().toString()))

        assertEquals(listOf("FOROM", "CNCC Formation", "IFEC"), relu.organismes)
    }

    @Test
    fun `une liste videe reste videe et ne revient pas aux valeurs par defaut`() {
        val vide = Parametres().copy(organismes = emptyList())

        val relu = Parametres.depuisJson(JSONObject(vide.toJson().toString()))

        assertTrue(relu.organismes.isEmpty())
    }

    @Test
    fun `un parametrage ancien sans organismes prend la liste par defaut`() {
        val ancien = JSONObject().put("nom", "Heuvrard").put("moyensPaiement", org.json.JSONArray())

        val relu = Parametres.depuisJson(ancien)

        assertEquals(Parametres.ORGANISMES_DEFAUT, relu.organismes)
    }

    @Test
    fun `retirer un organisme de la liste ne touche pas la depense qui le porte`() {
        val depense = depense("FOROM")
        val apresSuppression = Parametres().copy(organismes = listOf("CNCC Formation"))

        // La dépense garde son texte : c'est lui qui est lu, pas la liste.
        assertEquals("FOROM", depense.organisme)
        assertFalse(depense.organisme in apresSuppression.organismes)
    }

    @Test
    fun `recap excel - l organisme a sa colonne, meme absent de la liste`() {
        val detail = ExportManager.feuilles(
            Projet(nom = "Formation CNCC"),
            listOf(depense("FOROM"), depense("")),
        ).first()

        val entetes = detail.lignes[0].map { it.texte }
        assertEquals("Organisme", entetes[5])
        assertEquals("Paiement", entetes[4])
        assertEquals("Sens", entetes[6])

        assertEquals("FOROM", detail.lignes[1][5].texte)
        assertEquals("", detail.lignes[2][5].texte.orEmpty())
    }
}
