package io.thenativeweb.eventsourcingdb;

/**
 * A precondition that holds if the given subject does not have any events yet.
 *
 * @param subject the subject, e.g. {@code /books/42}
 */
public record IsSubjectPristinePrecondition(String subject) implements Precondition {}
