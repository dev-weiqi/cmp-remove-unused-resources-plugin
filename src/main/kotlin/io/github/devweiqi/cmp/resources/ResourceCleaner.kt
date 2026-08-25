package io.github.devweiqi.cmp.resources

import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.io.path.deleteIfExists
import kotlin.io.path.extension
import kotlin.io.path.invariantSeparatorsPathString
import kotlin.io.path.isDirectory
import kotlin.io.path.name
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes

internal data class CleanupResult(
    val unusedIds: Set<String>,
    val unusedRawFiles: Set<String>,
    val deletedFiles: Int,
    val editedXmlFiles: Int,
    val retainedRawFiles: Int,
)

internal class ResourceCleaner(
    private val projectRoot: Path,
    private val resourceRoots: List<Path>,
    private val scanRoots: List<Path>,
    private val dryRun: Boolean = false,
    private val excludeIds: List<String> = emptyList(),
    private val excludeIdPatterns: List<String> = emptyList(),
    private val excludeFilePatterns: List<String> = emptyList(),
    private val log: (String) -> Unit = {},
) {
    private val excludedIdRegexes = excludeIdPatterns.map(String::toRegex)
    private val excludedFileMatchers = excludeFilePatterns.map {
        FileSystems.getDefault().getPathMatcher("glob:$it")
    }

    fun clean(): CleanupResult {
        val inventory = inventory()
        val sourceUsage = scanSources(inventory.groups.keys)
        val excludedGroups = inventory.groups.values.filterTo(mutableSetOf()) { group ->
            isExcluded(group.id) || (group.files + group.fileVariants).any(::isExcludedFile)
        }
        val used = (sourceUsage.ids + sourceUsage.importedNames.flatMap { name ->
            inventory.groups.keys.filter { it.name == name }
        } + inventory.pinnedIds + excludedGroups.map { it.id }).toMutableSet()

        val queue = ArrayDeque(used)
        while (queue.isNotEmpty()) {
            inventory.groups[queue.removeFirst()]?.references.orEmpty().forEach { reference ->
                if (reference in inventory.groups && used.add(reference)) queue.add(reference)
            }
        }

        val unusedGroups = inventory.groups.values
            .filter { it.id !in used && it !in excludedGroups }
            .sortedBy { it.id.external }
        val dynamicTypes = sourceUsage.dynamicTypes
        val effectiveUnusedGroups = unusedGroups.filterNot { it.id.type in dynamicTypes }
        val unusedRaw = if (sourceUsage.hasDynamicRawPath) {
            emptyList()
        } else {
            inventory.rawFiles.filterNot { raw ->
                raw.relativePath in sourceUsage.rawPaths || isExcludedFile(raw.file)
            }
        }

        val xmlRemovals = mutableMapOf<Path, MutableList<XmlOccurrence>>()
        val filesToDelete = linkedSetOf<Path>()
        effectiveUnusedGroups.forEach { group ->
            filesToDelete.addAll(group.fileVariants)
            group.xmlVariants.forEach { occurrence ->
                xmlRemovals.getOrPut(occurrence.file) { mutableListOf() } += occurrence
            }
        }
        filesToDelete.addAll(unusedRaw.map { it.file })

        (filesToDelete + xmlRemovals.keys).forEach { file ->
            require(resourceRoots.any { file.normalized().startsWith(it.normalized()) }) {
                "Refusing to change a file outside configured resource roots: $file"
            }
        }

        var deletedFiles = 0
        var editedXmlFiles = 0
        if (!dryRun) {
            xmlRemovals.forEach { (file, occurrences) ->
                val xml = inventory.xmlFiles.getValue(file)
                val removedSpans = occurrences.map { it.span }.distinct()
                val remainingElements = xml.entries.count { it.span !in removedSpans }
                if (remainingElements == 0) {
                    filesToDelete.add(file)
                } else {
                    file.writeBytes(xml.remove(removedSpans))
                    editedXmlFiles++
                }
            }
            filesToDelete.forEach { file ->
                if (file.deleteIfExists()) deletedFiles++
            }
            resourceRoots.forEach(::deleteEmptyDirectories)
        }

        effectiveUnusedGroups.forEach { log("${marker()}unused resource: ${it.id.external}") }
        unusedRaw.forEach { log("${marker()}unused raw file: ${it.relativePath}") }
        if (sourceUsage.hasDynamicRawPath) {
            log("Dynamic Res.getUri/Res.readBytes path found; retained every files/** resource")
        }
        dynamicTypes.forEach { type ->
            log("Dynamic Res.all${type.dynamicName}Resources access found; retained every $type resource")
        }

        return CleanupResult(
            unusedIds = effectiveUnusedGroups.mapTo(linkedSetOf()) { it.id.external },
            unusedRawFiles = unusedRaw.mapTo(linkedSetOf()) { it.relativePath },
            deletedFiles = deletedFiles,
            editedXmlFiles = editedXmlFiles,
            retainedRawFiles = inventory.rawFiles.size - unusedRaw.size,
        )
    }

    private fun marker(): String = if (dryRun) "[dry run] " else ""

    private fun inventory(): Inventory {
        val groups = linkedMapOf<ResourceId, ResourceGroup>()
        val rawFiles = mutableListOf<RawFile>()
        val xmlFiles = mutableMapOf<Path, ExactXml>()
        val pinnedIds = mutableSetOf<ResourceId>()

        resourceRoots.map(Path::normalized).filter(Files::isDirectory).forEach { root ->
            Files.walk(root).use { paths ->
                paths.filter(Files::isRegularFile).forEach { file ->
                    val relative = root.relativize(file)
                    if (relative.nameCount < 2) return@forEach
                    val directory = relative.getName(0).toString()
                    when {
                        directory == "files" -> rawFiles += RawFile(
                            file,
                            relative.invariantSeparatorsPathString,
                        )

                        directory.matches(Regex("drawable(?:-.+)?")) -> {
                            val id = ResourceId("drawable", resourceFileName(file))
                            groups.getOrPut(id) { ResourceGroup(id) }.also { group ->
                                group.fileVariants.add(file)
                                if (file.extension.equals("xml", true)) {
                                    group.references += extractResourceReferences(file.readText())
                                }
                            }
                        }

                        directory.matches(Regex("font(?:-.+)?")) -> {
                            val id = ResourceId("font", resourceFileName(file))
                            groups.getOrPut(id) { ResourceGroup(id) }.fileVariants.add(file)
                        }

                        directory.matches(Regex("values(?:-.+)?")) && file.extension.equals("xml", true) -> {
                            val xml = ExactXml.read(file)
                            xmlFiles[file] = xml
                            xml.entries.forEach { entry ->
                                val type = when (entry.tagName) {
                                    "string" -> "string"
                                    "string-array" -> "array"
                                    "plurals" -> "plurals"
                                    else -> null
                                }
                                if (type == null || entry.resourceName == null) {
                                    pinnedIds += extractResourceReferences(entry.originalText(xml.text))
                                } else {
                                    val id = ResourceId(type, entry.resourceName)
                                    groups.getOrPut(id) { ResourceGroup(id) }.also { group ->
                                        group.xmlVariants += XmlOccurrence(file, entry.span)
                                        group.files.add(file)
                                        group.references += extractResourceReferences(entry.originalText(xml.text))
                                    }
                                    if (entry.hasOverride) pinnedIds += id
                                }
                            }
                        }

                        file.extension.equals("xml", true) -> {
                            pinnedIds += extractResourceReferences(file.readText())
                        }
                    }
                }
            }
        }
        return Inventory(groups, rawFiles, xmlFiles, pinnedIds)
    }

    private fun scanSources(ids: Set<ResourceId>): SourceUsage {
        val usedIds = mutableSetOf<ResourceId>()
        val importedNames = mutableSetOf<String>()
        val rawPaths = mutableSetOf<String>()
        val dynamicTypes = mutableSetOf<String>()
        var hasDynamicRawPath = false
        val knownNames = ids.mapTo(hashSetOf()) { it.name }
        val normalizedResourceRoots = resourceRoots.map(Path::normalized)

        scanRoots.map(Path::normalized).filter(Files::isDirectory).forEach { scanRoot ->
            Files.walk(scanRoot).use { paths ->
                paths.filter(Files::isRegularFile)
                    .filter { file ->
                        val normalized = file.normalized()
                        normalizedResourceRoots.none(normalized::startsWith) &&
                            !shouldSkipSource(scanRoot, normalized) &&
                            normalized.extension.lowercase() in SOURCE_EXTENSIONS
                    }
                    .forEach { file ->
                        val text = file.readText()
                        DIRECT_RESOURCE.findAll(text).forEach { match ->
                            usedIds += ResourceId(match.groupValues[1], match.groupValues[2].unquoteIdentifier())
                        }
                        if (GENERATED_RESOURCE_WILDCARD_IMPORT.containsMatchIn(text)) {
                            dynamicTypes += DYNAMIC_RESOURCE_MAPS.values
                        }
                        IMPORT.findAll(text).forEach { match ->
                            val name = match.groupValues[1].unquoteIdentifier()
                            if (name in knownNames) importedNames += name
                        }
                        DYNAMIC_RESOURCE_MAPS.forEach { (token, type) ->
                            if (token in text) dynamicTypes += type
                        }
                        RAW_LITERAL.findAll(text).forEach { match -> rawPaths += match.groupValues[1] }
                        parseRawCalls(text).also { calls ->
                            rawPaths += calls.literalPaths
                            hasDynamicRawPath = hasDynamicRawPath || calls.hasDynamicPath
                        }
                    }
            }
        }
        return SourceUsage(usedIds, importedNames, rawPaths, dynamicTypes, hasDynamicRawPath)
    }

    private fun shouldSkipSource(scanRoot: Path, file: Path): Boolean =
        scanRoot.relativize(file).any { it.toString() in SKIPPED_SOURCE_DIRECTORIES }

    private fun isExcluded(id: ResourceId): Boolean {
        val resId = id.external
        val androidId = "R.${id.type}.${id.name}"
        if (excludeIds.any { it == resId || it == androidId }) return true
        return excludedIdRegexes.any { regex ->
            regex.matches(resId) || regex.matches(androidId)
        }
    }

    private fun isExcludedFile(file: Path): Boolean {
        val absolute = file.normalized()
        val candidates = buildList {
            add(absolute)
            runCatching { add(projectRoot.normalized().relativize(absolute)) }
            resourceRoots.forEach { root ->
                runCatching { add(root.normalized().relativize(absolute)) }
            }
        }
        return excludedFileMatchers.any { matcher ->
            candidates.any(matcher::matches)
        }
    }

    private fun deleteEmptyDirectories(root: Path) {
        if (!root.isDirectory()) return
        Files.walk(root).sorted(Comparator.reverseOrder()).use { paths ->
            paths.filter { it != root && Files.isDirectory(it) }.forEach { directory ->
                Files.newDirectoryStream(directory).use { children ->
                    if (!children.iterator().hasNext()) directory.deleteIfExists()
                }
            }
        }
    }

    private data class Inventory(
        val groups: Map<ResourceId, ResourceGroup>,
        val rawFiles: List<RawFile>,
        val xmlFiles: Map<Path, ExactXml>,
        val pinnedIds: Set<ResourceId>,
    )

    private data class ResourceGroup(
        val id: ResourceId,
        val fileVariants: MutableSet<Path> = linkedSetOf(),
        val xmlVariants: MutableList<XmlOccurrence> = mutableListOf(),
        val files: MutableSet<Path> = linkedSetOf(),
        val references: MutableSet<ResourceId> = linkedSetOf(),
    )

    private data class SourceUsage(
        val ids: Set<ResourceId>,
        val importedNames: Set<String>,
        val rawPaths: Set<String>,
        val dynamicTypes: Set<String>,
        val hasDynamicRawPath: Boolean,
    )

    private data class RawFile(val file: Path, val relativePath: String)
    private data class XmlOccurrence(val file: Path, val span: IntRange)
}

