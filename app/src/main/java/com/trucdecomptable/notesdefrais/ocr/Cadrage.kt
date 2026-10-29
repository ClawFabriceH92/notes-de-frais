package com.trucdecomptable.notesdefrais.ocr

/** Rectangle en pixels de l'image (origine en haut à gauche). */
data class Zone(val left: Int, val top: Int, val right: Int, val bottom: Int) {

    val largeur: Int get() = (right - left).coerceAtLeast(0)
    val hauteur: Int get() = (bottom - top).coerceAtLeast(0)
    val aire: Long get() = largeur.toLong() * hauteur
    val vide: Boolean get() = largeur <= 0 || hauteur <= 0

    fun etendre(marge: Int, largeurImage: Int, hauteurImage: Int): Zone = Zone(
        left = (left - marge).coerceIn(0, (largeurImage - 1).coerceAtLeast(0)),
        top = (top - marge).coerceIn(0, (hauteurImage - 1).coerceAtLeast(0)),
        right = (right + marge).coerceIn(1, largeurImage),
        bottom = (bottom + marge).coerceIn(1, hauteurImage),
    )
}

/**
 * Cadrage automatique d'un ticket sur une photo.
 *
 * Aucune détection de contour : les blocs de texte trouvés par l'OCR donnent
 * déjà l'étendue du ticket. On prend leur enveloppe, on ajoute une marge, et on
 * refuse le recadrage quand le résultat serait douteux (texte trop petit dans
 * l'image, ou ticket qui remplit déjà la photo).
 */
object Cadrage {

    /** Marge autour du texte, en fraction du plus grand côté du ticket. */
    const val MARGE = 0.05f

    /** En dessous, la détection est trop incertaine pour couper. */
    const val AIRE_MIN = 0.06f

    /** Au-dessus, il n'y a rien à retirer : on laisse la photo telle quelle. */
    const val AIRE_MAX = 0.97f

    /** Enveloppe des zones non vides. */
    fun union(zones: List<Zone>): Zone? {
        val utiles = zones.filter { !it.vide }
        if (utiles.isEmpty()) return null
        return Zone(
            left = utiles.minOf { it.left },
            top = utiles.minOf { it.top },
            right = utiles.maxOf { it.right },
            bottom = utiles.maxOf { it.bottom },
        )
    }

    /**
     * Zone à conserver, ou null quand il n'y a pas lieu de recadrer.
     *
     * @param zones blocs de texte en pixels de [largeur] × [hauteur]
     */
    fun zoneDuTicket(
        zones: List<Zone>,
        largeur: Int,
        hauteur: Int,
        marge: Float = MARGE,
    ): Zone? {
        if (largeur <= 8 || hauteur <= 8) return null
        // Un seul bloc de texte : rien ne garantit qu'on tient tout le ticket.
        if (zones.count { !it.vide } < 2) return null

        val brut = union(zones) ?: return null
        val margePx = (maxOf(brut.largeur, brut.hauteur) * marge).toInt().coerceAtLeast(8)
        val zone = brut.etendre(margePx, largeur, hauteur)
        if (zone.vide) return null

        val part = zone.aire.toDouble() / (largeur.toDouble() * hauteur.toDouble())
        if (part < AIRE_MIN || part > AIRE_MAX) return null
        // Une bande de texte isolée (une seule ligne lue) ne fait pas un ticket.
        if (zone.hauteur < hauteur / 8 && zone.largeur < largeur / 8) return null
        return zone
    }
}
