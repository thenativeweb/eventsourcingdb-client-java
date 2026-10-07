package io.eventsourcingdb;

public record ReadEventsOptions(
        boolean recursive, Order order, Bound lowerBound, Bound upperBound, ReadFromLatestEvent fromLatestEvent) {
    public ReadEventsOptions(boolean recursive) {
        this(recursive, null, null, null, null);
    }

    public ReadEventsOptions withOrder(Order order) {
        return new ReadEventsOptions(recursive, order, lowerBound, upperBound, fromLatestEvent);
    }

    public ReadEventsOptions withLowerBound(Bound lowerBound) {
        return new ReadEventsOptions(recursive, order, lowerBound, upperBound, fromLatestEvent);
    }

    public ReadEventsOptions withUpperBound(Bound upperBound) {
        return new ReadEventsOptions(recursive, order, lowerBound, upperBound, fromLatestEvent);
    }

    public ReadEventsOptions withFromLatestEvent(ReadFromLatestEvent fromLatestEvent) {
        return new ReadEventsOptions(recursive, order, lowerBound, upperBound, fromLatestEvent);
    }
}
