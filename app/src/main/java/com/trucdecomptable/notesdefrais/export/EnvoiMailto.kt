package com.trucdecomptable.notesdefrais.export

import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Petites règles de l'envoi rapide par e-mail (icône de l'accueil).
 *
 * Le principe : les justificatifs du profil courant partent en PDF à l'adresse
 * « mailto » de ce profil, sans qu'aucune note de frais soit à remplir ni à
 * clôturer. On ne réutilise pas l'adresse d'export complet, qui reste dédiée
 * aux envois de fin de mission.
 *
 * Fonctions pures : testables sans Android.
 */
object EnvoiMailto {

    private val FORMAT_JOUR = DateTimeFormatter.ofPattern("dd/MM/yyyy")

    /** Adresse utilisable ? Un minimum de contrôle évite les envois perdus. */
    fun adresseValide(adresse: String?): Boolean {
        val propre = adresse?.trim().orEmpty()
        if (propre.isEmpty()) return false
        val arobase = propre.indexOf('@')
        if (arobase <= 0 || arobase != propre.lastIndexOf('@')) return false
        val domaine = propre.substring(arobase + 1)
        return domaine.contains('.') && !domaine.startsWith('.') && !domaine.endsWith('.') &&
            !propre.contains(' ')
    }

    /** Objet du message, lisible dans une boîte mail partagée. */
    fun sujet(nomProfil: String, jour: LocalDate = LocalDate.now()): String {
        val societe = nomProfil.trim().ifBlank { "sans société" }
        return "Notes de frais – $societe – ${jour.format(FORMAT_JOUR)}"
    }

    /** Corps du message : dit ce qui est joint, et par où cela a été envoyé. */
    fun corps(nomProfil: String, nombreJustificatifs: Int): String {
        val societe = nomProfil.trim().ifBlank { "sans société" }
        val pluriel = if (nombreJustificatifs > 1) "s" else ""
        return "Bonjour,\n\n" +
            "Ci-joint les justificatifs de $societe, au format PDF " +
            "(${nombreJustificatifTexte(nombreJustificatifs, pluriel)}).\n\n" +
            "Envoyé depuis l'application Notes de frais, en mode envoi rapide " +
            "(aucune note de frais n'a été remplie).\n"
    }

    private fun nombreJustificatifTexte(nombre: Int, pluriel: String): String =
        if (nombre <= 0) "aucun justificatif" else "$nombre justificatif$pluriel"
}
