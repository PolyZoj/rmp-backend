
plugins {
    application
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktor)
    id("org.jetbrains.kotlin.plugin.serialization") version "1.8.21"
}

group = "ru.polyZoj"
version = "0.0.1"

application {
    mainClass = "io.ktor.server.netty.EngineMain"

    val isDevelopment: Boolean = project.ext.has("development")
    applicationDefaultJvmArgs = listOf("-Dio.ktor.development=$isDevelopment")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("ru.polyZoj:common")
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.config.yaml)
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.5.1")
    implementation(libs.ktor.server.content.negotiation)
    implementation("org.apache.kafka:kafka-clients:3.7.1")
    implementation("org.postgresql:postgresql:42.7.2")
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.dao)
    implementation(libs.exposed.java.time)
    implementation("com.auth0:java-jwt:4.4.0")
    implementation("io.ktor:ktor-server-config-yaml:2.3.7")
    implementation(libs.logback.classic)

    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test.junit)
}

tasks.register("downloadDependencies") {
    doLast {
        configurations
        .filter { it.isCanBeResolved }
        .forEach { configuration ->
            configuration.resolve()
        }
    }
}