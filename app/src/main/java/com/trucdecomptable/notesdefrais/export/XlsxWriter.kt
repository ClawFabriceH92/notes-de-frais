package com.trucdecomptable.notesdefrais.export

import java.io.OutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * A small, dependency-free .xlsx writer.
 *
 * Apache POI costs tens of megabytes on Android and pulls in XML libraries the
 * platform already ships; a spreadsheet of expenses only needs a handful of
 * parts. Everything here is SpreadsheetML with inline strings, which Excel,
 * LibreOffice and Google Sheets all open.
 */
class XlsxWriter {

    /** A cell: text, number, or nothing. [style] indexes [STYLES]. */
    data class Cellule(
        val texte: String? = null,
        val nombre: Double? = null,
        val style: Int = NORMAL,
    ) {
        val vide: Boolean get() = texte == null && nombre == null
    }

    class Feuille(val nom: String) {
        val lignes = mutableListOf<List<Cellule>>()
        /** Column widths in Excel units, 0 = default. */
        val largeurs = mutableListOf<Int>()

        fun ligne(vararg cellules: Cellule) {
            lignes += cellules.toList()
        }

        fun ligne(cellules: List<Cellule>) {
            lignes += cellules
        }
    }

    companion object {
        const val NORMAL = 0
        const val GRAS = 1
        const val MONTANT = 2
        const val GRAS_MONTANT = 3
    }

    /**
     * Writes every sheet into [out]. The stream is closed, since a workbook
     * without its central directory is a corrupt file.
     */
    fun ecrire(feuilles: List<Feuille>, out: OutputStream) {
        require(feuilles.isNotEmpty()) { "au moins une feuille" }
        ZipOutputStream(out).use { zip ->
            entry(zip, "[Content_Types].xml", contentTypes(feuilles.size))
            entry(zip, "_rels/.rels", relsRoot())
            entry(zip, "xl/workbook.xml", workbook(feuilles))
            entry(zip, "xl/_rels/workbook.xml.rels", workbookRels(feuilles.size))
            entry(zip, "xl/styles.xml", STYLES_XML)
            feuilles.forEachIndexed { i, feuille ->
                entry(zip, "xl/worksheets/sheet${i + 1}.xml", sheet(feuille))
            }
        }
    }

    private fun entry(zip: ZipOutputStream, chemin: String, contenu: String) {
        zip.putNextEntry(ZipEntry(chemin))
        zip.write(contenu.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }

    private fun contentTypes(nbFeuilles: Int): String {
        val sheets = (1..nbFeuilles).joinToString("") {
            """<Override PartName="/xl/worksheets/sheet$it.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>"""
        }
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
<Default Extension="xml" ContentType="application/xml"/>
<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
$sheets
<Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
</Types>"""
    }

    private fun relsRoot(): String = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""

    private fun workbook(feuilles: List<Feuille>): String {
        val sheets = feuilles.mapIndexed { i, f ->
            """<sheet name="${echapper(f.nom)}" sheetId="${i + 1}" r:id="rId${i + 1}"/>"""
        }.joinToString("")
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
<sheets>$sheets</sheets>
</workbook>"""
    }

    private fun workbookRels(nbFeuilles: Int): String {
        val sheets = (1..nbFeuilles).joinToString("") {
            """<Relationship Id="rId$it" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet$it.xml"/>"""
        }
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
$sheets
<Relationship Id="rId${nbFeuilles + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""
    }

    private fun sheet(feuille: Feuille): String {
        val cols = if (feuille.largeurs.isEmpty()) "" else buildString {
            append("<cols>")
            feuille.largeurs.forEachIndexed { i, largeur ->
                if (largeur > 0) append("""<col min="${i + 1}" max="${i + 1}" width="$largeur" customWidth="1"/>""")
            }
            append("</cols>")
        }
        val rows = buildString {
            feuille.lignes.forEachIndexed { indexLigne, cellules ->
                append("""<row r="${indexLigne + 1}">""")
                cellules.forEachIndexed { indexColonne, cellule ->
                    if (cellule.vide) return@forEachIndexed
                    val ref = "${colonne(indexColonne)}${indexLigne + 1}"
                    when {
                        cellule.nombre != null ->
                            append("""<c r="$ref" s="${cellule.style}"><v>${nombre(cellule.nombre)}</v></c>""")

                        else ->
                            append(
                                """<c r="$ref" t="inlineStr" s="${cellule.style}"><is><t xml:space="preserve">""" +
                                    echapper(cellule.texte.orEmpty()) + "</t></is></c>",
                            )
                    }
                }
                append("</row>")
            }
        }
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">$cols<sheetData>$rows</sheetData></worksheet>"""
    }

    /** 0 -> A, 25 -> Z, 26 -> AA. */
    fun colonne(index: Int): String {
        var reste = index
        val sb = StringBuilder()
        while (true) {
            sb.insert(0, ('A' + reste % 26))
            reste = reste / 26 - 1
            if (reste < 0) break
        }
        return sb.toString()
    }

    private fun nombre(valeur: Double): String =
        if (valeur == valeur.toLong().toDouble()) valeur.toLong().toString()
        else String.format(java.util.Locale.US, "%.2f", valeur)

    private fun echapper(texte: String): String = texte
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .filter { it == '\t' || it == '\n' || it >= ' ' }

    private val STYLES_XML = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
<numFmts count="1"><numFmt numFmtId="164" formatCode="#,##0.00"/></numFmts>
<fonts count="2"><font><sz val="11"/><name val="Calibri"/></font><font><b/><sz val="11"/><name val="Calibri"/></font></fonts>
<fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>
<borders count="1"><border/></borders>
<cellStyleXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></cellStyleXfs>
<cellXfs count="4">
<xf numFmtId="0" fontId="0" fillId="0" borderId="0" xfId="0"/>
<xf numFmtId="0" fontId="1" fillId="0" borderId="0" xfId="0" applyFont="1"/>
<xf numFmtId="164" fontId="0" fillId="0" borderId="0" xfId="0" applyNumberFormat="1"/>
<xf numFmtId="164" fontId="1" fillId="0" borderId="0" xfId="0" applyNumberFormat="1" applyFont="1"/>
</cellXfs>
<cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyles>
</styleSheet>"""
}
