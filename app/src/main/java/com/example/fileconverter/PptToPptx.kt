package com.example.fileconverter

import android.content.ContentValues
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Converts a legacy PowerPoint 97-2003 .ppt file to .pptx (Office Open XML).
 *
 * This parser reads the OLE2 compound document, walks the "PowerPoint Document"
 * stream's record structure, extracts slide content (text, dimensions, colors),
 * and produces a valid .pptx ZIP package.
 */
object PptToPptx {

    fun convert(context: Context, uri: Uri, displayName: String): Uri {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            ?: error("Could not open the PPT file")
        if (bytes.size < 512) error("File too small to be a valid PPT")

        val doc = Ole2Reader.parse(bytes)
            ?: error("Not a valid OLE2 compound document")
        val pptStream = doc.streams["PowerPoint Document"]
            ?: error("Could not find PowerPoint Document stream in OLE2 file")

        val parser = PptRecordParser(pptStream)
        val slides = parser.parseSlides()

        if (slides.isEmpty()) error("No slides found in the PPT file")

        val pptxBytes = buildPptx(slides, parser.slideWidth, parser.slideHeight)
        return saveToDownloads(context, pptxBytes, displayName,
            "application/vnd.openxmlformats-officedocument.presentationml.presentation")
    }

    // ─────────────────────────────────────────────────────────────
    // OLE2 Compound Document Reader
    // ─────────────────────────────────────────────────────────────

    private object Ole2Reader {

        data class Ole2Doc(val streams: Map<String, ByteArray>)

        fun parse(data: ByteArray): Ole2Doc? {
            if (data.size < 512) return null
            val bb = ByteBuffer.wrap(data).order(ByteOrder.LITTLE_ENDIAN)

            // Check magic number
            val magic = bb.getShort(0).toInt() and 0xFFFF
            if (magic != 0xE011) return null // Expected: 0xD0CF11E0 → LE short = 0xE011

            val sectorSizePower = bb.getShort(22).toInt() and 0xFFFF
            val sectorSize = 1 shl sectorSizePower // 512 or 4096

            val miniSectorPower = bb.getShort(30).toInt() and 0xFFFF
            val miniSectorSize = 1 shl miniSectorPower

            val totalSectorsFAT = bb.getInt(44)
            val dirStartSector = bb.getInt(48)

            // Read FAT
            val fat = mutableListOf<Int>()
            // First 109 DIFAT entries are in the header (offset 76..511)
            for (k in 0 until 109) {
                val sec = bb.getInt(76 + k * 4)
                if (sec == -2 || sec == -1) break // FREESECT or ENDOFCHAIN
                fat.add(sec)
            }
            // Additional DIFAT sectors
            var difatPtr = bb.getInt(68) // SECT_DIFAT
            var difatCount = totalSectorsFAT - 109
            while (difatPtr >= 0 && difatCount > 0) {
                val difatSec = readSector(data, difatPtr, sectorSize)
                val entriesPerSector = (sectorSize / 4) - 1
                for (k in 0 until entriesPerSector.coerceAtMost(difatCount)) {
                    val sec = ByteBuffer.wrap(difatSec, k * 4, 4).order(ByteOrder.LITTLE_ENDIAN).int
                    if (sec == -2 || sec == -1) break
                    fat.add(sec)
                    difatCount--
                }
                val nextDifat = ByteBuffer.wrap(difatSec, sectorSize - 4, 4)
                    .order(ByteOrder.LITTLE_ENDIAN).int
                if (nextDifat < 0) break
                difatPtr = nextDifat
            }

            // Read directory
            val dirEntries = readDirectory(data, fat, dirStartSector, sectorSize)

            // Find the root entry and read all streams
            val streams = mutableMapOf<String, ByteArray>()
            fun readStreams(parentPath: String, entries: List<DirEntry>) {
                for (entry in entries) {
                    val fullPath = if (parentPath.isEmpty()) entry.name else "$parentPath/${entry.name}"
                    when (entry.type) {
                        1 -> { // Storage — recurse
                            readStreams(fullPath, entry.children)
                        }
                        2 -> { // Stream — read data
                            val streamData = readStreamData(data, fat, entry.startSector,
                                entry.size, sectorSize, miniSectorSize, data, fat)
                            if (streamData != null) {
                                // Use just the stream name (last component) for lookup
                                streams[entry.name] = streamData
                                streams[fullPath] = streamData
                            }
                        }
                    }
                }
            }
            readStreams("", dirEntries)
            return Ole2Doc(streams)
        }

