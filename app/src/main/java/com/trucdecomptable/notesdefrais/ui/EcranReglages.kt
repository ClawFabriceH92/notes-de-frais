package com.trucdecomptable.notesdefrais.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.SettingsBackupRestore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.trucdecomptable.notesdefrais.data.AnnuaireEntreprises
import com.trucdecomptable.notesdefrais.data.CategorieVehicule
import com.trucdecomptable.notesdefrais.data.Parametres
import com.trucdecomptable.notesdefrais.data.Profil
import kotlinx.coroutines.launch

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
    onSauvegarderParametres: () -> Unit = {},
    onRestaurerParametres: () -> Unit = {},
    profils: List<Profil> = emptyList(),
    onAjouterProfil: (Profil) -> Unit = {},
    onMajProfil: (Profil) -> Unit = {},
    onSupprimerProfil: (Profil) -> Unit = {},
    chercherSiret: suspend (String) -> AnnuaireEntreprises.Info? = { null },
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
    var pieceEnGrand by remember { mutableStateOf(initial.pieceEnGrand) }
    var recadrageAuto by remember { mutableStateOf(initial.recadrageAuto) }
    var profilEdite by remember { mutableStateOf<Profil?>(null) }
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
        pieceEnGrand = pieceEnGrand,
        pieceEnGrandV2 = initial.pieceEnGrandV2,
        profilCourant = initial.profilCourant,
        moyensPaiementV2 = initial.moyensPaiementV2,
        recadrageAuto = recadrageAuto,
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
            Text(
                "Le titulaire est salarié : sa société, son SIRET et son adresse se règlent " +
                    "dans la section « Sociétés (profils) » ci-dessous.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedTextField(prenom, { prenom = it }, label = { Text("Prénom") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(nom, { nom = it }, label = { Text("Nom") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(
                email,
                { email = it },
                label = { Text("Email où envoyer les justificatifs (par défaut)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                modifier = Modifier.fillMaxWidth(),
            )

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Sociétés (profils)", style = MaterialTheme.typography.titleMedium)
            Text(
                "Une société par jeu de projets : les projets, les exports et le destinataire des mails " +
                    "suivent la société choisie sur l'écran Projets. Les champs Société / SIRET / Adresse " +
                    "ci-dessus servent quand aucun profil n'est sélectionné.",
                style = MaterialTheme.typography.bodySmall,
            )
            profils.forEach { profil ->
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    Column(
                        Modifier.weight(1f).clickable { profilEdite = profil }.padding(vertical = 6.dp),
                    ) {
                        Text(profil.nom, style = MaterialTheme.typography.titleSmall)
                        // L'adresse des justificatifs : c'est le champ qu'on
                        // cherche à relire d'un coup d'œil depuis la liste.
                        Text(
                            profil.emailDestinataire.takeIf { it.isNotBlank() }
                                ?.let { "PDF → $it" }
                                ?: "PDF : aucune adresse email",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        val details = listOf(
                            profil.siret,
                            profil.adresse,
                            profil.moyenPaiementDefaut.takeIf { it.isNotBlank() }?.let { "Paiement : $it" }.orEmpty(),
                        ).filter { it.isNotBlank() }.joinToString(" · ")
                        if (details.isNotBlank()) {
                            Text(details, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                    IconButton(onClick = { profilEdite = profil }) {
                        Icon(Icons.Default.Edit, contentDescription = "Modifier ${profil.nom}")
                    }
                    IconButton(onClick = { onSupprimerProfil(profil) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Supprimer ${profil.nom}")
                    }
                }
            }
            OutlinedButton(
                onClick = { profilEdite = Profil(nom = societe.trim()) },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Icon(Icons.Default.Add, contentDescription = null)
                Text("  Ajouter une société")
            }

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
                    PuceChoix(selected = categorie == c, onClick = { categorie = c }, label = { Text(c.label) })
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
                    PuceChoix(selected = devise == code, onClick = { devise = code }, label = { Text(code) })
                }
            }
            Text("Taux de TVA", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(2000 to "20 %", 1000 to "10 %", 550 to "5,5 %", 0 to "Aucun").forEach { (bp, texte) ->
                    PuceChoix(selected = tauxTva == bp, onClick = { tauxTva = bp }, label = { Text(texte) })
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
            Text("Pièces jointes", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = pieceEnGrand, onCheckedChange = { pieceEnGrand = it })
                Text(
                    "  Ouvrir le justificatif en grand dès qu'on touche une dépense " +
                        "(le formulaire s'ouvre à la fermeture)",
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = recadrageAuto, onCheckedChange = { recadrageAuto = it })
                Text(
                    "  Ne garder que le ticket sur une photo (le fond est coupé). " +
                        "Le recadrage manuel reste disponible sur la pièce jointe.",
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
                    PuceChoix(
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

            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text("Paramétrage seul", style = MaterialTheme.typography.titleMedium)
            Text(
                "Un fichier JSON avec le titulaire, le véhicule, les moyens de paiement et les " +
                    "sociétés, sans aucune dépense : pratique pour passer d'un téléphone à l'autre " +
                    "sans écraser les notes déjà saisies.",
                style = MaterialTheme.typography.bodySmall,
            )
            OutlinedButton(onClick = onSauvegarderParametres, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Save, contentDescription = null)
                Text("  Sauvegarder le paramétrage et les sociétés")
            }
            OutlinedButton(onClick = onRestaurerParametres, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.SettingsBackupRestore, contentDescription = null)
                Text("  Restaurer le paramétrage et les sociétés")
            }

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

    profilEdite?.let { profil ->
        DialogueProfil(
            initial = profil,
            moyens = moyens,
            chercherSiret = chercherSiret,
            onAnnuler = { profilEdite = null },
            onValider = { retenu ->
                if (retenu.id == 0L) onAjouterProfil(retenu) else onMajProfil(retenu)
                profilEdite = null
            },
        )
    }
}

/**
 * Création / modification d'une société. Le SIRET sert deux fois : il valide la
 * saisie (clé de Luhn) et il remplit la raison sociale et l'adresse via
 * l'annuaire des entreprises, plutôt que de les faire recopier.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DialogueProfil(
    initial: Profil,
    moyens: List<String>,
    chercherSiret: suspend (String) -> AnnuaireEntreprises.Info?,
    onAnnuler: () -> Unit,
    onValider: (Profil) -> Unit,
) {
    var nom by remember { mutableStateOf(initial.nom) }
    var siret by remember { mutableStateOf(initial.siret) }
    var adresse by remember { mutableStateOf(initial.adresse) }
    var email by remember { mutableStateOf(initial.emailDestinataire) }
    var paiement by remember { mutableStateOf(initial.moyenPaiementDefaut) }
    // Moyens propres à la société, ajoutés en plus des moyens communs.
    var propres by remember { mutableStateOf(initial.moyens) }
    var ajout by remember { mutableStateOf<String?>(null) }
    var choixPaiement by remember { mutableStateOf(false) }
    val tous = (moyens + propres).distinct()
    var recherche by remember { mutableStateOf(false) }
    var retour by remember { mutableStateOf<String?>(null) }
    val portee = androidx.compose.runtime.rememberCoroutineScope()

    AlertDialog(
        onDismissRequest = onAnnuler,
        title = { Text(if (initial.id == 0L) "Nouvelle société" else "Modifier la société") },
        text = {
            Column(
                Modifier.verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = nom,
                    onValueChange = { nom = it },
                    label = { Text("Raison sociale") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = siret,
                    onValueChange = { siret = it.filter { c -> c.isDigit() }.take(14) },
                    label = { Text("SIRET (14 chiffres)") },
                    singleLine = true,
                    isError = siret.isNotBlank() && !AnnuaireEntreprises.valide(siret),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    enabled = !recherche && siret.length >= 9,
                    onClick = {
                        recherche = true
                        portee.launch {
                            val info = chercherSiret(siret)
                            if (info == null) {
                                retour = "Aucune entreprise trouvée pour ce SIRET"
                            } else {
                                nom = info.nom
                                if (info.adresse.isNotBlank()) adresse = info.adresse
                                retour = "Trouvé : ${info.nom}"
                            }
                            recherche = false
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(if (recherche) "Recherche…" else "Remplir depuis l'annuaire des entreprises")
                }
                retour?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                OutlinedTextField(
                    value = adresse,
                    onValueChange = { adresse = it },
                    label = { Text("Adresse") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = email,
                    onValueChange = { email = it },
                    label = { Text("Email où envoyer les justificatifs (PDF)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                    modifier = Modifier.fillMaxWidth(),
                )
                // Moyens de paiement : les communs s'appliquent à tous les
                // justificatifs et ne se suppriment pas ici ; la société peut
                // ajouter les siens (une carte propre à cette société).
                Text("Moyens de paiement de la société", style = MaterialTheme.typography.labelLarge)
                moyens.forEach { moyen ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text(moyen, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text(
                            "commun",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                    }
                }
                propres.forEach { moyen ->
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text(moyen, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        IconButton(onClick = {
                            propres = propres - moyen
                            if (paiement == moyen) paiement = ""
                        }) {
                            Icon(Icons.Default.Delete, contentDescription = "Retirer $moyen")
                        }
                    }
                }
                OutlinedButton(onClick = { ajout = "" }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Add, contentDescription = null)
                    Text("  Ajouter un moyen propre à cette société")
                }

                Row(
                    Modifier.fillMaxWidth().clickable { choixPaiement = true }.padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text("Moyen de paiement par défaut", style = MaterialTheme.typography.labelLarge)
                        Text(
                            paiement.ifBlank { "Aucun (celui du paramétrage général)" },
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                    TextButton(onClick = { choixPaiement = true }) { Text("Choisir") }
                }

                if (ajout != null) {
                    DialogueTexte(
                        titre = "Moyen de paiement de la société",
                        libelle = "Nom (ex. CB BNP de la société)",
                        valeurInitiale = ajout.orEmpty(),
                        onAnnuler = { ajout = null },
                        onValider = { nom ->
                            val propre = nom.trim()
                            if (propre.isNotEmpty()) {
                                propres = (propres + propre).distinct()
                                if (paiement.isBlank()) paiement = propre
                            }
                            ajout = null
                        },
                    )
                }

                if (choixPaiement) {
                    AlertDialog(
                        onDismissRequest = { choixPaiement = false },
                        title = { Text("Moyen de paiement de la société") },
                        text = {
                            Column(Modifier.verticalScroll(rememberScrollState())) {
                                Row(
                                    Modifier.fillMaxWidth()
                                        .clickable { paiement = ""; choixPaiement = false }
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(
                                        selected = paiement.isBlank(),
                                        onClick = { paiement = ""; choixPaiement = false },
                                    )
                                    Text(
                                        "Aucun (celui du paramétrage général)",
                                        style = MaterialTheme.typography.bodyMedium,
                                    )
                                }
                                tous.forEach { moyen ->
                                    Row(
                                        Modifier.fillMaxWidth()
                                            .clickable { paiement = moyen; choixPaiement = false }
                                            .padding(vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically,
                                    ) {
                                        RadioButton(
                                            selected = paiement == moyen,
                                            onClick = { paiement = moyen; choixPaiement = false },
                                        )
                                        Text(moyen, style = MaterialTheme.typography.bodyMedium)
                                    }
                                }
                            }
                        },
                        confirmButton = {
                            TextButton(onClick = { choixPaiement = false }) { Text("Fermer") }
                        },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = nom.isNotBlank(),
                onClick = {
                    onValider(
                        initial.copy(
                            nom = nom.trim(),
                            siret = siret.trim(),
                            adresse = adresse.trim(),
                            emailDestinataire = email.trim(),
                            moyenPaiementDefaut = paiement.trim(),
                            moyensPaiement = propres.joinToString("|"),
                        ),
                    )
                },
            ) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onAnnuler) { Text("Annuler") } },
    )
}
