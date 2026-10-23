package com.trucdecomptable.notesdefrais.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Vert = Color(0xFF1B6B4A)
private val VertClair = Color(0xFF7FD3AA)
private val Sable = Color(0xFFF4EFE6)

private val clair = lightColorScheme(
    primary = Vert,
    onPrimary = Color.White,
    primaryContainer = VertClair,
    onPrimaryContainer = Color(0xFF00281A),
    secondary = Color(0xFF4F6354),
    background = Sable,
    surface = Color(0xFFFFFBF5),
)

private val sombre = darkColorScheme(
    primary = VertClair,
    onPrimary = Color(0xFF003824),
    primaryContainer = Color(0xFF005235),
    background = Color(0xFF11140F),
    surface = Color(0xFF191D16),
)

@Composable
fun NotesTheme(contenu: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) sombre else clair,
        content = contenu,
    )
}
