package com.trucdecomptable.notesdefrais.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.trucdecomptable.notesdefrais.data.BaremeKm
import com.trucdecomptable.notesdefrais.data.Parametres
import com.trucdecomptable.notesdefrais.util.centimesDe
import com.trucdecomptable.notesdefrais.util.Dates
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Locale

/**
 * Mileage allowance entry. The amount is not typed: it comes from the official
 * bracket, shown live with the formula used, so the figure can be checked before
 * it is recorded.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DialogueIndemniteKm(
    parametres: Parametres,
    onAnnuler: () -> Unit,
    onValider: (date: String, distanceKm: Int, trajet: String, fraisCentimes: Long, libelle: String) -> Unit,
) {
    var date by remember { mutableStateOf(Dates.aujourdhuiFr()) }
    var trajet by remember { mutableStateOf("") }
    var distance by remember { mutableStateOf("") }
    var frais by remember { mutableStateOf("") }
    var libelle by remember { mutableStateOf("") }
    var choixDate by remember { mutableStateOf(false) }

    val distanceKm = distance.toIntOrNull() ?: 0
    val fraisCentimes = centimesDe(frais) ?: 0L
    val indemnite = BaremeKm.indemniteCentimes(
        distanceKm = distanceKm,
        cumulAnnuelKm = parametres.kmAnnuels,
        categorie = parametres.categorie,
        puissanceFiscale = parametres.puissanceFiscale,
        electrique = parametres.electrique,
    )
    val total = indemnite + fraisCentimes
    val valide = distanceKm > 0 && Dates.estValide(date)

    AlertDialog(
        onDismissRequest = onAnnuler,
        title = { Text("Indemnité kilométrique") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = date,
                        onValueChange = { date = it },
                        label = { Text("Date (jj/mm/aaaa)") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = { choixDate = true }) {
                        Icon(Icons.Default.DateRange, contentDescription = "Choisir une date")
                    }
                }
                OutlinedTextField(
                    value = trajet,
                    onValueChange = { trajet = it },
                    label = { Text("Trajet (Asnières → Rouen)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = distance,
                    onValueChange = { distance = it.filter { c -> c.isDigit() } },
                    label = { Text("Distance (km)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = frais,
                    onValueChange = { frais = it },
                    label = { Text("Péages et stationnement (optionnel)") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = libelle,
                    onValueChange = { libelle = it },
                    label = { Text("Objet du déplacement") },
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    BaremeKm.explication(
                        distanceKm = distanceKm,
                        cumulAnnuelKm = parametres.kmAnnuels,
                        categorie = parametres.categorie,
                        puissanceFiscale = parametres.puissanceFiscale,
                        electrique = parametres.electrique,
                    ),
                    style = MaterialTheme.typography.bodySmall,
                )
                if (parametres.plaque.isNotBlank()) {
                    Text("Véhicule ${parametres.plaque}", style = MaterialTheme.typography.bodySmall)
                }
                Text(
                    String.format(
                        Locale.FRANCE,
                        "Indemnité %.2f €%s · total %.2f €",
                        indemnite / 100.0,
                        if (fraisCentimes > 0) String.format(Locale.FRANCE, " + frais %.2f €", fraisCentimes / 100.0) else "",
                        total / 100.0,
                    ),
                    style = MaterialTheme.typography.titleMedium,
                )
                if (parametres.puissanceFiscale <= 0) {
                    Text(
                        "Renseigne la puissance fiscale dans le paramétrage pour un calcul exact.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valide,
                onClick = { onValider(Dates.frVersIso(date) ?: date.trim(), distanceKm, trajet.trim(), fraisCentimes, libelle.trim()) },
            ) { Text("Enregistrer") }
        },
        dismissButton = { TextButton(onClick = onAnnuler) { Text("Annuler") } },
    )

    if (choixDate) {
        val etat = rememberDatePickerState(
            initialSelectedDateMillis = runCatching {
                LocalDate.parse(Dates.frVersIso(date).orEmpty()).atStartOfDay(ZoneOffset.UTC).toInstant().toEpochMilli()
            }.getOrNull(),
        )
        DatePickerDialog(
            onDismissRequest = { choixDate = false },
            confirmButton = {
                TextButton(onClick = {
                    etat.selectedDateMillis?.let { millis ->
                        date = Dates.isoVersFr(java.time.Instant.ofEpochMilli(millis).atZone(ZoneOffset.UTC).toLocalDate().toString())
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
