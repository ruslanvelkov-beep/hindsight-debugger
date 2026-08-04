// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.sample;

/**
 * A bank account with a mutable {@code long} balance — the workload's source of
 * instance {@code FIELD_WRITE} records (long before/after pairs) and of string
 * values (the account name flows through args and {@code describe()}).
 */
public final class Account {

    private final String name;
    private long balance;
    private int mutations;

    /** Creates an account with an opening balance. */
    public Account(final String name, final long openingBalance) {
        this.name = name;
        this.balance = openingBalance;
    }

    /** The account holder's name. */
    public String name() {
        return name;
    }

    /** Current balance in cents. */
    public long balance() {
        return balance;
    }

    /** Adds funds; amount must be positive. */
    public void deposit(final long amount) {
        long updated = balance + amount; // local write
        balance = updated; // instance field write (long)
        mutations = mutations + 1; // instance field write (int)
    }

    /**
     * Removes funds.
     *
     * @throws InsufficientFundsException when the balance is too small — this is
     *     the workload's deliberate EXCEPTIONAL method exit
     */
    public void withdraw(final long amount) throws InsufficientFundsException {
        if (amount > balance) {
            long shortfall = amount - balance;
            throw new InsufficientFundsException(
                    "account " + name + " short by " + shortfall + " cents");
        }
        balance = balance - amount;
        mutations = mutations + 1;
    }

    /** Builds a description string (exercises string building + STRING values). */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        sb.append("Account[name=").append(name).append(", balance=").append(balance).append(']');
        String text = sb.toString(); // String-typed local write
        return text;
    }
}