private data class ResourceId(val type: String, val name: String) {
    val external: String get() = "Res.$type.$name"
}

private val String.dynamicName: String
    get() = when (this) {
        "array" -> "StringArray"
        "plurals" -> "PluralString"
        else -> replaceFirstChar(Char::uppercase)
    }

private data class RawCalls(val literalPaths: Set<String>, val hasDynamicPath: Boolean)

private fun parseRawCalls(text: String): RawCalls {
    val paths = mutableSetOf<String>()
    var dynamic = false
    RAW_CALL.findAll(text).forEach { call ->
        var index = call.range.last + 1
        while (index < text.length && text[index].isWhitespace()) index++
        if (index >= text.length || text[index] != '"') {
            dynamic = true
            return@forEach
        }
        val tripleQuoted = text.startsWith("\"\"\"", index)
        val quoteLength = if (tripleQuoted) 3 else 1
        val start = index + quoteLength
        var end = start
        while (end < text.length) {
            if (tripleQuoted && text.startsWith("\"\"\"", end)) break
            if (!tripleQuoted && text[end] == '"' && (end == start || text[end - 1] != '\\')) break
            end++
        }
        if (end >= text.length) {
            dynamic = true
            return@forEach
        }
        val value = text.substring(start, end)
        if ('$' in value || !value.startsWith("files/")) dynamic = true else paths += value
    }
    return RawCalls(paths, dynamic)
}

