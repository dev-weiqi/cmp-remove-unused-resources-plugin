package io.github.devweiqi.cmp.resources

import org.gradle.api.DefaultTask
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.work.DisableCachingByDefault

@DisableCachingByDefault(because = "This task intentionally edits source resource files")
abstract class RemoveUnusedResourcesTask : DefaultTask() {
    @get:Internal
    abstract val projectRoot: DirectoryProperty

    @get:Internal
    abstract val resourceDirectories: ConfigurableFileCollection

    @get:Internal
    abstract val scanDirectories: ConfigurableFileCollection

    @get:Input
    abstract val dryRun: Property<Boolean>

    @get:Input
    abstract val excludeIds: ListProperty<String>

    @get:Input
    abstract val excludeIdPatterns: ListProperty<String>

    @get:Input
    abstract val excludeFilePatterns: ListProperty<String>

    @TaskAction
    fun removeUnusedResources() {
        val result = ResourceCleaner(
            projectRoot = projectRoot.get().asFile.toPath(),
            resourceRoots = resourceDirectories.files.map { it.toPath() },
            scanRoots = scanDirectories.files.map { it.toPath() },
            dryRun = dryRun.get(),
            excludeIds = excludeIds.get(),
            excludeIdPatterns = excludeIdPatterns.get(),
            excludeFilePatterns = excludeFilePatterns.get(),
            log = logger::lifecycle,
        ).clean()
        logger.lifecycle(
            "${if (dryRun.get()) "[dry run] " else ""}" +
                "${result.unusedIds.size} unused typed resources, " +
                "${result.unusedRawFiles.size} unused files, " +
                "${result.deletedFiles} files deleted, ${result.editedXmlFiles} XML files edited",
        )
    }
}