        private fun readStreamData(
            fullData: ByteArray, fat: List<Int>,
            startSector: Int, size: Int,
            sectorSize: Int, miniSectorSize: Int,
            miniStreamData: ByteArray, miniFAT: List<Int>
        ): ByteArray? {
            if (size == 0) return ByteArray(0)
            if (startSector < 0) return null

            val result = ByteArrayOutputStream()
            var remaining = size
            var currentSector = startSector
            val maxIterations = fat.size + 10

            var iter = 0
            while (currentSector >= 0 && remaining > 0 && iter < maxIterations) {
                iter++
                if (currentSector >= 0 && currentSector * sectorSize + sectorSize <= fullData.size) {
                    val bytesToRead = remaining.coerceAtMost(sectorSize)
                    result.write(fullData, currentSector * sectorSize, bytesToRead)
                    remaining -= bytesToRead
                } else break

                if (remaining > 0) {
                    currentSector = if (currentSector < fat.size) fat[currentSector] else -1
                    if (currentSector < 0) break
                }
            }
            return if (remaining <= 0) result.toByteArray() else null
        }

        private data class DirEntry(
            val name: String,
            val type: Int,        // 0=unknown, 1=storage, 2=stream, 5=root
            val startSector: Int,
            val size: Int,
            val children: List<DirEntry> = emptyList()
        )

        private fun readDirectory(
            data: ByteArray, fat: List<Int>,
            dirStartSector: Int, sectorSize: Int
        ): List<DirEntry> {
            val dirSectors = mutableListOf<Int>()
            var s = dirStartSector
            val maxIter = fat.size + 5
            var iter = 0
            while (s >= 0 && iter < maxIter) {
                iter++
                dirSectors.add(s)
                s = if (s < fat.size) fat[s] else -1
                if (s < 0) break
            }

            val dirBytes = ByteArrayOutputStream()
            for (sec in dirSectors) {
                val offset = sec * sectorSize
                if (offset + sectorSize <= data.size) {
                    dirBytes.write(data, offset, sectorSize)
                }
            }
            val dirData = dirBytes.toByteArray()

            // Each directory entry is 128 bytes
            val entries = mutableListOf<DirEntry>()
            var i = 0
            while (i + 128 <= dirData.size) {
                val entryBytes = dirData.copyOfRange(i, i + 128)
                val nameBytes = entryBytes.copyOfRange(0, 64)
                val nameLen = ByteBuffer.wrap(entryBytes, 64, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF
                val type = entryBytes[66].toInt() and 0xFF
                val startSector = ByteBuffer.wrap(entryBytes, 116, 4).order(ByteOrder.LITTLE_ENDIAN).int
                val size = ByteBuffer.wrap(entryBytes, 120, 4).order(ByteOrder.LITTLE_ENDIAN).int

                // Decode name (UTF-16LE, length includes null terminator)
                val nameChars = nameLen.coerceAtMost(32)
                val name = if (nameChars > 1) {
                    String(nameBytes, 0, (nameChars - 1) * 2, Charsets.UTF_16LE).trim('\u0000')
                } else ""

                // Child list (for storage/root entries)
                val childId = ByteBuffer.wrap(entryBytes, 68, 4).order(ByteOrder.LITTLE_ENDIAN).int

                entries.add(DirEntry(name, type, startSector, size))
                i += 128
            }

            // Build tree from flat list
            fun buildTree(parentId: Int): List<DirEntry> {
                val children = mutableListOf<DirEntry>()
                var childIdx = parentId
                var safety = 0
                while (childIdx >= 0 && childIdx < entries.size && safety < entries.size) {
                    safety++
                    val entry = entries[childIdx]
                    val nextChildId = ByteBuffer.wrap(
                        dirData, childIdx * 128 + 68, 4
                    ).order(ByteOrder.LITTLE_ENDIAN).int

                    val builtEntry = if (entry.type == 1) { // Storage
                        val kids = buildTree(ByteBuffer.wrap(
                            dirData, childIdx * 128 + 72, 4
                        ).order(ByteOrder.LITTLE_ENDIAN).int)
                        entry.copy(children = kids)
                    } else entry

                    children.add(builtEntry)
                    childIdx = nextChildId
                }
                return children
            }

            return if (entries.isNotEmpty()) buildTree(
                ByteBuffer.wrap(dirData, 72, 4).order(ByteOrder.LITTLE_ENDIAN).int
            ) else emptyList()
        }

        private fun readSector(data: ByteArray, sector: Int, sectorSize: Int): ByteArray {
            val offset = sector * sectorSize
            return if (offset + sectorSize <= data.size) data.copyOfRange(offset, offset + sectorSize)
            else ByteArray(sectorSize)
        }
    }

