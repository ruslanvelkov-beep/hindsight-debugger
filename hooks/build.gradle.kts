// SPDX-License-Identifier: Apache-2.0
//
// The `hooks` jar is loaded onto the bootstrap classloader at agent premain
// via Instrumentation.appendToBootstrapClassLoaderSearch, so instrumented
// classes loaded by any classloader can resolve hindsight runtime hooks.
//
// To stay safe on the bootstrap loader, this module must depend ONLY on JDK
// modules — no Maven runtime deps. Tests can pull in JUnit etc. since they
// run in a normal classloader.

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// The hooks jar is loaded into whatever JVM runs the instrumented app; target the
// oldest bytecode we care to support so a JDK 21 node can load it.
tasks.compileJava {
    options.release.set(21)
}
