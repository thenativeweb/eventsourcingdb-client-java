package io.eventsourcingdb;

public sealed interface Precondition
        permits IsSubjectPristinePrecondition,
                IsSubjectPopulatedPrecondition,
                IsSubjectOnEventIdPrecondition,
                IsEventQlQueryTruePrecondition {}
