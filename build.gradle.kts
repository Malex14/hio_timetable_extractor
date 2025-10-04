plugins {
    application
    kotlin("jvm") version "2.2.0"
    kotlin("plugin.serialization") version "2.2.0"
}

group = "de.mbehrmann"
version = "1.0-SNAPSHOT"

val ktor_version: String by project
val jsoup_version: String by project
val kotlinx_serialization_json_version: String by project

application {
    mainClass.set("de.mbehrmann.hio_timetable_extractor.MainKt")
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation(kotlin("test"))
    implementation("io.ktor:ktor-client-core:${ktor_version}")
    implementation("io.ktor:ktor-client-cio:${ktor_version}")
    implementation("io.ktor:ktor-client-encoding:${ktor_version}")
    implementation("org.jsoup:jsoup:${jsoup_version}")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:${kotlinx_serialization_json_version}")
}

tasks.test {
    useJUnitPlatform()
}

tasks.jar {
    manifest {
        attributes["Main-Class"] = application.mainClass
    }

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    configurations.compileClasspath.get().forEach {
        from(if (it.isDirectory) it else zipTree(it))
    }
}

kotlin {
    jvmToolchain(21)
}