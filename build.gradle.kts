// SPDX-License-Identifier: GPL-3.0-or-later
plugins {
    java
}

allprojects {
    group = "io.hindsight"
    version = "0.0.1-SNAPSHOT"

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply(plugin = "java")

    extensions.configure<JavaPluginExtension> {
        toolchain {
            languageVersion.set(JavaLanguageVersion.of(25))
        }
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // The real-recording integration tests (reader/viewer) open recordings under the
        // directory named here — the parent of the transfer-N recordings. Provide it with
        // -Dhindsight.recordingsDir=<dir> or the HINDSIGHT_RECORDINGS_DIR env var to run them;
        // absent, they self-skip. Forwarded to the forked test JVM (a -D on the Gradle CLI does
        // not otherwise reach it).
        (System.getProperty("hindsight.recordingsDir") ?: System.getenv("HINDSIGHT_RECORDINGS_DIR"))
            ?.let { systemProperty("hindsight.recordingsDir", it) }
    }
}
