package com.example.fileconverter

import android.content.Context
import android.net.Uri
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream

/** Parses .pptx files into [PresentationDocument]. */
object PptxParser {
    fun parse(context: Context, uri: Uri): PresentationDocument {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return PresentationDocument(emptyList())
        return parseBytes(bytes)
    }
    fun parseBytes(bytes: ByteArray): PresentationDocument {
        val zipIn = ZipInputStream(ByteArrayInputStream(bytes)); val slideXmls = mutableListOf<String>()
        var entry = zipIn.nextEntry; while (entry != null) { if (entry.name.startsWith("ppt/slides/slide") && entry.name.endsWith(".xml")) slideXmls.add(zipIn.bufferedReader().readText()); entry = zipIn.nextEntry }; zipIn.close()
        if (slideXmls.isEmpty()) return PresentationDocument(emptyList())
        val slides = slideXmls.mapIndexed { idx, slideXml ->
            val elements = mutableListOf<SlideElement>(); val spRegex = Regex("""<p:sp[^>]*>(.*?)</p:sp>""", RegexOption.DOT_MATCHES_ALL); val textRegex = Regex("""<a:t>([^<]+)</a:t>""")
            val shapes = spRegex.findAll(slideXml).toList()
            if (shapes.isNotEmpty()) { var isTitle = true; for (sp in shapes) {
                val texts = textRegex.findAll(sp.value).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()
                if (texts.isNotEmpty()) { val joined = texts.joinToString(" ")
                    val extent = Regex("""<a:ext\s+cx="(\d+)"\s+cy="(\d+)"""").find(sp.value)
                    val x = Regex("""<a:off\s+x="(\d+)"""").find(sp.value)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
                    val y = Regex("""<a:off\s+y="(\d+)"""").find(sp.value)?.groupValues?.get(1)?.toFloatOrNull() ?: 0f
                    val w = extent?.groupValues?.get(1)?.toFloatOrNull() ?: 0f; val h = extent?.groupValues?.get(2)?.toFloatOrNull() ?: 0f
                    val fSize = if (isTitle && joined.length < 80) 32f else 18f
                    elements += SlideElement.TextBox(runs = listOf(WordRun(joined, bold = isTitle && joined.length < 80)), fontSize = fSize, x = x, y = y, width = w, height = h); isTitle = false } }
            } else { val allText = textRegex.findAll(slideXml).map { it.groupValues[1].trim() }.filter { it.isNotEmpty() }.toList()
                if (allText.isNotEmpty()) for (t in allText) elements += SlideElement.TextBox(runs = listOf(WordRun(t)), fontSize = 18f)
                else elements += SlideElement.TextBox(runs = listOf(WordRun("[No text content]")), fontSize = 14f) }
            PresentationSlide(index = idx, elements = elements) }
        return PresentationDocument(slides = slides)
    }
}