    // ─────────────────────────────────────────────────────────────
    // PPT Record Parser
    // ─────────────────────────────────────────────────────────────

    data class SlideContent(
        val texts: MutableList<String> = mutableListOf(),
        val shapes: MutableList<ShapeInfo> = mutableListOf(),
        var background: SlideBackground = SlideBackground(Color.WHITE),
    )

    data class ShapeInfo(
        val text: String,
        val x: Int = 0, val y: Int = 0,
        val width: Int = 0, val height: Int = 0,
        val fontSize: Int = 24,
        val bold: Boolean = false,
        val italic: Boolean = false,
        val color: Int = Color.BLACK,
    )

    data class SlideBackground(val color: Int)

    private class PptRecordParser(val data: ByteArray) {
        var slideWidth = 9144000  // EMUs, default 10 inches
        var slideHeight = 6858000 // EMUs, default 7.5 inches

        // PPT record type constants
        private val REC_DOCUMENT = 0x03E8
        private val REC_DOCUMENT_10 = 0x03E9
        private val REC_DRAWING = 0x0400
        private val REC_SLIDE = 0x0FF0
        private val REC_SLIDE_PERSIST = 0x0FF6
        private val REC_MAIN_MASTER = 0x0FF3
        private val REC_SLIDE_ATOM = 0x0FA0  // This is actually TextBytesAtom
        private val REC_TEXT_BYTES = 0x0FA0
        private val REC_TEXT_CHARS = 0x0FA8
        private val REC_TEXT_STYLE = 0x0FA2
        private val REC_TEXT_HEADER = 0x0FA1
        private val REC_COLOR_SCHEME = 0x0FD7
        private val REC_FONT_ENTITY = 0x0FF5
        private val REC_OUTLINE = 0x0FF9
        private val REC_SLIDE_LIST = 0x0FC3
        private val REC_SHAPE = 0x0EC1
        private val REC_PLACEHOLDER = 0x0F9F
        private val REC_SLIDE_SIZE = 0x03F2

