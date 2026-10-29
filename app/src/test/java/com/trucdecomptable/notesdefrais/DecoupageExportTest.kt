package com.trucdecomptable.notesdefrais

import com.trucdecomptable.notesdefrais.data.Depense
import com.trucdecomptable.notesdefrais.export.ExportManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.RandomAccessFile

class DecoupageExportTest {

    private fun justificatif(id: Long): ExportManager.Justificatif = ExportManager.Justificatif(
        depense = Depense(id = id, projetId = 1, date = "2026-10-12", montantCentimes = 1000 * id),
        numero = id.toInt(),
        noms = listOf("piece-$id.pdf"),
    )

    private val mo = 1024L * 1024L

    /** Fichier de la taille voulue, sans écrire réellement les octets (creux). */
    private fun fichier(nom: String, taille: Long): File {
        val fichier = File(createTempDir(), nom)
        RandomAccessFile(fichier, "rw").use { it.setLength(taille) }
        return fichier
    }

    @Test
    fun `les tas restent sous le seuil et couvrent tous les justificatifs`() {
        val pieces = (1L..6L).map { justificatif(it) }
        // 1 à 6 Mo : 21 Mo au total, aucun tas ne doit dépasser 10 Mo.
        val poids = pieces.associateWith { it.depense.id * mo }
        val tas = ExportManager.repartirEnTas(pieces, poids, 10 * mo)

        assertEquals(pieces.size, tas.sumOf { it.size })
        tas.forEach { contenu ->
            val charge = contenu.sumOf { poids[it] ?: 0L }
            assertTrue("tas de $charge octets", charge <= 10 * mo)
        }
        assertTrue("au moins deux tas attendus", tas.size >= 2)
        // Aucune pièce perdue ni dupliquée.
        assertEquals(pieces.toSet(), tas.flatten().toSet())
    }

    @Test
    fun `chaque partie garde l ordre chronologique de l export`() {
        val pieces = (1L..5L).map { justificatif(it) }
        val poids = pieces.associateWith { it.depense.id * mo }
        val tas = ExportManager.repartirEnTas(pieces, poids, 4 * mo)

        tas.forEach { contenu ->
            val rangs = contenu.map { it.depense.id }
            assertEquals(rangs.sorted(), rangs)
        }
        // La partie 1 commence par la dépense la plus ancienne.
        assertEquals(1L, tas.first().first().depense.id)
    }

    @Test
    fun `une piece plus lourde que le seuil part seule`() {
        val pieces = listOf(justificatif(1), justificatif(2))
        val poids = mapOf(pieces[0] to 1 * mo, pieces[1] to 25 * mo)
        val tas = ExportManager.repartirEnTas(pieces, poids, 10 * mo)

        // Deux tas quand même : la pièce de 25 Mo ne peut pas être ignorée. Ils
        // restent ordonnés par date, donc la petite pièce ouvre la partie 1.
        assertEquals(2, tas.size)
        assertEquals(listOf(pieces[0]), tas[0])
        assertEquals(listOf(pieces[1]), tas[1])
    }

    @Test
    fun `le seuil de decoupage est bien 10 Mo`() {
        assertEquals(10L * 1024 * 1024, ExportManager.SEUIL_DECOUPAGE)
    }

    @Test
    fun `les envois de pieces jointes restent sous le seuil`() {
        val a = fichier("a.pdf", 6 * mo)
        val b = fichier("b.pdf", 6 * mo)
        val c = fichier("c.pdf", 3 * mo)
        val envois = ExportManager.repartirEnEnvois(listOf(a, b, c))

        // a (6 Mo) seul, puis b + c (9 Mo) : deux mails suffisent.
        assertEquals(2, envois.size)
        assertEquals(listOf(a), envois[0])
        assertEquals(listOf(b, c), envois[1])
        envois.forEach { lot ->
            assertTrue(lot.sumOf { it.length() } <= ExportManager.SEUIL_DECOUPAGE)
        }
    }

    @Test
    fun `un seul envoi quand tout tient sous le seuil`() {
        val fichiers = listOf(fichier("a.pdf", 2 * mo), fichier("b.pdf", 3 * mo))
        val envois = ExportManager.repartirEnEnvois(fichiers)
        assertEquals(1, envois.size)
        assertEquals(fichiers, envois[0])
    }

    @Test
    fun `aucun envoi pour une liste vide`() {
        assertTrue(ExportManager.repartirEnEnvois(emptyList()).isEmpty())
    }
}
