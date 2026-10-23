package com.trucdecomptable.notesdefrais.ui

import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import com.trucdecomptable.notesdefrais.data.Depense
import com.trucdecomptable.notesdefrais.data.TauxBdf
import com.trucdecomptable.notesdefrais.data.TypeDepense
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
) {
    var date by remember { mutableStateOf(initiale.date) }
    var montant by remember { mutableStateOf(if (initiale.montantCentimes > 0) centimesVersTexte(initiale.montantCentimes) else "") }
    var devise by remember { mutableStateOf(initiale.devise) }
    var taux by remember { mutableStateOf(initiale.tauxChange?.toString().orEmpty()) }
    var type by remember { mutableStateOf(initiale.typeDepense) }
    var libelle by remember { mutableStateOf(initiale.libelle) }
    var ventiler by remember { mutableStateOf(initiale.tvaTauxBp != null) }
    var tauxTva by remember { mutableStateOf(initiale.tvaTauxBp ?: 2000) }
    var choixDate by remember { mutableStateOf(false) }
    var refacturable by remember { mutableStateOf(initiale.refacturable) }
    var tauxBdf by remember { mutableStateOf<TauxBdf.Taux?>(null) }

    val launcherPiece = androidx.activity.compose.rememberLauncherForActivityResult(
        androidx.activity.result.contract.ActivityResultContracts.GetContent(),
    ) { uri -> uri?.let { onAjouterPiece?.invoke(it) } }

    // The rate is the Banque de France parity for the expense date, published
    // by trucdecomptable.fr. It fills the field, and stays editable.
    LaunchedEffect(date, devise) {
        if (devise == "EUR") {
            tauxBdf = null
            taux = ""
            return@LaunchedEffect
        }
        val trouve = tauxPour(date.trim(), devise)
        tauxBdf = trouve
        if (trouve != null && taux.isBlank()) taux = pariteTexte(trouve.valeur)
    }

    val montantCentimes = centimesDe(montant)
    val valide = date.isNotBlank() && montantCentimes != null && montantCentimes > 0L

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
                        label = { Text("Date (AAAA-MM-JJ)") },
                        singleLine = true,
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
                        FilterChip(
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
                        FilterChip(
                            selected = type == t,
                            onClick = { type = t },
                            label = { Text(t.label) },
                        )
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Switch(checked = ventiler, onCheckedChange = { ventiler = it })
                    Spacer(Modifier.padding(start = 8.dp))
                    Text("Ventiler HT / TVA", style = MaterialTheme.typography.bodyMedium)
                }
                if (ventiler) {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TAUX_TVA.forEach { (bp, texte) ->
                            FilterChip(
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

                if (initiale.id != 0L && onAjouterPiece != null) {
                    TextButton(onClick = { launcherPiece.launch("image/*") }) {
                        Text(
                            "Ajouter une pièce jointe" +
                                if (initiale.tousLesFichiers.size > 1) " (${initiale.tousLesFichiers.size} déjà jointes)" else "",
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valide,
                onClick = {
                    onValider(
                        initiale.copy(
                            date = date.trim(),
                            montantCentimes = montantCentimes ?: 0L,
                            devise = devise,
                            tauxChange = if (devise == "EUR") null else taux.replace(',', '.').toDoubleOrNull(),
                            type = type.name,
                            libelle = libelle.trim(),
                            tvaTauxBp = if (ventiler) tauxTva else null,
                            refacturable = refacturable,
                        ),
                    )
                },
            ) { Text("Enregistrer") }
        },
        dismissButton = {
            Row {
                if (onSupprimer != null && initiale.id != 0L) {
                    TextButton(onClick = { onSupprimer(initiale) }) { Text("Supprimer") }
                }
                TextButton(onClick = onAnnuler) { Text("Annuler") }
            }
        },
    )

    if (choixDate) {
        val etat = rememberDatePickerState(
            initialSelectedDateMillis = runCatching {
                LocalDate.parse(date).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            }.getOrNull(),
        )
        DatePickerDialog(
            onDismissRequest = { choixDate = false },
            confirmButton = {
                TextButton(onClick = {
                    etat.selectedDateMillis?.let { millis ->
                        date = Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString()
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
