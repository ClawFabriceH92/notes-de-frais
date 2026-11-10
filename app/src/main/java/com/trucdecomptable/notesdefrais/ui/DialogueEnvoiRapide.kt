package com.trucdecomptable.notesdefrais.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.unit.dp
import com.trucdecomptable.notesdefrais.data.Profil
import com.trucdecomptable.notesdefrais.data.Projet
import com.trucdecomptable.notesdefrais.export.EnvoiMailto

/**
 * Fenêtre de l'envoi rapide (icône e-mail de l'accueil).
 *
 * Trois choix, sans jamais passer par une note de frais :
 * - la **société** : chacune a sa propre adresse d'envoi, enregistrée dans le
 *   paramétrage ; la choisir remplit l'adresse et restreint les projets à ceux
 *   de cette société. Sans ce choix, l'adresse de la société en cours servait
 *   pour toutes, et une facture pouvait partir au mauvais cabinet ;
 * - l'adresse, préremplie mais modifiable pour un envoi ponctuel ;
 * - la portée, toute la société ou un seul projet, pour distinguer les envois.
 *
 * L'adresse est vérifiée avant d'activer le bouton : une faute de frappe ne doit
 * pas produire un e-mail perdu.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun DialogueEnvoiRapide(
    nomProfil: String,
    profils: List<Profil>,
    projets: List<Projet>,
    profilInitial: Profil?,
    adresseInitiale: String,
    pieces: List<NotesViewModel.PhotoEnvoi> = emptyList(),
    onPrendrePhoto: (() -> Unit)? = null,
    onAjouterPage: ((NotesViewModel.PhotoEnvoi) -> Unit)? = null,
    onRetirerPiece: ((NotesViewModel.PhotoEnvoi) -> Unit)? = null,
    onFermer: () -> Unit,
    onEnvoyer: (adresse: String, profil: Profil?, projet: Projet?) -> Unit,
) {
    var societe by remember { mutableStateOf(profilInitial) }
    var adresse by remember { mutableStateOf(adresseInitiale) }
    var projetChoisi by remember { mutableStateOf<Projet?>(null) }
    val adresseOk = EnvoiMailto.adresseValide(adresse)

    // Les projets proposés sont ceux de la société choisie : envoyer les
    // justificatifs d'une autre société à cette adresse n'aurait aucun sens.
    val projetsDeLaSociete = remember(projets, societe) {
        if (societe == null) projets else projets.filter { it.profilId == societe?.id }
    }

    AlertDialog(
        onDismissRequest = onFermer,
        title = { Text("Envoyer les justificatifs") },
        text = {
            Column(
                Modifier.heightIn(max = 460.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    "Envoi direct en PDF, sans note de frais à remplir.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.outline,
                )

                if (profils.isNotEmpty()) {
                    Text(
                        "Société",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        profils.forEach { profil ->
                            FilterChip(
                                selected = societe?.id == profil.id,
                                onClick = {
                                    societe = profil
                                    projetChoisi = null
                                    // Chaque société porte son adresse : la
                                    // choisir remplace celle qui était saisie.
                                    profil.adresseEnvoi
                                        .takeIf { it.isNotBlank() }
                                        ?.let { adresse = it }
                                },
                                label = { Text(profil.nom) },
                            )
                        }
                    }
                    if (societe != null && societe?.adresseEnvoi.isNullOrBlank()) {
                        Text(
                            "Aucune adresse d'envoi enregistrée pour ${societe?.nom} : " +
                                "à saisir ci-dessous, ou dans le paramétrage de la société.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }

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

                // Photos prises à la volée depuis cette fenêtre : elles partent
                // en PDF sans créer de note de frais ni de projet.
                if (onPrendrePhoto != null) {
                    Text(
                        "Photos à joindre",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.SemiBold,
                    )
                    if (pieces.isEmpty()) {
                        Text(
                            "Aucune photo. « Prendre une photo » joint un ticket ou une facture " +
                                "directement à cet envoi, sans créer de dépense.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                    pieces.forEachIndexed { index, piece ->
                        Column {
                            Text(
                                "Photo ${index + 1} · " +
                                    if (piece.pages.size > 1) "${piece.pages.size} pages" else "1 page",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (onAjouterPage != null) {
                                    TextButton(onClick = { onAjouterPage(piece) }) {
                                        Text("Ajouter la page ${piece.pages.size + 1}")
                                    }
                                }
                                if (onRetirerPiece != null) {
                                    TextButton(onClick = { onRetirerPiece(piece) }) { Text("Retirer") }
                                }
                            }
                        }
                    }
                    TextButton(onClick = onPrendrePhoto) { Text("Prendre une photo") }
                }

                Text("Quoi envoyer", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        selected = projetChoisi == null,
                        onClick = { projetChoisi = null },
                        label = { Text("Toute la société") },
                    )
                    if (projetsDeLaSociete.isNotEmpty()) {
                        FilterChip(
                            selected = projetChoisi != null,
                            onClick = { projetChoisi = projetChoisi ?: projetsDeLaSociete.first() },
                            label = { Text("Un projet") },
                        )
                    }
                }
                if (projetsDeLaSociete.isNotEmpty()) {
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        projetsDeLaSociete.forEach { projet ->
                            FilterChip(
                                selected = projetChoisi?.id == projet.id,
                                onClick = { projetChoisi = projet },
                                label = { Text(projet.nom) },
                            )
                        }
                    }
                } else {
                    Text(
                        "Aucun projet : l'envoi couvrira tous les justificatifs de " +
                            (societe?.nom ?: nomProfil) + ".",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.outline,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onEnvoyer(adresse.trim(), societe, projetChoisi) },
                enabled = adresseOk,
            ) { Text("Envoyer") }
        },
        dismissButton = { TextButton(onClick = onFermer) { Text("Annuler") } },
    )
}
