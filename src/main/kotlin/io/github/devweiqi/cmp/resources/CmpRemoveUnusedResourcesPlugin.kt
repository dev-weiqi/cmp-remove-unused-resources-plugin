package io.github.devweiqi.cmp.resources

import org.gradle.api.Plugin
import org.gradle.api.Project

class CmpRemoveUnusedResourcesPlugin : Plugin<Project> {
    override fun apply(project: Project) {
        val extension = project.extensions.create(
            "removeUnusedResources",
            CmpRemoveUnusedResourcesExtension::class.java,
        ).apply {
            dryRun.convention(false)
            resourceDirectories.from(project.layout.projectDirectory.dir("src/commonMain/composeResources"))
            scanDirectories.from(project.rootProject.layout.projectDirectory)
            excludeIds.convention(emptyList())
            excludeIdPatterns.convention(emptyList())
            excludeFilePatterns.convention(emptyList())
        }

        project.tasks.register("removeUnusedResources", RemoveUnusedResourcesTask::class.java) { task ->
            task.group = "compose resources"
            task.description = "Removes unused Compose Multiplatform resources"
            task.projectRoot.set(project.rootProject.layout.projectDirectory)
            task.resourceDirectories.from(extension.resourceDirectories)
            task.scanDirectories.from(extension.scanDirectories)
            task.dryRun.set(
                project.providers.gradleProperty("rur.dryRun")
                    .map { it.toBooleanStrictOrNull() ?: true }
                    .orElse(extension.dryRun),
            )
            task.excludeIds.set(extension.excludeIds)
            task.excludeIdPatterns.set(extension.excludeIdPatterns)
            task.excludeFilePatterns.set(extension.excludeFilePatterns)
        }
    }
}
