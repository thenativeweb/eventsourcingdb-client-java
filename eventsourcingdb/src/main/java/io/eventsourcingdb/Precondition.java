package io.eventsourcingdb;

/**
 * A condition that must hold for {@link Client#writeEvents(java.util.List, java.util.List)} to write events. If any of
 * the preconditions does not hold, no event is written, and the server answers with status code 409.
 */
public sealed interface Precondition
        permits IsSubjectPristinePrecondition,
                IsSubjectPopulatedPrecondition,
                IsSubjectOnEventIdPrecondition,
                IsEventQlQueryTruePrecondition {}
