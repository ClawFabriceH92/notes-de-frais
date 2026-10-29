package com.trucdecomptable.notesdefrais.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.material.icons.filled.AccountBalance
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CloudDownload
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DeleteForever
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.IosShare
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.RestoreFromTrash
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Unarchive
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
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
import com.trucdecomptable.notesdefrais.data.Profil
import com.trucdecomptable.notesdefrais.data.Projet
import com.trucdecomptable.notesdefrais.util.Dates
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
    onCloturer: (Projet) -> Unit = {},
    onDupliquer: (Projet) -> Unit = {},
    profils: List<Profil> = emptyList(),
    profilCourant: Long = 0,
    onChoisirProfil: (Long) -> Unit = {},
    onAffecterProfil: (Projet?, Long, Boolean) -> Unit = { _, _, _ -> },
    onArchiver: (Projet) -> Unit = {},
    onVoirArchives: () -> Unit = {},
    nbArchives: Int = 0,
) {
    var nouveau by remember { mutableStateOf<String?>(null) }
    var renomme by remember { mutableStateOf<Projet?>(null) }
    var affectation by remember { mutableStateOf<Projet?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Note de frais by Fabrice Heuvrard", maxLines = 1) },
                actions = {
                    IconButton(onClick = onVoirArchives) {
                        BadgedBox(
                            badge = {
                                if (nbArchives > 0) Badge { Text("$nbArchives") }
                            },
                        ) {
                            Icon(Icons.Default.Archive, contentDescription = "Projets archivés")
                        }
                    }
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
        Column(Modifier.fillMaxSize().padding(padding)) {
            // Deux sociétés = deux jeux de projets : les puces choisissent la
            // société en cours, et « Toutes » remet la vue d'ensemble.
            if (profils.isNotEmpty()) {
                FlowRowAccueil(
                    profils = profils,
                    courant = profilCourant,
                    onChoisir = onChoisirProfil,
                )
            }
            if (projets.isEmpty()) {
                Box(
                    Modifier.fillMaxSize().padding(32.dp),
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
                    Modifier.fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    items(projets, key = { it.id }) { projet ->
                        CarteProjet(
                            projet = projet,
                            societe = profils.firstOrNull { it.id == projet.profilId }?.nom,
                            onOuvrir = onOuvrir,
                            onRenommer = { renomme = projet },
                            onSupprimer = onSupprimer,
                            onCloturer = { onCloturer(projet) },
                            onDupliquer = { onDupliquer(projet) },
                            onAffecter = if (profils.isEmpty()) null else ({ affectation = projet }),
                            onArchiver = { onArchiver(projet) },
                        )
                    }
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

    affectation?.let { projet ->
        DialogueAffectation(
            projet = projet,
            profils = profils,
            onAnnuler = { affectation = null },
            onValider = { profilId, tous ->
                onAffecterProfil(if (tous) null else projet, profilId, tous)
                affectation = null
            },
        )
    }
}

/**
 * Affecter un projet à une société. Les dépenses suivent leur projet : ranger le
 * projet range tout ce qu'il contient, sans toucher aux montants.
 */
@Composable
private fun DialogueAffectation(
    projet: Projet,
    profils: List<Profil>,
    onAnnuler: () -> Unit,
    onValider: (Long, Boolean) -> Unit,
) {
    var choisi by remember { mutableStateOf(projet.profilId) }
    var tous by remember { mutableStateOf(false) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onAnnuler,
        title = { Text("Société du projet") },
        text = {
            Column {
                Text(
                    "« ${projet.nom} » et ses dépenses seront rattachés à la société choisie.",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                listOf(Profil(id = 0, nom = "Sans société")).plus(profils).forEach { profil ->
                    Row(
                        Modifier.fillMaxWidth().clickable { choisi = profil.id },
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        androidx.compose.material3.RadioButton(
                            selected = choisi == profil.id,
                            onClick = { choisi = profil.id },
                        )
                        Text(profil.nom, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Switch(checked = tous, onCheckedChange = { tous = it })
                    Text(
                        "  Appliquer à tous les projets sans société",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = choisi > 0 || tous,
                onClick = { onValider(choisi, tous) },
            ) { Text("Affecter") }
        },
        dismissButton = { TextButton(onClick = onAnnuler) { Text("Annuler") } },
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowRowAccueil(
    profils: List<Profil>,
    courant: Long,
    onChoisir: (Long) -> Unit,
) {
    FlowRow(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PuceChoix(
            selected = courant == 0L,
            onClick = { onChoisir(0) },
            label = { Text("Toutes les sociétés") },
        )
        profils.forEach { profil ->
            PuceChoix(
                selected = courant == profil.id,
                onClick = { onChoisir(profil.id) },
                label = { Text(profil.nom) },
            )
        }
    }
}

@Composable
private fun CarteProjet(
    projet: Projet,
    societe: String? = null,
    onOuvrir: (Projet) -> Unit,
    onRenommer: () -> Unit,
    onSupprimer: (Projet) -> Unit,
    onCloturer: () -> Unit = {},
    onDupliquer: () -> Unit = {},
    onAffecter: (() -> Unit)? = null,
    onArchiver: () -> Unit = {},
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
                    buildString {
                        if (projet.cloture) append("clôturé") else
                            append("créé le " + SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).format(Date(projet.creeLe)))
                        if (!societe.isNullOrBlank()) append(" · ").append(societe)
                    },
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
                        text = { Text(if (projet.cloture) "Réouvrir le projet" else "Clôturer le projet") },
                        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null) },
                        onClick = { menu = false; onCloturer() },
                    )
                    if (onAffecter != null) {
                        DropdownMenuItem(
                            text = { Text(if (societe.isNullOrBlank()) "Affecter à une société" else "Changer de société") },
                            leadingIcon = { Icon(Icons.Default.AccountBalance, contentDescription = null) },
                            onClick = { menu = false; onAffecter() },
                        )
                    }
                    DropdownMenuItem(
                        text = { Text("Archiver (le ranger hors de l'accueil)") },
                        leadingIcon = { Icon(Icons.Default.Archive, contentDescription = null) },
                        onClick = { menu = false; onArchiver() },
                    )
                    DropdownMenuItem(
                        text = { Text("Dupliquer (sans les dépenses)") },
                        leadingIcon = { Icon(Icons.Default.ContentCopy, contentDescription = null) },
                        onClick = { menu = false; onDupliquer() },
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
    onEnvoyerPdf: () -> Unit = {},
    emailJustificatifs: String? = null,
    onOuvrirDepense: (Depense) -> Unit,
    onIndemniteKm: () -> Unit = {},
    onRecadrerPiece: (Depense, String, android.graphics.Bitmap) -> Unit = { _, _, _ -> },
    societe: String? = null,
    onCorbeille: () -> Unit = {},
    nbCorbeille: Int = 0,
) {
    var menuAjout by remember { mutableStateOf(false) }
    var menuExport by remember { mutableStateOf(false) }
    var recherche by remember { mutableStateOf("") }
    val totalEur = depenses.sumOf { it.montantEurCentimes ?: 0L } / 100.0
    val nonConverties = depenses.count { it.montantEurCentimes == null }
    val visibles = remember(depenses, recherche) {
        if (recherche.isBlank()) depenses
        else depenses.filter { d ->
            d.libelle.contains(recherche, ignoreCase = true) ||
                d.typeDepense.label.contains(recherche, ignoreCase = true) ||
                d.moyenPaiement.contains(recherche, ignoreCase = true) ||
                d.participants.contains(recherche, ignoreCase = true) ||
                d.societeParticipant.contains(recherche, ignoreCase = true) ||
                Dates.affichage(d.date).contains(recherche) ||
                d.date.contains(recherche) ||
                d.trajet.contains(recherche, ignoreCase = true)
        }
    }
    val parType = depenses.groupBy { it.typeDepense }
        .mapValues { (_, liste) -> liste.sumOf { it.montantEurCentimes ?: 0L } }
        .toList().sortedByDescending { it.second }.take(3)

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
                    IconButton(onClick = onCorbeille) {
                        BadgedBox(
                            badge = {
                                if (nbCorbeille > 0) Badge { Text("$nbCorbeille") }
                            },
                        ) {
                            Icon(Icons.Default.Delete, contentDescription = "Corbeille")
                        }
                    }
                    // Deux sorties : le ZIP complet (récap Excel + PDF), ou les
                    // justificatifs seuls en PDF vers l'email du profil.
                    Box {
                        IconButton(
                            onClick = { menuExport = true },
                            enabled = depenses.isNotEmpty() && !traitement,
                        ) {
                            Icon(Icons.Default.IosShare, contentDescription = "Exporter")
                        }
                        DropdownMenu(expanded = menuExport, onDismissRequest = { menuExport = false }) {
                            DropdownMenuItem(
                                text = { Text("Export ZIP (récap Excel + PDF)") },
                                leadingIcon = { Icon(Icons.Default.IosShare, contentDescription = null) },
                                onClick = { menuExport = false; onExport() },
                            )
                            DropdownMenuItem(
                                text = { Text("Envoyer les justificatifs en PDF") },
                                leadingIcon = { Icon(Icons.Default.Email, contentDescription = null) },
                                onClick = { menuExport = false; onEnvoyerPdf() },
                            )
                        }
                    }
                },
            )
        },
        floatingActionButton = {
            if (projet.cloture) return@Scaffold
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
                        text = { Text("Choisir un fichier (photo ou PDF)") },
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
                    // La société à laquelle le projet est rattaché : c'est elle
                    // qui apparaîtra sur l'export.
                    Text(
                        if (societe.isNullOrBlank()) "Aucune société affectée" else "Société : $societe",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold,
                    )
                    // Où les justificatifs PDF partent : l'adresse du profil.
                    Text(
                        if (emailJustificatifs.isNullOrBlank()) {
                            "PDF : aucune adresse email dans la société"
                        } else {
                            "PDF → $emailJustificatifs"
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Text(
                        "${depenses.size} dépense(s)" +
                            if (nonConverties > 0) " · $nonConverties sans taux de change" else "",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (parType.isNotEmpty()) {
                        Text(
                            parType.joinToString(" · ") {
                                "${it.first.label} " + String.format(Locale.FRANCE, "%.2f €", it.second / 100.0)
                            },
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }

            if (projet.cloture) {
                Text(
                    "Projet clôturé : lecture seule. Réouvre-le pour ajouter une dépense.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(horizontal = 20.dp),
                )
            }

            OutlinedTextField(
                value = recherche,
                onValueChange = { recherche = it },
                label = { Text("Rechercher (libellé, type, date, trajet)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
            )

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
                items(visibles, key = { it.id }) { depense ->
                    LigneDepense(
                        depense = depense,
                        onClick = { onOuvrirDepense(depense) },
                        onRecadrer = onRecadrerPiece,
                    )
                }
            }
        }
    }
}

@Composable
private fun LigneDepense(
    depense: Depense,
    onClick: () -> Unit,
    onRecadrer: (Depense, String, android.graphics.Bitmap) -> Unit = { _, _, _ -> },
) {
    var apercu by remember { mutableStateOf<String?>(null) }
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            depense.tousLesFichiers.firstOrNull()?.let { nom ->
                VignettePiece(
                    nom = nom,
                    taille = 48.dp,
                    modifier = Modifier.padding(end = 12.dp),
                ) { apercu = nom }
            }
            Column(Modifier.weight(1f)) {
                Text(depense.typeDepense.label, style = MaterialTheme.typography.titleSmall)
                Text(
                    buildString {
                        append(Dates.affichage(depense.date))
                        if (depense.libelle.isNotBlank()) append(" · ").append(depense.libelle)
                        depense.sens?.let { append(" · ").append(it.label) }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                )
                val details = listOfNotNull(
                    depense.moyenPaiement.takeIf { it.isNotBlank() },
                    depense.participants.takeIf { it.isNotBlank() },
                ).joinToString(" · ")
                if (details.isNotBlank()) {
                    Text(details, style = MaterialTheme.typography.bodySmall, maxLines = 1)
                }
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
    apercu?.let { nom ->
        ApercuPiece(
            nom = nom,
            onFermer = { apercu = null },
            onRecadrer = { piece, bitmap -> onRecadrer(depense, piece, bitmap) },
        )
    }
}

/**
 * La corbeille d'un projet : une dépense supprimée y reste avec son
 * justificatif, hors des totaux et de l'export, jusqu'à ce qu'on la restaure
 * ou qu'on l'efface pour de bon.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EcranCorbeille(
    projetNom: String,
    depenses: List<Depense>,
    onRetour: () -> Unit,
    onRestaurer: (Depense) -> Unit,
    onSupprimerDefinitivement: (Depense) -> Unit,
    onVider: () -> Unit,
) {
    var demandeVidage by remember { mutableStateOf(false) }
    var aEffacer by remember { mutableStateOf<Depense?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Corbeille") },
                navigationIcon = {
                    IconButton(onClick = onRetour) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
                actions = {
                    TextButton(onClick = { demandeVidage = true }, enabled = depenses.isNotEmpty()) {
                        Text("Vider")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                if (depenses.isEmpty()) {
                    "Corbeille vide. Les dépenses supprimées arrivent ici, avec leur justificatif."
                } else {
                    "${depenses.size} dépense(s) de « $projetNom » : hors des totaux et de l'export, " +
                        "les justificatifs sont conservés."
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(
                    start = 16.dp, end = 16.dp, top = 4.dp, bottom = 96.dp,
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(depenses, key = { it.id }) { depense ->
                    LigneCorbeille(
                        depense = depense,
                        onRestaurer = { onRestaurer(depense) },
                        onEffacer = { aEffacer = depense },
                    )
                }
            }
        }
    }

    if (demandeVidage) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { demandeVidage = false },
            title = { Text("Vider la corbeille ?") },
            text = {
                Text(
                    "Les ${depenses.size} dépense(s) de la corbeille seront effacées " +
                        "définitivement, avec leurs justificatifs. C'est irréversible.",
                )
            },
            confirmButton = {
                TextButton(onClick = { demandeVidage = false; onVider() }) { Text("Vider") }
            },
            dismissButton = {
                TextButton(onClick = { demandeVidage = false }) { Text("Annuler") }
            },
        )
    }

    aEffacer?.let { depense ->
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { aEffacer = null },
            title = { Text("Effacer définitivement ?") },
            text = {
                Text(
                    "${depense.typeDepense.label} · ${NotesViewModel.montantLisible(depense)} · " +
                        "${Dates.affichage(depense.date)}\n\nLa dépense et son justificatif seront " +
                        "supprimés. C'est irréversible.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        aEffacer = null
                        onSupprimerDefinitivement(depense)
                    },
                ) { Text("Effacer") }
            },
            dismissButton = { TextButton(onClick = { aEffacer = null }) { Text("Annuler") } },
        )
    }
}

@Composable
private fun LigneCorbeille(
    depense: Depense,
    onRestaurer: () -> Unit,
    onEffacer: () -> Unit,
) {
    var apercu by remember { mutableStateOf<String?>(null) }
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                depense.tousLesFichiers.firstOrNull()?.let { nom ->
                    VignettePiece(
                        nom = nom,
                        taille = 48.dp,
                        modifier = Modifier.padding(end = 12.dp),
                    ) { apercu = nom }
                }
                Column(Modifier.weight(1f)) {
                    Text(depense.typeDepense.label, style = MaterialTheme.typography.titleSmall)
                    Text(
                        buildString {
                            append(Dates.affichage(depense.date))
                            if (depense.libelle.isNotBlank()) append(" · ").append(depense.libelle)
                        },
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                    )
                    depense.corbeilleLe?.let { quand ->
                        Text(
                            "supprimée le " + SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.FRANCE)
                                .format(Date(quand)),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
                Text(
                    NotesViewModel.montantLisible(depense),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onRestaurer) {
                    Icon(
                        Icons.Default.RestoreFromTrash,
                        contentDescription = null,
                        modifier = Modifier.padding(end = 6.dp).height(18.dp),
                    )
                    Text("Restaurer")
                }
                IconButton(onClick = onEffacer) {
                    Icon(Icons.Default.DeleteForever, contentDescription = "Effacer définitivement")
                }
            }
        }
    }
    apercu?.let { nom ->
        ApercuPiece(nom = nom, onFermer = { apercu = null })
    }
}

/**
 * Les projets archivés : rangés, jamais perdus. Un projet archivé n'a pas
 * changé d'un centime, seule sa place dans l'accueil a disparu. On peut le
 * rouvrir (il reste archivé) ou le restaurer pour le remettre dans la liste.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EcranArchives(
    projets: List<Projet>,
    societePour: (Projet) -> String?,
    onRetour: () -> Unit,
    onOuvrir: (Projet) -> Unit,
    onDesarchiver: (Projet) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Projets archivés") },
                navigationIcon = {
                    IconButton(onClick = onRetour) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            Text(
                if (projets.isEmpty()) {
                    "Aucun projet archivé. Depuis l'accueil, menu d'un projet → Archiver."
                } else {
                    "${projets.size} projet(s) archivé(s) : dépenses et justificatifs intacts."
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(projets, key = { it.id }) { projet ->
                    Card(Modifier.fillMaxWidth()) {
                        Column(Modifier.fillMaxWidth().padding(16.dp)) {
                            Text(
                                projet.nom,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.SemiBold,
                            )
                            Text(
                                buildString {
                                    projet.archiveLe?.let {
                                        append("archivé le ")
                                        append(SimpleDateFormat("dd/MM/yyyy", Locale.FRANCE).format(Date(it)))
                                    }
                                    if (projet.cloture) append(" · clôturé")
                                    societePour(projet)?.takeIf { it.isNotBlank() }?.let {
                                        append(" · ").append(it)
                                    }
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Row(
                                Modifier.fillMaxWidth().padding(top = 8.dp),
                                horizontalArrangement = Arrangement.End,
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                TextButton(onClick = { onOuvrir(projet) }) { Text("Ouvrir") }
                                TextButton(onClick = { onDesarchiver(projet) }) {
                                    Icon(
                                        Icons.Default.Unarchive,
                                        contentDescription = null,
                                        modifier = Modifier.padding(end = 6.dp).height(18.dp),
                                    )
                                    Text("Restaurer")
                                }
                            }
                        }
                    }
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
