package com.trucdecomptable.notesdefrais.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.trucdecomptable.notesdefrais.backup.RedemarrageProcessus
import com.trucdecomptable.notesdefrais.data.Depense
import com.trucdecomptable.notesdefrais.data.Profil
import com.trucdecomptable.notesdefrais.data.Projet
import com.trucdecomptable.notesdefrais.export.EnvoiMailto
import com.trucdecomptable.notesdefrais.export.ExportManager
import com.trucdecomptable.notesdefrais.data.Parametres
import com.trucdecomptable.notesdefrais.update.UpdateManager
import com.trucdecomptable.notesdefrais.util.Diag
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * Point d'entrée. Une fois une restauration terminée (ou annulée après la
 * fermeture de la base), plus aucun écran n'est composé : seul reste le
 * dialogue bloquant, qui relance le processus. Le contenu quitte la
 * composition, donc ses flux Room ne sont plus collectés sur la base fermée.
 */
@Composable
fun Racine(
    partages: StateFlow<List<Uri>> = MutableStateFlow(emptyList()),
    onPartagesLus: () -> Unit = {},
    vm: NotesViewModel = viewModel(),
) {
    val fin by vm.finRestauration.collectAsStateWithLifecycle()
    val finCourante = fin
    if (finCourante != null) {
        EcranFinRestauration(finCourante)
    } else {
        RacineContenu(partages, onPartagesLus, vm)
    }
}

/** Dialogue bloquant : on ne peut ni le fermer ni retourner dans l'application. */
@Composable
private fun EcranFinRestauration(fin: NotesViewModel.FinRestauration) {
    val context = LocalContext.current
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {}
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text(fin.titre) },
        text = { Text(fin.message, modifier = Modifier.verticalScroll(rememberScrollState())) },
        confirmButton = {
            TextButton(onClick = { RedemarrageProcessus.relancer(context) }) {
                Text("Redémarrer l'application")
            }
        },
    )
}

/** Confirmation explicite avant toute restauration : contenu de l'archive et ce qui sera écrasé. */
@Composable
private fun DialogueConfirmationRestauration(
    demande: NotesViewModel.ConfirmationRestauration,
    onConfirmer: () -> Unit,
    onAnnuler: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onAnnuler,
        title = { Text("Restaurer cette sauvegarde ?") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                demande.apercu.lignes.forEachIndexed { rang, ligne ->
                    Text(ligne, modifier = Modifier.padding(top = if (rang == 0) 0.dp else 8.dp))
                }
            }
        },
        confirmButton = { TextButton(onClick = onConfirmer) { Text("Restaurer") } },
        dismissButton = { TextButton(onClick = onAnnuler) { Text("Annuler") } },
    )
}

/** Progression de la restauration : bloquant, sans bouton. */
@Composable
private fun DialogueRestaurationEnCours(etape: String) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        title = { Text("Restauration en cours") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                Text(etape)
                LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 16.dp))
                Text(
                    "Ne quittez pas l'application.",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }
        },
        confirmButton = {},
    )
}

