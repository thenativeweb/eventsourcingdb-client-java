package io.eventsourcingdb;

/** Tells whether a {@link Bound} includes or excludes its event. */
public enum BoundType {
    /** The range includes the event. */
    INCLUSIVE,
    /** The range excludes the event. */
    EXCLUSIVE
}
