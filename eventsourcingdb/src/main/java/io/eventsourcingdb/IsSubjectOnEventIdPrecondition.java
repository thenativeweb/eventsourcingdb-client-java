package io.eventsourcingdb;

public record IsSubjectOnEventIdPrecondition(String subject, String eventId) implements Precondition {}
