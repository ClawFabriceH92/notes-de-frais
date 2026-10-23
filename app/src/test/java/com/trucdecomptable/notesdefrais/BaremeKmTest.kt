package com.trucdecomptable.notesdefrais

import com.trucdecomptable.notesdefrais.data.BaremeKm
import com.trucdecomptable.notesdefrais.data.CategorieVehicule
import com.trucdecomptable.notesdefrais.data.Parametres
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Barème kilométrique 2026, valeurs officielles (economie.gouv.fr, URSSAF,
 * article 6 B de l'annexe IV au CGI). Les exemples de l'administration servent
 * de référence : 4 000 km en 5 CV = 2 544 €, 4 000 km en 6 CV = 2 660 €.
 */
class BaremeKmTest {

    private fun centimes(euros: Double) = Math.round(euros * 100)

    @Test
    fun `exemple officiel 4000 km en 5 CV`() {
        val montant = BaremeKm.indemniteCentimes(4000, 0, CategorieVehicule.VOITURE, 5, false)
        assertEquals(centimes(2544.0), montant)
    }

    @Test
    fun `exemple officiel 4000 km en 6 CV`() {
        val montant = BaremeKm.indemniteCentimes(4000, 0, CategorieVehicule.VOITURE, 6, false)
        assertEquals(centimes(2660.0), montant)
    }

    @Test
    fun `3 CV et moins au dela de 7 CV utilisent la bonne ligne`() {
        assertEquals(centimes(0.529 * 1000), BaremeKm.indemniteCentimes(1000, 0, CategorieVehicule.VOITURE, 2, false))
        assertEquals(centimes(0.697 * 1000), BaremeKm.indemniteCentimes(1000, 0, CategorieVehicule.VOITURE, 9, false))
    }

    @Test
    fun `vehicule electrique majore de 20 pourcent`() {
        val thermique = BaremeKm.indemniteCentimes(1000, 0, CategorieVehicule.VOITURE, 5, false)
        val electrique = BaremeKm.indemniteCentimes(1000, 0, CategorieVehicule.VOITURE, 5, true)
        assertEquals(centimes(636.0), thermique)
        assertEquals(centimes(763.0), electrique)
    }

    @Test
    fun `deuxieme tranche - cumul 4800 puis 400 km`() {
        // F(d) = 0,357 d + 1 395 au-delà de 5 000 km.
        val avant = 0.636 * 4800
        val apres = 0.357 * 5200 + 1395
        val montant = BaremeKm.indemniteCentimes(400, 4800, CategorieVehicule.VOITURE, 5, false)
        assertEquals(centimes(apres - avant), montant)
    }

    @Test
    fun `troisieme tranche - au dela de 20000 km le coefficient seul s applique`() {
        // Au-delà de 20 000 km le barème officiel n'a plus de forfait : les deux
        // totaux annuels utilisent 0,470 €/km, donc le trajet vaut 0,470 × d.
        val montant = BaremeKm.indemniteCentimes(1000, 24000, CategorieVehicule.VOITURE, 7, false)
        assertEquals(centimes(470.0), montant)
    }

    @Test
    fun `moto et cyclomoteur`() {
        assertEquals(centimes(468.0), BaremeKm.indemniteCentimes(1000, 0, CategorieVehicule.MOTO, 3, false))
        assertEquals(centimes(315.0), BaremeKm.indemniteCentimes(1000, 0, CategorieVehicule.CYCLO, 1, false))
        // Moto électrique : 0,474 €/km dans la première tranche
        assertEquals(centimes(474.0), BaremeKm.indemniteCentimes(1000, 0, CategorieVehicule.MOTO, 1, true))
    }

    @Test
    fun `distance nulle ne produit rien`() {
        assertEquals(0L, BaremeKm.indemniteCentimes(0, 0, CategorieVehicule.VOITURE, 5, false))
        assertEquals(0L, BaremeKm.indemniteCentimes(-30, 0, CategorieVehicule.VOITURE, 5, false))
    }

    @Test
    fun `explication cite le bareme et la formule`() {
        val texte = BaremeKm.explication(250, 0, CategorieVehicule.VOITURE, 5, false)
        assertTrue(texte.contains("2026"))
        assertTrue(texte.contains("5 CV"))
        assertTrue(texte.contains("0,636 €/km"))
    }

    @Test
    fun `parametres serialises et relus sans perte`() {
        val p = Parametres(
            nom = "Heuvrard",
            prenom = "Fabrice",
            societe = "HL Conseil",
            adresse = "12 rue des Fleurs, 92600 Asnières",
            siret = "12345678900012",
            emailDestinataire = "compta@exemple.fr",
            plaque = "AB-123-CD",
            categorieVehicule = CategorieVehicule.MOTO.name,
            puissanceFiscale = 3,
            electrique = true,
            kmAnnuels = 4200,
            deviseDefaut = "CHF",
            tvaDefautBp = 1000,
        )
        val relu = Parametres.depuisJson(JSONObject(p.toJson().toString()))
        assertEquals(p, relu)
        assertEquals("HL Conseil · Fabrice Heuvrard", relu.mention)
        assertEquals(CategorieVehicule.MOTO, relu.categorie)
    }

    @Test
    fun `parametres vides restent utilisables`() {
        val p = Parametres.depuisJson(JSONObject("{}"))
        assertEquals("Notes de frais", p.mention)
        assertEquals(CategorieVehicule.VOITURE, p.categorie)
        assertEquals(20.0, p.tvaDefautBp / 100.0, 0.001)
    }
}
