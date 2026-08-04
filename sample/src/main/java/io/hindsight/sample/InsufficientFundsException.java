// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.sample;

/**
 * Thrown by {@link Account#withdraw} when the balance cannot cover a transfer.
 * Exists so the recording contains EXCEPTIONAL {@code METHOD_EXIT} records for
 * a user-defined throwable type (the end-to-end test asserts one appears).
 */
public final class InsufficientFundsException extends Exception {

    private static final long serialVersionUID = 1L;

    /** Creates the exception with a human-readable shortfall description. */
    public InsufficientFundsException(final String message) {
        super(message);
    }
}
