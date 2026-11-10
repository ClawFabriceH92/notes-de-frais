package com.trucdecomptable.notesdefrais

import com.trucdecomptable.notesdefrais.backup.EchangeAtomique
import com.trucdecomptable.notesdefrais.backup.EchangeAtomique.Remplacement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

/**
 * La bascule par renommages : succès, échec simulé à mi-chemin (l'état
 * d'origine doit revenir intact, justificatifs compris) et reprise après la
 * mort du processus.
 */
class EchangeAtomiqueTest {

    @get:Rule
    val dossier = TemporaryFolder()

    private lateinit var donnees: File
    private lateinit var racine: File
    private lateinit var base: File
    private lateinit var wal: File
    private lateinit var shm: File
    private lateinit var pieces: File
    private lateinit var nouvelleBase: File
    private lateinit var nouveauWal: File
    private lateinit var nouvellesPieces: File

    @Before
    fun preparer() {
        donnees = dossier.newFolder("donnees")
        racine = File(donnees, "files/restauration-tmp")
        File(donnees, "databases").mkdirs()
        base = File(donnees, "databases/notes-de-frais.db").apply { writeText("ANCIENNE BASE") }
        wal = File(donnees, "databases/notes-de-frais.db-wal").apply { writeText("ANCIEN WAL") }
        shm = File(donnees, "databases/notes-de-frais.db-shm").apply { writeText("ANCIEN SHM") }
        pieces = File(donnees, "files/justificatifs").apply { mkdirs() }
        File(pieces, "ancien1.jpg").writeText("ANCIENNE PIECE 1")
        File(pieces, "ancien2.pdf").writeText("ANCIENNE PIECE 2")

        val nouveau = File(racine, "nouveau")
        File(nouveau, "db").mkdirs()
        nouvelleBase = File(nouveau, "db/notes-de-frais.db").apply { writeText("NOUVELLE BASE") }
        nouveauWal = File(nouveau, "db/notes-de-frais.db-wal").apply { writeText("NOUVEAU WAL") }
        nouvellesPieces = File(nouveau, "justificatifs").apply { mkdirs() }
        File(nouvellesPieces, "nouveau1.jpg").writeText("NOUVELLE PIECE")
    }

    private fun remplacements(avecWal: Boolean = true) = listOf(
        Remplacement(base, nouvelleBase),
        Remplacement(wal, if (avecWal) nouveauWal else null),
        Remplacement(shm, null),
        Remplacement(pieces, nouvellesPieces),
    )

    private fun verifierEtatOrigine() {
        assertEquals("ANCIENNE BASE", base.readText())
        assertEquals("ANCIEN WAL", wal.readText())
        assertEquals("ANCIEN SHM", shm.readText())
        assertEquals(setOf("ancien1.jpg", "ancien2.pdf"), pieces.list()!!.toSet())
        assertEquals("ANCIENNE PIECE 1", File(pieces, "ancien1.jpg").readText())
        assertEquals("ANCIENNE PIECE 2", File(pieces, "ancien2.pdf").readText())
    }

    @Test
    fun `succes le nouveau contenu est en place et l ancien est range de cote sans etre supprime`() {
        val echange = EchangeAtomique(racine)
        echange.echanger(remplacements())
        echange.valider()

        assertEquals("NOUVELLE BASE", base.readText())
        assertEquals("NOUVEAU WAL", wal.readText())
        assertFalse(shm.exists())
        assertEquals(listOf("nouveau1.jpg"), pieces.list()!!.toList())
        // Avant terminer : l'ancien contenu existe encore, dans le dossier temporaire.
        val ancien = File(racine, "ancien")
        assertEquals("ANCIENNE BASE", File(ancien, "0-notes-de-frais.db").readText())
        assertEquals("ANCIENNE PIECE 1", File(ancien, "3-justificatifs/ancien1.jpg").readText())
    }

    @Test
    fun `une base sans wal dans l archive retire le wal de l ancienne base`() {
        val echange = EchangeAtomique(racine)
        echange.echanger(remplacements(avecWal = false))
        assertEquals("NOUVELLE BASE", base.readText())
        assertFalse("un ancien -wal ne doit pas se rejouer sur la nouvelle base", wal.exists())
    }

