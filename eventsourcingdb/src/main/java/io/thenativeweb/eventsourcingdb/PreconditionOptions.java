package io.thenativeweb.eventsourcingdb;

/**
 * Options for the preconditions on a subject, i.e. {@link IsSubjectPristinePrecondition},
 * {@link IsSubjectPopulatedPrecondition}, and {@link IsSubjectOnEventIdPrecondition}.
 *
 * <pre>{@code
 * new PreconditionOptions(true)
 * }</pre>
 *
 * <p>Only a server that offers the v2 API can check a recursive precondition. If the server does not offer it, a write
 * with a recursive precondition throws an {@link IncompatibleServerException}, and writes nothing.
 *
 * @param recursive whether the precondition applies to the nested subjects, too, rather than the subject alone
 */
public record PreconditionOptions(boolean recursive) {}
