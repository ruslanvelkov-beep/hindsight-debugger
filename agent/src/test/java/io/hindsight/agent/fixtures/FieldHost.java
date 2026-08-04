// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.agent.fixtures;

/**
 * Instrumentation fixture exercising the field-write surface of the agent: instance fields of
 * every §2.3 category, static fields, constructor-initialization writes and reference fields
 * going null → String / null → arbitrary object. All values deterministic.
 */
public final class FieldHost {

    public static int staticCount;
    public static Object staticRef;

    private int count;
    private long ticks;
    private float fraction;
    private double ratio;
    private boolean flag;
    private char letter;
    private short small;
    private byte tiny;
    private String label;
    private Object payload;

    /** Two ctor-init writes (after {@code super()}): {@code count} and {@code label}. */
    public FieldHost(int initial) {
        this.count = initial;
        this.label = "init";
    }

    /** One write per field, including the statics. */
    public void update() {
        count = 42;
        ticks = 99L;
        fraction = 0.5f;
        ratio = 2.25;
        flag = true;
        letter = 'x';
        small = 3;
        tiny = 1;
        label = "updated";
        payload = new StringBuilder("p");
        staticCount = 7;
        staticRef = "sref";
    }

    /** Readback so tests can confirm the instrumented writes really happened. */
    public int count() {
        return count;
    }

    /** Readback of the boolean field (exercises a GETFIELD after the spliced write). */
    public boolean flag() {
        return flag;
    }
}
