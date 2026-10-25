package com.trucdecomptable.notesdefrais

import com.trucdecomptable.notesdefrais.data.Depense
import com.trucdecomptable.notesdefrais.data.Parametres
import com.trucdecomptable.notesdefrais.data.Projet
import com.trucdecomptable.notesdefrais.data.TypeDepense
import com.trucdecomptable.notesdefrais.export.ExportManager
import com.trucdecomptable.notesdefrais.export.XlsxWriter
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

/**
 * The export now names and numbers the attachments, and every expense carries
 * the card that paid it: both have to survive the trip to the workbook.
 */
class PaiementJustificatifsTest {

    private fun hotel(id: Long, fichier: String?, annexes: String = ""): Depense = Depense(
        id = id,
        projetId = 1,
        date = "2026-10-12",
        montantCentimes = 24850,
        type = TypeDepense.HOTEL.name,
        fichier = fichier,
        fichiersSupplementaires = annexes,
        moyenPaiement = "CB pro",
    )

    @Test
    fun `les justificatifs sont numerotes dans l ordre du zip`() {
        val premier = hotel(1, "a.jpg")
        val second = hotel(2, "b.jpg") // même jour, même type, même montant
        val manuel = hotel(3, null) // saisie sans justificatif
        val plan = ExportManager.planJustificatifs("Mission_Paris", listOf(premier, second, manuel))

        assertEquals(3, plan.size)
        assertEquals(1, plan[0].numero)
        assertEquals("Mission_Paris_2026-10-12_hotel_248.50EUR.pdf", plan[0].noms.single())
        // A duplicate name is suffixed instead of overwriting the first file.
        assertEquals(2, plan[1].numero)
        assertEquals("Mission_Paris_2026-10-12_hotel_248.50EUR_2.pdf", plan[1].noms.single())
        // An expense with no file gets no number, so the numbers stay 1..n.
        assertEquals(0, plan[2].numero)
        assertTrue(plan[2].noms.isEmpty())
    }

    @Test
    fun `les annexes sont nommees et listees`() {
        val depense = hotel(1, "a.jpg", annexes = "b.pdf|feuille-de-route.pdf")
        val plan = ExportManager.planJustificatifs("Mission_Paris", listOf(depense))

        assertEquals(
            listOf(
                "Mission_Paris_2026-10-12_hotel_248.50EUR.pdf",
                "Mission_Paris_2026-10-12_hotel_248.50EUR_annexe2.pdf",
                "Mission_Paris_2026-10-12_hotel_248.50EUR_annexe3.pdf",
            ),
            plan.single().noms,
        )
    }

    @Test
    fun `un fichier disparu du telephone n est pas annonce`() {
        val depense = hotel(1, "a.jpg", annexes = "perdue.pdf")
        val plan = ExportManager.planJustificatifs("Mission_Paris", listOf(depense)) { nom -> nom == "a.jpg" }

        assertEquals(listOf("Mission_Paris_2026-10-12_hotel_248.50EUR.pdf"), plan.single().noms)
    }

    @Test
    fun `le classeur porte le numero, le nom et le paiement`() {
        val projet = Projet(id = 1, nom = "Mission Paris")
        val depenses = listOf(
            hotel(1, "a.jpg"),
            Depense(
                id = 2,
                projetId = 1,
                date = "2026-10-13",
                montantCentimes = 8900,
                type = TypeDepense.TRAIN.name,
                moyenPaiement = "CB perso",
            ),
        )
        val sortie = ByteArrayOutputStream()
        XlsxWriter().ecrire(ExportManager.feuilles(projet, depenses), sortie)

        val entrees = mutableMapOf<String, String>()
        ZipInputStream(sortie.toByteArray().inputStream()).use { zip ->
            var entree = zip.nextEntry
            while (entree != null) {
                entrees[entree.name] = zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
                entree = zip.nextEntry
            }
        }

        val detail = entrees["xl/worksheets/sheet1.xml"].orEmpty()
        assertTrue(detail.contains("Paiement"))
        assertTrue(detail.contains("CB pro"))
        assertTrue(detail.contains("Mission_Paris_2026-10-12_hotel_248.50EUR.pdf"))
        // The number is a real cell, not text the reader has to parse.
        assertTrue(detail.contains("<c r=\"A2\" s=\"0\"><v>1</v></c>"))

        val synthese = entrees["xl/worksheets/sheet2.xml"].orEmpty()
        assertTrue(synthese.contains("CB pro"))
        assertTrue(synthese.contains("CB perso"))
        assertTrue(synthese.contains("non renseigné").not())
    }

    @Test
    fun `les moyens de paiement survivent au parametrage`() {
        val initial = Parametres(
            moyensPaiement = listOf("CB BNP pro", "CB perso", "Espèces"),
            moyenPaiementDefaut = "CB perso",
        )
        val relu = Parametres.depuisJson(initial.toJson())

        assertEquals(initial.moyensPaiement, relu.moyensPaiement)
        assertEquals("CB perso", relu.moyenPaiementDefaut)
    }

    @Test
    fun `un parametrage sans moyens de paiement retombe sur la liste par defaut`() {
        val relu = Parametres.depuisJson(JSONObject("""{"nom":"Heuvrard"}"""))

        assertEquals(Parametres.MOYENS_PAIEMENT_DEFAUT, relu.moyensPaiement)
        assertEquals(Parametres.MOYENS_PAIEMENT_DEFAUT.first(), relu.moyenPaiementDefaut)
    }
}
