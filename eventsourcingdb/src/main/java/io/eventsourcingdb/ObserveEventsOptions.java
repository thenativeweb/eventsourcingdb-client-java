package io.eventsourcingdb;

public record ObserveEventsOptions(boolean recursive, Bound lowerBound, ObserveFromLatestEvent fromLatestEvent) {
    public ObserveEventsOptions(boolean recursive) {
        this(recursive, null, null);
    }

    public ObserveEventsOptions withLowerBound(Bound lowerBound) {
        return new ObserveEventsOptions(recursive, lowerBound, fromLatestEvent);
    }

    public ObserveEventsOptions withFromLatestEvent(ObserveFromLatestEvent fromLatestEvent) {
        return new ObserveEventsOptions(recursive, lowerBound, fromLatestEvent);
    }
}
