package io.eventsourcingdb;

/**
 * A bound of a range of events, given by the ID of an event, and whether the range includes that event.
 *
 * @param id the ID of the event
 * @param type whether the range includes or excludes the event
 */
public record Bound(String id, BoundType type) {}
