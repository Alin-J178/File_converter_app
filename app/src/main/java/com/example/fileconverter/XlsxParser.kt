package com.example.fileconverter

import android.content.Context
import android.net.Uri
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/** Parses .xlsx files into [SpreadsheetDocument]. Legacy .xls goes through POI HSSF. */
object XlsxParser {
    fun parse(context: Context, uri: Uri): SpreadsheetDocument {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return SpreadsheetDocument(emptyList())
        return parseBytes(bytes)
    }
    fun parseBytes(bytes: ByteArray): SpreadsheetDocument {
        // Legacy binary .xls (OLE2 compound document) — parse with POI HSSF.
        if (bytes.size >= 8 && bytes[0] == 0xD0.toByte() && bytes[1] == 0xCF.toByte()) return parseLegacyXls(bytes)
        val zipIn = ZipInputStream(ByteArrayInputStream(bytes)); val zipEntries = mutableMapOf<String, String>()
        var entry = zipIn.nextEntry; while (entry != null) { if (entry.name == "xl/sharedStrings.xml" || entry.name.startsWith("xl/worksheets/sheet") || entry.name == "xl/workbook.xml") zipEntries[entry.name] = zipIn.bufferedReader().readText(); entry = zipIn.nextEntry }; zipIn.close()
        val sharedStrings = parseSharedStrings(zipEntries["xl/sharedStrings.xml"] ?: ""); val sheets = mutableListOf<SpreadsheetSheet>()
        for (sheetName in zipEntries.keys.filter { it.startsWith("xl/worksheets/sheet") }.sorted()) {
            val sheetXml = zipEntries[sheetName] ?: continue; val sheetNum = sheetName.replace("xl/worksheets/sheet", "").replace(".xml", "")
            val rows = mutableListOf<SpreadsheetRow>()
            for (rowMatch in Regex("""<row[^>]*>(.*?)</row>""", RegexOption.DOT_MATCHES_ALL).findAll(sheetXml)) {
                val cells = mutableListOf<SpreadsheetCell>()
                for (cellMatch in Regex("""<c[^>]*r="([^"]*)"[^>]*>(?:<v>([^<]*)</v>)?</c>""", RegexOption.DOT_MATCHES_ALL).findAll(rowMatch.groupValues[1])) {
                    val value = cellMatch.groupValues[2]; val isString = cellMatch.value.contains("""t="s"""")
                    val display = if (isString && value.isNotEmpty()) { val idx = value.toIntOrNull() ?: -1; if (idx in sharedStrings.indices) sharedStrings[idx] else value }
                    else if (value.isNotEmpty()) { value.toDoubleOrNull()?.let { d -> if (d == d.toLong().toDouble()) d.toLong().toString() else value } ?: value } else ""
                    val type = if (isString) CellType.STRING else if (display.toDoubleOrNull() != null) CellType.NUMBER else CellType.STRING
                    cells += SpreadsheetCell(value = display, type = type) }
                if (cells.any { it.value.isNotEmpty() }) rows += SpreadsheetRow(cells = cells) }
            sheets += SpreadsheetSheet(name = "Sheet $sheetNum", rows = rows) }
        return if (sheets.isEmpty()) SpreadsheetDocument(listOf(SpreadsheetSheet("Sheet 1", emptyList()))) else SpreadsheetDocument(sheets)
    }

    /** Legacy .xls via Apache POI HSSF (binary; same OLE2 family as legacy .doc). */
    private fun parseLegacyXls(bytes: ByteArray): SpreadsheetDocument {
        return try {
            val wb = org.apache.poi.hssf.usermodel.HSSFWorkbook(ByteArrayInputStream(bytes))
            try {
                val sheets = mutableListOf<SpreadsheetSheet>()
                for (i in 0 until wb.numberOfSheets) {
                    val sheet = wb.getSheetAt(i)
                    val rows = mutableListOf<SpreadsheetRow>()
                    val rowIter = sheet.rowIterator()
                    while (rowIter.hasNext()) {
                        val r = rowIter.next()
                        val cells = mutableListOf<SpreadsheetCell>()
                        val cellIter = r.cellIterator()
                        while (cellIter.hasNext()) {
                            val cell = cellIter.next()
                            val value = when (cell.cellType) {
                                org.apache.poi.ss.usermodel.CellType.STRING -> cell.stringCellValue
                                org.apache.poi.ss.usermodel.CellType.NUMERIC -> {
                                    val d = cell.numericCellValue
                                    if (org.apache.poi.ss.usermodel.DateUtil.isCellDateFormatted(cell)) cell.dateCellValue.toString()
                                    else if (d == d.toLong().toDouble()) d.toLong().toString() else d.toString()
                                }
                                org.apache.poi.ss.usermodel.CellType.BOOLEAN -> cell.booleanCellValue.toString()
                                org.apache.poi.ss.usermodel.CellType.FORMULA -> try { cell.stringCellValue } catch (_: Exception) { try { cell.numericCellValue.toString() } catch (_: Exception) { "" } }
                                else -> ""
                            }
                            cells += SpreadsheetCell(value = value, type = if (cell.cellType == org.apache.poi.ss.usermodel.CellType.STRING) CellType.STRING else CellType.NUMBER,
                                bold = cell.cellStyle.fontIndexAsInt == 0 && wb.getFontAt(0).bold)
                        }
                        if (cells.isNotEmpty()) rows += SpreadsheetRow(cells = cells)
                    }
                    sheets += SpreadsheetSheet(name = wb.getSheetName(i), rows = rows)
                }
                if (sheets.isEmpty()) SpreadsheetDocument(listOf(SpreadsheetSheet("Sheet 1", emptyList()))) else SpreadsheetDocument(sheets)
            } finally { wb.close() }
        } catch (e: Exception) {
            android.util.Log.w("XlsxParser", "HSSF parse failed", e)
            SpreadsheetDocument(listOf(SpreadsheetSheet("Sheet 1", listOf(SpreadsheetRow(cells = listOf(SpreadsheetCell(value = "[Could not read this XLS file]")))))))
        }
    }

    private fun parseSharedStrings(xml: String): List<String> { if (xml.isEmpty()) return emptyList(); val result = mutableListOf<String>(); val tagRegex = Regex("""<[^>]+>""")
        for (match in Regex("""<si>(.*?)</si>""", RegexOption.DOT_MATCHES_ALL).findAll(xml)) result.add(tagRegex.replace(match.groupValues[1], "").trim()); return result }
}
