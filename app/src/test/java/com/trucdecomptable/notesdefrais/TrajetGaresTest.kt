package com.trucdecomptable.notesdefrais

import com.trucdecomptable.notesdefrais.data.Depense
import com.trucdecomptable.notesdefrais.data.Projet
import com.trucdecomptable.notesdefrais.data.SensTrajet
import com.trucdecomptable.notesdefrais.data.TypeDepense
import com.trucdecomptable.notesdefrais.export.ExportManager
import com.trucdecomptable.notesdefrais.ocr.ReceiptParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Gares de départ et d'arrivée d'un trajet en train, et cas de l'aller-retour :
 * ce qui s'affiche, ce qui part dans le récap, et ce que l'OCR pré-remplit.
 */
class TrajetGaresTest {

    private fun trajet(
        type: TypeDepense = TypeDepense.TRAIN,
        depart: String = "Paris Gare de Lyon",
        arrivee: String = "Lyon Part-Dieu",
        sens: SensTrajet? = null,
        retour: String = "",
    ) = Depense(
        projetId = 1,
        date = "2026-10-12",
        montantCentimes = 4500,
        type = type.name,
        gareDepart = depart,
        gareArrivee = arrivee,
        sensTrajet = sens?.name.orEmpty(),
        dateRetour = retour,
    )

    @Test
    fun `gares affichees dans le sens du trajet`() {
        assertEquals("Paris Gare de Lyon → Lyon Part-Dieu", trajet().gares)
    }

    @Test
    fun `aller-retour lisible avec sa date de retour`() {
        val d = trajet(sens = SensTrajet.ALLER_RETOUR, retour = "2026-10-15")
        assertEquals(
            "Paris Gare de Lyon → Lyon Part-Dieu · aller-retour · retour le 15/10/2026",
            d.trajetLisible,
        )
    }

    @Test
    fun `aller simple sans date de retour`() {
        assertEquals(
            "Paris Gare de Lyon → Lyon Part-Dieu · aller simple",
            trajet(sens = SensTrajet.ALLER).trajetLisible,
        )
    }

    @Test
    fun `une seule gare renseignee ne casse pas la ligne`() {
        assertEquals("Paris Gare de Lyon", trajet(depart = "Paris Gare de Lyon", arrivee = "").gares)
        assertEquals("Lyon Part-Dieu", trajet(depart = "", arrivee = "Lyon Part-Dieu").gares)
        assertEquals("", trajet(depart = "", arrivee = "").trajetLisible)
    }

    @Test
    fun `recap excel - les gares et la date de retour ont leurs colonnes`() {
        val depense = trajet(sens = SensTrajet.ALLER_RETOUR, retour = "2026-10-15")
        val detail = ExportManager.feuilles(Projet(nom = "Formation CNCC"), listOf(depense)).first()

        val entetes = detail.lignes[0].map { it.texte }
        assertEquals("Sens", entetes[5])
        assertEquals("Gare départ", entetes[6])
        assertEquals("Gare arrivée", entetes[7])
        assertEquals("Date retour", entetes[8])

        val ligne = detail.lignes[1]
        assertEquals("Aller-retour", ligne[5].texte)
        assertEquals("Paris Gare de Lyon", ligne[6].texte)
        assertEquals("Lyon Part-Dieu", ligne[7].texte)
        assertEquals("15/10/2026", ligne[8].texte)
    }

    @Test
    fun `recap excel - les totaux restent alignes sur leurs colonnes`() {
        val detail = ExportManager.feuilles(Projet(nom = "Formation CNCC"), listOf(trajet())).first()
        val total = detail.lignes.last()

        assertEquals("TOTAL", total[0].texte)
        // 22 colonnes de détail : le total tombe sur « Montant EUR », la TVA et
        // les kilomètres, sans se décaler quand une colonne est ajoutée.
        assertEquals(22, total.size)
        assertEquals(45.0, total[15].nombre ?: 0.0, 0.001)
        assertEquals(0.0, total[18].nombre ?: 0.0, 0.001)
        assertEquals(0.0, total[19].nombre ?: 0.0, 0.001)
        assertTrue((total[21].texte ?: "").contains("dépense"))
    }

    @Test
    fun `ocr - une fleche entre deux gares remplit le trajet`() {
        val champs = ReceiptParser.parse(
            """
            SNCF VOYAGEURS
            PARIS GARE DE LYON → LYON PART DIEU
            12/10/2026 08:12
            TOTAL TTC 45,00
            """.trimIndent(),
        )
        assertEquals(TypeDepense.TRAIN, champs.type)
        assertEquals("PARIS GARE DE LYON", champs.gareDepart)
        assertEquals("LYON PART DIEU", champs.gareArrivee)
    }

    @Test
    fun `ocr - un libelle depart arrivee remplit le trajet`() {
        val champs = ReceiptParser.parse(
            """
            BILLET DE TRAIN
            Départ : PARIS MONTARNASSE 1
            Arrivée : BORDEAUX SAINT JEAN
            TOTAL TTC 62,00
            """.trimIndent(),
        )
        assertEquals("PARIS MONTARNASSE", champs.gareDepart)
        assertEquals("BORDEAUX SAINT JEAN", champs.gareArrivee)
    }

    @Test
    fun `ocr - une ligne de montant ne devient pas une gare`() {
        val champs = ReceiptParser.parse(
            """
            SNCF
            PRIX DU BILLET -> 45,00 EUR
            TOTAL TTC 45,00
            """.trimIndent(),
        )
        assertEquals("", champs.gareDepart)
        assertEquals("", champs.gareArrivee)
    }

    @Test
    fun `ocr - aucune gare hors train et avion`() {
        val champs = ReceiptParser.parse(
            """
            RESTAURANT LE BISTROT
            PARIS → LYON
            TOTAL TTC 32,00
            """.trimIndent(),
        )
        assertTrue(champs.type != TypeDepense.TRAIN)
        assertEquals("", champs.gareDepart)
        assertEquals("", champs.gareArrivee)
    }
}
