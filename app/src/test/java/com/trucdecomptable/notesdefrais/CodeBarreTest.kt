package com.trucdecomptable.notesdefrais

import com.trucdecomptable.notesdefrais.ocr.CodeBarre
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Choix du meilleur code-barres pour un portail enseigne : priorité aux codes
 * d'au moins 22 chiffres, rejet des codes courts ou non numériques.
 */
class CodeBarreTest {

    private val code13 = "3596710312345"             // EAN 13
    private val code22 = "1234567890123456789012"   // code 128 d'un ticket Auchan

    @Test
    fun `prefere un code de 22 chiffres a un code de 13`() {
        assertEquals(code22, CodeBarre.meilleur(listOf(code13, code22)))
        // L'ordre de la liste n'influe pas.
        assertEquals(code22, CodeBarre.meilleur(listOf(code22, code13)))
    }

    @Test
    fun `parmi les codes courts, prend le plus long`() {
        assertEquals(
            "1234567890",
            CodeBarre.meilleur(listOf("12345678", "1234567890")),
        )
    }

    @Test
    fun `ignore les codes de moins de 8 chiffres`() {
        assertNull(CodeBarre.meilleur(listOf("1234567")))
    }

    @Test
    fun `ignore les codes non numeriques`() {
        assertNull(CodeBarre.meilleur(listOf("ABC-12345678")))
        assertNull(CodeBarre.meilleur(listOf("12 345678")))
    }

    @Test
    fun `renvoie null si rien de valable`() {
        assertNull(CodeBarre.meilleur(emptyList()))
        assertNull(CodeBarre.meilleur(listOf("abc", "12", "1234567")))
    }
}
