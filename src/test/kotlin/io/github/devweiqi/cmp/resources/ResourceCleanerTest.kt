package io.github.devweiqi.cmp.resources

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readBytes
import kotlin.io.path.readText
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ResourceCleanerTest {
    @Test
    fun `deletes every qualifier of an unused file resource and keeps used resources`() {
        val project = project()
        project.resource("drawable/logo.png")
        project.resource("drawable-dark/logo.png")
        project.resource("drawable/unused.webp")
        project.resource("drawable-xxxhdpi/unused.webp")
        project.resource("drawable/unused_patch.9.png")
        project.resource("drawable-dark/unused_patch.9.png")
        project.source("feature/src/commonMain/kotlin/App.kt", "Image(painterResource(Res.drawable.logo))")

        val result = project.clean()

        assertTrue(project.resourcePath("drawable/logo.png").exists())
        assertTrue(project.resourcePath("drawable-dark/logo.png").exists())
        assertFalse(project.resourcePath("drawable/unused.webp").exists())
        assertFalse(project.resourcePath("drawable-xxxhdpi/unused.webp").exists())
        assertFalse(project.resourcePath("drawable/unused_patch.9.png").exists())
        assertFalse(project.resourcePath("drawable-dark/unused_patch.9.png").exists())
        assertEquals(setOf("Res.drawable.unused", "Res.drawable.unused_patch"), result.unusedIds)
        assertEquals(4, result.deletedFiles)
    }

    @Test
    fun `removes unused values without changing the remaining XML characters`() {
        val project = project()
        project.resource(
            "values/strings.xml",
            """<?xml version="1.0" encoding="utf-8"?>
                |<resources>
                |    <!-- keep this comment -->
                |    <string name="used">Tom &amp; Jerry</string>
                |    <string name="empty" />
                |    <string name="copyright">&#169;</string>
                |    <string name="unused"><![CDATA[<b>unused</b>]]></string>
                |</resources>
                |""".trimMargin(),
        )
        project.resource(
            "values-zh-rTW/strings.xml",
            """<resources>
                |    <string name="used">Tom and Jerry</string>
                |    <string name="unused">Unused</string>
                |</resources>
                |""".trimMargin(),
        )
        project.source(
            "src/commonMain/kotlin/App.kt",
            "Text(stringResource(Res.string.used)); Res.string.empty; Res.string.copyright",
        )

        val result = project.clean()

        assertEquals(setOf("Res.string.unused"), result.unusedIds)
        assertEquals(
            """<?xml version="1.0" encoding="utf-8"?>
                |<resources>
                |    <!-- keep this comment -->
                |    <string name="used">Tom &amp; Jerry</string>
                |    <string name="empty" />
                |    <string name="copyright">&#169;</string>
                |</resources>
                |""".trimMargin(),
            project.resourcePath("values/strings.xml").readText(),
        )
        assertEquals(
            """<resources>
                |    <string name="used">Tom and Jerry</string>
                |</resources>
                |""".trimMargin(),
            project.resourcePath("values-zh-rTW/strings.xml").readText(),
        )
    }

    @Test
    fun `supports string arrays plurals imports and transitive XML references`() {
        val project = project()
        project.resource(
            "values/strings.xml",
            """<resources>
                |    <string-array name="weekdays"><item>Mon</item></string-array>
                |    <plurals name="cats"><item quantity="other">%d cats</item></plurals>
                |    <string name="title">@string/subtitle</string>
                |    <string name="subtitle">Subtitle</string>
                |    <string name="unused">Unused</string>
                |</resources>
                |""".trimMargin(),
        )
        project.source(
            "src/commonMain/kotlin/App.kt",
            """import demo.generated.resources.weekdays
                |fun ui() {
                |  stringArrayResource(Res.array.weekdays)
                |  pluralStringResource(Res.plurals.cats, 2)
                |  stringResource(Res.string.title)
                |}
                |""".trimMargin(),
        )

        val result = project.clean()

        assertEquals(setOf("Res.string.unused"), result.unusedIds)
        val xml = project.resourcePath("values/strings.xml").readText()
        assertTrue("name=\"weekdays\"" in xml)
        assertTrue("name=\"cats\"" in xml)
        assertTrue("name=\"subtitle\"" in xml)
        assertFalse("name=\"unused\"" in xml)
    }

    @Test
    fun `dry run and every exclude rule prevent deletion`() {
        val project = project()
        project.resource("drawable/exact.png")
        project.resource("drawable/pattern_icon.png")
        project.resource("drawable/by_file.png")
        project.resource("font/dry_run.ttf")

        val result = project.clean(
            dryRun = true,
            excludeIds = listOf("R.drawable.exact"),
            excludeIdPatterns = listOf("R\\.drawable\\.pattern_.*"),
            excludeFilePatterns = listOf("**/drawable/by_file.png"),
        )

        assertTrue(project.resourcePath("drawable/exact.png").exists())
        assertTrue(project.resourcePath("drawable/pattern_icon.png").exists())
        assertTrue(project.resourcePath("drawable/by_file.png").exists())
        assertTrue(project.resourcePath("font/dry_run.ttf").exists())
        assertEquals(setOf("Res.font.dry_run"), result.unusedIds)
        assertEquals(0, result.deletedFiles)
    }

    @Test
    fun `removes unused raw files and dynamic resource maps retain their type`() {
        val project = project()
        project.resource("files/config.json", "{}")
        project.resource("files/unused.bin")
        project.resource("drawable/a.png")
        project.resource("drawable/b.png")
        project.resource("font/unused.ttf")
        project.source(
            "src/commonMain/kotlin/App.kt",
            "val image = Res.allDrawableResources[key]\nval config = Res.getUri(\"files/config.json\")",
        )

        val result = project.clean()

        assertTrue(project.resourcePath("files/config.json").exists())
        assertFalse(project.resourcePath("files/unused.bin").exists())
        assertTrue(project.resourcePath("drawable/a.png").exists())
        assertTrue(project.resourcePath("drawable/b.png").exists())
        assertFalse(project.resourcePath("font/unused.ttf").exists())
        assertEquals(setOf("files/unused.bin"), result.unusedRawFiles)
        assertEquals(1, result.retainedRawFiles)
    }

    @Test
    fun `retains every raw file when a raw path is dynamic`() {
        val project = project()
        project.resource("files/a.json")
        project.resource("files/b.json")
        project.source(
            "src/commonMain/kotlin/App.kt",
            "val path = inputPath\nval bytes = Res.readBytes(path)",
        )

        val result = project.clean()

        assertTrue(project.resourcePath("files/a.json").exists())
        assertTrue(project.resourcePath("files/b.json").exists())
        assertTrue(result.unusedRawFiles.isEmpty())
        assertEquals(2, result.retainedRawFiles)
    }

    @Test
    fun `an excluded qualifier retains the whole alternative resource group`() {
        val project = project()
        project.resource("drawable/icon.png")
        project.resource("drawable-dark/icon.png")

        project.clean(excludeFilePatterns = listOf("**/drawable-dark/icon.png"))

        assertTrue(project.resourcePath("drawable/icon.png").exists())
        assertTrue(project.resourcePath("drawable-dark/icon.png").exists())
    }

    @Test
    fun `tools override retains the whole values resource group`() {
        val project = project()
        project.resource(
            "values/strings.xml",
            """<resources xmlns:tools="http://schemas.android.com/tools">
                |    <string name="overridden" tools:override="true">Base</string>
                |    <string name="unused">Unused</string>
                |</resources>
                |""".trimMargin(),
        )
        project.resource(
            "values-zh/strings.xml",
            """<resources>
                |    <string name="overridden">Override</string>
                |    <string name="unused">Unused</string>
                |</resources>
                |""".trimMargin(),
        )

        val result = project.clean()

        assertEquals(setOf("Res.string.unused"), result.unusedIds)
        assertTrue("name=\"overridden\"" in project.resourcePath("values/strings.xml").readText())
        assertTrue("name=\"overridden\"" in project.resourcePath("values-zh/strings.xml").readText())
    }

    @Test
    fun `preserves UTF-16 encoding and byte order mark`() {
        val project = project()
        val text = """<?xml version="1.0" encoding="UTF-16"?>
            |<resources>
            |    <string name="used">Used</string>
            |    <string name="unused">Unused</string>
            |</resources>
            |""".trimMargin()
        val bom = byteArrayOf(0xFF.toByte(), 0xFE.toByte())
        project.resourceBytes("values/strings.xml", bom + text.toByteArray(Charsets.UTF_16LE))
        project.source("src/commonMain/kotlin/App.kt", "Res.string.used")

        project.clean()

        val bytes = project.resourcePath("values/strings.xml").readBytes()
        assertTrue(bytes.take(2).toByteArray().contentEquals(bom))
        val updated = bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16LE)
        assertTrue("name=\"used\"" in updated)
        assertFalse("name=\"unused\"" in updated)
    }

    @Test
    fun `invalid XML aborts before deleting any resource`() {
        val project = project()
        val drawable = project.resource("drawable/unused.png")
        project.resource("values/strings.xml", "<resources><string name=\"broken\"></resources>")

        assertFailsWith<Exception> { project.clean() }

        assertTrue(drawable.exists())
    }

    private fun project(): TestProject = TestProject(Files.createTempDirectory("cmp-rur-test"))

    private class TestProject(private val root: Path) {
        private val resources = root.resolve("resources/src/commonMain/composeResources")

        fun resource(relative: String, content: String = relative): Path = resourcePath(relative).also {
            it.parent.createDirectories()
            it.writeBytes(content.toByteArray())
        }

        fun resourcePath(relative: String): Path = resources.resolve(relative)

        fun resourceBytes(relative: String, content: ByteArray): Path = resourcePath(relative).also {
            it.parent.createDirectories()
            it.writeBytes(content)
        }

        fun source(relative: String, content: String): Path = root.resolve(relative).also {
            it.parent.createDirectories()
            it.writeText(content)
        }

        fun clean(
            dryRun: Boolean = false,
            excludeIds: List<String> = emptyList(),
            excludeIdPatterns: List<String> = emptyList(),
            excludeFilePatterns: List<String> = emptyList(),
        ): CleanupResult = ResourceCleaner(
            projectRoot = root,
            resourceRoots = listOf(resources),
            scanRoots = listOf(root),
            dryRun = dryRun,
            excludeIds = excludeIds,
            excludeIdPatterns = excludeIdPatterns,
            excludeFilePatterns = excludeFilePatterns,
        ).clean()
    }
}
