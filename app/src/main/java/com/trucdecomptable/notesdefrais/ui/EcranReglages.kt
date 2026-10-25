package com.trucdecomptable.notesdefrais.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.trucdecomptable.notesdefrais.data.CategorieVehicule
import com.trucdecomptable.notesdefrais.data.Parametres

/**
 * Everything the exports and the mileage allowance need to know: who is
 * claiming, which vehicle, and where the ZIP is mailed.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun EcranReglages(
    initial: Parametres,
    couvertureTaux: Pair<String, String>?,
    onRetour: () -> Unit,
    onEnregistrer: (Parametres) -> Unit,
    onSauvegarder: () -> Unit,
    onRestaurer: () -> Unit,
) {
    var prenom by remember { mutableStateOf(initial.prenom) }
    var nom by remember { mutableStateOf(initial.nom) }
    var societe by remember { mutableStateOf(initial.societe) }
    var adresse by remember { mutableStateOf(initial.adresse) }
    var siret by remember { mutableStateOf(initial.siret) }
    var email by remember { mutableStateOf(initial.emailDestinataire) }
    var plaque by remember { mutableStateOf(initial.plaque) }
    var categorie by remember { mutableStateOf(initial.categorie) }
    var cv by remember { mutableStateOf(initial.puissanceFiscale.toString()) }
    var electrique by remember { mutableStateOf(initial.electrique) }
    var kmAnnuels by remember { mutableStateOf(initial.kmAnnuels.toString()) }
    var devise by remember { mutableStateOf(initial.deviseDefaut) }
    var tauxTva by remember { mutableStateOf(initial.tvaDefautBp) }
    var zoneParis by remember { mutableStateOf(initial.zoneParis) }
    var moyens by remember { mutableStateOf(initial.moyensPaiement) }
    var paiementDefaut by remember { mutableStateOf(initial.moyenPaiementDefaut) }
    var nouveauMoyen by remember { mutableStateOf<String?>(null) }
    var code by remember { mutableStateOf("") }
    var codeHash by remember { mutableStateOf(initial.codeAccesHash) }

    fun courant() = Parametres(
        nom = nom.trim(),
        prenom = prenom.trim(),
        societe = societe.trim(),
        adresse = adresse.trim(),
        siret = siret.trim(),
        emailDestinataire = email.trim(),
        plaque = plaque.trim().uppercase(),
        categorieVehicule = categorie.name,
        puissanceFiscale = cv.toIntOrNull() ?: 0,
        electrique = electrique,
        kmAnnuels = kmAnnuels.toIntOrNull() ?: 0,
        deviseDefaut = devise,
        tvaDefautBp = tauxTva,
        zoneParis = zoneParis,
        codeAccesHash = if (code.isBlank()) codeHash else com.trucdecomptable.notesdefrais.util.empreinte(code),
        moyensPaiement = moyens,
        moyenPaiementDefaut = paiementDefaut.takeIf { it in moyens }.orEmpty(),
        // Already done: saving the settings must not trigger it a second time.
        paiementRempliAuto = initial.paiementRempliAuto,
    )

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Paramétrage") },
                navigationIcon = {
                    IconButton(onClick = onRetour) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Titulaire", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(prenom, { prenom = it }, label = { Text("Prénom") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(nom, { nom = it }, label = { Text("Nom") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(societe, { societe = it }, label = { Text("Société") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(adresse, { adresse = it }, label = { Text("Adresse") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(siret, { siret = it }, label = { Text("SIRET") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                email,
                { email = it },
                label = { Text("Email destinataire des notes de frais") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Véhicule et barème kilométrique", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(
                plaque,
                { plaque = it },
                label = { Text("Immatriculation") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CategorieVehicule.entries.forEach { c ->
                    FilterChip(selected = categorie == c, onClick = { categorie = c }, label = { Text(c.label) })
                }
            }
            OutlinedTextField(
                cv,
                { cv = it.filter { ch -> ch.isDigit() } },
                label = { Text("Puissance fiscale (CV)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = electrique, onCheckedChange = { electrique = it })
                Text("  Véhicule 100 % électrique (barème majoré de 20 %)", style = MaterialTheme.typography.bodyMedium)
            }
            OutlinedTextField(
                kmAnnuels,
                { kmAnnuels = it.filter { ch -> ch.isDigit() } },
                label = { Text("Km professionnels déjà parcourus cette année") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
            )
            Text(
                "Ce cumul choisit la tranche du barème ${com.trucdecomptable.notesdefrais.data.BaremeKm.ANNEE}. " +
                    "Il est augmenté automatiquement à chaque indemnité enregistrée. Laisser 0 pour rester dans la première tranche.",
                style = MaterialTheme.typography.bodySmall,
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Valeurs par défaut", style = MaterialTheme.typography.titleMedium)
            Text("Devise", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("EUR", "USD", "GBP", "CHF").forEach { code ->
                    FilterChip(selected = devise == code, onClick = { devise = code }, label = { Text(code) })
                }
            }
            Text("Taux de TVA", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(2000 to "20 %", 1000 to "10 %", 550 to "5,5 %", 0 to "Aucun").forEach { (bp, texte) ->
                    FilterChip(selected = tauxTva == bp, onClick = { tauxTva = bp }, label = { Text(texte) })
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = zoneParis, onCheckedChange = { zoneParis = it })
                Text(
                    "  Zone Paris / 92-93-94 (plafond hôtel plus élevé)",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Moyens de paiement", style = MaterialTheme.typography.titleMedium)
            Text(
                "Tes cartes et les autres façons de payer. La puce pleine est celle proposée par défaut sur une nouvelle dépense.",
                style = MaterialTheme.typography.bodySmall,
            )
            moyens.forEach { moyen ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    FilterChip(
                        selected = paiementDefaut == moyen,
                        onClick = { paiementDefaut = if (paiementDefaut == moyen) "" else moyen },
                        label = { Text(moyen) },
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = {
                        moyens = moyens - moyen
                        if (paiementDefaut == moyen) paiementDefaut = moyens.firstOrNull().orEmpty()
                    }) {
                        Icon(Icons.Default.Delete, contentDescription = "Retirer $moyen")
                    }
                }
            }
            OutlinedButton(onClick = { nouveauMoyen = "" }, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text("  Ajouter un moyen de paiement")
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Verrouillage", style = MaterialTheme.typography.titleMedium)
            Text(
                if (initial.verrouillageActif) "Code d'accès activé. Saisir un nouveau code pour le changer."
                else "Aucun code : l'app s'ouvre directement.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(
                value = code,
                onValueChange = { code = it.filter { c -> c.isDigit() }.take(8) },
                label = { Text("Nouveau code (chiffres)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                modifier = Modifier.fillMaxWidth(),
            )
            if (initial.verrouillageActif) {
                OutlinedButton(onClick = { codeHash = ""; code = "" }, modifier = Modifier.fillMaxWidth()) {
                    Text("Supprimer le code d'accès")
                }
            }

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Sauvegarde", style = MaterialTheme.typography.titleMedium)
            Text(
                couvertureTaux?.let { "Parités Banque de France : ${it.first} → ${it.second}" }
                    ?: "Parités Banque de France : pas encore téléchargées",
                style = MaterialTheme.typography.bodySmall,
            )
            Button(onClick = onSauvegarder, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Save, contentDescription = null)
                Text("  Sauvegarder maintenant")
            }
            OutlinedButton(onClick = onRestaurer, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.SettingsBackupRestore, contentDescription = null)
                Text("  Restaurer une sauvegarde")
            }
            Text(
                "Une sauvegarde complète (dépenses, justificatifs, paramétrage) est aussi créée " +
                    "automatiquement avant chaque mise à jour de l'app.",
                style = MaterialTheme.typography.bodySmall,
            )

            Button(onClick = { onEnregistrer(courant()) }, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text("Enregistrer le paramétrage")
            }
        }
    }

    nouveauMoyen?.let { valeur ->
        DialogueTexte(
            titre = "Nouveau moyen de paiement",
            libelle = "Nom (ex. CB BNP pro)",
            valeurInitiale = valeur,
            onAnnuler = { nouveauMoyen = null },
            onValider = { nom ->
                val propre = nom.trim()
                if (propre.isNotEmpty()) {
                    moyens = (moyens + propre).distinct()
                    if (paiementDefaut.isBlank()) paiementDefaut = propre
                }
                nouveauMoyen = null
            },
        )
    }
}
