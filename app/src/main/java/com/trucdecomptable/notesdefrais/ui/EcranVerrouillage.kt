package com.trucdecomptable.notesdefrais.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.trucdecomptable.notesdefrais.util.empreinte

/**
 * The app holds expense receipts, which are personal data: an access code keeps
 * a borrowed phone from becoming a leak. Only the SHA-256 of the code is stored.
 */
@Composable
fun EcranVerrouillage(hash: String, onDeverrouille: () -> Unit) {
    var saisie by remember { mutableStateOf("") }
    var erreurs by remember { mutableStateOf(0) }

    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(
            Icons.Default.Lock,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
        )
        Text(
            "Notes de frais est verrouillée",
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.padding(top = 12.dp, bottom = 16.dp),
        )
        OutlinedTextField(
            value = saisie,
            onValueChange = { code ->
                saisie = code.filter { it.isDigit() }.take(8)
                if (erreurs > 0) erreurs = 0
            },
            label = { Text("Code d'accès") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth(),
        )
        if (erreurs > 0) {
            Text(
                "Code incorrect ($erreurs)",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 8.dp),
            )
        }
        Button(
            onClick = {
                if (empreinte(saisie) == hash) {
                    onDeverrouille()
                } else {
                    erreurs += 1
                    saisie = ""
                }
            },
            enabled = saisie.isNotBlank(),
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        ) {
            Text("Déverrouiller")
        }
    }
}
