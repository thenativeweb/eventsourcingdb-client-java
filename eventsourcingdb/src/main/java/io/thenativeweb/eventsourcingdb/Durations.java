package io.thenativeweb.eventsourcingdb;

import java.time.Duration;

// Durations describes a duration in words for the messages of exceptions,
// e.g. "30 seconds", which reads better than "PT30S". A duration that is no
// whole number of seconds is described in milliseconds, which is as fine as
// a stall timeout gets in practice.
final class Durations {
    private Durations() {}

    static String describe(Duration duration) {
        if (duration.toNanosPart() == 0) {
            return count(duration.toSeconds(), "second");
        }

        return count(duration.toMillis(), "millisecond");
    }

    private static String count(long amount, String unit) {
        return amount + " " + unit + (amount == 1 ? "" : "s");
    }
}
