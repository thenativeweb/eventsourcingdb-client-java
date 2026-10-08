package io.thenativeweb.eventsourcingdb;

/** The order in which to read events. */
public enum Order {
    /** From the oldest event to the newest, which is the default. */
    CHRONOLOGICAL,
    /** From the newest event to the oldest. */
    ANTICHRONOLOGICAL
}
