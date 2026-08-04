// SPDX-License-Identifier: GPL-3.0-or-later
//
// The `agent` jar is a Java agent (premain entry). It contains ByteBuddy,
// ASM, and the bytecode visitors that inject calls to the hooks runtime.
//
// At agent startup the premain calls
//   Instrumentation.appendToBootstrapClassLoaderSearch(<hooks.jar>)
// so the hooks become globally resolvable. The path to the hooks jar is
// passed as an agent argument.

plugins {
    `java-library`
}

dependencies {
    // compileOnly: hooks classes must exist exactly once at runtime — on the
    // bootstrap classpath (appended by premain) — never inside this fat jar.
    compileOnly(project(":hooks"))
    implementation("net.bytebuddy:byte-buddy:1.18.11")
    implementation("org.ow2.asm:asm:9.10.1")
    implementation("org.ow2.asm:asm-commons:9.10.1")
    implementation("org.ow2.asm:asm-tree:9.10.1")

    testImplementation(project(":hooks"))
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Same bytecode floor as hooks: the agent jar must load into a JDK 21+ node JVM.
tasks.compileJava {
    options.release.set(21)
}

// Build a fat agent jar with all runtime deps shaded in.
tasks.jar {
    archiveBaseName.set("hindsight-agent")
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) }) {
        exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA", "module-info.class")
    }
    manifest {
        attributes(
            "Premain-Class" to "io.hindsight.agent.HindsightAgent",
            "Agent-Class" to "io.hindsight.agent.HindsightAgent",
            "Can-Redefine-Classes" to "true",
            "Can-Retransform-Classes" to "true",
            "Can-Set-Native-Method-Prefix" to "false",
        )
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
