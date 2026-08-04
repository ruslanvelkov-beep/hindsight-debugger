// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.sample;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The shared ledger: a {@code HashMap} of accounts plus a static transfer
 * counter (the workload's {@code PUTSTATIC} source). All mutation goes through
 * {@code synchronized} methods so the two worker threads keep the total balance
 * invariant — which the summary at the end of {@link BankSim#main} checks, making
 * a successful run externally verifiable despite scheduling nondeterminism.
 */
public final class Bank {

    /** Total completed transfers across all Bank instances — PUTSTATIC on every transfer. */
    private static long transferCount;

    private final Map<String, Account> ledger = new HashMap<>();

    /** Number of transfers recorded process-wide so far. */
    public static long transferCount() {
        return transferCount;
    }

    /** Opens an account and registers it in the ledger. */
    public Account open(final String name, final long openingBalance) {
        Account account = new Account(name, openingBalance);
        ledger.put(name, account);
        return account;
    }

    /** Looks an account up by name; the workload only asks for existing ones. */
    public Account account(final String name) {
        return ledger.get(name);
    }

    /**
     * Moves {@code amount} cents between two accounts. Deliberately verbose so a
     * single call produces locals of many types: int, long, double, String,
     * boxed Long, and references.
     *
     * @return the fee charged in cents (always 1% rounded down)
     * @throws InsufficientFundsException when the source cannot cover amount + fee
     */
    public synchronized long transfer(final String fromName, final String toName, final long amount)
            throws InsufficientFundsException {
        Account from = ledger.get(fromName); // ref local
        Account to = ledger.get(toName); // ref local
        int feePercent = 1; // int local
        double feeRate = feePercent / 100.0; // double local
        long fee = (long) (amount * feeRate); // long local
        Long total = amount + fee; // boxed local
        String memo = fromName + "->" + toName + ":" + amount; // String local
        from.withdraw(total);
        to.deposit(amount);
        deposit(feeAccountName(), fee);
        transferCount = transferCount + 1; // PUTSTATIC
        consumeMemo(memo);
        return fee;
    }

    /** Deposits into a named account — an extra call layer for the call tree. */
    public synchronized void deposit(final String name, final long amount) {
        ledger.get(name).deposit(amount);
    }

    /** Sum of all balances; iterates the ledger with a lambda (instrumented synthetic method). */
    public synchronized long totalBalance() {
        long[] sum = new long[1];
        ledger.forEach((name, account) -> {
            long b = account.balance(); // local write inside lambda body
            sum[0] += b;
        });
        return sum[0];
    }

    /** All account descriptions, sorted by name for deterministic output. */
    public synchronized List<String> statements() {
        List<String> names = new ArrayList<>(ledger.keySet());
        names.sort(String::compareTo);
        List<String> lines = new ArrayList<>(names.size());
        for (String name : names) {
            lines.add(ledger.get(name).describe());
        }
        return lines;
    }

    /** The house account collecting fees. */
    public static String feeAccountName() {
        return "house";
    }

    private static void consumeMemo(final String memo) {
        if (memo.isEmpty()) {
            throw new IllegalStateException("memo cannot be empty");
        }
    }

    /**
     * Compound interest via recursion (the workload's recursive call chain):
     * {@code balance * (1 + ratePercent/100)^years}, integer cents.
     */
    public static long compound(final long balance, final int ratePercent, final int years) {
        if (years <= 0) {
            return balance;
        }
        long grown = balance + balance * ratePercent / 100;
        return compound(grown, ratePercent, years - 1);
    }
}
