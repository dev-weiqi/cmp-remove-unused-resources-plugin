plugins {
    kotlin("jvm") version "2.3.0"
    `java-gradle-plugin`
    id("com.gradle.plugin-publish") version "2.1.1"
    id("com.vanniktech.maven.publish") version "0.37.0"
}

group = "io.github.dev-weiqi"
version = providers.gradleProperty("VERSION_NAME").getOrElse("0.1.0")

kotlin {
    jvmToolchain(17)
}

dependencies {
    testImplementation(kotlin("test-junit"))
}

gradlePlugin {
    website = "https://github.com/dev-weiqi/cmp-remove-unused-resources-plugin"
    vcsUrl = "https://github.com/dev-weiqi/cmp-remove-unused-resources-plugin"
    plugins {
        create("cmpRemoveUnusedResources") {
            id = "io.github.dev-weiqi.cmp-remove-unused-resources"
            displayName = "Remove Unused Resources Plugin for Compose Multiplatform"
            description = "Safely removes unused Compose Multiplatform resources"
            tags = listOf("compose-multiplatform", "kotlin-multiplatform", "resources")
            implementationClass = "io.github.devweiqi.cmp.resources.CmpRemoveUnusedResourcesPlugin"
        }
    }
}

mavenPublishing {
    publishToMavenCentral()
    signAllPublications()
    pom {
        name.set("CMP Remove Unused Resources Plugin")
        description.set("A Gradle plugin that safely removes unused Compose Multiplatform resources")
        inceptionYear.set("2026")
        url.set("https://github.com/dev-weiqi/cmp-remove-unused-resources-plugin")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
            }
        }
        developers {
            developer {
                id.set("dev-weiqi")
                name.set("dev-weiqi")
                url.set("https://github.com/dev-weiqi")
            }
        }
        scm {
            url.set("https://github.com/dev-weiqi/cmp-remove-unused-resources-plugin")
            connection.set("scm:git:git://github.com/dev-weiqi/cmp-remove-unused-resources-plugin.git")
            developerConnection.set("scm:git:ssh://git@github.com/dev-weiqi/cmp-remove-unused-resources-plugin.git")
        }
    }
}
