package com.trucdecomptable.notesdefrais.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trucdecomptable.notesdefrais.data.Parametres
import com.trucdecomptable.notesdefrais.update.UpdateManager
import com.trucdecomptable.notesdefrais.util.Diag
import java.io.File

/** Whole app: projects, project detail, settings, dialogs and the update prompt. */
@Composable
fun Racine(vm: NotesViewModel = viewModel()) {
    val context = LocalContext.current

    val projets by vm.projets.collectAsStateWithLifecycle()
    val projet by vm.projet.collectAsStateWithLifecycle()
    val depenses by vm.depenses.collectAsStateWithLifecycle()
    val traitement by vm.traitement.collectAsStateWithLifecycle()
    val brouillon by vm.brouillon.collectAsStateWithLifecycle()
    val edition by vm.edition.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val export by vm.export.collectAsStateWithLifecycle()
    val sauvegarde by vm.sauvegarde.collectAsStateWithLifecycle()
    val derniere by vm.derniere.collectAsStateWithLifecycle()
    val parametres by vm.parametres.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    var uriPhoto by remember { mutableStateOf<Uri?>(null) }
    var reglages by remember { mutableStateOf(false) }
    var indemnite by remember { mutableStateOf(false) }

    val launcherPhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { reussi -> if (reussi) uriPhoto?.let(vm::traiterJustificatif) }

    val launcherGalerie = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> uri?.let(vm::traiterJustificatif) }

    val launcherRestauration = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> uri?.let(vm::restaurer) }

    fun prendrePhoto() {
        val dossier = File(context.cacheDir, "photos").apply { mkdirs() }
        val fichier = File(dossier, "photo-${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", fichier)
        uriPhoto = uri
        launcherPhoto.launch(uri)
    }

    /** Hands a file to another app; mail is pre-addressed from the settings. */
    fun partager(fichier: File, type: String, sujet: String, email: String) {
        try {
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", fichier)
            val intent = Intent(Intent.ACTION_SEND).apply {
                this.type = type
                putExtra(Intent.EXTRA_STREAM, uri)
                putExtra(Intent.EXTRA_SUBJECT, sujet)
                if (email.isNotBlank()) {
                    putExtra(Intent.EXTRA_EMAIL, arrayOf(email))
                    putExtra(Intent.EXTRA_TEXT, "Notes de frais : $sujet")
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                if (email.isNotBlank()) {
                    Intent.createChooser(intent, "Envoyer à $email")
                } else {
                    Intent.createChooser(intent, "Envoyer le fichier")
                },
            )
        } catch (e: Exception) {
            Diag.record("partage", e)
        }
    }

    // Messages: the one right after a save offers a one-tap correction.
    LaunchedEffect(message) {
        val texte = message ?: return@LaunchedEffect
        val resultat = snackbar.showSnackbar(
            message = texte,
            actionLabel = if (derniere != null) "Modifier" else null,
            duration = SnackbarDuration.Short,
        )
        if (resultat == SnackbarResult.ActionPerformed) derniere?.let(vm::editer)
        vm.messageLu()
        vm.derniereOubliee()
    }

    // The ZIP goes to the configured recipient, or wherever the user picks.
    LaunchedEffect(export) {
        val fichier = export ?: return@LaunchedEffect
        val nom = projet?.nom.orEmpty()
        partager(
            fichier = fichier,
            type = "application/zip",
            sujet = "Notes de frais · $nom · ${parametres.mention}",
            email = parametres.emailDestinataire,
        )
        vm.exportConsomme()
    }

    LaunchedEffect(sauvegarde) {
        val fichier = sauvegarde ?: return@LaunchedEffect
        partager(
            fichier = fichier,
            type = "application/zip",
            sujet = "Sauvegarde Notes de frais · ${parametres.mention}",
            email = parametres.emailDestinataire,
        )
        vm.sauvegardeConsommee()
    }

    val etatMaj by UpdateManager.state.collectAsStateWithLifecycle()
    LaunchedEffect(Unit) { UpdateManager.checkAtLaunch("") }

    Box(Modifier.fillMaxSize()) {
        val courant = projet
        when {
            reglages -> EcranReglages(
                initial = parametres,
                couvertureTaux = vm.couvertureTaux(),
                onRetour = { reglages = false },
                onEnregistrer = vm::enregistrerParametres,
                onSauvegarder = { vm.sauvegarder() },
                onRestaurer = { launcherRestauration.launch("application/zip") },
            )

            courant == null -> EcranProjets(
                projets = projets,
                onOuvrir = vm::ouvrirProjet,
                onCreer = vm::creerProjet,
                onRenommer = vm::renommerProjet,
                onSupprimer = vm::supprimerProjet,
                onVerifierMaj = { UpdateManager.checkNow() },
                onReglages = { reglages = true },
            )

            else -> EcranProjet(
                projet = courant,
                depenses = depenses,
                traitement = traitement,
                onRetour = vm::fermerProjet,
                onPhoto = ::prendrePhoto,
                onGalerie = { launcherGalerie.launch("image/*") },
                onManuel = vm::ajouterManuellement,
                onExport = vm::exporter,
                onOuvrirDepense = vm::editer,
                onIndemniteKm = { indemnite = true },
            )
        }

        SnackbarHost(
            hostState = snackbar,
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 88.dp),
        )
    }

    brouillon?.let { initiale ->
        DialogueDepense(
            initiale = initiale,
            onAnnuler = vm::fermerEditeur,
            onValider = vm::validerBrouillon,
            tauxPour = vm::tauxBdf,
        )
    }

    edition?.let { initiale ->
        DialogueDepense(
            initiale = initiale,
            onAnnuler = vm::fermerEditeur,
            onValider = vm::validerBrouillon,
            onSupprimer = { vm.supprimerDepense(it); vm.fermerEditeur() },
            tauxPour = vm::tauxBdf,
        )
    }

    if (indemnite) {
        DialogueIndemniteKm(
            parametres = parametres,
            onAnnuler = { indemnite = false },
            onValider = { date, distance, trajet, frais, libelle ->
                vm.ajouterIndemniteKm(date, distance, trajet, frais, libelle)
                indemnite = false
            },
        )
    }

    DialogueMiseAJour(
        etat = etatMaj,
        onTelecharger = { UpdateManager.download(context) },
        onInstaller = {
            // A schema migration is exactly when data can be lost: archive first.
            vm.sauvegarderPuisInstaller { UpdateManager.install(context) }
        },
        onAutoriser = { UpdateManager.openInstallSettings(context) },
        onFermer = { UpdateManager.dismiss() },
    )
}

