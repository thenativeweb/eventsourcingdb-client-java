package io.thenativeweb.eventsourcingdb;

/**
 * A precondition that holds if the given subject already has at least one event.
 *
 * @param subject the subject, e.g. {@code /books/42}
 */
public record IsSubjectPopulatedPrecondition(String subject) implements Precondition {}
