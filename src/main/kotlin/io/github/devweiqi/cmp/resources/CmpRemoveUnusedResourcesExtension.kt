package io.github.devweiqi.cmp.resources

import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property

abstract class CmpRemoveUnusedResourcesExtension {
    abstract val dryRun: Property<Boolean>
    abstract val resourceDirectories: ConfigurableFileCollection
    abstract val scanDirectories: ConfigurableFileCollection
    abstract val excludeIds: ListProperty<String>
    abstract val excludeIdPatterns: ListProperty<String>
    abstract val excludeFilePatterns: ListProperty<String>
}