private fun resourceFileName(file: Path): String = file.name
    .removeSuffix(".${file.extension}")
    .removeSuffix(".9")

private fun extractResourceReferences(text: String): Set<ResourceId> =
    XML_REFERENCE.findAll(text).mapTo(linkedSetOf()) {
        ResourceId(
            type = when (it.groupValues[1]) {
                "string-array" -> "array"
                else -> it.groupValues[1]
            },
            name = it.groupValues[2],
        )
    }

private fun Path.normalized(): Path = toAbsolutePath().normalize()
private fun String.unquoteIdentifier(): String = removeSurrounding("`")

private val SOURCE_EXTENSIONS = setOf("kt", "kts", "java", "xml", "html", "css")
private val SKIPPED_SOURCE_DIRECTORIES = setOf(".git", ".gradle", ".idea", "build")
private val DIRECT_RESOURCE = Regex("""\b[A-Za-z_][A-Za-z0-9_]*\s*\.\s*(drawable|string|array|plurals|font)\s*\.\s*(`[^`]+`|[A-Za-z_][A-Za-z0-9_]*)""")
private val IMPORT = Regex("""(?m)^\s*import\s+[^\s]+\.(`[^`]+`|[A-Za-z_][A-Za-z0-9_]*)(?:\s+as\s+\w+)?\s*$""")
private val GENERATED_RESOURCE_WILDCARD_IMPORT = Regex("""(?m)^\s*import\s+\S+\.generated\.resources\.\*\s*$""")
private val RAW_LITERAL = Regex("""[\"'](files/[^\"'\r\n$]+)[\"']""")
private val RAW_CALL = Regex("""\b[A-Za-z_][A-Za-z0-9_]*\s*\.\s*(?:getUri|readBytes)\s*\(""")
private val XML_REFERENCE = Regex("""@(?:\+)?(drawable|string|string-array|array|plurals|font)/([A-Za-z_][A-Za-z0-9_]*)""")
private val DYNAMIC_RESOURCE_MAPS = mapOf(
    "allDrawableResources" to "drawable",
    "allStringResources" to "string",
    "allStringArrayResources" to "array",
    "allPluralStringResources" to "plurals",
    "allFontResources" to "font",
)

