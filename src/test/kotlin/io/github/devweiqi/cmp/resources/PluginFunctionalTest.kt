package io.github.devweiqi.cmp.resources

import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PluginFunctionalTest {
    @Test
    fun `plugin supports dry run and real deletion without applying Compose`() {
        val root = Files.createTempDirectory("cmp-rur-functional")
        root.resolve("settings.gradle.kts").writeText("rootProject.name = \"fixture\"")
        root.resolve("build.gradle.kts").writeText(
            """plugins {
                |    id("io.github.dev-weiqi.cmp-remove-unused-resources")
                |}
                |""".trimMargin(),
        )
        val resources = root.resolve("src/commonMain/composeResources/drawable").also { it.createDirectories() }
        val used = resources.resolve("used.png").also { it.writeText("used") }
        val unused = resources.resolve("unused.png").also { it.writeText("unused") }
        root.resolve("src/commonMain/kotlin/App.kt").also {
            it.parent.createDirectories()
            it.writeText("val image = Res.drawable.used")
        }

        val dryRun = runner(root.toFile(), "removeUnusedResources", "-Prur.dryRun").build()

        assertEquals(TaskOutcome.SUCCESS, dryRun.task(":removeUnusedResources")?.outcome)
        assertTrue("[dry run] unused resource: Res.drawable.unused" in dryRun.output)
        assertTrue(used.exists())
        assertTrue(unused.exists())

        val deletion = runner(root.toFile(), "removeUnusedResources").build()

        assertEquals(TaskOutcome.SUCCESS, deletion.task(":removeUnusedResources")?.outcome)
        assertTrue(used.exists())
        assertFalse(unused.exists())
    }

    private fun runner(projectDir: java.io.File, vararg arguments: String): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDir)
            .withArguments(*arguments, "--configuration-cache", "--stacktrace")
            .withGradleVersion("8.5")
            .withPluginClasspath()
}
