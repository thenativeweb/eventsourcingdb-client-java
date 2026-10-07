package io.eventsourcingdb;

/**
 * A precondition that holds if the last event of the given subject has the given ID, i.e. if no event was written to
 * the subject since that one.
 *
 * @param subject the subject, e.g. {@code /books/42}
 * @param eventId the ID of the event that must be the last one of the subject
 */
public record IsSubjectOnEventIdPrecondition(String subject, String eventId) implements Precondition {}
