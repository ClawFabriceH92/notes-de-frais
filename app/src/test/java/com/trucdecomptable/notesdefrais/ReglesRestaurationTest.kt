package com.trucdecomptable.notesdefrais

import com.trucdecomptable.notesdefrais.backup.CibleEntree
import com.trucdecomptable.notesdefrais.backup.Decision
import com.trucdecomptable.notesdefrais.backup.MetaArchive
import com.trucdecomptable.notesdefrais.backup.ReglesRestauration
import com.trucdecomptable.notesdefrais.backup.ReglesRestauration.ArchiveFichier
import com.trucdecomptable.notesdefrais.export.ExportManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Les règles décidables de la restauration : tout ce qui se juge sans Android. */
class ReglesRestaurationTest {

    private val mo = 1024L * 1024

    // ------------------------------------------------------------ meta.json

    @Test
    fun `meta lue depuis un meta json complet`() {
        val meta = ReglesRestauration.lireMeta(
            """{"version":"1.20.1","date":"2026-11-08T10:15:30.123","motif":"manuel","projets":3,"depenses":12,"justificatifs":14}""",
        )
        assertEquals(MetaArchive("1.20.1", "2026-11-08T10:15:30.123", "manuel", 3, 12, 14), meta)
    }

    @Test
    fun `meta absente, vide ou illisible donne null`() {
        assertNull(ReglesRestauration.lireMeta(null))
        assertNull(ReglesRestauration.lireMeta(""))
        assertNull(ReglesRestauration.lireMeta("pas du json"))
    }

    @Test
    fun `un champ manquant ou negatif reste inconnu et n est pas confondu avec zero`() {
        val meta = ReglesRestauration.lireMeta("""{"version":"1.2.0","depenses":-4}""")!!
        assertNull(meta.depenses)
        assertNull(meta.projets)
        assertNull(meta.justificatifs)
    }

    @Test
    fun `la date est affichee en francais, le texte illisible est rendu tel quel`() {
        assertEquals("08/11/2026 à 10:15", ReglesRestauration.formaterDate("2026-11-08T10:15:30.123"))
        assertEquals("hier soir", ReglesRestauration.formaterDate("hier soir"))
        assertEquals("date inconnue", ReglesRestauration.formaterDate(null))
    }

    @Test
    fun `comparaison de versions`() {
        assertTrue(ReglesRestauration.comparerVersions("1.20.1", "1.20.0") > 0)
        assertTrue(ReglesRestauration.comparerVersions("1.9.0", "1.20.0") < 0)
        assertEquals(0, ReglesRestauration.comparerVersions("1.20", "1.20.0"))
        assertTrue(ReglesRestauration.comparerVersions("2.0.0", "1.99.99") > 0)
    }

    // ------------------------------------------------------------ décision

    private val metaOk = MetaArchive("1.20.1", "2026-11-08T10:15:30", "manuel", 3, 12, 14)

    @Test
    fun `sans base dans l archive la restauration est refusee`() {
        val decision = ReglesRestauration.decider(metaOk, baseVue = false, versionApp = "1.20.1")
        assertTrue(decision is Decision.Refusee)
    }

    @Test
    fun `une archive normale est autorisee sans avertissement`() {
        val decision = ReglesRestauration.decider(metaOk, baseVue = true, versionApp = "1.20.1")
        assertEquals(Decision.Autorisee(emptyList()), decision)
    }

    @Test
    fun `archive sans meta autorisee mais avec avertissement`() {
        val decision = ReglesRestauration.decider(null, baseVue = true, versionApp = "1.20.1") as Decision.Autorisee
        assertEquals(1, decision.avertissements.size)
        assertTrue(decision.avertissements.first().contains("meta.json"))
    }

    @Test
    fun `archive d une version plus recente et archive vide donnent un avertissement`() {
        val recente = ReglesRestauration.decider(metaOk.copy(version = "1.21.0"), true, "1.20.1") as Decision.Autorisee
        assertTrue(recente.avertissements.single().contains("1.21.0"))
        val vide = ReglesRestauration.decider(metaOk.copy(projets = 0, depenses = 0, justificatifs = 0), true, "1.20.1") as Decision.Autorisee
        assertTrue(vide.avertissements.single().contains("vide"))
    }

