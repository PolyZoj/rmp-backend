plugins {
    kotlin("jvm") version "2.1.20"
    id("org.jetbrains.kotlin.plugin.serialization") version "1.8.21"
}

group = "ru.polyZoj"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    // Kotlinx serialization:
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.5.1")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
}