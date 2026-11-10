package com.trucdecomptable.notesdefrais

import com.trucdecomptable.notesdefrais.backup.ExtractionArchive
import com.trucdecomptable.notesdefrais.backup.RestaurationRefusee
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Lecture en flux de l'archive : écriture dans un dossier temporaire, jamais en mémoire. */
class ExtractionArchiveTest {

    @get:Rule
    val dossier = TemporaryFolder()

    private val meta = """{"version":"1.20.1","date":"2026-11-08T10:15:30","motif":"manuel","projets":1,"depenses":2,"justificatifs":2}"""

    private fun zip(vararg entrees: Pair<String, ByteArray>): ByteArray {
        val sortie = ByteArrayOutputStream()
        ZipOutputStream(sortie).use { z ->
            for ((nom, octets) in entrees) {
                z.putNextEntry(ZipEntry(nom))
                z.write(octets)
                z.closeEntry()
            }
        }
        return sortie.toByteArray()
    }

    /** Flux qui compte les octets réellement lus dans le ZIP. */
    private class Compteur(flux: InputStream) : FilterInputStream(flux) {
        var lus = 0L
        override fun read(): Int = super.read().also { if (it >= 0) lus++ }
        override fun read(b: ByteArray, off: Int, len: Int): Int =
            super.read(b, off, len).also { if (it > 0) lus += it }
    }

    private fun octetsAleatoires(taille: Int): ByteArray =
        ByteArray(taille).also { java.util.Random(42).nextBytes(it) } // incompressible

    @Test
    fun `l apercu lit meta et la base puis s arrete, sans parcourir les pieces`() {
        val gros = octetsAleatoires(3 * 1024 * 1024)
        val archive = zip(
            "meta.json" to meta.toByteArray(),
            "notes-de-frais.db" to "SQLite format 3\u0000".toByteArray(),
            "justificatifs/a.jpg" to gros,
        )
        val compteur = Compteur(ByteArrayInputStream(archive))
        val apercu = ExtractionArchive.apercu(compteur)
        assertTrue(apercu.baseVue)
        assertEquals(2, apercu.meta?.depenses)
        assertTrue("lu ${compteur.lus} octets sur ${archive.size}", compteur.lus < 200_000)
    }

    @Test
    fun `l apercu d une ancienne archive avec meta en queue parcourt tout sans rien stocker`() {
        val archive = zip(
            "notes-de-frais.db" to "SQLite format 3\u0000".toByteArray(),
            "justificatifs/a.jpg" to octetsAleatoires(100_000),
            "meta.json" to meta.toByteArray(),
        )
        val apercu = ExtractionArchive.apercu(ByteArrayInputStream(archive))
        assertTrue(apercu.baseVue)
        assertEquals("1.20.1", apercu.meta?.version)
    }

    @Test
    fun `l apercu signale l absence de base et de meta`() {
        val apercu = ExtractionArchive.apercu(ByteArrayInputStream(zip("lisez-moi.txt" to "x".toByteArray())))
        assertFalse(apercu.baseVue)
        assertNull(apercu.meta)
    }

    @Test
    fun `un fichier qui n est pas un zip ne donne ni base ni meta`() {
        val apercu = ExtractionArchive.apercu(ByteArrayInputStream("du texte".toByteArray()))
        assertFalse(apercu.baseVue)
        assertNull(apercu.meta)
    }

    @Test
    fun `l apercu refuse une entree qui sort du dossier`() {
        val archive = zip("../../evil" to "x".toByteArray())
        try {
            ExtractionArchive.apercu(ByteArrayInputStream(archive))
            fail("attendu : refus")
        } catch (e: RestaurationRefusee) {
            assertTrue(e.message!!.contains("evil"))
        }
    }

