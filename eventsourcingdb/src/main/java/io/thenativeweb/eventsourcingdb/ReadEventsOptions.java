package io.thenativeweb.eventsourcingdb;

/**
 * Options for {@link Client#readEvents(String, ReadEventsOptions)}.
 *
 * <p>Create them with {@link #ReadEventsOptions(boolean)}, and set further options with the {@code with} methods:
 *
 * <pre>{@code
 * new ReadEventsOptions(false)
 *     .withOrder(Order.ANTICHRONOLOGICAL)
 *     .withLowerBound(new Bound("100", BoundType.INCLUSIVE))
 * }</pre>
 *
 * @param recursive whether to read the events of nested subjects, too
 * @param order the order to read the events in, or {@code null} for chronological order
 * @param lowerBound the bound to start reading at, or {@code null}
 * @param upperBound the bound to stop reading at, or {@code null}
 * @param fromLatestEvent the latest event of a given type to start reading at, or {@code null}; it can not be
 *     combined with a lower bound
 */
public record ReadEventsOptions(
        boolean recursive, Order order, Bound lowerBound, Bound upperBound, ReadFromLatestEvent fromLatestEvent) {
    /**
     * Creates options without an order, bounds, or a latest event to start at.
     *
     * @param recursive whether to read the events of nested subjects, too
     */
    public ReadEventsOptions(boolean recursive) {
        this(recursive, null, null, null, null);
    }

    /**
     * {@return a copy of these options with the given order}
     *
     * @param order the order to read the events in
     */
    public ReadEventsOptions withOrder(Order order) {
        return new ReadEventsOptions(recursive, order, lowerBound, upperBound, fromLatestEvent);
    }

    /**
     * {@return a copy of these options with the given lower bound}
     *
     * @param lowerBound the bound to start reading at
     */
    public ReadEventsOptions withLowerBound(Bound lowerBound) {
        return new ReadEventsOptions(recursive, order, lowerBound, upperBound, fromLatestEvent);
    }

    /**
     * {@return a copy of these options with the given upper bound}
     *
     * @param upperBound the bound to stop reading at
     */
    public ReadEventsOptions withUpperBound(Bound upperBound) {
        return new ReadEventsOptions(recursive, order, lowerBound, upperBound, fromLatestEvent);
    }

    /**
     * {@return a copy of these options that start reading at the latest event of a given type}
     *
     * @param fromLatestEvent the latest event of a given type to start reading at
     */
    public ReadEventsOptions withFromLatestEvent(ReadFromLatestEvent fromLatestEvent) {
        return new ReadEventsOptions(recursive, order, lowerBound, upperBound, fromLatestEvent);
    }
}