    @Test
    fun `la confirmation annonce l archive, ce qui sera ecrase et la sauvegarde de securite`() {
        val texte = ReglesRestauration.lignesConfirmation(metaOk, emptyList(), 5, 40, 22).joinToString("\n")
        assertTrue(texte.contains("08/11/2026 à 10:15"))
        assertTrue(texte.contains("version 1.20.1"))
        assertTrue(texte.contains("12 dépense(s), 3 projet(s) et 14 pièce(s)"))
        assertTrue(texte.contains("40 dépense(s), 5 projet(s)"))
        assertTrue(texte.contains("22 justificatif(s)"))
        assertTrue(texte.contains("sauvegarde de l'état actuel"))
        assertFalse(texte.contains("—"))
    }

    @Test
    fun `la confirmation reprend les avertissements et marque les nombres inconnus`() {
        val texte = ReglesRestauration.lignesConfirmation(
            MetaArchive("1.0", null, null, null, null, null), listOf("ATTENTION"), 0, 0, 0,
        ).joinToString("\n")
        assertTrue(texte.contains("? dépense(s)"))
        assertTrue(texte.contains("ATTENTION"))
    }

    // ------------------------------------------------------------ entrées du ZIP

    @Test
    fun `les entrees connues sont classees`() {
        assertEquals(CibleEntree.Meta, ReglesRestauration.classer("meta.json", false))
        assertEquals(CibleEntree.Parametres, ReglesRestauration.classer("parametres.json", false))
        assertEquals(CibleEntree.Base(""), ReglesRestauration.classer("notes-de-frais.db", false))
        assertEquals(CibleEntree.Base("-wal"), ReglesRestauration.classer("notes-de-frais.db-wal", false))
        assertEquals(CibleEntree.Ignoree, ReglesRestauration.classer("notes-de-frais.db-shm", false))
        assertEquals(CibleEntree.Justificatif("a.jpg"), ReglesRestauration.classer("justificatifs/a.jpg", false))
        assertEquals(CibleEntree.Ignoree, ReglesRestauration.classer("justificatifs/", true))
        assertEquals(CibleEntree.Ignoree, ReglesRestauration.classer("autre.txt", false))
    }

    @Test
    fun `un chemin qui sort du dossier est suspect`() {
        assertTrue(ReglesRestauration.classer("../../databases/x", false) is CibleEntree.Suspecte)
        assertTrue(ReglesRestauration.classer("justificatifs/../../x.jpg", false) is CibleEntree.Suspecte)
        assertTrue(ReglesRestauration.classer("/data/data/x", false) is CibleEntree.Suspecte)
        assertTrue(ReglesRestauration.classer("justificatifs\\..\\x", false) is CibleEntree.Suspecte)
    }

    @Test
    fun `un sous dossier de justificatifs n est pas aplati`() {
        assertEquals(CibleEntree.Ignoree, ReglesRestauration.classer("justificatifs/sous/a.jpg", false))
    }

    @Test
    fun `les constantes suivent celles de l export`() {
        assertEquals(ExportManager.DOSSIER_JUSTIFICATIFS, ReglesRestauration.DOSSIER_JUSTIFICATIFS)
    }

    // ------------------------------------------------------------ espace

    @Test
    fun `espace necessaire deux fois l archive, l etat courant et une marge`() {
        val besoin = ReglesRestauration.espaceNecessaire(100 * mo, 50 * mo)
        assertEquals(200 * mo + 50 * mo + ReglesRestauration.MARGE_ESPACE, besoin)
    }

    @Test
    fun `taille d archive inconnue estimation prudente`() {
        val besoin = ReglesRestauration.espaceNecessaire(-1, 10 * mo)
        assertTrue(besoin >= 64 * mo * 2)
        val gros = ReglesRestauration.espaceNecessaire(0, 300 * mo)
        assertTrue(gros >= 600 * mo * 2 + 300 * mo)
    }

