# CMP Remove Unused Resources Plugin

A Gradle plugin that safely removes unused [Compose Multiplatform resources](https://www.jetbrains.com/help/kotlin-multiplatform-dev/compose-multiplatform-resources.html).

This plugin adapts the resource-removal workflow and feature set of [irgaly/android-remove-unused-resources-plugin](https://github.com/irgaly/android-remove-unused-resources-plugin) for Compose Multiplatform resources. The CMP implementation is maintained independently and is not an official release of the original project.

## Features

- Detects usages of `Res.drawable`, `Res.font`, `Res.string`, `Res.array`, and `Res.plurals` across a multi-module build.
- Detects `files/**` usages through static paths passed to `Res.getUri` and `Res.readBytes`.
- Keeps all resources of a type when `Res.all*Resources` or generated-resource wildcard imports make usage dynamic.
- Keeps all `files/**` resources when a raw path is dynamic.
- Removes every qualifier variant of an unused resource as one group.
- Removes unused entries from every `values-*` XML while preserving the original encoding and untouched characters.
- Preserves resources referenced by other resources and entries marked `tools:override="true"`.
- Supports dry-run, exact resource exclusions, regex exclusions, and glob file exclusions.
- Has no runtime dependency on Android Gradle Plugin, Compose, or an XML library.

## Requirements

- Gradle 8.5 or newer
- JDK 17 or newer

The plugin does not require applying the Compose or Kotlin Multiplatform plugin to the same project. Apply it to the module that owns `src/commonMain/composeResources`.

## Usage

```kotlin
// settings.gradle.kts
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}
```

```kotlin
// resources/build.gradle.kts
plugins {
    id("io.github.dev-weiqi.cmp-remove-unused-resources") version "0.1.0"
}
```

Always run a dry-run first:

```shell
./gradlew :resources:removeUnusedResources -Prur.dryRun
```

Then remove the reported resources:

```shell
./gradlew :resources:removeUnusedResources
```

The [`sample`](sample) is a Compose Multiplatform project with one used and one unused resource. Run its dry-run with:

```shell
./gradlew -p sample removeUnusedResources -Prur.dryRun
```

The default resource directory is `src/commonMain/composeResources`. Source usage is scanned from the root project, excluding `.git`, `.gradle`, `.idea`, `build`, and the configured resource directories.

## Configuration

```kotlin
removeUnusedResources {
    dryRun.set(true)

    // Replace the default resource root or add more roots.
    resourceDirectories.setFrom("src/commonMain/composeResources")
    resourceDirectories.from("src/desktopMain/composeResources")

    // The root project is scanned by default. Explicit directories under build/ are allowed.
    scanDirectories.from(layout.buildDirectory.dir("generated/mySources"))

    excludeIds.add("Res.drawable.generated_at_runtime")
    excludeIdPatterns.add("Res\\..*\\.remote_.*")
    excludeFilePatterns.add("**/values/legal_strings.xml")
}
```

`excludeIds` also accepts Android-style `R.type.name` values to ease migration from the original plugin. Regex rules are matched against both `Res.type.name` and `R.type.name`. File globs use JDK `FileSystem.getPathMatcher` syntax and can match paths relative to the root project or a resource root.

The `-Prur.dryRun` Gradle property overrides the configured `dryRun` value.

## Detection and safety

Typed resources are retained when the scanner finds a direct accessor, an imported resource property, a resource-to-resource XML reference, an exclusion, or a dynamic all-resources map for that type. A generated resources wildcard import conservatively retains all typed resources.

For `files/**`, literal paths such as `Res.readBytes("files/config.json")` are tracked exactly. If any `Res.readBytes(...)` or `Res.getUri(...)` call uses a variable, template, or other dynamic expression, every raw file is retained and a warning is printed.

The plugin calculates and validates the complete deletion plan before changing files. It only deletes files discovered below an explicitly configured resource root.

## Differences from the Android plugin

Compose Multiplatform has no Android build variants or Android Lint `UnusedResources` report, so this plugin does not provide `removeUnusedResources{variant}`, `rur.lintResultXml`, `rur.lint.onlyUnusedResources`, or `rur.lint.overrideLintConfig`. It scans CMP resource accessors and source files directly instead.

Android resource types that Compose Multiplatform does not generate (`layout`, `menu`, `anim`, and others) are outside this plugin's scope. CMP `files/**` support is included even though it has no direct Android-plugin equivalent.

## CI

```yaml
- name: Report unused Compose resources
  run: ./gradlew :resources:removeUnusedResources -Prur.dryRun
```

Run the non-dry task only on a branch where the resulting source changes can be reviewed.

## Publishing

Tags matching `v*.*.*` run the release workflow for both Maven Central and the Gradle Plugin Portal. Maven Central requires a verified `io.github.dev-weiqi` namespace, a Central Portal user token, and a published GPG key. The required GitHub Actions secrets are documented in [`.github/workflows/publish.yml`](.github/workflows/publish.yml).

## License

Apache License 2.0. See [LICENSE](LICENSE).
