plugins {
    kotlin("jvm") version "1.9.22"
    id("io.ktor.plugin") version "3.0.3"
    id("com.airbnb.viaduct.application-gradle-plugin") version "0.20.0"
    id("com.airbnb.viaduct.module-gradle-plugin") version "0.20.0"
}

group = "com.example"
version = "0.0.1"

application {
    mainClass.set("com.example.MainKt")
}

viaductApplication {
    modulePackagePrefix.set("com.example")
}

viaductModule {
    modulePackageSuffix.set("resolvers")
}

repositories {
    mavenCentral()
}

dependencies {
    // Ktor
    implementation("io.ktor:ktor-server-core:3.0.3")
    implementation("io.ktor:ktor-server-netty:3.0.3")
    implementation("io.ktor:ktor-server-content-negotiation:3.0.3")
    implementation("io.ktor:ktor-serialization-jackson:3.0.3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    // Jackson
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.17.0")

    // Viaduct
    implementation("com.airbnb.viaduct:service-api:0.20.0")
    implementation("com.airbnb.viaduct:service-wiring:0.20.0")

    // Logging
    implementation("ch.qos.logback:logback-classic:1.4.14")

    // Testing
    testImplementation("io.ktor:ktor-server-tests:3.0.3")
    testImplementation("org.jetbrains.kotlin:kotlin-test")
}
