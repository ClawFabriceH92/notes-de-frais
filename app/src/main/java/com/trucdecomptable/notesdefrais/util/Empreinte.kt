package com.trucdecomptable.notesdefrais.util

import java.security.MessageDigest

/** SHA-256 in hex, used to store the access code without keeping it. */
fun empreinte(texte: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(texte.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
