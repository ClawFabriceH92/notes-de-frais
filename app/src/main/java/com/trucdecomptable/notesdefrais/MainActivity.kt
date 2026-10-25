package com.trucdecomptable.notesdefrais

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.core.content.IntentCompat
import com.trucdecomptable.notesdefrais.ui.NotesTheme
import com.trucdecomptable.notesdefrais.ui.Racine
import kotlinx.coroutines.flow.MutableStateFlow

class MainActivity : ComponentActivity() {

    /**
     * Files another app handed over through "Partager". A StateFlow rather than
     * a plain extra so a share that arrives while the app is already running
     * reaches the same ViewModel, current project included (the activity is
     * singleTop, so onNewIntent fires instead of a fresh task).
     */
    private val partages = MutableStateFlow<List<Uri>>(emptyList())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        partages.value = urisPartagees(intent)
        setContent {
            NotesTheme {
                Racine(
                    partages = partages,
                    onPartagesLus = { partages.value = emptyList() },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val uris = urisPartagees(intent)
        if (uris.isNotEmpty()) partages.value = uris
    }

    /**
     * Reads the shared URIs, then strips them from the intent: a rotation or a
     * return to the app must not import the same receipt a second time.
     */
    private fun urisPartagees(intent: Intent?): List<Uri> {
        if (intent == null) return emptyList()
        val uris = when (intent.action) {
            Intent.ACTION_SEND ->
                listOfNotNull(IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java))

            Intent.ACTION_SEND_MULTIPLE ->
                IntentCompat.getParcelableArrayListExtra(intent, Intent.EXTRA_STREAM, Uri::class.java).orEmpty()

            else -> emptyList()
        }
        if (uris.isNotEmpty()) {
            intent.removeExtra(Intent.EXTRA_STREAM)
            intent.action = null
        }
        return uris
    }
}
