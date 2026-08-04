// SPDX-License-Identifier: GPL-3.0-or-later
//
// Deterministic sample workload recorded by the end-to-end test: a small
// multi-threaded bank-transfer simulation exercising every v1 event type
// (method brackets, args/returns, local writes, field writes, exceptions,
// strings, boxed values, statics, recursion).

tasks.jar {
    manifest {
        attributes("Main-Class" to "io.hindsight.sample.BankSim")
    }
}
