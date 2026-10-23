package com.trucdecomptable.notesdefrais.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.trucdecomptable.notesdefrais.data.Depense
import com.trucdecomptable.notesdefrais.data.Projet
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EcranProjets(
    projets: List<Projet>,
    onOuvrir: (Projet) -> Unit,
    onCreer: (String) -> Unit,
    onRenommer: (Projet, String) -> Unit,
    onSupprimer: (Projet) -> Unit,
    onVerifierMaj: () -> Unit,
    onReglages: () -> Unit = {},
) {
    var nouveau by remember { mutableStateOf<String?>(null) }
    var renomme by remember { mutableStateOf<Projet?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Notes de frais") },
                actions = {
                    IconButton(onClick = onReglages) {
                        Icon(Icons.Default.Settings, contentDescription = "Paramétrage")
                    }
                    IconButton(onClick = onVerifierMaj) {
                        Icon(Icons.Default.CloudDownload, contentDescription = "Vérifier les mises à jour")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { nouveau = "" }) {
                Icon(Icons.Default.Add, contentDescription = "Nouveau projet")
            }
        },
    ) { padding ->
        if (projets.isEmpty()) {
            Box(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Folder,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                    )
                    Spacer(Modifier.height(12.dp))
                    Text("Aucun projet", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "Crée un projet, photographie tes justificatifs, exporte le ZIP.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        } else {
            LazyColumn(
                Modifier.fillMaxSize().padding(padding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(projets, key = { it.id }) { projet ->
                    CarteProjet(projet, onOuvrir, onRenommer = { renomme = projet }, onSupprimer)
                }
            }
        }
    }

    nouveau?.let { valeur ->
        DialogueTexte(
            titre = "Nouveau projet",
            libelle = "Nom du projet",
            valeurInitiale = valeur,
            onAnnuler = { nouveau = null },
            onValider = { nom ->
                onCreer(nom)
                nouveau = null
            },
        )
    }

    renomme?.let { projet ->
        DialogueTexte(
            titre = "Renommer",
            libelle = "Nom du projet",
            valeurInitiale = projet.nom,
            onAnnuler = { renomme = null },
            onValider = { nom ->
                onRenommer(projet, nom)
                renomme = null
            },
        )
    }
}

@Composable
private fun CarteProjet(
    projet: Projet,
    onOuvrir: (Projet) -> Unit,
    onRenommer: () -> Unit,
    onSupprimer: (Projet) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Card(
        onClick = { onOuvrir(projet) },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(projet.nom, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(
                    "créé le " + SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).format(Date(projet.creeLe)),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            Box {
                IconButton(onClick = { menu = true }) {
                    Icon(Icons.Default.MoreVert, contentDescription = "Options")
                }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(
                        text = { Text("Renommer") },
                        leadingIcon = { Icon(Icons.Default.Edit, contentDescription = null) },
                        onClick = { menu = false; onRenommer() },
                    )
                    DropdownMenuItem(
                        text = { Text("Supprimer") },
                        leadingIcon = { Icon(Icons.Default.Delete, contentDescription = null) },
                        onClick = { menu = false; onSupprimer(projet) },
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EcranProjet(
    projet: Projet,
    depenses: List<Depense>,
    traitement: Boolean,
    onRetour: () -> Unit,
    onPhoto: () -> Unit,
    onGalerie: () -> Unit,
    onManuel: () -> Unit,
    onExport: () -> Unit,
    onOuvrirDepense: (Depense) -> Unit,
    onIndemniteKm: () -> Unit = {},
) {
    var menuAjout by remember { mutableStateOf(false) }
    val totalEur = depenses.sumOf { it.montantEurCentimes ?: 0L } / 100.0
    val nonConverties = depenses.count { it.montantEurCentimes == null }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(projet.nom) },
                navigationIcon = {
                    IconButton(onClick = onRetour) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    IconButton(onClick = onExport, enabled = depenses.isNotEmpty() && !traitement) {
                        Icon(Icons.Default.IosShare, contentDescription = "Exporter le ZIP")
                    }
                },
            )
        },
        floatingActionButton = {
            Box {
                FloatingActionButton(onClick = { menuAjout = true }) {
                    Icon(Icons.Default.Add, contentDescription = "Ajouter une dépense")
                }
                DropdownMenu(expanded = menuAjout, onDismissRequest = { menuAjout = false }) {
                    DropdownMenuItem(
                        text = { Text("Prendre une photo") },
                        onClick = { menuAjout = false; onPhoto() },
                    )
                    DropdownMenuItem(
                        text = { Text("Choisir un fichier") },
                        onClick = { menuAjout = false; onGalerie() },
                    )
                    DropdownMenuItem(
                        text = { Text("Indemnité kilométrique") },
                        onClick = { menuAjout = false; onIndemniteKm() },
                    )
                    DropdownMenuItem(
                        text = { Text("Saisie manuelle") },
                        onClick = { menuAjout = false; onManuel() },
                    )
                }
            }
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Card(
                Modifier.fillMaxWidth().padding(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text(
                        String.format(Locale.FRANCE, "%.2f €", totalEur),
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        "${depenses.size} dépense(s)" +
                            if (nonConverties > 0) " · $nonConverties sans taux de change" else "",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }

            if (traitement) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.height(18.dp).padding(end = 8.dp))
                    Text("Lecture du justificatif…", style = MaterialTheme.typography.bodySmall)
                }
            }

            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(depenses, key = { it.id }) { depense ->
                    LigneDepense(depense) { onOuvrirDepense(depense) }
                }
            }
        }
    }
}

@Composable
private fun LigneDepense(depense: Depense, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(depense.typeDepense.label, style = MaterialTheme.typography.titleSmall)
                Text(
                    buildString {
                        append(depense.date)
                        if (depense.libelle.isNotBlank()) append(" · ").append(depense.libelle)
                    },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
            }
            Column(horizontalAlignment = Alignment.End) {
                Text(
                    NotesViewModel.montantLisible(depense),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                if (depense.devise != "EUR") {
                    val eur = depense.montantEurCentimes
                    Text(
                        if (eur != null) String.format(Locale.FRANCE, "%.2f €", eur / 100.0) else "taux manquant",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        }
    }
}

@Composable
fun DialogueTexte(
    titre: String,
    libelle: String,
    valeurInitiale: String,
    onAnnuler: () -> Unit,
    onValider: (String) -> Unit,
) {
    var texte by remember { mutableStateOf(valeurInitiale) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onAnnuler,
        title = { Text(titre) },
        text = {
            androidx.compose.material3.OutlinedTextField(
                value = texte,
                onValueChange = { texte = it },
                label = { Text(libelle) },
                singleLine = true,
            )
        },
        confirmButton = { TextButton(onClick = { onValider(texte) }) { Text("Valider") } },
        dismissButton = { TextButton(onClick = onAnnuler) { Text("Annuler") } },
    )
}
