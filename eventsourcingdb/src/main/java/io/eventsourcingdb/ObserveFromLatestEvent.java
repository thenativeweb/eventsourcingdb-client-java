package io.eventsourcingdb;

public record ObserveFromLatestEvent(String subject, String type, ObserveIfEventIsMissing ifEventIsMissing) {}