private data class ExactXml(
    val text: String,
    val charset: Charset,
    val bom: ByteArray,
    val entries: List<XmlEntry>,
) {
    fun remove(spans: List<IntRange>): ByteArray {
        val output = StringBuilder(text)
        spans.map { expandToWholeLine(text, it) }.distinct().sortedByDescending { it.first }.forEach {
            output.delete(it.first, it.last + 1)
        }
        return bom + output.toString().toByteArray(charset)
    }

    companion object {
        fun read(file: Path): ExactXml {
            val bytes = file.readBytes()
            val (charset, bomSize) = detectCharset(bytes)
            val content = bytes.copyOfRange(bomSize, bytes.size)
            val text = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(java.nio.ByteBuffer.wrap(content))
                .toString()
            validateXml(bytes)
            return ExactXml(text, charset, bytes.copyOfRange(0, bomSize), scanTopLevelEntries(text))
        }
    }
}

private data class XmlEntry(
    val tagName: String,
    val resourceName: String?,
    val hasOverride: Boolean,
    val span: IntRange,
) {
    fun originalText(xml: String): String = xml.substring(span.first, span.last + 1)
}

private fun validateXml(bytes: ByteArray) {
    val factory = DocumentBuilderFactory.newInstance().apply {
        setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        setFeature("http://xml.org/sax/features/external-general-entities", false)
        setFeature("http://xml.org/sax/features/external-parameter-entities", false)
        setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
        setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
        isExpandEntityReferences = false
        isNamespaceAware = true
    }
    val root = factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes)).documentElement
    require(root is Element && root.localName == "resources") { "Values XML root must be <resources>" }
}