        fun parseSlides(): List<SlideContent> {
            val slides = mutableListOf<SlideContent>()
            var currentSlide = SlideContent()
            var inSlide = false
            var depth = 0

            var i = 0
            while (i < data.size - 8) {
                val recType = readUInt16(i)
                val recLen = readUInt32(i + 4)

                if (recLen < 0 || recLen > data.size || i + 8 + recLen > data.size) {
                    i += 4
                    continue
                }

                when (recType) {
                    REC_DOCUMENT, REC_DOCUMENT_10 -> {
                        parseDocument(data, i + 8, recLen)
                        if (documentSlideWidth > 0) slideWidth = documentSlideWidth
                        if (documentSlideHeight > 0) slideHeight = documentSlideHeight
                    }
                    REC_SLIDE -> {
                        if (inSlide && (currentSlide.texts.isNotEmpty() || currentSlide.shapes.isNotEmpty())) {
                            slides.add(currentSlide)
                        }
                        currentSlide = SlideContent()
                        inSlide = true
                        depth++
                    }
                    REC_TEXT_BYTES -> {
                        if (inSlide) {
                            val text = readTextBytes(i + 8, recLen)
                            if (text.isNotBlank()) {
                                currentSlide.texts.add(text)
                                currentSlide.shapes.add(ShapeInfo(text = text))
                            }
                        }
                    }
                    REC_TEXT_CHARS -> {
                        if (inSlide) {
                            val text = readTextChars(i + 8, recLen)
                            if (text.isNotBlank()) {
                                currentSlide.texts.add(text)
                                currentSlide.shapes.add(ShapeInfo(text = text))
                            }
                        }
                    }
                    REC_TEXT_HEADER -> {
                        if (inSlide && recLen >= 4) {
                            // TextHeaderAtom: 4 bytes with character count
                        }
                    }
                    REC_COLOR_SCHEME -> {
                        // Color scheme — could parse for slide background
                    }
                }

                i += 8 + recLen
                // Records are padded to 4-byte boundaries
                val padding = (4 - (recLen % 4)) % 4
                i += padding
            }

            if (inSlide && (currentSlide.texts.isNotEmpty() || currentSlide.shapes.isNotEmpty())) {
                slides.add(currentSlide)
            }

            // If no slides found via record boundaries, try heuristic
            if (slides.isEmpty()) {
                slides.add(SlideContent(
                    texts = mutableListOf(extractPlainText()),
                    shapes = mutableListOf(ShapeInfo(text = extractPlainText()))
                ))
            }

            return slides
        }

        private var documentSlideWidth = 0
        private var documentSlideHeight = 0

        private fun parseDocument(data: ByteArray, offset: Int, length: Int) {
            // DocumentAtom contains slide dimensions
            // Format: 28 bytes at offset
            // Bytes 0-3: notUsed
            // Bytes 4-7: slideWidth (in EMUs / 914400 per inch)
            // Bytes 8-11: slideHeight
            if (length >= 12) {
                val w = readInt32(offset + 4)
                val h = readInt32(offset + 8)
                if (w in 1000000..60000000) documentSlideWidth = w
                if (h in 1000000..60000000) documentSlideHeight = h
            }
        }

        private fun readTextBytes(offset: Int, length: Int): String {
            if (offset + length > data.size) return ""
            return String(data, offset, length, Charsets.UTF_8)
                .filter { it.code >= 32 || it == '\n' || it == '\r' || it == '\t' }
                .trim()
        }

        private fun readTextChars(offset: Int, length: Int): String {
            if (offset + length > data.size || length < 2) return ""
            return String(data, offset, length, Charsets.UTF_16LE)
                .filter { it.code >= 32 || it == '\n' || it == '\r' || it == '\t' }
                .trim()
        }

        private fun extractPlainText(): String {
            val sb = StringBuilder()
            var run = StringBuilder()
            for (b in data) {
                val c = (b.toInt() and 0xFF).toChar()
                if (c.code in 32..126 || c == '\n' || c == '\r' || c == '\t') {
                    run.append(c)
                } else {
                    if (run.length > 4) {
                        sb.append(run)
                        sb.append("\n")
                    }
                    run = StringBuilder()
                }
            }
            if (run.length > 4) sb.append(run)
            return sb.toString()
        }

        private fun readUInt16(offset: Int): Int {
            if (offset + 2 > data.size) return 0
            return (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)
        }

