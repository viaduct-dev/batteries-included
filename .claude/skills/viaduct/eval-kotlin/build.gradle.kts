plugins {
    kotlin("jvm") version "1.9.22"
    kotlin("plugin.serialization") version "1.9.22"
}

group = "com.viaduct.skill"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    // Kotlin serialization for JSON parsing
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.2")

    // Coroutines for async operations
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Testing
    testImplementation(kotlin("test"))
    testImplementation("io.kotest:kotest-runner-junit5:5.8.0")
    testImplementation("io.kotest:kotest-assertions-core:5.8.0")
    testImplementation("io.kotest:kotest-framework-datatest:5.8.0")
}

tasks.test {
    useJUnitPlatform()

    // Pass environment variables to tests
    environment("SKILL_DIR", rootProject.projectDir.parentFile.absolutePath)

    // Increase timeout for evaluations
    systemProperty("kotest.framework.timeout", "600000")

    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}

kotlin {
    jvmToolchain(17)
}
