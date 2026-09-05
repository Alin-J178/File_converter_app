package com.example.fileconverter

/**
 * Multi-format document models.
 *
 * Architecture:
 *   Format Parser → Format-Appropriate Model → Viewer / Converter
 *
 * Different formats use different models because they have fundamentally
 * different structures. Word processing uses paragraphs and tables.
 * Spreadsheets use sheets and cells. Presentations use slides.
 */

// ═══════════════════════════════════════════════════════════════
//  Union type for the viewer — dispatches to format-specific renderer
// ═══════════════════════════════════════════════════════════════

sealed class ParsedDocument {
    data class Word(val doc: WordDocument) : ParsedDocument()
    data class Spreadsheet(val doc: SpreadsheetDocument) : ParsedDocument()
    data class Presentation(val doc: PresentationDocument) : ParsedDocument()
    data class Text(val doc: TextDocument) : ParsedDocument()
}

// ═══════════════════════════════════════════════════════════════
//  WORD PROCESSING MODEL (DOC, DOCX, ODT, RTF, HTML)
// ═══════════════════════════════════════════════════════════════

data class WordDocument(val blocks: List<WordBlock>)

sealed class WordBlock {
    data class Paragraph(
        val runs: List<WordRun>,
        val alignment: DocAlignment = DocAlignment.LEFT,
        val style: String = "",
        val spacing: DocSpacing = DocSpacing(),
        val indent: DocIndent = DocIndent(),
        val bulleted: Boolean = false,
        val numbered: Boolean = false,
        val pageBreakBefore: Boolean = false,
    )  : WordBlock()

    data class Heading(
        val runs: List<WordRun>,
        val level: Int = 1,
        val alignment: DocAlignment = DocAlignment.LEFT,
    )  : WordBlock()

    data class Table(
        val rows: List<WordTableRow>,
        val columnWidths: List<Float> = emptyList(),
    )  : WordBlock()

    data class EmbeddedImage(
        val data: ByteArray,
        val width: Int,
        val height: Int,
        val alt: String = "",
        val contentType: String = "image/png",
    ) : WordBlock() {
        override fun equals(other: Any?): Boolean = this === other || (other is EmbeddedImage && data.contentEquals(other.data) && width == other.width)
        override fun hashCode(): Int = data.contentHashCode() * 31 + width
    }

    data class ChartBlock(
        val type: String, val title: String, val categories: List<String>,
        val series: List<DocChartSeries>, val heightPt: Float = 200f,
    )  : WordBlock()

    data class ListItem(
        val runs: List<WordRun>, val level: Int = 0, val bulleted: Boolean = true,
        /** Word numbering format resolved from numbering.xml: "decimal", "lowerLetter",
         *  "upperLetter", "lowerRoman", "upperRoman", or "" (bullet / not a numbered list). */
        val numFmt: String = "",
        /** Word numbering instance (numId) so renderers can reset counters between lists. */
        val numId: Int = -1,
        /** Restart value for this level from numbering.xml (<w:start/>). */
        val start: Int = 1,
    )  : WordBlock()

    data class Unsupported(val description: String)  : WordBlock()
    data object PageBreak  : WordBlock()
}

data class WordRun(
    val text: String, val bold: Boolean = false, val italic: Boolean = false,
    val underline: Boolean = false, val strikethrough: Boolean = false,
    val fontSize: Float = 12f, val fontFamily: String = "", val color: Int = 0,
    val superscript: Boolean = false, val subscript: Boolean = false,
)

data class WordTableRow(val cells: List<WordTableCell>, val isHeader: Boolean = false)
data class WordTableCell(
    val blocks: List<WordBlock>, val gridSpan: Int = 1,
    val background: Int = 0, val width: Float = 0f,
    /** True when this cell is the "continue" half of a <w:vMerge/> (vertical merge).
     *  Content lives in the first (restart) cell; renderers should not redraw it. */
    val vMergeCont: Boolean = false,
)

// ═══════════════════════════════════════════════════════════════
//  SPREADSHEET MODEL (XLSX, CSV, ODS)
// ═══════════════════════════════════════════════════════════════