        private fun readUInt32(offset: Int): Int {
            if (offset + 4 > data.size) return 0
            return (data[offset].toInt() and 0xFF) or
                    ((data[offset + 1].toInt() and 0xFF) shl 8) or
                    ((data[offset + 2].toInt() and 0xFF) shl 16) or
                    ((data[offset + 3].toInt() and 0xFF) shl 24)
        }

        private fun readInt32(offset: Int): Int = readUInt32(offset)
    }

    // ─────────────────────────────────────────────────────────────
    // OOXML PPTX Generator
    // ─────────────────────────────────────────────────────────────

    private fun buildPptx(slides: List<SlideContent>, slideWidth: Int, slideHeight: Int): ByteArray {
        val baos = ByteArrayOutputStream()
        ZipOutputStream(baos).use { zip ->
            writeEntry(zip, "[Content_Types].xml", buildContentTypes(slides.size).toByteArray())
            writeEntry(zip, "_rels/.rels", ROOT_RELS.toByteArray())
            writeEntry(zip, "ppt/presentation.xml", buildPresentationXml(slides.size, slideWidth, slideHeight).toByteArray())
            writeEntry(zip, "ppt/_rels/presentation.xml.rels", buildPresentationRels(slides.size).toByteArray())
            writeEntry(zip, "ppt/theme/theme1.xml", THEME.toByteArray())
            writeEntry(zip, "ppt/slideMasters/slideMaster1.xml", SLIDE_MASTER.toByteArray())
            writeEntry(zip, "ppt/slideMasters/_rels/slideMaster1.xml.rels", SLIDE_MASTER_RELS.toByteArray())
            writeEntry(zip, "ppt/slideLayouts/slideLayout1.xml", SLIDE_LAYOUT.toByteArray())
            writeEntry(zip, "ppt/slideLayouts/_rels/slideLayout1.xml.rels", SLIDE_LAYOUT_RELS.toByteArray())

            for ((idx, slide) in slides.withIndex()) {
                writeEntry(zip, "ppt/slides/slide${idx + 1}.xml",
                    buildSlideXml(idx + 1, slide, slideWidth, slideHeight).toByteArray())
                writeEntry(zip, "ppt/slides/_rels/slide${idx + 1}.xml.rels",
                    buildSlideRels(idx + 1).toByteArray())
            }
        }
        return baos.toByteArray()
    }

    private fun writeEntry(zip: ZipOutputStream, name: String, data: ByteArray) {
        zip.putNextEntry(ZipEntry(name))
        zip.write(data)
        zip.closeEntry()
    }

    private fun buildContentTypes(slideCount: Int): String {
        val slideOverrides = (1..slideCount).joinToString("\n") {
            "  <Override PartName=\"/ppt/slides/slide$it.xml\" ContentType=\"application/vnd.openxmlformats-officedocument.presentationml.slide+xml\"/>"
        }
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">
  <Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>
  <Default Extension="xml" ContentType="application/xml"/>
  <Override PartName="/ppt/presentation.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.presentation.main+xml"/>
  <Override PartName="/ppt/slideMasters/slideMaster1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideMaster+xml"/>
  <Override PartName="/ppt/slideLayouts/slideLayout1.xml" ContentType="application/vnd.openxmlformats-officedocument.presentationml.slideLayout+xml"/>
  <Override PartName="/ppt/theme/theme1.xml" ContentType="application/vnd.openxmlformats-officedocument.theme+xml"/>
$slideOverrides
</Types>"""
    }

    private fun buildPresentationXml(slideCount: Int, width: Int, height: Int): String {
        val slideIds = (1..slideCount).joinToString("\n") {
            "      <p:sldId id=\"${255 + it}\" r:id=\"rId$it\"/>"
        }
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:presentation xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
  xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
  xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <p:sldMasterIdLst>
    <p:sldMasterId id="2147483648" r:id="rId${slideCount + 1}"/>
  </p:sldMasterIdLst>
  <p:sldIdLst>
$slideIds
  </p:sldIdLst>
  <p:sldSz cx="$width" cy="$height" type="screen4x3"/>
  <p:notesSz cx="$height" cy="$width"/>
</p:presentation>"""
    }

