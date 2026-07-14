// SPDX-License-Identifier: Apache-2.0
package io.hindsight.agent;

import net.bytebuddy.asm.Advice;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.description.method.MethodDescription;
import net.bytebuddy.description.method.ParameterDescription;
import net.bytebuddy.description.type.TypeDescription;

/**
 * ByteBuddy {@link Advice.OffsetMapping} factories binding the {@link SiteId} and
 * {@link ReturnTag} advice parameters to per-method integer constants.
 *
 * <p>This is the point where method sites are <em>assigned</em>: ByteBuddy calls
 * {@code resolve()} once per advice application (enter and exit each), handing over the
 * instrumented type and method; {@link SiteRegistry#methodSite} deduplicates, so both
 * resolutions of one method observe the same id — the invariant the reader's ENTER/EXIT
 * pairing depends on.
 */
final class SiteMappings {

    private SiteMappings() {}

    // §2.3 tags, mirrored locally (same precedent as the hook classes: the agent must not
    // touch hooks classes before premain has appended them to the bootstrap search).
    private static final int TAG_REF = 0;
    private static final int TAG_INT = 1;
    private static final int TAG_LONG = 2;
    private static final int TAG_FLOAT = 3;
    private static final int TAG_DOUBLE = 4;
    private static final int TAG_BOOLEAN = 5;
    private static final int TAG_BYTE = 6;
    private static final int TAG_CHAR = 7;
    private static final int TAG_SHORT = 8;
    private static final int TAG_VOID = 12;

    /** Binds {@code @SiteId int} to the method's site id, resolved through the registry. */
    static final class SiteIdFactory implements Advice.OffsetMapping.Factory<SiteId> {

        private final SiteRegistry registry;

        SiteIdFactory(SiteRegistry registry) {
            this.registry = registry;
        }

        @Override
        public Class<SiteId> getAnnotationType() {
            return SiteId.class;
        }

        @Override
        public Advice.OffsetMapping make(
                ParameterDescription.InDefinedShape target,
                AnnotationDescription.Loadable<SiteId> annotation,
                AdviceType adviceType) {
            return (instrumentedType, instrumentedMethod, assigner, argumentHandler, sort) ->
                    Advice.OffsetMapping.Target.ForStackManipulation.of(
                            registry.methodSite(
                                    instrumentedType.getName(),
                                    instrumentedMethod.getInternalName(),
                                    instrumentedMethod.getDescriptor(),
                                    instrumentedMethod.isStatic()));
        }
    }

    /** Binds {@code @ReturnTag int} to the §2.3 tag of the method's declared return type. */
    static final class ReturnTagFactory implements Advice.OffsetMapping.Factory<ReturnTag> {

        @Override
        public Class<ReturnTag> getAnnotationType() {
            return ReturnTag.class;
        }

        @Override
        public Advice.OffsetMapping make(
                ParameterDescription.InDefinedShape target,
                AnnotationDescription.Loadable<ReturnTag> annotation,
                AdviceType adviceType) {
            return (instrumentedType, instrumentedMethod, assigner, argumentHandler, sort) ->
                    Advice.OffsetMapping.Target.ForStackManipulation.of(
                            returnTagOf(instrumentedMethod));
        }
    }

    private static int returnTagOf(MethodDescription method) {
        final TypeDescription returned = method.getReturnType().asErasure();
        if (returned.represents(void.class)) {
            return TAG_VOID;
        }
        if (returned.represents(int.class)) {
            return TAG_INT;
        }
        if (returned.represents(long.class)) {
            return TAG_LONG;
        }
        if (returned.represents(float.class)) {
            return TAG_FLOAT;
        }
        if (returned.represents(double.class)) {
            return TAG_DOUBLE;
        }
        if (returned.represents(boolean.class)) {
            return TAG_BOOLEAN;
        }
        if (returned.represents(byte.class)) {
            return TAG_BYTE;
        }
        if (returned.represents(char.class)) {
            return TAG_CHAR;
        }
        if (returned.represents(short.class)) {
            return TAG_SHORT;
        }
        return TAG_REF;
    }
}
