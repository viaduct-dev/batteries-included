plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.viaduct.application)
    alias(libs.plugins.viaduct.module)
    kotlin("plugin.serialization") version "2.1.0"
    application
}

viaductApplication {
    modulePackagePrefix.set("com.viaduct")
}

viaductModule {
    modulePackageSuffix.set("resolvers")
}

dependencies {
    // Viaduct service-wiring for CheckerExecutorFactory registration
    implementation(libs.viaduct.service.wiring)

    // Ktor server (upgraded to 3.2.0 for Koin 4.x compatibility)
    implementation("io.ktor:ktor-server-core:3.2.0")
    implementation("io.ktor:ktor-server-cio:3.2.0")
    implementation("io.ktor:ktor-server-content-negotiation:3.2.0")
    implementation("io.ktor:ktor-serialization-jackson:3.2.0")
    implementation("io.ktor:ktor-server-cors:3.2.0")
    implementation("io.ktor:ktor-server-call-logging:3.2.0")

    // Kotlin and coroutines
    implementation(libs.kotlin.reflect)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")

    // Jackson for JSON
    implementation(libs.jackson.module.kotlin)

    // Logging
    implementation("ch.qos.logback:logback-classic:1.4.11")

    // Koin for dependency injection (upgraded to 4.1.1)
    implementation("io.insert-koin:koin-core:4.1.1")
    implementation("io.insert-koin:koin-ktor:4.1.1")
    implementation("io.insert-koin:koin-logger-slf4j:4.1.1")

    // CRaC (Coordinated Restore at Checkpoint) for fast startup
    implementation("org.crac:crac:1.5.0")

    // Supabase Kotlin client (version 3.x uses BOM)
    implementation(platform("io.github.jan-tennert.supabase:bom:3.2.5"))
    implementation("io.github.jan-tennert.supabase:postgrest-kt")
    implementation("io.github.jan-tennert.supabase:auth-kt")
    implementation(libs.ktor.client.cio)
    implementation(libs.java.jwt)

    // Ktor test dependencies (upgraded to 3.2.0)
    testImplementation("io.ktor:ktor-server-test-host:3.2.0")
    testImplementation("io.ktor:ktor-client-content-negotiation:3.2.0")

    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.io.mockk.jvm)
    testImplementation(libs.kotest.runner.junit)
    testImplementation(libs.kotest.assertions.core)
    testImplementation(libs.kotest.assertions.json)
}

application {
    mainClass.set("com.viaduct.CracMainKt")
}

tasks.withType<Test> {
    useJUnitPlatform()

    // Exclude example tests (commented out code)
    exclude("**/examples/**")
}

tasks.withType<JavaExec> {
    jvmArgs = listOf(
        "--add-opens", "java.base/java.lang=ALL-UNNAMED"
    )
}
