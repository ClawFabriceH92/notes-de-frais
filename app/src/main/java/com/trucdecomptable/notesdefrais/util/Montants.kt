package com.trucdecomptable.notesdefrais.util

import java.math.BigDecimal
import java.math.RoundingMode

/** Amounts typed by hand: "24,50" and "24.50" both mean 2450 cents. */
fun centimesDe(texte: String): Long? {
    val propre = texte.replace(" ", "").replace("\u00a0", "").replace(",", ".").trim()
    if (propre.isEmpty()) return null
    val valeur = propre.toBigDecimalOrNull() ?: return null
    if (valeur < BigDecimal.ZERO) return null
    return valeur.movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong()
}

fun centimesVersTexte(centimes: Long): String =
    BigDecimal(centimes).movePointLeft(2).setScale(2, RoundingMode.HALF_UP).toPlainString().replace('.', ',')

/** Parity as displayed and edited: 1.1205 -> "1,1205". */
fun pariteTexte(valeur: Double): String =
    BigDecimal.valueOf(valeur)
        .setScale(6, RoundingMode.HALF_UP)
        .stripTrailingZeros()
        .toPlainString()
        .replace('.', ',')