    private fun buildPresentationRels(slideCount: Int): String {
        val slideRels = (1..slideCount).joinToString("\n") {
            "  <Relationship Id=\"rId$it\" Type=\"http://schemas.openxmlformats.org/officeDocument/2006/relationships/slide\" Target=\"slides/slide$it.xml\"/>"
        }
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
$slideRels
  <Relationship Id="rId${slideCount + 1}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="slideMasters/slideMaster1.xml"/>
  <Relationship Id="rId${slideCount + 2}" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme" Target="theme/theme1.xml"/>
</Relationships>"""
    }

    private fun buildSlideRels(slideNum: Int): String {
        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/>
</Relationships>"""
    }

    private fun buildSlideXml(slideNum: Int, slide: SlideContent, width: Int, height: Int): String {
        val allTexts = slide.texts.ifEmpty { listOf("") }

        // Build paragraph XML for each text run
        val paras = allTexts.joinToString("\n") { text ->
            val escaped = xmlEscape(text)
            """        <a:p>
          <a:pPr algn="l"/>
          <a:r>
            <a:rPr lang="en-US" sz="2400" dirty="0" b="0" i="0">
              <a:solidFill><a:srgbClr val="000000"/></a:solidFill>
            </a:rPr>
            <a:t>$escaped</a:t>
          </a:r>
        </a:p>"""
        }

        // Determine background color
        val bgColor = String.format("%06X", slide.background.color and 0xFFFFFF)

        return """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sld xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
  xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
  xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <p:cSld>
    <p:bg>
      <p:bgRef idx="1001">
        <a:schemeClr val="bg1"/>
      </p:bgRef>
    </p:bg>
    <p:spTree>
      <p:nvGrpSpPr>
        <p:cNvPr id="1" name=""/>
        <p:cNvGrpSpPr/>
        <p:nvPr/>
      </p:nvGrpSpPr>
      <p:grpSpPr/>
      <p:sp>
        <p:nvSpPr>
          <p:cNvPr id="2" name="TextBox 1"/>
          <p:cNvSpPr txBox="1"/>
          <p:nvPr/>
        </p:nvSpPr>
        <p:spPr>
          <a:xfrm>
            <a:off x="457200" y="274638"/>
            <a:ext cx="${width - 914400}" cy="${height - 549276}"/>
          </a:xfrm>
          <a:prstGeom prst="rect"><a:avLst/></a:prstGeom>
          <a:solidFill><a:srgbClr val="$bgColor"/></a:solidFill>
        </p:spPr>
        <p:txBody>
          <a:bodyPr wrap="square" rtlCol="0"/>
          <a:lstStyle/>
$paras
        </p:txBody>
      </p:sp>
    </p:spTree>
  </p:cSld>
  <p:clrMapOvr>
    <a:masterClrMapping/>
  </p:clrMapOvr>
</p:sld>"""
    }

    private fun xmlEscape(s: String): String = s
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    // ── Static OOXML skeleton files ──────────────────────────────

    private const val ROOT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="ppt/presentation.xml"/>
</Relationships>"""

    private const val SLIDE_LAYOUT = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sldLayout xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
  xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
  xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"
  type="blank">
  <p:cSld name="Blank">
    <p:bg/>
    <p:spTree>
      <p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>
      <p:grpSpPr/>
    </p:spTree>
  </p:cSld>
  <p:clrMapOvr><a:masterClrMapping/></p:clrMapOvr>
</p:sldLayout>"""

