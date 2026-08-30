package com.example.fileconverter

import android.content.Context
import android.net.Uri
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

object OdtParser {
    fun parse(ctx: Context, uri: Uri): WordDocument {
        val input = ctx.contentResolver.openInputStream(uri) ?: return WordDocument(emptyList())
        val bytes = input.use { it.readBytes() }
        val zipIn = ZipInputStream(ByteArrayInputStream(bytes))
        var contentXml = ""
        var entry = zipIn.nextEntry
        while (entry != null) {
            if (entry.name == "content.xml") { contentXml = zipIn.bufferedReader().readText(); break }
            entry = zipIn.nextEntry
        }
        zipIn.close()
        if (contentXml.isEmpty()) return WordDocument(listOf(WordBlock.Paragraph(listOf(WordRun("Empty document")))))
        val tagRegex = Regex("""<[^>]+>""")
        val paraRegex = Regex("""<text:p[^>]*>(.*?)</text:p>""", RegexOption.DOT_MATCHES_ALL)
        val paras = paraRegex.findAll(contentXml).map { tagRegex.replace(it.groupValues[1], "").trim() }
            .filter { it.isNotEmpty() }
            .map { WordBlock.Paragraph(listOf(WordRun(it))) }
            .toList()
        return WordDocument(paras)
    }
}
