package io.thenativeweb.eventsourcingdb;

/** Tells what to read if there is no event to start at for {@link ReadFromLatestEvent}. */
public enum ReadIfEventIsMissing {
    /** Read no events at all. */
    READ_NOTHING,
    /** Read all events, as if no latest event to start at was given. */
    READ_EVERYTHING
}
