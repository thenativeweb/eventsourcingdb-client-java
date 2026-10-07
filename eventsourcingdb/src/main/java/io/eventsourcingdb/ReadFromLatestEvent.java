package io.eventsourcingdb;

public record ReadFromLatestEvent(String subject, String type, ReadIfEventIsMissing ifEventIsMissing) {}