private fun scanTopLevelEntries(xml: String): List<XmlEntry> {
    val resourcesStart = findStartTag(xml, "resources")
    require(resourcesStart >= 0) { "Values XML root must be <resources>" }
    var index = findTagEnd(xml, resourcesStart) + 1
    var depth = 0
    var currentStart = -1
    var currentTag = ""
    var currentName: String? = null
    var currentOverride = false
    val entries = mutableListOf<XmlEntry>()

    while (index < xml.length) {
        val next = xml.indexOf('<', index)
        if (next < 0) break
        when {
            xml.startsWith("<!--", next) -> index = xml.indexOf("-->", next + 4).requireFound("comment") + 3
            xml.startsWith("<![CDATA[", next) -> index = xml.indexOf("]]>", next + 9).requireFound("CDATA") + 3
            xml.startsWith("<?", next) -> index = xml.indexOf("?>", next + 2).requireFound("processing instruction") + 2
            xml.startsWith("</", next) -> {
                val end = findTagEnd(xml, next)
                if (depth == 0) break
                depth--
                if (depth == 0) entries += XmlEntry(currentTag, currentName, currentOverride, currentStart..end)
                index = end + 1
            }
            xml.startsWith("<!", next) -> index = findTagEnd(xml, next) + 1
            else -> {
                val end = findTagEnd(xml, next)
                val tagText = xml.substring(next, end + 1)
                val selfClosing = tagText.dropLast(1).trimEnd().endsWith('/')
                if (depth == 0) {
                    currentStart = next
                    currentTag = tagText.substringAfter('<').takeWhile { !it.isWhitespace() && it != '/' && it != '>' }.substringAfter(':')
                    currentName = NAME_ATTRIBUTE.find(tagText)?.groupValues?.get(2)
                    currentOverride = OVERRIDE_ATTRIBUTE.containsMatchIn(tagText)
                    if (selfClosing) entries += XmlEntry(currentTag, currentName, currentOverride, currentStart..end)
                }
                if (!selfClosing) depth++
                index = end + 1
            }
        }
    }
    return entries
}

private fun findStartTag(xml: String, name: String): Int {
    var index = 0
    while (index < xml.length) {
        val start = xml.indexOf('<', index)
        if (start < 0) return -1
        if (xml.startsWith("<!--", start)) {
            index = xml.indexOf("-->", start + 4).requireFound("comment") + 3
            continue
        }
        if (xml.startsWith("<?", start)) {
            index = xml.indexOf("?>", start + 2).requireFound("processing instruction") + 2
            continue
        }
        val tag = xml.substring(start + 1).takeWhile { !it.isWhitespace() && it != '>' && it != '/' }
        if (tag.substringAfter(':') == name) return start
        index = findTagEnd(xml, start) + 1
    }
    return -1
}

private fun findTagEnd(xml: String, start: Int): Int {
    var quote: Char? = null
    for (index in start + 1 until xml.length) {
        val character = xml[index]
        if (quote == null && (character == '\'' || character == '"')) quote = character
        else if (quote == character) quote = null
        else if (quote == null && character == '>') return index
    }
    error("Unterminated XML tag")
}

private fun Int.requireFound(what: String): Int {
    require(this >= 0) { "Unterminated XML $what" }
    return this
}

private fun expandToWholeLine(text: String, span: IntRange): IntRange {
    val lineStart = text.lastIndexOf('\n', span.first - 1).let { if (it < 0) 0 else it + 1 }
    val lineEnd = text.indexOf('\n', span.last + 1).let { if (it < 0) text.length else it + 1 }
    val before = text.substring(lineStart, span.first)
    val contentEnd = if (lineEnd > 0 && text[lineEnd - 1] == '\n') lineEnd - 1 else lineEnd
    val after = text.substring(span.last + 1, contentEnd)
    return if (before.isBlank() && after.isBlank()) lineStart until lineEnd else span
}

private fun detectCharset(bytes: ByteArray): Pair<Charset, Int> = when {
    bytes.startsWith(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())) -> StandardCharsets.UTF_8 to 3
    bytes.startsWith(byteArrayOf(0xFE.toByte(), 0xFF.toByte())) -> StandardCharsets.UTF_16BE to 2
    bytes.startsWith(byteArrayOf(0xFF.toByte(), 0xFE.toByte())) -> StandardCharsets.UTF_16LE to 2
    else -> {
        val header = bytes.take(256).toByteArray().toString(StandardCharsets.US_ASCII)
        val declared = ENCODING.find(header)?.groupValues?.get(1)
        (declared?.let(Charset::forName) ?: StandardCharsets.UTF_8) to 0
    }
}

private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
    size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

private val NAME_ATTRIBUTE = Regex("""\bname\s*=\s*(['\"])(.*?)\1""", RegexOption.DOT_MATCHES_ALL)
private val OVERRIDE_ATTRIBUTE = Regex("""(?:\b[A-Za-z_][\w.-]*:)?override\s*=\s*(['\"])true\1""", RegexOption.IGNORE_CASE)
private val ENCODING = Regex("""encoding\s*=\s*['\"]([^'\"]+)['\"]""", RegexOption.IGNORE_CASE)