@Composable
private fun DialogueMiseAJour(
    etat: UpdateManager.State,
    onTelecharger: () -> Unit,
    onInstaller: () -> Unit,
    onAutoriser: () -> Unit,
    onFermer: () -> Unit,
) {
    when (etat) {
        is UpdateManager.State.Available -> AlertDialog(
            onDismissRequest = onFermer,
            title = { Text("Version ${etat.version} disponible") },
            text = { Text(etat.notes.ifBlank { "Une nouvelle version est prête." }) },
            confirmButton = { TextButton(onClick = onTelecharger) { Text("Télécharger") } },
            dismissButton = { TextButton(onClick = onFermer) { Text("Plus tard") } },
        )

        is UpdateManager.State.Downloading -> AlertDialog(
            onDismissRequest = {},
            title = { Text("Téléchargement…") },
            text = {
                Text("${(etat.progress * 100).toInt()} %")
                LinearProgressIndicator(
                    progress = { etat.progress },
                    modifier = Modifier.padding(top = 8.dp),
                )
            },
            confirmButton = {},
        )

        is UpdateManager.State.ReadyToInstall -> AlertDialog(
            onDismissRequest = onFermer,
            title = { Text("Prêt à installer") },
            text = { Text("La version ${etat.version} est téléchargée. Une sauvegarde complète est créée avant l'installation.") },
            confirmButton = { TextButton(onClick = onInstaller) { Text("Installer") } },
            dismissButton = { TextButton(onClick = onFermer) { Text("Plus tard") } },
        )

        is UpdateManager.State.UpToDate -> AlertDialog(
            onDismissRequest = onFermer,
            title = { Text("À jour") },
            text = { Text("Version installée : ${etat.version}") },
            confirmButton = { TextButton(onClick = onFermer) { Text("OK") } },
        )

        is UpdateManager.State.Failed -> AlertDialog(
            onDismissRequest = onFermer,
            title = { Text("Vérification impossible") },
            text = { Text(etat.detail ?: "GitHub n'a pas répondu.") },
            confirmButton = { TextButton(onClick = onFermer) { Text("OK") } },
            dismissButton = { TextButton(onClick = onAutoriser) { Text("Autoriser l'installation") } },
        )

        UpdateManager.State.Idle -> Unit
    }
}
