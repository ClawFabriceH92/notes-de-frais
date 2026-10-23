package com.trucdecomptable.notesdefrais

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.trucdecomptable.notesdefrais.ui.NotesTheme
import com.trucdecomptable.notesdefrais.ui.Racine

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            NotesTheme {
                Racine()
            }
        }
    }
}
