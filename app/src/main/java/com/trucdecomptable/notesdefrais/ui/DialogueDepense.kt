package com.trucdecomptable.notesdefrais.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.trucdecomptable.notesdefrais.data.Controles
import com.trucdecomptable.notesdefrais.data.Depense
import com.trucdecomptable.notesdefrais.data.Justificatifs
import com.trucdecomptable.notesdefrais.data.Plafonds
import com.trucdecomptable.notesdefrais.data.SensTrajet
import com.trucdecomptable.notesdefrais.data.SituationRepas
import com.trucdecomptable.notesdefrais.data.TauxBdf
import com.trucdecomptable.notesdefrais.data.TypeDepense
import com.trucdecomptable.notesdefrais.util.Dates
import com.trucdecomptable.notesdefrais.util.centimesDe
import com.trucdecomptable.notesdefrais.util.centimesVersTexte
import com.trucdecomptable.notesdefrais.util.pariteTexte
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

private val DEVISES = listOf("EUR", "USD", "GBP", "CHF")
private val TAUX_TVA = listOf(2000 to "20 %", 1000 to "10 %", 550 to "5,5 %", 0 to "0 %")

/**
 * The one form in the app: it shows up when OCR could not decide, or when an
 * expense is opened for correction.
 */
@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
fun DialogueDepense(
    initiale: Depense,
    onAnnuler: () -> Unit,
    onValider: (Depense) -> Unit,
    onSupprimer: ((Depense) -> Unit)? = null,
    tauxPour: (String, String) -> TauxBdf.Taux? = { _, _ -> null },
    onAjouterPiece: ((android.net.Uri) -> Unit)? = null,
    moyensPaiement: List<String> = emptyList(),
    organismes: List<String> = emptyList(),
    onRecadrerPiece: ((String, android.graphics.Bitmap) -> Unit)? = null,
    projetsCibles: List<com.trucdecomptable.notesdefrais.data.Projet> = emptyList(),
    onDeplacer: ((com.trucdecomptable.notesdefrais.data.Projet) -> Unit)? = null,
    // Paris (92, 93, 94) change le plafond de la nuitée ; il est transmis par
    // l'appelant, comme la situation du repas change le plafond repas.
    zoneParis: Boolean = false,
) {
    var date by remember { mutableStateOf(Dates.isoVersFr(initiale.date)) }
    var montant by remember { mutableStateOf(if (initiale.montantCentimes > 0) centimesVersTexte(initiale.montantCentimes) else "") }
    var devise by remember { mutableStateOf(initiale.devise) }
    var taux by remember { mutableStateOf(initiale.tauxChange?.toString().orEmpty()) }
    var type by remember { mutableStateOf(initiale.typeDepense) }
    var libelle by remember { mutableStateOf(initiale.libelle) }
    var ventiler by remember { mutableStateOf(initiale.tvaTauxBp != null) }
    var tauxTva by remember { mutableStateOf(initiale.tvaTauxBp ?: 2000) }
    var choixDate by remember { mutableStateOf(false) }
    var refacturable by remember { mutableStateOf(initiale.refacturable) }
    var paiement by remember { mutableStateOf(initiale.moyenPaiement) }
    var organisme by remember { mutableStateOf(initiale.organisme) }
    var sens by remember { mutableStateOf(initiale.sensTrajet) }
    var gareDepart by remember { mutableStateOf(initiale.gareDepart) }
    var gareArrivee by remember { mutableStateOf(initiale.gareArrivee) }
    var dateRetour by remember { mutableStateOf(Dates.isoVersFr(initiale.dateRetour)) }
    var participants by remember { mutableStateOf(initiale.participants) }
    var societeParticipant by remember { mutableStateOf(initiale.societeParticipant) }
    var motifRepas by remember { mutableStateOf(initiale.motifRepas) }
    // La situation du repas change son plafond d'exonération : on la garde en
    // état, lue depuis la dépense, puis reportée dans `candidate`.
    var situation by remember { mutableStateOf(initiale.situation) }
    var numFacture by remember { mutableStateOf(initiale.numFacture) }
    var tauxBdf by remember { mutableStateOf<TauxBdf.Taux?>(null) }
    var apercu by remember { mutableStateOf<String?>(null) }
    var choixProjet by remember { mutableStateOf(false) }
    var confirmationCorbeille by remember { mutableStateOf(false) }
    var confirmationFacture by remember { mutableStateOf(false) }

    val launcherPiece = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.OpenDocument(),
    ) { uri -> uri?.let { onAjouterPiece?.invoke(it) } }

    // The rate is the Banque de France parity for the expense date, published
    // by trucdecomptable.fr. It fills the field, and stays editable.
    LaunchedEffect(date, devise) {
        if (devise == "EUR") {
            tauxBdf = null
            taux = ""
            return@LaunchedEffect
        }
        val trouve = tauxPour(Dates.frVersIso(date) ?: date, devise)
        tauxBdf = trouve
        if (trouve != null && taux.isBlank()) taux = pariteTexte(trouve.valeur)
    }

    val montantCentimes = centimesDe(montant)
    // Le type courant décide des questions à poser (trajet, invités) ; il sert
    // aussi au bouton Enregistrer, donc il vit hors du contenu du dialogue.
    val typeCourant = initiale.copy(type = type.name)
    // La date de retour n'est demandée que pour un aller-retour : vide permise
    // (billet non encore daté), mais une date incomplète bloque l'enregistrement.
    val dateRetourDemandee = typeCourant.trajetDemande && sens == SensTrajet.ALLER_RETOUR.name
    val dateRetourValide = !dateRetourDemandee || dateRetour.isBlank() || Dates.estValide(dateRetour)
    val valide = Dates.estValide(date) && montantCentimes != null && montantCentimes > 0L && dateRetourValide

    // La dépense telle qu'elle partirait à l'enregistrement : elle sert au
    // contrôle de la facture obligatoire, puis à l'enregistrement lui-même.
    val candidate = initiale.copy(
        date = Dates.frVersIso(date) ?: initiale.date,
        montantCentimes = montantCentimes ?: 0L,
        devise = devise,
        tauxChange = if (devise == "EUR") null else taux.replace(',', '.').toDoubleOrNull(),
        type = type.name,
        libelle = libelle.trim(),
        tvaTauxBp = if (ventiler) tauxTva else null,
        refacturable = refacturable,
        moyenPaiement = paiement.trim(),
        organisme = organisme.trim(),
        sensTrajet = if (typeCourant.trajetDemande) sens else "",
        gareDepart = if (typeCourant.trajetDemande) gareDepart.trim() else "",
        gareArrivee = if (typeCourant.trajetDemande) gareArrivee.trim() else "",
        dateRetour = if (dateRetourDemandee) Dates.frVersIso(dateRetour).orEmpty() else "",
        participants = if (typeCourant.repasDemande) participants.trim() else "",
        societeParticipant = if (typeCourant.repasDemande) societeParticipant.trim() else "",
        motifRepas = if (typeCourant.repasDemande) motifRepas.trim() else "",
        // Numéro de facture : repère fiable contre les doublons. Facultatif,
        // toutes dépenses : beaucoup de tickets n'en portent pas.
        numFacture = numFacture.trim(),
        // La situation du repas, choisie plus bas, change le plafond appliqué
        // par Plafonds.alerte : c'est elle qui est enregistrée, pas le plafond.
        situationRepas = situation.name,
    )
    // Repas au restaurant au-delà de 150 € HT : la facture nominative devient
    // le seul justificatif valable. On le dit avant d'enregistrer, pas après.
    val alerteFacture = Justificatifs.alerte(candidate)

    AlertDialog(
        onDismissRequest = onAnnuler,
        title = { Text(if (initiale.id == 0L) "Nouvelle dépense" else "Modifier la dépense") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = date,
                        onValueChange = { date = it },
                        label = { Text("Date (jj/mm/aaaa)") },
                        placeholder = { Text("12/10/2026") },
                        singleLine = true,
                        isError = date.isNotBlank() && !Dates.estValide(date),
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { choixDate = true }) {
                        Icon(Icons.Default.DateRange, contentDescription = "Choisir une date")
                    }
                }

                OutlinedTextField(
                    value = montant,
                    onValueChange = { montant = it },
                    label = { Text("Montant TTC") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )

                Text("Devise", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DEVISES.forEach { code ->
                        PuceChoix(
                            selected = devise == code,
                            onClick = { devise = code },
                            label = { Text(code) },
                        )
                    }
                }

                if (devise != "EUR") {
                    OutlinedTextField(
                        value = taux,
                        onValueChange = { taux = it },
                        label = { Text("1 € = ? $devise") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    val trouve = tauxBdf
                    if (trouve != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "Banque de France · ${trouve.libelle()}",
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f),
                            )
                            if (taux != pariteTexte(trouve.valeur)) {
                                TextButton(onClick = { taux = pariteTexte(trouve.valeur) }) {
                                    Text("Rétablir")
                                }
                            }
                        }
                    } else {
                        Text(
                            "Aucune parité publiée pour cette date : saisir le taux à la main.",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                Text("Type de dépense", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TypeDepense.entries.forEach { t ->
                        PuceChoix(
                            selected = type == t,
                            onClick = { type = t },
                            label = { Text(t.label) },
                        )
                    }
                }

                // Numéro de facture tel qu'imprimé sur le justificatif. Il sert
                // de repère fiable contre les doublons ; facultatif, toutes
                // dépenses : beaucoup de tickets n'en portent pas.
                OutlinedTextField(
                    value = numFacture,
                    onValueChange = { numFacture = it },
                    label = { Text("N° de facture") },
                    placeholder = { Text("facultatif") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                // Train et avion : un aller ou un aller-retour ? La question
                // n'apparaît que pour ces deux types.
                if (typeCourant.trajetDemande) {
                    Text("Trajet", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SensTrajet.entries.forEach { option ->
                            PuceChoix(
                                selected = sens == option.name,
                                onClick = { sens = if (sens == option.name) "" else option.name },
                                label = { Text(option.label) },
                            )
                        }
                    }
                    // Gares du trajet : sur un aller-retour, les mêmes gares
                    // servent dans les deux sens, on ne les saisit qu'une fois.
                    val mot = if (type == TypeDepense.TRAIN) "Gare" else "Aéroport"
                    OutlinedTextField(
                        value = gareDepart,
                        onValueChange = { gareDepart = it },
                        label = { Text("$mot de départ") },
                        placeholder = { Text(if (type == TypeDepense.TRAIN) "Paris Gare de Lyon" else "Paris Orly") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = gareArrivee,
                        onValueChange = { gareArrivee = it },
                        label = { Text("$mot d'arrivée") },
                        placeholder = { Text(if (type == TypeDepense.TRAIN) "Lyon Part-Dieu" else "Nice") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (dateRetourDemandee) {
                        OutlinedTextField(
                            value = dateRetour,
                            onValueChange = { dateRetour = it },
                            label = { Text("Date du retour (jj/mm/aaaa)") },
                            placeholder = { Text("facultatif") },
                            singleLine = true,
                            isError = dateRetour.isNotBlank() && !Dates.estValide(dateRetour),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    // Ce que lira la comptabilité : les gares, le sens, le retour.
                    val apercuTrajet = initiale.copy(
                        type = type.name,
                        sensTrajet = sens,
                        gareDepart = gareDepart,
                        gareArrivee = gareArrivee,
                        dateRetour = if (dateRetourDemandee) Dates.frVersIso(dateRetour).orEmpty() else "",
                    ).trajetLisible
                    if (apercuTrajet.isNotBlank()) {
                        Text("Trajet : $apercuTrajet", style = MaterialTheme.typography.bodySmall)
                    }
                }

                // Repas : la déductibilité tient à la situation (qui change le
                // plafond), aux invités et au motif. Trois informations que la
                // comptabilité voudrait voir sur la ligne.
                if (typeCourant.repasDemande) {
                    // La situation du repas change le plafond d'exonération : le
                    // même repas au restaurant ne vaut pas le même plafond en
                    // grand déplacement qu'au lieu de travail. On le demande, et
                    // on rappelle le plafond qui s'applique.
                    Text("Situation du repas", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        SituationRepas.entries.forEach { option ->
                            PuceChoix(
                                selected = situation == option,
                                onClick = { situation = option },
                                label = { Text(option.label) },
                            )
                        }
                    }
                    Plafonds.libellePlafond(typeCourant.typeDepense, zoneParis, situation)?.let { libelle ->
                        Text(libelle, style = MaterialTheme.typography.bodySmall)
                    }

                    Text("Invités et motif", style = MaterialTheme.typography.labelLarge)
                    OutlinedTextField(
                        value = participants,
                        onValueChange = { participants = it },
                        label = { Text("Nom et prénom des invités") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = societeParticipant,
                        onValueChange = { societeParticipant = it },
                        label = { Text("Société des invités") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    OutlinedTextField(
                        value = motifRepas,
                        onValueChange = { motifRepas = it },
                        label = { Text("Motif du repas") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }

                // Les contrôles de saisie (date à venir, TVA improbable, repas
                // incomplet) et le dépassement de plafond : ils avertissent sans
                // bloquer l'enregistrement, affichés en rouge sous les champs.
                (Controles.alertesSaisie(candidate) + listOfNotNull(Plafonds.alerte(candidate, zoneParis)))
                    .forEach { alerte ->
                        Text(
                            alerte,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }

                // Which card paid: several CBs, cash, a transfer. The labels come
                // from the settings, and an older value keeps its own chip so a
                // renamed card never loses the history.
                Text("Mode de paiement", style = MaterialTheme.typography.labelLarge)
                val options = remember(moyensPaiement, paiement) {
                    (moyensPaiement + paiement)
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .distinct()
                }
                if (options.isEmpty()) {
                    OutlinedTextField(
                        value = paiement,
                        onValueChange = { paiement = it },
                        label = { Text("Moyen de paiement (ex. CB pro)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        options.forEach { moyen ->
                            PuceChoix(
                                selected = paiement == moyen,
                                onClick = { paiement = if (paiement == moyen) "" else moyen },
                                label = { Text(moyen) },
                            )
                        }
                    }
                    Text(
                        "Toucher une puce pour l'enlever. La liste se règle dans le paramétrage.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }

                // Organisme de formation : FOROM, CNCC Formation, ou un nom libre.
                // La liste permanente se règle dans le paramétrage ; un nom qui
                // n'y figure plus garde sa puce, donc rien n'est perdu.
                Text("Organisme (formation)", style = MaterialTheme.typography.labelLarge)
                val optionsOrganismes = remember(organismes, organisme) {
                    (organismes + organisme)
                        .map { it.trim() }
                        .filter { it.isNotEmpty() }
                        .distinct()
                }
                if (optionsOrganismes.isNotEmpty()) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        optionsOrganismes.forEach { nom ->
                            PuceChoix(
                                selected = organisme == nom,
                                onClick = { organisme = if (organisme == nom) "" else nom },
                                label = { Text(nom) },
                            )
                        }
                    }
                }
                OutlinedTextField(
                    value = organisme,
                    onValueChange = { organisme = it },
                    label = { Text("Organisme (libre)") },
                    placeholder = { Text("facultatif · un nom saisi ici reste sur cette dépense") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = ventiler, onCheckedChange = { ventiler = it })
                    Spacer(Modifier.padding(start = 8.dp))
                    Text("Ventiler HT / TVA", style = MaterialTheme.typography.bodyMedium)
                }
                if (ventiler) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TAUX_TVA.forEach { (bp, texte) ->
                            PuceChoix(
                                selected = tauxTva == bp,
                                onClick = { tauxTva = bp },
                                label = { Text(texte) },
                            )
                        }
                    }
                    val tva = montantCentimes?.let { m ->
                        val valeur = BigDecimal(tauxTva).movePointLeft(4)
                        BigDecimal(m).multiply(valeur)
                            .divide(BigDecimal.ONE.add(valeur), 0, RoundingMode.HALF_UP).toLong()
                    }
                    if (tva != null && montantCentimes != null) {
                        Text(
                            "HT ${centimesVersTexte(montantCentimes - tva)} · TVA ${centimesVersTexte(tva)}",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }

                // Seuil de la facture obligatoire : visible pendant la saisie,
                // pour ne pas découvrir la contrainte au moment d'enregistrer.
                if (alerteFacture != null) {
                    Text(
                        alerteFacture,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }

                OutlinedTextField(
                    value = libelle,
                    onValueChange = { libelle = it },
                    label = { Text("Libellé (fournisseur, objet)") },
                    modifier = Modifier.fillMaxWidth(),
                )

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = refacturable, onCheckedChange = { refacturable = it })
                    Spacer(Modifier.padding(start = 8.dp))
                    Text("Refacturable au client", style = MaterialTheme.typography.bodyMedium)
                }

                val pieces = initiale.tousLesFichiers
                if (pieces.isNotEmpty()) {
                    Text("Pièces jointes", style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        pieces.forEach { nom ->
                            VignettePiece(nom, taille = 64.dp) { apercu = nom }
                        }
                    }
                }

                if (initiale.id != 0L && onAjouterPiece != null) {
                    TextButton(onClick = { launcherPiece.launch(arrayOf("image/*", "application/pdf")) }) {
                        Text(
                            "Ajouter une pièce jointe" +
                                if (initiale.tousLesFichiers.size > 1) " (${initiale.tousLesFichiers.size} déjà jointes)" else "",
                        )
                    }
                }

                // Déplacement vers un autre projet (jamais vers un projet clôturé).
                if (initiale.id != 0L && onDeplacer != null && projetsCibles.isNotEmpty()) {
                    TextButton(onClick = { choixProjet = true }) {
                        Text("Déplacer vers un autre projet")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valide,
                onClick = {
                    // Le repas au-delà du seuil demande une facture : on le
                    // signale dans une fenêtre dédiée avant d'enregistrer.
                    if (alerteFacture != null) confirmationFacture = true else onValider(candidate)
                },
            ) { Text("Enregistrer") }
        },
        dismissButton = {
            Row {
                if (onSupprimer != null && initiale.id != 0L) {
                    TextButton(onClick = { confirmationCorbeille = true }) { Text("Corbeille") }
                }
                TextButton(onClick = onAnnuler) { Text("Annuler") }
            }
        },
    )

    if (confirmationCorbeille) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmationCorbeille = false },
            title = { Text("Mettre à la corbeille ?") },
            text = {
                Text(
                    "La dépense sort des totaux et de l'export, mais son justificatif est " +
                        "conservé : tu pourras la restaurer depuis la corbeille du projet.",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmationCorbeille = false
                        onSupprimer?.invoke(initiale)
                    },
                ) { Text("Mettre à la corbeille") }
            },
            dismissButton = {
                TextButton(onClick = { confirmationCorbeille = false }) { Text("Annuler") }
            },
        )
    }

    if (confirmationFacture) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { confirmationFacture = false },
            title = { Text("Facture obligatoire") },
            text = { Text(Justificatifs.explication(candidate)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmationFacture = false
                        onValider(candidate)
                    },
                ) { Text("Enregistrer quand même") }
            },
            dismissButton = {
                Row {
                    // Joindre la facture suppose une dépense déjà enregistrée :
                    // sur une nouvelle dépense, la pièce s'ajoute après coup.
                    if (onAjouterPiece != null && initiale.id != 0L) {
                        TextButton(
                            onClick = {
                                confirmationFacture = false
                                launcherPiece.launch(arrayOf("image/*", "application/pdf"))
                            },
                        ) { Text("Joindre la facture") }
                    }
                    TextButton(onClick = { confirmationFacture = false }) { Text("Annuler") }
                }
            },
        )
    }

    apercu?.let { nom ->
        ApercuPiece(
            nom = nom,
            onFermer = { apercu = null },
            onRecadrer = { piece, bitmap -> onRecadrerPiece?.invoke(piece, bitmap) },
        )
    }

    if (choixProjet) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = { choixProjet = false },
            title = { Text("Déplacer vers un projet") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    Text(
                        "La dépense, son montant et ses pièces jointes changent de projet. " +
                            "Les projets clôturés ne sont pas proposés.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    projetsCibles.forEach { cible ->
                        Row(
                            Modifier.fillMaxWidth()
                                .clickable {
                                    choixProjet = false
                                    onDeplacer?.invoke(cible)
                                }
                                .padding(vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                Icons.Default.Folder,
                                contentDescription = null,
                                modifier = Modifier.padding(end = 10.dp),
                            )
                            Text(cible.nom, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { choixProjet = false }) { Text("Annuler") } },
        )
    }

    if (choixDate) {
        val etat = rememberDatePickerState(
            initialSelectedDateMillis = runCatching {
                LocalDate.parse(Dates.frVersIso(date).orEmpty())
                    .atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            }.getOrNull(),
        )
        DatePickerDialog(
            onDismissRequest = { choixDate = false },
            confirmButton = {
                TextButton(onClick = {
                    etat.selectedDateMillis?.let { millis ->
                        date = Dates.isoVersFr(
                            Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString(),
                        )
                    }
                    choixDate = false
                }) { Text("OK") }
            },
            dismissButton = { TextButton(onClick = { choixDate = false }) { Text("Annuler") } },
        ) {
            DatePicker(state = etat)
        }
    }
}