    @Test
    fun `l extraction range base, pieces, reglages et meta dans le dossier temporaire`() {
        val base = "SQLite format 3\u0000".toByteArray() + ByteArray(200) { 7 }
        val piece = octetsAleatoires(5000)
        val archive = zip(
            "meta.json" to meta.toByteArray(),
            "notes-de-frais.db" to base,
            "notes-de-frais.db-wal" to byteArrayOf(1, 2, 3),
            "notes-de-frais.db-shm" to byteArrayOf(9, 9),
            "justificatifs/a.jpg" to piece,
            "justificatifs/b.pdf" to byteArrayOf(5),
            "parametres.json" to """{"profilCourant":3}""".toByteArray(),
        )
        val destination = dossier.newFolder("nouveau")
        val extrait = ExtractionArchive.extraire(ByteArrayInputStream(archive), destination, budget = Long.MAX_VALUE)

        assertTrue(extrait.baseVue)
        assertEquals(2, extrait.justificatifs)
        assertEquals("1.20.1", extrait.meta?.version)
        assertArrayEquals(base, extrait.base!!.readBytes())
        assertArrayEquals(byteArrayOf(1, 2, 3), File(destination, "db/notes-de-frais.db-wal").readBytes())
        assertFalse("le -shm n est jamais restaure", File(destination, "db/notes-de-frais.db-shm").exists())
        assertArrayEquals(piece, File(destination, "justificatifs/a.jpg").readBytes())
        assertEquals("""{"profilCourant":3}""", extrait.parametres!!.readText())
        assertEquals(base.size + 3 + piece.size + 1 + """{"profilCourant":3}""".length + meta.length.toLong(), extrait.octets)
    }

    @Test
    fun `une archive sans base est signalee par l extraction`() {
        val archive = zip("meta.json" to meta.toByteArray(), "justificatifs/a.jpg" to byteArrayOf(1))
        val extrait = ExtractionArchive.extraire(ByteArrayInputStream(archive), dossier.newFolder("n"), Long.MAX_VALUE)
        assertFalse(extrait.baseVue)
        assertNull(extrait.base)
    }

    @Test
    fun `le budget d ecriture est respecte, l archive trop grosse est refusee`() {
        val archive = zip(
            "notes-de-frais.db" to ByteArray(1_000_000),   // se compresse à presque rien
            "justificatifs/a.jpg" to ByteArray(1_000_000),
        )
        assertTrue("l archive est petite", archive.size < 10_000)
        try {
            ExtractionArchive.extraire(ByteArrayInputStream(archive), dossier.newFolder("n"), budget = 1_500_000)
            fail("attendu : refus pour espace")
        } catch (e: RestaurationRefusee) {
            assertTrue(e.message!!.contains("Espace insuffisant"))
        }
    }

    @Test
    fun `un parametres json demesure est refuse`() {
        val archive = zip("parametres.json" to ByteArray(6 * 1024 * 1024) { 'a'.code.toByte() })
        try {
            ExtractionArchive.extraire(ByteArrayInputStream(archive), dossier.newFolder("n"), Long.MAX_VALUE)
            fail("attendu : refus")
        } catch (e: RestaurationRefusee) {
            assertTrue(e.message!!.contains("volumineuse"))
        }
    }

    @Test
    fun `l extraction refuse un chemin suspect et ne depose rien en dehors du dossier`() {
        val archive = zip("justificatifs/../../dehors.txt" to "x".toByteArray())
        val destination = dossier.newFolder("nouveau")
        try {
            ExtractionArchive.extraire(ByteArrayInputStream(archive), destination, Long.MAX_VALUE)
            fail("attendu : refus")
        } catch (e: RestaurationRefusee) {
            // attendu
        }
        assertFalse(File(destination.parentFile, "dehors.txt").exists())
        assertFalse(File(destination, "dehors.txt").exists())
    }

    @Test
    fun `l annulation interrompt la lecture`() {
        val archive = zip("notes-de-frais.db" to ByteArray(500_000))
        var appels = 0
        try {
            ExtractionArchive.extraire(ByteArrayInputStream(archive), dossier.newFolder("n"), Long.MAX_VALUE) {
                if (++appels > 3) throw java.util.concurrent.CancellationException("annulé")
            }
            fail("attendu : annulation")
        } catch (e: java.util.concurrent.CancellationException) {
            assertNotNull(e)
        }
    }

    @Test
    fun `lecture de l en-tete et taille cumulee`() {
        val f = dossier.newFile("x.db").apply { writeBytes(ByteArray(300) { 1 }) }
        assertEquals(100, ExtractionArchive.lireEntete(f).size)
        val court = dossier.newFile("c.db").apply { writeBytes(ByteArray(10)) }
        assertEquals(10, ExtractionArchive.lireEntete(court).size)
        val d = dossier.newFolder("d")
        File(d, "a").writeBytes(ByteArray(5))
        File(d, "s").mkdirs()
        File(d, "s/b").writeBytes(ByteArray(7))
        assertEquals(12, ExtractionArchive.taille(d))
        assertEquals(300, ExtractionArchive.taille(f))
        assertEquals(0, ExtractionArchive.taille(File(d, "absent")))
    }
}
