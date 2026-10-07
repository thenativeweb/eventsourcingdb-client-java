package io.eventsourcingdb;

/** Tells what to observe if there is no event to start at for {@link ObserveFromLatestEvent}. */
public enum ObserveIfEventIsMissing {
    /** Wait for an event of the given type, and start observing there. */
    WAIT_FOR_EVENT,
    /** Observe all events, as if no latest event to start at was given. */
    READ_EVERYTHING
}
