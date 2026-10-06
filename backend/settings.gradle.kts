pluginManagement {
    val viaductVersion: String by settings
    val pgPersistencePluginVersion: String by settings

    repositories {
        maven("https://central.sonatype.com/repository/maven-snapshots/")
        gradlePluginPortal()
    }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id == "dev.viaduct.pg-persistence") {
                useModule("dev.viaduct.persistence:plugin:$pgPersistencePluginVersion")
            }
        }
    }
    plugins {
        id("com.airbnb.viaduct.settings-gradle-plugin") version viaductVersion
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
