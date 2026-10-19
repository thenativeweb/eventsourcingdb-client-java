package io.thenativeweb.eventsourcingdb;

/**
 * Options for {@link Client#readSubjects(String, ReadSubjectsOptions)}.
 *
 * <pre>{@code
 * new ReadSubjectsOptions(true)
 * }</pre>
 *
 * @param recursive whether to read the subjects within the subject, too, rather than the subject alone
 */
public record ReadSubjectsOptions(boolean recursive) {}
