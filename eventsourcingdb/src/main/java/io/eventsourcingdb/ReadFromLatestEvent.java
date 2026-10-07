package io.eventsourcingdb;

/**
 * Tells {@link Client#readEvents(String, ReadEventsOptions)} to start reading at the latest event of a given type,
 * e.g. at the latest snapshot.
 *
 * @param subject the subject to look for the event in
 * @param type the type of the event
 * @param ifEventIsMissing what to read if there is no such event
 */
public record ReadFromLatestEvent(String subject, String type, ReadIfEventIsMissing ifEventIsMissing) {}
