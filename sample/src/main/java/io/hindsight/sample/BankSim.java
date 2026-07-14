// SPDX-License-Identifier: Apache-2.0
package io.hindsight.sample;

import java.util.Random;

/**
 * Deterministic multi-threaded bank simulation recorded by the end-to-end test.
 * Designed to exercise every v1 event type (FORMAT.md §2.1) in a few thousand
 * method calls: instance and static field writes, locals of every interesting
 * shape, boxed values, string building, a caught custom exception (EXCEPTIONAL
 * method exits), recursion, a lambda body, a HashMap ledger, and three active
 * threads (two fixed-seed workers plus main).
 *
 * <p>Determinism contract: worker seeds are fixed and each worker touches a
 * disjoint account pair, so the final total balance and per-worker attempt
 * counts are identical on every run; only interleaving varies. The summary on
 * stdout ends with {@code BANKSIM OK} iff the money-conservation invariant
 * holds, so success is externally checkable. Exits by returning from main —
 * never {@code System.exit} — so the recorder's shutdown hook runs normally.
 */
public final class BankSim {

    /** Transfers each worker attempts; sized for ~5–20k recorded calls total. */
    private static final int TRANSFERS_PER_WORKER = 800;

    private static final long OPENING_BALANCE = 100_000L;

    private BankSim() {}

    /** One worker: a fixed-seed stream of transfers between its two accounts. */
    private static final class Worker implements Runnable {
        private final Bank bank;
        private final String left;
        private final String right;
        private final long seed;
        private int attempted;
        private int rejected;

        Worker(final Bank bank, final String left, final String right, final long seed) {
            this.bank = bank;
            this.left = left;
            this.right = right;
            this.seed = seed;
        }

        @Override
        public void run() {
            Random random = new Random(seed);
            for (int i = 0; i < TRANSFERS_PER_WORKER; i++) {
                boolean forward = random.nextBoolean();
                String from = forward ? left : right;
                String to = forward ? right : left;
                // occasionally ask for far more than any account holds → exception path
                long amount = random.nextInt(50) == 0
                        ? OPENING_BALANCE * 10
                        : 1 + random.nextInt(500);
                attempted = attempted + 1;
                try {
                    long fee = bank.transfer(from, to, amount);
                    if (fee < 0) {
                        throw new IllegalStateException("negative fee");
                    }
                } catch (InsufficientFundsException e) {
                    rejected = rejected + 1; // caught: program continues, recording keeps the throw
                }
            }
        }
    }

    /** Runs the simulation; see class Javadoc for the determinism contract. */
    public static void main(final String[] args) throws InterruptedException {
        Bank bank = new Bank();
        bank.open("alice", OPENING_BALANCE);
        bank.open("bob", OPENING_BALANCE);
        bank.open("carol", OPENING_BALANCE);
        bank.open("dave", OPENING_BALANCE);
        bank.open(Bank.feeAccountName(), 0L);
        long expectedTotal = bank.totalBalance();

        Worker w1 = new Worker(bank, "alice", "bob", 42L);
        Worker w2 = new Worker(bank, "carol", "dave", 1337L);
        Thread t1 = new Thread(w1, "teller-1");
        Thread t2 = new Thread(w2, "teller-2");
        t1.start();
        t2.start();

        // main-thread activity while the workers run
        long projected = Bank.compound(OPENING_BALANCE, 3, 12); // recursion
        Double rate = 0.03; // boxed local
        String projection = "projection@" + rate + ": " + projected;

        t1.join();
        t2.join();

        long finalTotal = bank.totalBalance();
        System.out.println("=== BankSim summary ===");
        System.out.println(projection);
        for (String line : bank.statements()) {
            System.out.println(line);
        }
        System.out.println("transfers completed: " + Bank.transferCount());
        System.out.println("worker1 attempted=" + w1.attempted + " rejected=" + w1.rejected);
        System.out.println("worker2 attempted=" + w2.attempted + " rejected=" + w2.rejected);
        System.out.println("total balance: " + finalTotal + " (expected " + expectedTotal + ")");
        System.out.println(finalTotal == expectedTotal ? "BANKSIM OK" : "BANKSIM FAIL");
    }
}
