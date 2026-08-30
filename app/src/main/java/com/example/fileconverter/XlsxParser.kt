package com.example.fileconverter

import android.content.Context
import android.net.Uri
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/** Parses .xlsx files into [SpreadsheetDocument]. */
object XlsxParser {
    fun parse(context: Context, uri: Uri): SpreadsheetDocument {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return SpreadsheetDocument(emptyList())
        return parseBytes(bytes)
    }
    fun parseBytes(bytes: ByteArray): SpreadsheetDocument {
        val zipIn = ZipInputStream(ByteArrayInputStream(bytes)); val zipEntries = mutableMapOf<String, String>()
        var entry = zipIn.nextEntry; while (entry != null) { if (entry.name == "xl/sharedStrings.xml" || entry.name.startsWith("xl/worksheets/sheet") || entry.name == "xl/workbook.xml") zipEntries[entry.name] = zipIn.bufferedReader().readText(); entry = zipIn.nextEntry }; zipIn.close()
        val sharedStrings = parseSharedStrings(zipEntries["xl/sharedStrings.xml"] ?: ""); val sheets = mutableListOf<SpreadsheetSheet>()
        for (sheetName in zipEntries.keys.filter { it.startsWith("xl/worksheets/sheet") }.sorted()) {
            val sheetXml = zipEntries[sheetName] ?: continue; val sheetNum = sheetName.replace("xl/worksheets/sheet", "").replace(".xml", "")
            val rows = mutableListOf<SpreadsheetRow>()
            for (rowMatch in Regex("""<row[^>]*>(.*?)</row>""", RegexOption.DOT_MATCHES_ALL).findAll(sheetXml)) {
                val cells = mutableListOf<SpreadsheetCell>()
                for (cellMatch in Regex("""<c[^>]*r="([^"]*)"[^>]*>(?:<v>([^<]*)</v>)?</c>""", RegexOption.DOT_MATCHES_ALL).findAll(rowMatch.groupValues[1])) {
                    val value = cellMatch.groupValues[2]; val isString = cellMatch.value.contains("""t="s""")
                    val display = if (isString && value.isNotEmpty()) { val idx = value.toIntOrNull() ?: -1; if (idx in sharedStrings.indices) sharedStrings[idx] else value }
                    else if (value.isNotEmpty()) { value.toDoubleOrNull()?.let { d -> if (d == d.toLong().toDouble()) d.toLong().toString() else value } ?: value } else ""
                    val type = if (isString) CellType.STRING else if (display.toDoubleOrNull() != null) CellType.NUMBER else CellType.STRING
                    cells += SpreadsheetCell(value = display, type = type) }
                if (cells.any { it.value.isNotEmpty() }) rows += SpreadsheetRow(cells = cells) }
            sheets += SpreadsheetSheet(name = "Sheet $sheetNum", rows = rows) }
        return if (sheets.isEmpty()) SpreadsheetDocument(listOf(SpreadsheetSheet("Sheet 1", emptyList()))) else SpreadsheetDocument(sheets)
    }
    private fun parseSharedStrings(xml: String): List<String> { if (xml.isEmpty()) return emptyList(); val result = mutableListOf<String>(); val tagRegex = Regex("""<[^>]+>""")
        for (match in Regex("""<si>(.*?)</si>""", RegexOption.DOT_MATCHES_ALL).findAll(xml)) result.add(tagRegex.replace(match.groupValues[1], "").trim()); return result }
}