data class SpreadsheetDocument(val sheets: List<SpreadsheetSheet>)
data class SpreadsheetSheet(
    val name: String,
    val rows: List<SpreadsheetRow>,
)
data class SpreadsheetRow(val cells: List<SpreadsheetCell>)
data class SpreadsheetCell(
    val value: String,
    val formula: String = "",
    val type: CellType = CellType.STRING,
    val bold: Boolean = false,
    val background: Int = 0,
    val columnSpan: Int = 1,
)
enum class CellType { STRING, NUMBER, BOOLEAN, DATE, FORMULA }

// ═══════════════════════════════════════════════════════════════
//  PRESENTATION MODEL (PPTX, PPT)
// ═══════════════════════════════════════════════════════════════

data class PresentationDocument(
    val slides: List<PresentationSlide>,
    val slideWidth: Float = 9144000f,  // EMUs
    val slideHeight: Float = 6858000f,
)
data class PresentationSlide(
    val index: Int,
    val elements: List<SlideElement>,
    val background: Int = 0,
)
sealed class SlideElement {
    data class TextBox(
        val runs: List<WordRun>,
        val x: Float = 0f, val y: Float = 0f,
        val width: Float = 0f, val height: Float = 0f,
        val alignment: DocAlignment = DocAlignment.LEFT,
        val fontSize: Float = 18f,
        val bold: Boolean = false,
    ) : SlideElement()

    data class ImageElement(
        val data: ByteArray,
        val x: Float = 0f, val y: Float = 0f,
        val width: Float = 0f, val height: Float = 0f,
    ) : SlideElement() {
        override fun equals(other: Any?): Boolean = this === other || (other is ImageElement && data.contentEquals(other.data))
        override fun hashCode(): Int = data.contentHashCode()
    }

    data class Shape(val description: String) : SlideElement()
}

// ═══════════════════════════════════════════════════════════════
//  TEXT MODEL (TXT, MD, RTF)
// ═══════════════════════════════════════════════════════════════

data class TextDocument(val blocks: List<TextBlock>)
data class TextBlock(
    val text: String,
    val isHeading: Boolean = false,
    val headingLevel: Int = 0,
    val isBold: Boolean = false,
    val isCode: Boolean = false,
)

// ═══════════════════════════════════════════════════════════════
//  SHARED FORMATTING TYPES
// ═══════════════════════════════════════════════════════════════

enum class DocAlignment { LEFT, CENTER, RIGHT, JUSTIFY }

data class DocSpacing(
    val before: Float = 0f, val after: Float = 0f,
    val line: Float = 0f, val lineRule: String = "",
)

data class DocIndent(
    val left: Float = 0f, val right: Float = 0f,
    val firstLine: Float = 0f, val hanging: Float = 0f,
)

data class DocChartSeries(val name: String, val color: Int, val values: List<Double>)

// ═══════════════════════════════════════════════════════════════
//  HELPERS
// ═══════════════════════════════════════════════════════════════

fun parseDocColor(hex: String?): Int {
    if (hex == null || hex.isEmpty()) return 0
    return try {
        val c = hex.trimStart('#')
        when (c.length) { 6 -> (0xFF shl 24) or c.toLong(16).toInt(); 8 -> c.toLong(16).toInt(); else -> 0 }
    } catch (_: Exception) { 0 }
}

fun headingFontSize(level: Int): Float = when (level) { 1 -> 28f; 2 -> 24f; 3 -> 20f; 4 -> 18f; 5 -> 16f; else -> 14f }

fun docFormatColor(ext: String): Int = when (ext.uppercase()) {
    "DOCX", "DOC" -> 0xFF2B579A.toInt()
    "XLSX", "XLS", "CSV" -> 0xFF217346.toInt()
    "PPTX", "PPT" -> 0xFFD04423.toInt()
    "ODT" -> 0xFF0066CC.toInt()
    "RTF" -> 0xFF8B4513.toInt()
    "TXT" -> 0xFF616161.toInt()
    "MD" -> 0xFF455A64.toInt()
    "HTML", "HTM" -> 0xFFE65100.toInt()
    "PDF" -> 0xFFE91E63.toInt()
    else -> 0xFF757575.toInt()
}