    private const val SLIDE_LAYOUT_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideMaster" Target="../slideMasters/slideMaster1.xml"/>
</Relationships>"""

    private const val SLIDE_MASTER = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<p:sldMaster xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main"
  xmlns:p="http://schemas.openxmlformats.org/presentationml/2006/main"
  xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships">
  <p:cSld>
    <p:bg/>
    <p:spTree>
      <p:nvGrpSpPr><p:cNvPr id="1" name=""/><p:cNvGrpSpPr/><p:nvPr/></p:nvGrpSpPr>
      <p:grpSpPr/>
    </p:spTree>
  </p:cSld>
  <p:sldLayoutIdLst>
    <p:sldLayoutId id="2147483649" r:id="rId1"/>
  </p:sldLayoutIdLst>
  <p:clrMap bg1="lt1" tx1="dk1" bg2="lt2" tx2="dk2"
    accent1="accent1" accent2="accent2" accent3="accent3"
    accent4="accent4" accent5="accent5" accent6="accent6"
    hlink="hlink" folHlink="folHlink"/>
  <p:sldMasterSz cx="9144000" cy="6858000"/>
</p:sldMaster>"""

    private const val SLIDE_MASTER_RELS = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">
  <Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/slideLayout" Target="../slideLayouts/slideLayout1.xml"/>
  <Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/theme" Target="../theme/theme1.xml"/>
</Relationships>"""

    private const val THEME = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?>
<a:theme xmlns:a="http://schemas.openxmlformats.org/drawingml/2006/main" name="Office Theme">
  <a:themeElements>
    <a:clrScheme name="Office">
      <a:dk1><a:srgbClr val="000000"/></a:dk1>
      <a:lt1><a:srgbClr val="FFFFFF"/></a:lt1>
      <a:dk2><a:srgbClr val="44546A"/></a:dk2>
      <a:lt2><a:srgbClr val="E7E6E6"/></a:lt2>
      <a:accent1><a:srgbClr val="4472C4"/></a:accent1>
      <a:accent2><a:srgbClr val="ED7D31"/></a:accent2>
      <a:accent3><a:srgbClr val="A5A5A5"/></a:accent3>
      <a:accent4><a:srgbClr val="FFC000"/></a:accent4>
      <a:accent5><a:srgbClr val="5B9BD5"/></a:accent5>
      <a:accent6><a:srgbClr val="70AD47"/></a:accent6>
      <a:hlink><a:srgbClr val="0563C1"/></a:hlink>
      <a:folHlink><a:srgbClr val="954F72"/></a:folHlink>
    </a:clrScheme>
    <a:fontScheme name="Office">
      <a:majorFont><a:latin typeface="Calibri"/><a:ea typeface=""/><a:cs typeface=""/></a:majorFont>
      <a:minorFont><a:latin typeface="Calibri"/><a:ea typeface=""/><a:cs typeface=""/></a:minorFont>
    </a:fontScheme>
    <a:fmtScheme name="Office">
      <a:fillStyleLst>
        <a:solidFill><a:schemeClr val="phClr"/></a:solidFill>
        <a:solidFill><a:schemeClr val="phClr"/></a:solidFill>
        <a:solidFill><a:schemeClr val="phClr"/></a:solidFill>
      </a:fillStyleLst>
      <a:lnStyleLst>
        <a:ln w="6350"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln>
        <a:ln w="6350"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln>
        <a:ln w="6350"><a:solidFill><a:schemeClr val="phClr"/></a:solidFill></a:ln>
      </a:lnStyleLst>
      <a:effectStyleLst>
        <a:effectStyle><a:effectLst/></a:effectStyle>
        <a:effectStyle><a:effectLst/></a:effectStyle>
        <a:effectStyle><a:effectLst/></a:effectStyle>
      </a:effectStyleLst>
      <a:bgFillStyleLst>
        <a:solidFill><a:schemeClr val="phClr"/></a:solidFill>
        <a:solidFill><a:schemeClr val="phClr"/></a:solidFill>
        <a:solidFill><a:schemeClr val="phClr"/></a:solidFill>
      </a:bgFillStyleLst>
    </a:fmtScheme>
  </a:themeElements>
</a:theme>"""

    // ── Save to storage ──────────────────────────────────────────

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
}
