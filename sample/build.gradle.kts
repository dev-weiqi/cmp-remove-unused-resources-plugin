plugins {
    kotlin("multiplatform") version "2.3.0"
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.0"
    id("org.jetbrains.compose") version "1.10.0"
    id("io.github.dev-weiqi.cmp-remove-unused-resources")
}

kotlin {
    jvm()

    sourceSets {
        commonMain.dependencies {
            implementation("org.jetbrains.compose.runtime:runtime:1.10.0")
            implementation("org.jetbrains.compose.components:components-resources:1.10.0")
        }
    }
}

compose.resources {
    packageOfResClass = "sample.generated.resources"
}
