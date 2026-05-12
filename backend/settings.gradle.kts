val viaductVersion: String by settings

pluginManagement {
    repositories {
        maven("https://central.sonatype.com/repository/maven-snapshots/")
        gradlePluginPortal()
    }
}

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
