pluginManagement {
    val viaductVersion: String by settings
    val pgPersistenceVersion: String by settings

    repositories {
        maven("https://central.sonatype.com/repository/maven-snapshots/")
        gradlePluginPortal()
    }
    plugins {
        id("com.airbnb.viaduct.settings-gradle-plugin") version viaductVersion
        id("dev.viaduct.pg-persistence") version pgPersistenceVersion
    }
}

plugins {
    id("com.airbnb.viaduct.settings-gradle-plugin")
}

val viaductVersion: String by settings

dependencyResolutionManagement {
    repositories {
        maven("https://central.sonatype.com/repository/maven-snapshots/")
        mavenCentral()
    }
    versionCatalogs {
        create("libs") {
            // This injects a dynamic value that your TOML can reference.
            version("viaduct", viaductVersion)
        }
    }
}

rootProject.name = "viaduct-backend"

includeViaductApplication {
    project(":")
    modulePackagePrefix("com.example")

    includeModule {
        project(":")
        modulePackageSuffix("resolvers")
    }
}
