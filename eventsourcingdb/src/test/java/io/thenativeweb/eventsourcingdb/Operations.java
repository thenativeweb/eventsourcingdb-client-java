package io.thenativeweb.eventsourcingdb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;

// Operations lists every request the client can make, so that tests can check
// a behavior that all of them share.
final class Operations {
    // The operations that write, whose failures may leave open whether the
    // server stored the write.
    private static final Set<String> WRITES = Set.of("write events", "register event schema");

    private Operations() {}

    // A stream is opened by open, and call consumes it completely. A request
    // has no open, and call makes it.
    record Operation(String action, Function<Client, Stream<?>> open, Consumer<Client> call) {
        static Operation request(String action, Consumer<Client> call) {
            return new Operation(action, null, call);
        }

        static Operation stream(String action, Function<Client, Stream<?>> open) {
            return new Operation(action, open, client -> {
                try (var items = open.apply(client)) {
                    items.forEach(item -> {});
                }
            });
        }

        boolean isStream() {
            return open != null;
        }

        boolean isWrite() {
            return WRITES.contains(action);
        }

        @Override
        public String toString() {
            return action;
        }
    }

    static List<Operation> all() {
        return List.of(
                Operation.request("ping", Client::ping),
                Operation.request("verify API token", Client::verifyApiToken),
                Operation.request(
                        "write events",
                        client -> client.writeEvents(List.of(new EventCandidate(
                                "https://www.eventsourcingdb.io", "/test", "io.eventsourcingdb.test", Map.of())))),
                Operation.stream("read events", client -> client.readEvents("/", new ReadEventsOptions(true))),
                Operation.stream("observe events", client -> client.observeEvents("/", new ObserveEventsOptions(true))),
                Operation.stream(
                        "run EventQL query", client -> client.runEventQlQuery("FROM e IN events PROJECT INTO e")),
                Operation.stream("read subjects", client -> client.readSubjects("/", new ReadSubjectsOptions(true))),
                Operation.stream("read event types", Client::readEventTypes),
                Operation.request("read event type", client -> client.readEventType("io.eventsourcingdb.test")),
                Operation.request(
                        "register event schema",
                        client -> client.registerEventSchema("io.eventsourcingdb.test", Map.of("type", "object"))));
    }

    static List<Operation> authenticated() {
        return all().stream()
                .filter(operation -> !operation.action().equals("ping"))
                .toList();
    }

    static List<Operation> streams() {
        return all().stream().filter(Operation::isStream).toList();
    }

    static List<Operation> writes() {
        return all().stream().filter(Operation::isWrite).toList();
    }

    // Asserts that the operation fails with the given type of exception, for
    // a failure after which a write may have been stored nevertheless: a
    // write then fails with an OutcomeUnknownException whose cause is of the
    // given type. Returns the exception of the given type.
    static <T extends Throwable> T assertFailsAfterSending(Class<T> type, Operation operation, Client client) {
        return assertFailedAfterSending(
                type,
                operation,
                assertThrows(Throwable.class, () -> operation.call().accept(client)));
    }

    // Asserts what assertFailsAfterSending does, for an operation that threw
    // the given exception.
    static <T extends Throwable> T assertFailedAfterSending(Class<T> type, Operation operation, Throwable thrown) {
        if (operation.isWrite()) {
            var outcomeUnknown = assertInstanceOf(OutcomeUnknownException.class, thrown);
            assertEquals("failed to " + operation.action() + ", the outcome is unknown", outcomeUnknown.getMessage());
            return assertInstanceOf(type, outcomeUnknown.getCause());
        }

        return assertInstanceOf(type, thrown);
    }
}
