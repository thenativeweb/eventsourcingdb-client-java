package io.thenativeweb.eventsourcingdb;

/**
 * Options for the preconditions on a subject, i.e. {@link IsSubjectPristinePrecondition},
 * {@link IsSubjectPopulatedPrecondition}, and {@link IsSubjectOnEventIdPrecondition}.
 *
 * <pre>{@code
 * new PreconditionOptions(true)
 * }</pre>
 *
 * <p>Only a server that offers the v2 API can check a recursive precondition. If the client remembers from an earlier
 * request that the server does not offer it, it asks the server again before a write with a recursive precondition,
 * since the server may have been upgraded meanwhile, and sends the write through the v2 API if the server offers it
 * now. If the server does not offer the v2 API, a write with a recursive precondition throws an
 * {@link IncompatibleServerException}, and writes nothing.
 *
 * @param recursive whether the precondition applies to the nested subjects, too, rather than the subject alone
 */
public record PreconditionOptions(boolean recursive) {}