    @Test
    fun `echec du dernier renommage base et justificatifs d origine intacts`() {
        var appels = 0
        val echange = EchangeAtomique(racine) { source, cible ->
            appels++
            // 4 premiers : base (ancien, nouveau) puis wal (ancien, nouveau) ; on échoue sur les justificatifs.
            if (source == nouvellesPieces) false else source.renameTo(cible)
        }
        try {
            echange.echanger(remplacements())
            fail("attendu : IOException")
        } catch (e: IOException) {
            assertTrue(e.message!!.contains("Renommage impossible"))
        }
        verifierEtatOrigine()
        assertTrue("l appel a bien eu lieu apres plusieurs renommages reussis", appels > 4)
    }

    @Test
    fun `echec a chaque rang possible l etat d origine revient toujours`() {
        val total = 7 // 4 mises de côté (base, wal, shm, pieces) + 3 mises en place (base, wal, pieces)
        for (echecAu in 1..total) {
            dossier.root.listFiles()?.forEach { it.deleteRecursively() }
            preparer()
            var appel = 0
            val echange = EchangeAtomique(racine) { source, cible ->
                appel++
                if (appel == echecAu) false else source.renameTo(cible)
            }
            try {
                echange.echanger(remplacements())
                fail("attendu : échec au renommage $echecAu")
            } catch (e: IOException) {
                // attendu
            }
            verifierEtatOrigine()
        }
    }

    @Test
    fun `une exception inattendue pendant l echange est aussi annulee`() {
        var appel = 0
        val echange = EchangeAtomique(racine) { source, cible ->
            if (++appel == 5) throw IllegalStateException("disque ejecte")
            source.renameTo(cible)
        }
        try {
            echange.echanger(remplacements())
            fail("attendu")
        } catch (e: IllegalStateException) {
            assertEquals("disque ejecte", e.message)
        }
        verifierEtatOrigine()
    }

    @Test
    fun `echec apres l echange annuler remet tout en place`() {
        val echange = EchangeAtomique(racine)
        echange.echanger(remplacements())
        // par exemple l'écriture des réglages échoue ensuite
        assertTrue(echange.annuler().isEmpty())
        verifierEtatOrigine()
    }

    @Test
    fun `reprise apres mort du processus avant la validation l etat d origine revient`() {
        EchangeAtomique(racine).echanger(remplacements()) // pas de valider() : le processus « meurt » ici
        assertEquals("NOUVELLE BASE", base.readText())

        val diagnostic = EchangeAtomique.recuperer(racine)

        assertNotNull(diagnostic)
        assertTrue(diagnostic!!.contains("état d'origine rétabli"))
        verifierEtatOrigine()
        assertFalse("le dossier temporaire est supprimé", racine.exists())
    }

    @Test
    fun `reprise apres mort du processus en plein echange`() {
        // Etat réel d'un crash après deux renommages : base d'origine rangée,
        // nouvelle base mise en place, journal sans « TERMINE ».
        val ancien = File(racine, "ancien").apply { mkdirs() }
        val rangee = File(ancien, "0-notes-de-frais.db")
        val journal = File(racine, "journal.txt")
        journal.appendText("RENOMMER\t${base.path}\t${rangee.path}\n")
        assertTrue(base.renameTo(rangee))
        journal.appendText("RENOMMER\t${nouvelleBase.path}\t${base.path}\n")
        assertTrue(nouvelleBase.renameTo(base))
        // Un renommage journalisé mais jamais exécuté (mort entre les deux) : sans effet.
        journal.appendText("RENOMMER\t${wal.path}\t${File(ancien, "1-notes-de-frais.db-wal").path}\n")
        assertEquals("NOUVELLE BASE", base.readText())

        EchangeAtomique.recuperer(racine)

        verifierEtatOrigine()
        assertFalse(racine.exists())
    }

    @Test
    fun `reprise apres une bascule validee on garde le nouveau et on nettoie`() {
        val echange = EchangeAtomique(racine)
        echange.echanger(remplacements())
        echange.valider()

        val diagnostic = EchangeAtomique.recuperer(racine)

        assertTrue(diagnostic!!.contains("terminée"))
        assertEquals("NOUVELLE BASE", base.readText())
        assertEquals(listOf("nouveau1.jpg"), pieces.list()!!.toList())
        assertFalse(racine.exists())
    }

    @Test
    fun `reprise sans rien a reprendre`() {
        assertNull(EchangeAtomique.recuperer(File(donnees, "absent")))
    }

    @Test
    fun `reprise apres un crash pendant l extraction simple nettoyage`() {
        // pas de journal : on n'a jamais touché à l'état courant
        val diagnostic = EchangeAtomique.recuperer(racine)
        assertTrue(diagnostic!!.contains("avant la bascule"))
        verifierEtatOrigine()
        assertFalse(racine.exists())
    }
}
