package com.trucdecomptable.notesdefrais.backup

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Handler
import android.os.Looper
import com.trucdecomptable.notesdefrais.util.Diag

/**
 * Redémarrage complet du processus après une restauration. Une simple
 * recréation d'activité ne suffit pas : la base Room est fermée et les DAO du
 * ViewModel, comme leurs flux, restent attachés à l'ancienne instance. Seule
 * la mort du processus remet tout à neuf, avec la base restaurée.
 */
object RedemarrageProcessus {

    /** Relance l'écran d'accueil dans une nouvelle tâche, puis termine le processus. */
    fun relancer(context: Context) {
        val application = context.applicationContext
        val relance = application.packageManager.getLaunchIntentForPackage(application.packageName)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        if (relance != null) {
            runCatching { context.startActivity(relance) }.onFailure { Diag.record("restauration/relance", it) }
        }
        trouverActivite(context)?.finishAffinity()
        // Laisser au système le temps de prendre en compte la demande de lancement avant de tuer le processus.
        Handler(Looper.getMainLooper()).postDelayed({ Runtime.getRuntime().exit(0) }, 400)
    }

    private fun trouverActivite(context: Context): Activity? {
        var courant: Context? = context
        while (courant is ContextWrapper) {
            if (courant is Activity) return courant
            courant = courant.baseContext
        }
        return null
    }
}
