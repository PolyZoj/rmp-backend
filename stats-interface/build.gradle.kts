
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
    implementation("org.slf4j:slf4j-api:2.0.7")
    implementation("com.clickhouse:clickhouse-jdbc:0.4.6")
    implementation("ru.polyZoj:common")
    implementation(libs.ktor.simple.cache)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.simple.redis.cache)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.auth.jwt)
    implementation(libs.ktor.server.netty)
    implementation(libs.logback.classic)
    implementation(libs.ktor.server.config.yaml)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.kotlin.test.junit)
    implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.4.0")
    implementation("io.lettuce:lettuce-core:6.2.3.RELEASE")
    implementation("io.ktor:ktor-server-content-negotiation:3.1.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.5.1")
    implementation("io.ktor:ktor-client-core:2.3.2")
    implementation("io.ktor:ktor-client-cio:2.3.2") 
    implementation("org.apache.kafka:kafka-clients:3.7.1")
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
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

