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

// Start local Supabase (Docker containers) if not already running.
// Migrations from supabase/migrations/ (symlink to schema/migrations/) are
// applied automatically on first start.
val startSupabase by tasks.registering(Exec::class) {
    workingDir = rootProject.projectDir.parentFile // project root (parent of backend/)
    commandLine("supabase", "start")
    // supabase start is idempotent — returns quickly if already running
    isIgnoreExitValue = true
}

tasks.withType<Test> {
    useJUnitPlatform()
    dependsOn(startSupabase)

    // Exclude example tests (commented out code)
    exclude("**/examples/**")

    // Pass Supabase credentials to tests via system properties
    environment("SUPABASE_URL", System.getenv("SUPABASE_URL") ?: "http://127.0.0.1:54321")
    environment("SUPABASE_ANON_KEY", System.getenv("SUPABASE_ANON_KEY") ?: "sb_publishable_ACJWlzQHlZjBrEguHvfOxg_3BJgxAaH")
    environment("SUPABASE_SERVICE_ROLE_KEY", System.getenv("SUPABASE_SERVICE_ROLE_KEY") ?: "sb_secret_N7UND0UgjKTVK-Uodkm0Hg_xSvEMPvz")
}

tasks.withType<JavaExec> {
    jvmArgs = listOf(
        "--add-opens", "java.base/java.lang=ALL-UNNAMED"
    )
}
