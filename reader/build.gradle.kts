// SPDX-License-Identifier: Apache-2.0
//
// Offline reader for hindsight recordings: parses the recording directory
// (FORMAT.md), merges per-thread streams by seq, and provides the dump/stats/verify
// CLI. Runs on a modern JDK; not bootstrap-constrained.

plugins {
    application
}

application {
    mainClass.set("io.hindsight.reader.Main")
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// The end-to-end test records the sample app under the real agent. Jar paths are
// handed over via system properties; the test skips itself when they are absent.
val agentJarTask = project(":agent").tasks.named<Jar>("jar")
val hooksJarTask = project(":hooks").tasks.named<Jar>("jar")
val sampleJarTask = project(":sample").tasks.named<Jar>("jar")

tasks.test {
    dependsOn(agentJarTask, hooksJarTask, sampleJarTask)
    doFirst {
        systemProperty("hindsight.agentJar", agentJarTask.get().archiveFile.get().asFile.absolutePath)
        systemProperty("hindsight.hooksJar", hooksJarTask.get().archiveFile.get().asFile.absolutePath)
        systemProperty("hindsight.sampleJar", sampleJarTask.get().archiveFile.get().asFile.absolutePath)
        systemProperty("hindsight.javaHome", System.getProperty("java.home") ?: "")
    }
}
