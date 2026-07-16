// SPDX-License-Identifier: Apache-2.0
//
// The `viewer` module: an interactive, read-only, terminal "inspect-time" debugger over a
// Hindsight recording (VIEWER.md). It depends on `reader` for parsing/reconstruction inputs
// and on Lanterna for the alternate-screen TUI. Runs on a modern JDK; not bootstrap-constrained.

plugins {
    application
}

application {
    mainClass.set("io.hindsight.viewer.Main")
}

dependencies {
    implementation(project(":reader"))
    implementation("com.googlecode.lanterna:lanterna:3.1.3")

    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}
