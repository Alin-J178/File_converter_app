package com.example.fileconverter

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Converts a CSV file to XLSX (Office Open XML) format.
 * Produces a valid .xlsx file by writing the raw XML components inside a ZIP archive.
 */
object CsvToXlsx {

    fun convert(context: Context, uri: Uri, displayName: String): Uri {
        val csvText = context.contentResolver.openInputStream(uri)?.use { it.bufferedReader().readText() }
            ?: error("Could not read the CSV file")

        val rows = parseCsv(csvText)
        val xlsxBytes = buildXlsx(rows)
        return saveToDownloads(context, xlsxBytes, displayName, "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet")
    }

    private fun parseCsv(text: String): List<List<String>> {
        val result = mutableListOf<MutableList<String>>()
        var current = mutableListOf<String>()
        var field = StringBuilder()
        var inQuotes = false
        var i = 0

        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes -> {
                    if (c == '"' && i + 1 < text.length && text[i + 1] == '"') {
                        field.append('"')
                        i += 2
                    } else if (c == '"') {
                        inQuotes = false
                        i++
                    } else {
                        field.append(c)
                        i++
                    }
                }
                c == '"' -> { inQuotes = true; i++ }
                c == ',' -> { current.add(field.toString()); field = StringBuilder(); i++ }
                c == '\n' || c == '\r' -> {
                    current.add(field.toString())
                    if (current.any { it.isNotEmpty() }) result.add(current)
                    current = mutableListOf()
                    field = StringBuilder()
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    i++
                }
                else -> { field.append(c); i++ }
            }
        }
        // Last field/row
        current.add(field.toString())
        if (current.any { it.isNotEmpty() }) result.add(current)
        return result
    }

    private fun buildXlsx(rows: List<List<String>>): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            // [Content_Types].xml
            zip.putNextEntry(ZipEntry("[Content_Types].xml"))
            zip.write(CONTENT_TYPES.toByteArray())
            zip.closeEntry()

            // _rels/.rels
            zip.putNextEntry(ZipEntry("_rels/.rels"))
            zip.write(RELS.toByteArray())
            zip.closeEntry()

            // xl/workbook.xml
            zip.putNextEntry(ZipEntry("xl/workbook.xml"))
            zip.write(WORKBOOK.toByteArray())
            zip.closeEntry()

            // xl/_rels/workbook.xml.rels
            zip.putNextEntry(ZipEntry("xl/_rels/workbook.xml.rels"))
            zip.write(WORKBOOK_RELS.toByteArray())
            zip.closeEntry()

            // xl/styles.xml
            zip.putNextEntry(ZipEntry("xl/styles.xml"))
            zip.write(STYLES.toByteArray())
            zip.closeEntry()

            // xl/worksheets/sheet1.xml
            zip.putNextEntry(ZipEntry("xl/worksheets/sheet1.xml"))
            zip.write(buildSheetXml(rows).toByteArray())
            zip.closeEntry()
        }
        return baos.toByteArray()
    }

    private fun buildSheetXml(rows: List<List<String>>): String {
        val sb = StringBuilder()
        sb.appendLine("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>")
        sb.appendLine("<worksheet xmlns=\"http://schemas.openxmlformats.org/spreadsheetml/2006/main\" xmlns:r=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships\">")

        // Column widths
        val maxCols = rows.maxOfOrNull { it.size } ?: 1
        sb.appendLine("<cols>")
        for (c in 1..maxCols) {
            sb.appendLine("<col min=\"$c\" max=\"$c\" width=\"15\" customWidth=\"1\"/>")
        }
        sb.appendLine("</cols>")

        sb.appendLine("<sheetData>")
        for ((rowIdx, row) in rows.withIndex()) {
            val rowNum = rowIdx + 1
            sb.appendLine("<row r=\"$rowNum\">")
            for ((colIdx, cell) in row.withIndex()) {
                val colRef = colIndexToRef(colIdx)
                val cellRef = "$colRef$rowNum"
                val numeric = cell.toDoubleOrNull()
                if (numeric != null) {
                    sb.appendLine("<c r=\"$cellRef\" t=\"n\" s=\"0\"><v>$cell</v></c>")
                } else {
                    val escaped = xmlEscape(cell)
                    sb.appendLine("<c r=\"$cellRef\" t=\"str\" s=\"0\"><v>$escaped</v></c>")
                }
            }
            sb.appendLine("</row>")
        }
        sb.appendLine("</sheetData>")
        sb.appendLine("</worksheet>")
        return sb.toString()
    }

    private fun colIndexToRef(idx: Int): String {
        val sb = StringBuilder()
        var i = idx
        do {
            sb.insert(0, ('A' + i % 26))
            i = i / 26 - 1
        } while (i >= 0)
        return sb.toString()
    }

    private fun xmlEscape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")

    private fun saveToDownloads(context: Context, bytes: ByteArray, displayName: String, mime: String): Uri {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, displayName)
                put(MediaStore.Downloads.MIME_TYPE, mime)
                put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/FileConverter")
                put(MediaStore.Downloads.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL), values)
                ?: error("Failed to create MediaStore entry")
            resolver.openOutputStream(uri)?.use { it.write(bytes) }
                ?: error("Failed to open output stream")
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            uri
        } else {
            val dir = File(context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS), "FileConverter")
            if (!dir.exists() && !dir.mkdirs()) error("Failed to create output directory")
            val file = File(dir, displayName)
            FileOutputStream(file).use { it.write(bytes) }
            Uri.fromFile(file)
        }
    }

    // ── Static OOXML skeleton files ──────────────────────────────

    private const val CONTENT_TYPES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>
  <Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>
  <Override PartName="/xl/styles.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.styles+xml"/>
</Types>"""

    private const val RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>
</Relationships>"""

    private const val WORKBOOK = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <sheets>
    <sheet name="Sheet1" sheetId="1" r:id="rId1"/>
  </sheets>
</workbook>"""

    private const val WORKBOOK_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/styles" Target="styles.xml"/>
</Relationships>"""

    private const val STYLES = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<styleSheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main">
  <fonts count="1"><font><sz val="11"/><name val="Calibri"/></font></fonts>
  <fills count="2"><fill><patternFill patternType="none"/></fill><fill><patternFill patternType="gray125"/></fill></fills>
  <borders count="1"><border><left/><right/><top/><bottom/><diagonal/></border></borders>
  <cellStyleXfs count="1"><xf/></cellStyleXfs>
  <cellXfs count="1"><xf numFmtId="0" fontId="0" fillId="0" borderId="0"/></xf>
  <cellStyles count="1"><cellStyle name="Normal" xfId="0" builtinId="0"/></cellStyle>
</styleSheet>"""
}
