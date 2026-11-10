package com.trucdecomptable.notesdefrais.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * Question posée juste avant de photographier : ce justificatif tient-il sur une
 * seule page, ou faut-il enchaîner plusieurs vues (facture sur deux feuilles,
 * ticket plus facture, note d'hôtel et son annexe) ?
 *
 * La réponse change la suite : « une page » enregistre la dépense et referme la
 * prise de vue ; « plusieurs pages » laisse le justificatif ouvert pour que la
 * photo suivante devienne la page 2, puis 3…
 */
@Composable
fun DialogueChoixPages(
    onUnePage: () -> Unit,
    onPlusieursPages: () -> Unit,
    onAnnuler: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onAnnuler,
        title = { Text("Photographier un justificatif") },
        text = {
            Column {
                Text(
                    "Combien de pages pour ce justificatif ?",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                ChoixPages(
                    titre = "Une seule page",
                    detail = "Un ticket, une facture d'une feuille : la dépense est créée tout de suite.",
                    onClick = onUnePage,
                )
                ChoixPages(
                    titre = "Plusieurs pages",
                    detail = "Une facture sur deux feuilles, un ticket et sa facture : la photo suivante " +
                        "s'ajoute au même justificatif, jusqu'à « Terminer ».",
                    onClick = onPlusieursPages,
                )
            }
        },
        confirmButton = { TextButton(onClick = onAnnuler) { Text("Annuler") } },
    )
}

@Composable
private fun ChoixPages(titre: String, detail: String, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Text(titre, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
            detail,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.outline,
        )
    }
}
