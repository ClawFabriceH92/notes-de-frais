package com.trucdecomptable.notesdefrais.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
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
import com.trucdecomptable.notesdefrais.data.Depense
import com.trucdecomptable.notesdefrais.data.Parametres
import com.trucdecomptable.notesdefrais.update.UpdateManager
import com.trucdecomptable.notesdefrais.util.Diag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Whole app: projects, project detail, settings, dialogs and the update prompt. */
@Composable
fun Racine(
    partages: StateFlow<List<Uri>> = MutableStateFlow(emptyList()),
    onPartagesLus: () -> Unit = {},
    vm: NotesViewModel = viewModel(),
) {
    val context = LocalContext.current

    val projets by vm.projets.collectAsStateWithLifecycle()
    val projet by vm.projet.collectAsStateWithLifecycle()
    val depenses by vm.depenses.collectAsStateWithLifecycle()
    val traitement by vm.traitement.collectAsStateWithLifecycle()
    val brouillon by vm.brouillon.collectAsStateWithLifecycle()
    val edition by vm.edition.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val export by vm.export.collectAsStateWithLifecycle()
    val emailExport by vm.emailExport.collectAsStateWithLifecycle()
    val sauvegarde by vm.sauvegarde.collectAsStateWithLifecycle()
    val derniere by vm.derniere.collectAsStateWithLifecycle()
    val parametres by vm.parametres.collectAsStateWithLifecycle()
    val profils by vm.profils.collectAsStateWithLifecycle()
    val profilCourant by vm.profilCourant.collectAsStateWithLifecycle()

    val snackbar = remember { SnackbarHostState() }
    var uriPhoto by remember { mutableStateOf<Uri?>(null) }
    var reglages by remember { mutableStateOf(false) }
    var indemnite by remember { mutableStateOf(false) }
    var deverrouille by remember { mutableStateOf(!parametres.verrouillageActif) }
    // Pièce ouverte en grand avant le formulaire (option du paramétrage).
    var pieceEnGrand by remember { mutableStateOf<String?>(null) }
    var apresGrand by remember { mutableStateOf<Depense?>(null) }

    val launcherPhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { reussi -> if (reussi) uriPhoto?.let(vm::traiterJustificatif) }

    val launcherGalerie = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(vm::traiterJustificatif) }

    val launcherRestauration = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> uri?.let(vm::restaurer) }

    // A file shared from another app: PDF, photo, or several at once.
    val fichiersPartages by partages.collectAsStateWithLifecycle()
    LaunchedEffect(fichiersPartages) {
        if (fichiersPartages.isEmpty()) return@LaunchedEffect
        vm.recevoirPartage(fichiersPartages)
        onPartagesLus()
    }

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
            email = emailExport ?: parametres.emailDestinataire,
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
    // One check at launch, then again every 30 minutes while the app stays open:
    // an app left running for days used to never look again. The manager keeps
    // its own hourly floor, so the extra wake-ups cost one HTTP call an hour.
    val contexteApp = context.applicationContext
    LaunchedEffect(Unit) {
        while (true) {
            UpdateManager.checkAtLaunch(contexteApp, "")
            kotlinx.coroutines.delay(30 * 60_000L)
        }
    }

    Box(Modifier.fillMaxSize()) {
        val courant = projet
        when {
            !deverrouille -> EcranVerrouillage(
                hash = parametres.codeAccesHash,
                onDeverrouille = { deverrouille = true },
            )

            reglages -> EcranReglages(
                initial = parametres,
                couvertureTaux = vm.couvertureTaux(),
                onRetour = { reglages = false },
                onEnregistrer = vm::enregistrerParametres,
                onSauvegarder = { vm.sauvegarder() },
                onRestaurer = { launcherRestauration.launch("application/zip") },
                profils = profils,
                onAjouterProfil = vm::ajouterProfil,
                onMajProfil = vm::majProfil,
                onSupprimerProfil = vm::supprimerProfil,
                chercherSiret = { siret -> vm.chercherSiret(siret) },
            )

            courant == null -> EcranProjets(
                projets = projets,
                onOuvrir = vm::ouvrirProjet,
                onCreer = vm::creerProjet,
                onRenommer = vm::renommerProjet,
                onSupprimer = vm::supprimerProjet,
                onVerifierMaj = { UpdateManager.checkNow(contexteApp) },
                onReglages = { reglages = true },
                onCloturer = vm::cloturerProjet,
                onDupliquer = vm::dupliquerProjet,
                profils = profils,
                profilCourant = profilCourant,
                onChoisirProfil = vm::choisirProfil,
            )

            else -> EcranProjet(
                projet = courant,
                depenses = depenses,
                traitement = traitement,
                onRetour = vm::fermerProjet,
                onPhoto = ::prendrePhoto,
                onGalerie = { launcherGalerie.launch(arrayOf("image/*", "application/pdf")) },
                onManuel = vm::ajouterManuellement,
                onExport = vm::exporter,
                onOuvrirDepense = { depense ->
                    val piece = depense.tousLesFichiers.firstOrNull()
                    if (parametres.pieceEnGrand && piece != null) {
                        apresGrand = depense
                        pieceEnGrand = piece
                    } else {
                        vm.editer(depense)
                    }
                },
                onIndemniteKm = { indemnite = true },
                onRecadrerPiece = vm::recadrerPiece,
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
            moyensPaiement = parametres.moyensPaiement,
        )
    }

    edition?.let { initiale ->
        DialogueDepense(
            initiale = initiale,
            onAnnuler = vm::fermerEditeur,
            onValider = vm::validerBrouillon,
            onSupprimer = { vm.supprimerDepense(it); vm.fermerEditeur() },
            tauxPour = vm::tauxBdf,
            onAjouterPiece = { uri -> vm.ajouterPieceJointe(initiale, uri) },
            moyensPaiement = parametres.moyensPaiement,
            onRecadrerPiece = { nom, bitmap -> vm.recadrerPiece(initiale, nom, bitmap) },
        )
    }

    // Ouverture « en grand d'abord » : la pièce s'affiche, puis le formulaire
    // s'ouvre à la fermeture (option du paramétrage).
    pieceEnGrand?.let { nom ->
        ApercuPiece(
            nom = nom,
            onFermer = {
                pieceEnGrand = null
                apresGrand?.let { vm.editer(it) }
                apresGrand = null
            },
            onRecadrer = { piece, bitmap ->
                apresGrand?.let { vm.recadrerPiece(it, piece, bitmap) }
            },
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
            // Le pourcentage vit dans le TITRE : il reste lisible quelle que
            // soit la place laissée par la barre.
            title = { Text("Téléchargement… ${(etat.progress * 100).toInt()} %") },
            text = {
                Column(Modifier.fillMaxWidth()) {
                    LinearProgressIndicator(
                        progress = { etat.progress },
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    )
                    Text(
                        "${(etat.progress * 100).toInt()} % téléchargé",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(top = 20.dp),
                    )
                }
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