    @Test
    fun `espace suffisant exactement au seuil, insuffisant d un octet en dessous`() {
        val besoin = ReglesRestauration.espaceNecessaire(80 * mo, 20 * mo)
        assertTrue(ReglesRestauration.espaceSuffisant(besoin, 80 * mo, 20 * mo))
        assertFalse(ReglesRestauration.espaceSuffisant(besoin - 1, 80 * mo, 20 * mo))
    }

    @Test
    fun `le budget d extraction laisse la place de la sauvegarde de securite`() {
        assertEquals(1000 * mo - 200 * mo - ReglesRestauration.MARGE_ESPACE, ReglesRestauration.budgetExtraction(1000 * mo, 200 * mo))
        assertEquals(0, ReglesRestauration.budgetExtraction(10 * mo, 200 * mo))
    }

    @Test
    fun `le message d espace insuffisant donne les deux chiffres`() {
        val message = ReglesRestauration.messageEspaceInsuffisant(120 * mo, 500 * mo)
        assertTrue(message.contains("500 Mo"))
        assertTrue(message.contains("120 Mo"))
        assertTrue(message.contains("rien n'a été modifié"))
    }

    // ------------------------------------------------------------ base SQLite

    private fun entete(signature: String = "SQLite format 3\u0000"): ByteArray =
        signature.toByteArray(Charsets.ISO_8859_1) + ByteArray(84)

    @Test
    fun `en-tete SQLite valide`() {
        assertTrue(ReglesRestauration.enteteSqliteValide(entete(), 4096))
    }

    @Test
    fun `un fichier texte, tronque ou vide n est pas une base`() {
        assertFalse(ReglesRestauration.enteteSqliteValide("ceci est du texte, pas une base".toByteArray(), 4096))
        assertFalse(ReglesRestauration.enteteSqliteValide(entete(), 99))
        assertFalse(ReglesRestauration.enteteSqliteValide(ByteArray(0), 0))
        assertFalse(ReglesRestauration.enteteSqliteValide(entete("SQLite format 2\u0000"), 4096))
    }

    private val tables = setOf("projets", "depenses", "profils", "room_master_table")

    @Test
    fun `base saine a la version de l application acceptee`() {
        assertNull(ReglesRestauration.verifierBase(15, 15, listOf("ok"), tables))
    }

    @Test
    fun `base plus ancienne acceptee, Room la migrera`() {
        assertNull(ReglesRestauration.verifierBase(9, 15, listOf("ok"), tables))
    }

    @Test
    fun `version superieure a celle de l application refusee`() {
        val refus = ReglesRestauration.verifierBase(99, 15, listOf("ok"), tables)
        assertNotNull(refus)
        assertTrue(refus!!.contains("99"))
        assertTrue(refus.contains("15"))
        assertNotNull(ReglesRestauration.verifierBase(16, 15, listOf("ok"), tables))
    }

    @Test
    fun `version zero refusee, ce n est pas une base Room`() {
        assertNotNull(ReglesRestauration.verifierBase(0, 15, listOf("ok"), tables))
    }

    @Test
    fun `quick_check different de ok refuse`() {
        val refus = ReglesRestauration.verifierBase(15, 15, listOf("row 3 missing from index idx_x"), tables)
        assertNotNull(refus)
        assertTrue(refus!!.contains("corrompue"))
        assertNotNull(ReglesRestauration.verifierBase(15, 15, emptyList(), tables))
        assertNotNull(ReglesRestauration.verifierBase(15, 15, listOf("ok", "autre"), tables))
    }

    @Test
    fun `tables attendues absentes refusees`() {
        val refus = ReglesRestauration.verifierBase(15, 15, listOf("ok"), setOf("autre"))
        assertNotNull(refus)
        assertTrue(refus!!.contains("depenses"))
    }

    @Test
    fun `meme version mais schema different refuse, version differente ignoree`() {
        assertNotNull(ReglesRestauration.verifierBase(15, 15, listOf("ok"), tables, "aaa", "bbb"))
        assertNull(ReglesRestauration.verifierBase(15, 15, listOf("ok"), tables, "aaa", "aaa"))
        assertNull(ReglesRestauration.verifierBase(14, 15, listOf("ok"), tables, "aaa", "bbb"))
        assertNull(ReglesRestauration.verifierBase(15, 15, listOf("ok"), tables, null, "bbb"))
    }

