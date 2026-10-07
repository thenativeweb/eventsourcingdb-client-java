package io.eventsourcingdb;

/**
 * A precondition that holds if the given EventQL query returns {@code true}. The query must return a single row with a
 * single value, which is interpreted as a boolean.
 *
 * @param query the query, e.g.
 *     {@code FROM e IN events WHERE e.type == 'io.eventsourcingdb.library.book-borrowed' PROJECT INTO COUNT() < 10}
 */
public record IsEventQlQueryTruePrecondition(String query) implements Precondition {}
