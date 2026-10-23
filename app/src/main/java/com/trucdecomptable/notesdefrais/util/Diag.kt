package com.trucdecomptable.notesdefrais.util

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * One line per event, kept in memory and mirrored to logcat. The in-app
 * updater and the OCR pipeline both fail for reasons that only make sense
 * afterwards, so the app has to be able to say what it tried.
 */
object Diag {

    private const val TAG = "NotesDeFrais"
    private const val MAX = 400

    private val lignes = ArrayDeque<String>()

    @Synchronized
    fun record(categorie: String, message: String) {
        val heure = SimpleDateFormat("HH:mm:ss", Locale.FRANCE).format(Date())
        val ligne = "$heure [$categorie] $message"
        lignes.addLast(ligne)
        while (lignes.size > MAX) lignes.removeFirst()
        Log.d(TAG, ligne)
    }

    fun record(categorie: String, e: Throwable) =
        record(categorie, "${e.javaClass.simpleName}: ${e.message.orEmpty()}")

    @Synchronized
    fun dump(): String = lignes.joinToString("\n")

    @Synchronized
    fun clear() = lignes.clear()
}
