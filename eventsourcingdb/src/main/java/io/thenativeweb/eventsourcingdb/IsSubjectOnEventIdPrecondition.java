package io.thenativeweb.eventsourcingdb;

import org.jspecify.annotations.Nullable;

/**
 * A precondition that holds if the last event of the given subject has the given ID, i.e. if no event was written to
 * the subject since that one. If the options make it recursive, it holds if the last event among the subject and its
 * nested subjects has the given ID.
 *
 * @param subject the subject, e.g. {@code /books/42}
 * @param eventId the ID of the event that must be the last one of the subject
 * @param options the options, i.e. whether the precondition applies to the nested subjects, too, or {@code null} for
 *     the subject alone
 */
public record IsSubjectOnEventIdPrecondition(
        String subject, String eventId, @Nullable PreconditionOptions options) implements Precondition {
    /**
     * Creates a precondition on the subject alone.
     *
     * @param subject the subject, e.g. {@code /books/42}
     * @param eventId the ID of the event that must be the last one of the subject
     */
    public IsSubjectOnEventIdPrecondition(String subject, String eventId) {
        this(subject, eventId, null);
    }
}