    // ------------------------------------------------------------ rétention

    private fun avant(horodatage: String, modifie: Long) =
        ArchiveFichier("notes-de-frais-avant-restauration-$horodatage.zip", modifie)

    private fun autre(motif: String, horodatage: String, modifie: Long) =
        ArchiveFichier("notes-de-frais-$motif-$horodatage.zip", modifie)

    @Test
    fun `on reconnait les archives avant restauration`() {
        assertTrue(ReglesRestauration.estAvantRestauration("notes-de-frais-avant-restauration-20261108-101530.zip"))
        assertFalse(ReglesRestauration.estAvantRestauration("notes-de-frais-avant-maj-20261108-101530.zip"))
        assertFalse(ReglesRestauration.estAvantRestauration("notes-de-frais-avant-restauration-20261108-101530.zip.tmp"))
    }

    @Test
    fun `on garde les 3 archives avant restauration les plus recentes`() {
        val liste = (1..5).map { avant("2026110$it", it * 1000L) }
        val supprimes = ReglesRestauration.aSupprimer(liste, maintenant = 10_000L)
        assertEquals(
            setOf("notes-de-frais-avant-restauration-20261102.zip", "notes-de-frais-avant-restauration-20261101.zip"),
            supprimes.toSet(),
        )
    }

    @Test
    fun `la rotation des 10 sauvegardes generales n emporte jamais une archive avant restauration`() {
        val filet = avant("20260101", 1L) // la plus ancienne de toutes
        val generales = (1..15).map { autre("manuel", "202611%02d".format(it), 1000L + it) }
        val supprimes = ReglesRestauration.aSupprimer(generales + filet, maintenant = 100_000L)
        assertFalse(filet.nom in supprimes)
        assertEquals(5, supprimes.size)
        assertTrue(supprimes.all { it.contains("manuel") })
    }

    @Test
    fun `les generales gardent les 10 plus recentes, parametrage compris`() {
        val liste = (1..12).map { autre("manuel", "202611%02d".format(it), it.toLong()) } +
            ArchiveFichier("parametrage-20261101-000000.json", 0L)
        val supprimes = ReglesRestauration.aSupprimer(liste, maintenant = 1000L)
        assertEquals(3, supprimes.size)
        assertTrue("parametrage-20261101-000000.json" in supprimes)
    }

    @Test
    fun `apres une restauration seules les avant restauration sont purgees`() {
        val generales = (1..15).map { autre("manuel", "202611%02d".format(it), 1000L + it) }
        val avants = (1..4).map { avant("2026120$it", 5000L + it) }
        val supprimes = ReglesRestauration.aSupprimer(generales + avants, 100_000L, garderGenerales = Int.MAX_VALUE)
        assertEquals(listOf("notes-de-frais-avant-restauration-20261201.zip"), supprimes)
    }

    @Test
    fun `on ne supprime jamais la derniere archive avant restauration meme avec un quota nul`() {
        val liste = listOf(avant("20261101", 1L), avant("20261102", 2L))
        val supprimes = ReglesRestauration.aSupprimer(liste, 10L, garderAvantRestauration = 0)
        assertEquals(listOf("notes-de-frais-avant-restauration-20261101.zip"), supprimes)
    }

    @Test
    fun `un fichier partiel recent est laisse, un partiel abandonne est supprime`() {
        val heure = 60L * 60 * 1000
        val liste = listOf(
            ArchiveFichier("notes-de-frais-manuel-1.zip.tmp", 100_000_000L),
            ArchiveFichier("notes-de-frais-manuel-2.zip.tmp", 100_000_000L - 2 * heure),
        )
        val supprimes = ReglesRestauration.aSupprimer(liste, maintenant = 100_000_000L + 1000)
        assertEquals(listOf("notes-de-frais-manuel-2.zip.tmp"), supprimes)
    }
}
