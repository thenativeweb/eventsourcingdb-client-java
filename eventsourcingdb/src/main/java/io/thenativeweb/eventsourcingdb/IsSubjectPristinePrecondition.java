package io.thenativeweb.eventsourcingdb;

import org.jspecify.annotations.Nullable;

/**
 * A precondition that holds if the given subject does not have any events yet. If the options make it recursive, it
 * holds if neither the subject nor any of its nested subjects has an event.
 *
 * @param subject the subject, e.g. {@code /books/42}
 * @param options the options, i.e. whether the precondition applies to the nested subjects, too, or {@code null} for
 *     the subject alone
 */
public record IsSubjectPristinePrecondition(
        String subject, @Nullable PreconditionOptions options) implements Precondition {
    /**
     * Creates a precondition on the subject alone.
     *
     * @param subject the subject, e.g. {@code /books/42}
     */
    public IsSubjectPristinePrecondition(String subject) {
        this(subject, null);
    }
}