/** Whole app: projects, project detail, settings, dialogs and the update prompt. */
@Composable
private fun RacineContenu(
    partages: StateFlow<List<Uri>>,
    onPartagesLus: () -> Unit,
    vm: NotesViewModel,
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
    val exportTrop by vm.exportTrop.collectAsStateWithLifecycle()
    val controlesExport by vm.controlesExport.collectAsStateWithLifecycle()
    val archivageEnAttente by vm.archivageEnAttente.collectAsStateWithLifecycle()
    val envoi by vm.envoi.collectAsStateWithLifecycle()
    val envoiSuivant by vm.envoiSuivant.collectAsStateWithLifecycle()
    val emailExport by vm.emailExport.collectAsStateWithLifecycle()
    val sauvegarde by vm.sauvegarde.collectAsStateWithLifecycle()
    val derniere by vm.derniere.collectAsStateWithLifecycle()
    val parametres by vm.parametres.collectAsStateWithLifecycle()
    val profils by vm.profils.collectAsStateWithLifecycle()
    val profilCourant by vm.profilCourant.collectAsStateWithLifecycle()
    val tousProjets by vm.tousProjets.collectAsStateWithLifecycle()
    val profilActif by vm.profilActif.collectAsStateWithLifecycle()
    val projetsArchives by vm.projetsArchives.collectAsStateWithLifecycle()
    val corbeille by vm.corbeille.collectAsStateWithLifecycle()
    val nbCorbeille by vm.nbCorbeille.collectAsStateWithLifecycle()
    val remplacementTicket by vm.remplacementTicket.collectAsStateWithLifecycle()
    val capture by vm.capture.collectAsStateWithLifecycle()

    // Moyens de paiement proposés : les communs, plus ceux de la société en cours.
    val moyensPaiement = remember(parametres.moyensPaiement, profilActif) {
        (parametres.moyensPaiement + (profilActif?.moyens ?: emptyList())).distinct()
    }

    val snackbar = remember { SnackbarHostState() }
    val portee = rememberCoroutineScope()
    var uriPhoto by remember { mutableStateOf<Uri?>(null) }
    // Dépense qui reçoit la photo quand on en est aux pages 2, 3… d'un
    // justificatif ; null quand la photo crée une nouvelle dépense.
    var ciblePhoto by remember { mutableStateOf<Long?>(null) }
    var reglages by remember { mutableStateOf(false) }
    // Envoi rapide : la fenêtre s'ouvre depuis l'icône e-mail de l'accueil.
    var dialogueMailto by remember { mutableStateOf(false) }
    var corbeilleOuverte by remember { mutableStateOf(false) }
    var archivesOuvertes by remember { mutableStateOf(false) }
    var indemnite by remember { mutableStateOf(false) }
    var deverrouille by remember { mutableStateOf(!parametres.verrouillageActif) }
    // Pièce ouverte en grand avant le formulaire (option du paramétrage).
    var pieceEnGrand by remember { mutableStateOf<String?>(null) }
    var apresGrand by remember { mutableStateOf<Depense?>(null) }

    val launcherPhoto = rememberLauncherForActivityResult(
        ActivityResultContracts.TakePicture(),
    ) { reussi ->
        val uri = uriPhoto
        val cible = ciblePhoto
        ciblePhoto = null
        // Une photo prise pendant la prise de vue en cours devient une page du
        // justificatif déjà enregistré, au lieu d'une dépense de plus.
        if (reussi && uri != null) {
            if (cible != null) vm.ajouterPageJustificatif(cible, uri) else vm.traiterJustificatif(uri)
        }
    }

    val launcherGalerie = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(vm::traiterJustificatif) }

    val launcherRestauration = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent(),
    ) { uri -> uri?.let(vm::demanderRestauration) }

    val launcherParametres = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let(vm::restaurerParametres) }

    // Partage du fichier de paramétrage (JSON) dès qu'il est prêt : voir plus
    // bas, après la définition de `partager`.

    // A file shared from another app: PDF, photo, or several at once.
    val fichiersPartages by partages.collectAsStateWithLifecycle()
    LaunchedEffect(fichiersPartages) {
        if (fichiersPartages.isEmpty()) return@LaunchedEffect
        vm.recevoirPartage(fichiersPartages)
        onPartagesLus()
    }

    fun prendrePhoto(pourDepense: Long? = null) {
        val dossier = File(context.cacheDir, "photos").apply { mkdirs() }
        val fichier = File(dossier, "photo-${System.currentTimeMillis()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", fichier)
        uriPhoto = uri
        ciblePhoto = pourDepense
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
            vm.signalerPartage(e)
        }
    }

    /** Plusieurs archives d'un coup (export découpé) : un seul partage. */
    fun partagerPlusieurs(
        fichiers: List<File>,
        type: String,
        sujet: String,
        email: String,
        corps: String? = null,
    ) {
        try {
            val uris = ArrayList(
                fichiers.map { FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", it) },
            )
            val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
                this.type = type
                putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
                putExtra(Intent.EXTRA_SUBJECT, sujet)
                if (email.isNotBlank()) {
                    putExtra(Intent.EXTRA_EMAIL, arrayOf(email))
                    putExtra(Intent.EXTRA_TEXT, corps ?: "Notes de frais : $sujet")
                } else {
                    corps?.let { putExtra(Intent.EXTRA_TEXT, it) }
                }
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(
                if (email.isNotBlank()) {
                    Intent.createChooser(intent, "Envoyer à $email")
                } else {
                    Intent.createChooser(intent, "Envoyer les fichiers")
                },
            )
        } catch (e: Exception) {
            Diag.record("partage/multiple", e)
            vm.signalerPartage(e)
        }
    }

    /**
     * Envoi rapide (icône e-mail de l'accueil) : rassemble les justificatifs de
     * la société choisie, les met en PDF et ouvre le mail prérempli vers
     * l'adresse « mailto » de cette société. Aucune note de frais à remplir,
     * aucun projet à créer.
     */
    suspend fun envoyerMailtoRapide(
        adresseDemandee: String,
        profilChoisi: Profil?,
        projetChoisi: Projet?,
    ) {
        // La société choisie dans la fenêtre fait foi ; à défaut celle du projet,
        // puis celle en cours de travail.
        val profil = profilChoisi
            ?: projetChoisi?.profilId?.takeIf { it > 0L }?.let { id -> profils.firstOrNull { it.id == id } }
            ?: profilActif
        if (profil == null) {
            vm.signaler("Choisis d'abord une société : l'adresse d'envoi y est rattachée.")
            return
        }
        val adresse = adresseDemandee.trim().ifBlank { profil.adresseEnvoi }
        if (!EnvoiMailto.adresseValide(adresse)) {
            vm.signaler("Adresse d'envoi invalide : rien n'a été envoyé.")
            return
        }
        val depenses = if (projetChoisi != null) {
            vm.depensesDuProjet(projetChoisi.id)
        } else {
            vm.depensesDuProfil(profil.id)
        }
        if (depenses.isEmpty()) {
            val ou = projetChoisi?.let { "le projet ${it.nom}" } ?: profil.nom
            vm.signaler("Aucun justificatif à envoyer pour $ou.")
            return
        }
        // Un projet fictif ne sert qu'à nommer les fichiers produits : rien n'est
        // enregistré en base, donc aucune note de frais n'apparaît.
        val projetEnvoi = Projet(
            nom = projetChoisi?.nom ?: "Envoi rapide",
            profilId = profil.id,
        )
        val fichiers = try {
            ExportManager.justificatifsPdf(context, projetEnvoi, depenses, parametres, profil)
        } catch (e: Exception) {
            Diag.record("mailto/pdf", e)
            vm.signaler("Impossible de préparer le PDF : ${e.message ?: e.javaClass.simpleName}")
            return
        }
        if (fichiers.isEmpty()) {
            vm.signaler("Aucun justificatif lisible pour ${profil.nom}.")
            return
        }
        Diag.record("mailto", "envoi de ${fichiers.size} PDF à $adresse")
        partagerPlusieurs(
            fichiers = fichiers,
            type = "application/pdf",
            sujet = EnvoiMailto.sujet(profil.nom, nomProjet = projetChoisi?.nom),
            email = adresse,
            corps = EnvoiMailto.corps(profil.nom, fichiers.size, nomProjet = projetChoisi?.nom),
        )
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

    // Fenêtre d'envoi rapide : société, adresse modifiable et portée au choix.
    if (dialogueMailto) {
        DialogueEnvoiRapide(
            nomProfil = profilActif?.nom.orEmpty().ifBlank { "la société en cours" },
            profils = profils,
            projets = tousProjets,
            profilInitial = profilActif,
            adresseInitiale = profilActif?.adresseEnvoi.orEmpty(),
            onFermer = { dialogueMailto = false },
            onEnvoyer = { adresse, societe, projetChoisi ->
                dialogueMailto = false
                portee.launch { envoyerMailtoRapide(adresse, societe, projetChoisi) }
            },
        )
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

    // Envois successifs : un mail par lot, chacun sous la limite de pièces
    // jointes. Le lot suivant n'est proposé qu'après le départ du précédent.
    LaunchedEffect(envoi) {
        val courant = envoi ?: return@LaunchedEffect
        partagerPlusieurs(
            fichiers = courant.fichiers,
            type = courant.type,
            sujet = courant.sujet,
            email = emailExport ?: parametres.emailDestinataire,
        )
        vm.envoiConsomme()
    }

    LaunchedEffect(sauvegarde) {
        val fichier = sauvegarde ?: return@LaunchedEffect
        partager(
            fichier = fichier,
            type = "application/zip",
            sujet = "Sauvegarde Notes de frais · ${parametres.mention}",
            // Jamais pré-adressée : elle contient toute la base et les pièces.
            email = "",
        )
        vm.sauvegardeConsommee()
    }

    // Restauration : confirmation explicite, puis progression bloquante.
    val confirmationRestauration by vm.confirmationRestauration.collectAsStateWithLifecycle()
    val restaurationEnCours by vm.restaurationEnCours.collectAsStateWithLifecycle()
    confirmationRestauration?.let { demande ->
        DialogueConfirmationRestauration(
            demande = demande,
            onConfirmer = vm::confirmerRestauration,
            onAnnuler = vm::annulerRestauration,
        )
    }
    restaurationEnCours?.let { DialogueRestaurationEnCours(it) }

    // Paramétrage et sociétés seuls : un JSON, sans les dépenses.
    val fichierParametres by vm.fichierParametres.collectAsStateWithLifecycle()
    LaunchedEffect(fichierParametres) {
        val fichier = fichierParametres ?: return@LaunchedEffect
        partager(
            fichier = fichier,
            type = "application/json",
            sujet = "Paramétrage Notes de frais · ${parametres.mention}",
            // Jamais pré-adressé : le fichier contient les réglages de l'application.
            email = "",
        )
        vm.fichierParametresConsomme()
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
                onSauvegarderParametres = vm::sauvegarderParametres,
                onRestaurerParametres = { launcherParametres.launch(arrayOf("application/json", "text/plain", "*/*")) },
                profils = profils,
                onAjouterProfil = vm::ajouterProfil,
                onMajProfil = vm::majProfil,
                onSupprimerProfil = vm::supprimerProfil,
                chercherSiret = { siret -> vm.chercherSiret(siret) },
            )

            courant == null && archivesOuvertes -> EcranArchives(
                projets = projetsArchives,
                societePour = { projet -> profils.firstOrNull { it.id == projet.profilId }?.nom },
                onRetour = { archivesOuvertes = false },
                onOuvrir = vm::ouvrirProjet,
                onDesarchiver = vm::desarchiverProjet,
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
                onAffecterProfil = vm::affecterProfil,
                onArchiver = vm::demanderArchivage,
                onVoirArchives = { archivesOuvertes = true },
                nbArchives = projetsArchives.size,
                onEnvoyerMailto = { dialogueMailto = true },
            )

            else -> if (corbeilleOuverte) {
                EcranCorbeille(
                    projetNom = courant.nom,
                    depenses = corbeille,
                    onRetour = { corbeilleOuverte = false },
                    onRestaurer = vm::restaurerDepense,
                    onSupprimerDefinitivement = vm::supprimerDefinitivement,
                    onVider = vm::viderCorbeille,
                )
            } else EcranProjet(
                projet = courant,
                depenses = depenses,
                traitement = traitement,
                onRetour = {
                    corbeilleOuverte = false
                    vm.fermerProjet()
                },
                onPhoto = { prendrePhoto() },
                onGalerie = { launcherGalerie.launch(arrayOf("image/*", "application/pdf")) },
                onManuel = vm::ajouterManuellement,
                onExport = vm::exporter,
                onEnvoyerPdf = vm::envoyerJustificatifsPdf,
                onAlleger = vm::allegerJustificatifs,
                emailJustificatifs = profils.firstOrNull { it.id == courant.profilId }
                    ?.emailDestinataire?.takeIf { it.isNotBlank() }
                    ?: parametres.emailDestinataire.takeIf { it.isNotBlank() },
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
                societe = profils.firstOrNull { it.id == courant.profilId }?.nom
                    ?: parametres.societe.takeIf { it.isNotBlank() },
                onCorbeille = { corbeilleOuverte = true },
                nbCorbeille = nbCorbeille,
                capture = capture,
                onAjouterPage = { capture?.let { prendrePhoto(it.depenseId) } },
                onTerminerCapture = vm::terminerCapture,
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
            moyensPaiement = moyensPaiement,
            organismes = parametres.organismes,
            zoneParis = parametres.zoneParis,
            onGenererFacture = vm::ouvrirFactureEnLigne,
            onAjouterFacture = { uri -> vm.ajouterFacture(initiale, uri) },
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
            moyensPaiement = moyensPaiement,
            organismes = parametres.organismes,
            onRecadrerPiece = { nom, bitmap -> vm.recadrerPiece(initiale, nom, bitmap) },
            // Un projet clôturé est en lecture seule : il n'est pas proposé.
            projetsCibles = tousProjets.filter { !it.cloture && it.id != initiale.projetId },
            onDeplacer = { cible -> vm.deplacerDepense(initiale, cible) },
            zoneParis = parametres.zoneParis,
            onGenererFacture = vm::ouvrirFactureEnLigne,
            onAjouterFacture = { uri -> vm.ajouterFacture(initiale, uri) },
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

    exportTrop?.let { fichier ->
        val mo = String.format(java.util.Locale.FRANCE, "%.1f", fichier.length() / (1024.0 * 1024.0))
        AlertDialog(
            onDismissRequest = { vm.annulerDecoupage() },
            title = { Text("Export volumineux") },
            text = {
                Text(
                    "Le ZIP fait $mo Mo, au-delà de la limite de 10 Mo pour un envoi simple. " +
                        "Le découper en plusieurs archives, une par mail ? Le récap reste dans la " +
                        "première, les justificatifs sont répartis, et rien n'est perdu.",
                )
            },
            confirmButton = { TextButton(onClick = { vm.decouperExport() }) { Text("Découper") } },
            dismissButton = {
                Row {
                    TextButton(onClick = { vm.envoyerExportTelQuel() }) { Text("Envoyer tel quel") }
                    TextButton(onClick = { vm.annulerDecoupage() }) { Text("Annuler") }
                }
            },
        )
    }

    // Justificatifs manquants et taux absents relevés avant l'export : on les liste, mais rien
    // n'est bloqué. « Exporter quand même » lance l'export, « Annuler » ferme.
    controlesExport?.let { lignes ->
        AlertDialog(
            onDismissRequest = { vm.annulerExport() },
            title = { Text("Contrôle avant export") },
            text = {
                Column(
                    Modifier.verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    lignes.forEach { ligne ->
                        Text(ligne, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { vm.confirmerExport() }) { Text("Exporter quand même") }
            },
            dismissButton = {
                TextButton(onClick = { vm.annulerExport() }) { Text("Annuler") }
            },
        )
    }

    // Archiver sans avoir envoyé les pièces laisserait la comptabilité sans
    // justificatifs : on propose l'envoi avant de ranger le projet.
    archivageEnAttente?.let { enAttente ->
        val profilDuProjet = profils.firstOrNull { it.id == enAttente.profilId }
        val adressePdf = profilDuProjet?.emailDestinataire?.takeIf { it.isNotBlank() }
            ?: parametres.emailDestinataire
        AlertDialog(
            onDismissRequest = { vm.annulerArchivage() },
            title = { Text("Archiver « ${enAttente.nom} »") },
            text = {
                Text(
                    if (adressePdf.isNotBlank()) {
                        "Les justificatifs n'ont pas encore été envoyés à la comptabilité. " +
                            "Les envoyer en PDF à $adressePdf avant d'archiver ? Le projet " +
                            "part ensuite dans les archives, entier."
                    } else {
                        "Les justificatifs n'ont pas encore été envoyés à la comptabilité, et " +
                            "aucune adresse email n'est renseignée pour cette société. " +
                            "L'envoi ouvrira le partage, à toi de choisir le destinataire."
                    },
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.envoyerEtArchiver(enAttente) }) {
                    Text("Envoyer et archiver")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { vm.archiverProjet(enAttente) }) {
                        Text("Archiver sans envoyer")
                    }
                    TextButton(onClick = { vm.annulerArchivage() }) { Text("Annuler") }
                }
            },
        )
    }

    // Envoi découpé : chaque mail restant est proposé séparément, puisque la
    // messagerie doit être validée une fois par mail.
    envoiSuivant?.let { suivant ->
        val mo = String.format(java.util.Locale.FRANCE, "%.1f", suivant.poids / (1024.0 * 1024.0))
        AlertDialog(
            onDismissRequest = { vm.arreterEnvois() },
            title = { Text("Envoi ${suivant.rang} sur ${suivant.total}") },
            text = {
                Text(
                    "Une fois le mail précédent parti, il reste ${suivant.fichiers.size} " +
                        "fichier(s) ($mo Mo) à envoyer : ils ne tenaient pas dans le même " +
                        "mail. Envoyer celui-ci maintenant ?",
                )
            },
            confirmButton = { TextButton(onClick = { vm.envoyerEnvoiSuivant() }) { Text("Envoyer") } },
            dismissButton = { TextButton(onClick = { vm.arreterEnvois() }) { Text("Arrêter") } },
        )
    }

    // Une facture vient d'être jointe à une dépense qui a déjà un ticket : on
    // propose de retirer ce ticket. La suppression n'est jamais automatique.
    remplacementTicket?.let {
        AlertDialog(
            onDismissRequest = { vm.refuserRemplacementTicket() },
            title = { Text("Ticket et facture") },
            text = {
                Text("La facture est jointe. Supprimer la photo du ticket pour éviter un doublon ?")
            },
            confirmButton = {
                TextButton(onClick = { vm.remplacerTicketParFacture() }) { Text("Supprimer le ticket") }
            },
            dismissButton = {
                TextButton(onClick = { vm.refuserRemplacementTicket() }) { Text("Garder les deux") }
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
