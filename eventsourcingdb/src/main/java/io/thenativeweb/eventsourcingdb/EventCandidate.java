package io.thenativeweb.eventsourcingdb;

/**
 * An event to write, in the <a href="https://docs.eventsourcingdb.io/fundamentals/cloud-events/">CloudEvents</a>
 * format. The server adds the remaining fields, such as the ID and the time, when it writes the event.
 *
 * @param source the source of the event, which identifies the system that writes it, e.g.
 *     {@code https://library.eventsourcingdb.io}
 * @param subject the subject the event belongs to, e.g. {@code /books/42}
 * @param type the type of the event, e.g. {@code io.eventsourcingdb.library.book-acquired}
 * @param data the data of the event, i.e. any object that Jackson can serialize to a JSON object, e.g. a record or a
 *     map
 * @param traceParent the {@code traceparent} of a W3C trace context, or {@code null}
 * @param traceState the {@code tracestate} of a W3C trace context, or {@code null}
 */
public record EventCandidate(
        String source, String subject, String type, Object data, String traceParent, String traceState) {
    /**
     * Creates an event to write without a trace context.
     *
     * @param source the source of the event, which identifies the system that writes it
     * @param subject the subject the event belongs to
     * @param type the type of the event
     * @param data the data of the event
     */
    public EventCandidate(String source, String subject, String type, Object data) {
        this(source, subject, type, data, null, null);
    }
}
