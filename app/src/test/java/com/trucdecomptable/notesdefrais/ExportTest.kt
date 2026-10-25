package com.trucdecomptable.notesdefrais

import com.trucdecomptable.notesdefrais.data.Depense
import com.trucdecomptable.notesdefrais.data.Projet
import com.trucdecomptable.notesdefrais.data.TypeDepense
import com.trucdecomptable.notesdefrais.data.convertirEnEuros
import com.trucdecomptable.notesdefrais.export.ExportManager
import com.trucdecomptable.notesdefrais.export.XlsxWriter
import com.trucdecomptable.notesdefrais.util.centimesDe
import com.trucdecomptable.notesdefrais.util.centimesVersTexte
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipInputStream

class ExportTest {

    @Test
    fun `nom du PDF - projet date type montant devise`() {
        val depense = Depense(
            projetId = 1,
            date = "2026-10-12",
            montantCentimes = 24850,
            type = TypeDepense.HOTEL.name,
        )
        assertEquals(
            "Mission_Paris_2026-10-12_hotel_248.50EUR.pdf",
            ExportManager.nomJustificatif("Mission_Paris", depense),
        )
    }

    @Test
    fun `slug de projet sans accents ni espaces`() {
        assertEquals("Mission_Paris", ExportManager.slugProjet("Mission Paris"))
        assertEquals("Sejour_a_Lyon", ExportManager.slugProjet("Séjour à Lyon"))
        assertEquals("Projet", ExportManager.slugProjet("   "))
    }

    @Test
    fun `conversion de devise arrondie au centime`() {
        assertEquals(10000L, convertirEnEuros(10800, 1.08))
        assertEquals(5000L, convertirEnEuros(5000, 1.0))
    }

    @Test
    fun `montant saisi a la main`() {
        assertEquals(2450L, centimesDe("24,50"))
        assertEquals(2450L, centimesDe("24.50"))
        assertEquals(124850L, centimesDe("1 248,50"))
        assertNull(centimesDe("abc"))
        assertEquals("24,50", centimesVersTexte(2450))
    }

    @Test
    fun `ventilation HT et TVA`() {
        val depense = Depense(
            projetId = 1,
            date = "2026-10-12",
            montantCentimes = 12000,
            tvaTauxBp = 2000,
        )
        assertEquals(2000L, depense.tvaCentimes)
        assertEquals(10000L, depense.htCentimes)
    }

    @Test
    fun `le xlsx produit un classeur valide`() {
        val projet = Projet(id = 1, nom = "Mission Paris")
        val depenses = listOf(
            Depense(projetId = 1, date = "2026-10-12", montantCentimes = 24850, type = TypeDepense.HOTEL.name, tvaTauxBp = 1000),
            Depense(projetId = 1, date = "2026-10-13", montantCentimes = 8900, type = TypeDepense.TRAIN.name),
        )
        val feuilles = ExportManager.feuilles(projet, depenses)

        val sortie = ByteArrayOutputStream()
        XlsxWriter().ecrire(feuilles, sortie)
        val octets = sortie.toByteArray()

        // A real xlsx is a ZIP starting with "PK".
        assertEquals("PK", String(octets.copyOfRange(0, 2)))

        val entrees = mutableMapOf<String, String>()
        ZipInputStream(octets.inputStream()).use { zip ->
            var entree = zip.nextEntry
            while (entree != null) {
                entrees[entree.name] = zip.readBytes().toString(Charsets.UTF_8)
                zip.closeEntry()
                entree = zip.nextEntry
            }
        }

        assertTrue(entrees.containsKey("[Content_Types].xml"))
        assertTrue(entrees.containsKey("xl/workbook.xml"))
        assertTrue(entrees.containsKey("xl/worksheets/sheet1.xml"))
        assertTrue(entrees.containsKey("xl/worksheets/sheet2.xml"))
        assertTrue(entrees.containsKey("xl/styles.xml"))

        val detail = entrees["xl/worksheets/sheet1.xml"].orEmpty()
        assertTrue(detail.contains("Dépenses").not()) // the name lives in workbook.xml
        // Les dates sortent en jj/mm/aaaa, comme Fabrice les lit.
        assertTrue(detail.contains("12/10/2026"))
        assertTrue(detail.contains("Hôtel"))
        assertTrue(detail.contains("248.5") || detail.contains("248.50"))
        assertTrue(detail.contains("TOTAL"))

        val classeur = entrees["xl/workbook.xml"].orEmpty()
        assertTrue(classeur.contains("Dépenses"))
        assertTrue(classeur.contains("Synthèse"))

        val synthese = entrees["xl/worksheets/sheet2.xml"].orEmpty()
        assertTrue(synthese.contains("Mission Paris"))
        assertTrue(synthese.contains("337.5") || synthese.contains("337.50"))
    }

    @Test
    fun `colonne excel au dela de Z`() {
        val writer = XlsxWriter()
        assertEquals("A", writer.colonne(0))
        assertEquals("Z", writer.colonne(25))
        assertEquals("AA", writer.colonne(26))
        assertEquals("AB", writer.colonne(27))
    }
}
