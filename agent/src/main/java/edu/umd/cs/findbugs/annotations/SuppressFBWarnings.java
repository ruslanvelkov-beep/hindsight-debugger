// SPDX-License-Identifier: GPL-3.0-or-later
package edu.umd.cs.findbugs.annotations;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Compile-time stub of the SpotBugs/FindBugs suppression annotation.
 *
 * <p>ByteBuddy's published class files are annotated with this type (CLASS retention) but do
 * not declare a dependency on it — it is compile-only by SpotBugs convention. Under this
 * project's {@code -Xlint:all -Werror} regime, javac reading those class files emits
 * {@code [classfile] Cannot find annotation method 'value()'} warnings for every ByteBuddy
 * type the agent touches, which {@code -Werror} turns fatal. The {@code [classfile]} category
 * cannot be suppressed with {@code @SuppressWarnings}, so the only warning-free option that
 * does not weaken lint for the whole build is to make the annotation resolvable.
 *
 * <p>This is an original, dependency-free re-declaration of the annotation's shape (the two
 * members ByteBuddy uses), not copied code. It is never read at runtime by anything in this
 * project; it exists purely so javac can resolve the annotation while compiling against
 * ByteBuddy.
 */
@Retention(RetentionPolicy.CLASS)
@Target({
    ElementType.TYPE,
    ElementType.FIELD,
    ElementType.METHOD,
    ElementType.PARAMETER,
    ElementType.CONSTRUCTOR,
    ElementType.PACKAGE,
})
public @interface SuppressFBWarnings {

    /** The warning identifiers to suppress. */
    String[] value() default {};

    /** Why the suppression is justified. */
    String justification() default "";
}
