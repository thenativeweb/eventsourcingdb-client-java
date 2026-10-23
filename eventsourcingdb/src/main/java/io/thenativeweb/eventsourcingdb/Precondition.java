package io.thenativeweb.eventsourcingdb;

/**
 * A condition that must hold for {@link Client#writeEvents(java.util.List, java.util.List)} to write events. If any of
 * the preconditions does not hold, no event is written, and the client throws a {@link DbApiException} with the code
 * {@link ErrorCode#PRECONDITION_VIOLATED}, whichever API the server offers.
 */
public sealed interface Precondition
        permits IsSubjectPristinePrecondition,
                IsSubjectPopulatedPrecondition,
                IsSubjectOnEventIdPrecondition,
                IsEventQlQueryTruePrecondition {}
