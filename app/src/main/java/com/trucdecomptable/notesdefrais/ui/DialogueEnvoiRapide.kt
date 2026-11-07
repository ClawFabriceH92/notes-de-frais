package com.trucdecomptable.notesdefrais.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.trucdecomptable.notesdefrais.data.Projet
import com.trucdecomptable.notesdefrais.export.EnvoiMailto

/**
 * Fenêtre de l'envoi rapide (icône e-mail de l'accueil).
 *
 * Deux choix, sans jamais passer par une note de frais :
 * - l'adresse, préremplie avec le « mailto » de la société mais modifiable pour
 *   un envoi ponctuel ;
 * - la portée, toute la société ou un seul projet, pour distinguer les envois.
 *
 * L'adresse est vérifiée avant d'activer le bouton : une faute de frappe ne doit
 * pas produire un e-mail perdu.
 */
@Composable
fun DialogueEnvoiRapide(
    nomProfil: String,
    projets: List<Projet>,
    adresseInitiale: String,
    onFermer: () -> Unit,
    onEnvoyer: (adresse: String, projet: Projet?) -> Unit,
) {
    var adresse by remember { mutableStateOf(adresseInitiale) }
    var projetChoisi by remember { mutableStateOf<Projet?>(null) }
    val adresseOk = EnvoiMailto.adresseValide(adresse)

    AlertDialog(
        onDismissRequest = onFermer,
        title = { Text("Envoyer les justificatifs") },
        text = {
            Column(
                Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Envoi direct en PDF, sans note de frais à remplir.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )
                OutlinedTextField(
                    value = adresse,
                    onValueChange = { adresse = it },
                    label = { Text("À") },
                    singleLine = true,
                    isError = adresse.isNotBlank() && !adresseOk,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                if (adresse.isNotBlank() && !adresseOk) {
                    Text(
                        "Adresse incomplète.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                Text("Quoi envoyer", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = projetChoisi == null,
                        onClick = { projetChoisi = null },
                        label = { Text("Toute la société") },
                    )
                    if (projets.isNotEmpty()) {
                        FilterChip(
                            selected = projetChoisi != null,
                            onClick = { projetChoisi = projetChoisi ?: projets.first() },
                            label = { Text("Un projet") },
                        )
                    }
                }
                if (projets.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        projets.forEach { projet ->
                            FilterChip(
                                selected = projetChoisi?.id == projet.id,
                                onClick = { projetChoisi = projet },
                                label = { Text(projet.nom) },
                            )
                        }
                    }
                } else {
                    Text(
                        "Aucun projet : l'envoi couvrira tous les justificatifs de $nomProfil.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onEnvoyer(adresse.trim(), projetChoisi) },
                enabled = adresseOk,
            ) { Text("Envoyer") }
        },
        dismissButton = { TextButton(onClick = onFermer) { Text("Annuler") } },
    )
}
