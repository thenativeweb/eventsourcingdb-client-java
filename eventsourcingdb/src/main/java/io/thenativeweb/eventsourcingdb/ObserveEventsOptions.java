package io.thenativeweb.eventsourcingdb;

/**
 * Options for {@link Client#observeEvents(String, ObserveEventsOptions)}.
 *
 * <p>Create them with {@link #ObserveEventsOptions(boolean)}, and set further options with the {@code with} methods:
 *
 * <pre>{@code
 * new ObserveEventsOptions(false)
 *     .withLowerBound(new Bound("100", BoundType.INCLUSIVE))
 * }</pre>
 *
 * @param recursive whether to observe the events of nested subjects, too
 * @param lowerBound the bound to start observing at, or {@code null}
 * @param fromLatestEvent the latest event of a given type to start observing at, or {@code null}; it can not be
 *     combined with a lower bound
 */
public record ObserveEventsOptions(boolean recursive, Bound lowerBound, ObserveFromLatestEvent fromLatestEvent) {
    /**
     * Creates options without a bound or a latest event to start at.
     *
     * @param recursive whether to observe the events of nested subjects, too
     */
    public ObserveEventsOptions(boolean recursive) {
        this(recursive, null, null);
    }

    /**
     * {@return a copy of these options with the given lower bound}
     *
     * @param lowerBound the bound to start observing at
     */
    public ObserveEventsOptions withLowerBound(Bound lowerBound) {
        return new ObserveEventsOptions(recursive, lowerBound, fromLatestEvent);
    }

    /**
     * {@return a copy of these options that start observing at the latest event of a given type}
     *
     * @param fromLatestEvent the latest event of a given type to start observing at
     */
    public ObserveEventsOptions withFromLatestEvent(ObserveFromLatestEvent fromLatestEvent) {
        return new ObserveEventsOptions(recursive, lowerBound, fromLatestEvent);
    }
}
