package io.thenativeweb.eventsourcingdb;

/**
 * Tells {@link Client#observeEvents(String, ObserveEventsOptions)} to start observing at the latest event of a given
 * type, e.g. at the latest snapshot.
 *
 * @param subject the subject to look for the event in
 * @param type the type of the event
 * @param ifEventIsMissing what to observe if there is no such event
 */
public record ObserveFromLatestEvent(String subject, String type, ObserveIfEventIsMissing ifEventIsMissing) {}
