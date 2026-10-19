package io.thenativeweb.eventsourcingdb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.core.JacksonException;

@Timeout(10)
class StreamTest {
    // A stream along with a line that holds one of its items.
    record ItemStream(String name, Function<Client, Stream<?>> open, Function<ApiVersion, String> item) {
        @Override
        public String toString() {
            return name;
        }
    }

    // The streams that end once they are complete, which observing never is.
    static List<ItemStream> finiteStreams() {
        return List.of(
                new ItemStream(
                        "read events", client -> client.readEvents("/", new ReadEventsOptions(true)), Lines::event),
                new ItemStream(
                        "run EventQL query",
                        client -> client.runEventQlQuery("FROM e IN events PROJECT INTO e"),
                        Lines::row),
                new ItemStream(
                        "read subjects",
                        client -> client.readSubjects("/", new ReadSubjectsOptions(true)),
                        Lines::subject),
                new ItemStream("read event types", Client::readEventTypes, Lines::eventType));
    }

    static Stream<Arguments> finiteStreamsOnEveryApi() {
        return Stream.of(ApiVersion.values())
                .flatMap(api -> finiteStreams().stream().map(stream -> Arguments.of(api, stream)));
    }

    private static Stream<String> readSubjects(FakeServer server) {
        return new Client(server.url(), "secret").readSubjects("/", new ReadSubjectsOptions(true));
    }

    @ParameterizedTest
    @EnumSource(ApiVersion.class)
    void readsAnEventExactlyAsTheServerSentIt(ApiVersion api) {
        try (var server = FakeServer.streaming(api, Lines.event(api));
                var events = new Client(server.url(), "secret").readEvents("/a", new ReadEventsOptions(false))) {
            var event = events.toList().getFirst();

            assertEquals(Lines.SPECIAL_CHARACTERS, event.data().path("z").asString());
            assertEquals(Optional.of("00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01"), event.traceParent());
            event.verifyHash();
        }
    }

    // The v2 API sends a trace context that an event does not have as null,
    // rather than leaving it out, as the v1 API does.
    @Test
    void readsAnEventOfTheV2ApiWithoutATraceContext() {
        try (var server = FakeServer.streaming(ApiVersion.V2, Lines.EVENT_OF_THE_V2_API);
                var events = new Client(server.url(), "secret").readEvents("/a", new ReadEventsOptions(false))) {
            var event = events.toList().getFirst();

            assertEquals(Optional.empty(), event.traceParent());
            assertEquals(Optional.empty(), event.traceState());
            event.verifyHash();
        }
    }

    @ParameterizedTest
    @EnumSource(ApiVersion.class)
    void skipsBlankLinesAndHeartbeats(ApiVersion api) {
        try (var server = FakeServer.streaming(api, "", Lines.heartbeat(api), Lines.subject(api));
                var subjects = readSubjects(server)) {
            assertEquals(List.of("/"), subjects.toList());
        }
    }

    @ParameterizedTest
    @EnumSource(ApiVersion.class)
    void readsTheValuesThatAQueryProjects(ApiVersion api) {
        try (var server = FakeServer.streaming(api, Lines.row(api));
                var rows = new Client(server.url(), "secret").runEventQlQuery("FROM e IN events PROJECT INTO 23")) {
            assertEquals(List.of(23), rows.map(row -> row.asInt()).toList());
        }
    }

    @Test
    void endsWithTheErrorThatTheV1ApiSends() {
        try (var server =
                        FakeServer.streaming(ApiVersion.V1, Lines.subject(ApiVersion.V1), Lines.error(ApiVersion.V1));
                var subjects = readSubjects(server)) {
            var subjectsRead = new ArrayList<String>();

            var exception = assertThrows(DbApiException.class, () -> subjects.forEach(subjectsRead::add));

            assertEquals(List.of("/"), subjectsRead);
            assertEquals("failed to read subjects, got error: something went wrong", exception.getMessage());
            assertEquals(200, exception.status());
            assertEquals("", exception.code());
            assertEquals("something went wrong", exception.description());
            assertEquals(Json.MAPPER.createObjectNode(), exception.details());
        }
    }

    @Test
    void endsWithTheErrorThatTheV2ApiSends() {
        try (var server =
                        FakeServer.streaming(ApiVersion.V2, Lines.subject(ApiVersion.V2), Lines.error(ApiVersion.V2));
                var subjects = readSubjects(server)) {
            var subjectsRead = new ArrayList<String>();

            var exception = assertThrows(DbApiException.class, () -> subjects.forEach(subjectsRead::add));

            assertEquals(List.of("/"), subjectsRead);
            assertEquals("failed to read subjects, got error: something went wrong", exception.getMessage());
            assertEquals(200, exception.status());
            assertEquals(ErrorCode.STORE_FAILED, exception.code());
            assertEquals("something went wrong", exception.description());
            assertEquals(0, exception.details().path("event").asInt(-1));
        }
    }

    // The v2 API always sends the details of an error, as an object, which
    // the client stands in for if the server sends none nevertheless.
    @Test
    void reportsAnErrorWithoutDetailsWithDetailsThatAreEmpty() {
        var error =
                ApiVersion.V2.item("error", "{\"code\":\"" + ErrorCode.STORE_FAILED + "\",\"description\":\"failed\"}");

        try (var server = FakeServer.streaming(ApiVersion.V2, error);
                var subjects = readSubjects(server)) {
            var exception = assertThrows(DbApiException.class, subjects::toList);

            assertEquals(Json.MAPPER.createObjectNode(), exception.details());
        }
    }

    @ParameterizedTest
    @MethodSource("finiteStreamsOnEveryApi")
    void endsAStreamThatIsComplete(ApiVersion api, ItemStream stream) {
        try (var server = FakeServer.streaming(api, stream.item().apply(api));
                var items = stream.open().apply(new Client(server.url(), "secret"))) {
            assertEquals(1, items.count());
        }
    }

    // The server ends a stream of the v2 API with an end item, so that one it
    // aborts can be told apart.
    @ParameterizedTest
    @MethodSource("finiteStreams")
    void failsIfAStreamOfTheV2ApiEndsWithoutItsEndItem(ItemStream stream) {
        try (var server = FakeServer.start(ApiVersion.V2, (exchange, fakeServer) -> {
                    FakeServer.startStream(exchange);
                    FakeServer.writeLine(exchange, stream.item().apply(ApiVersion.V2));
                });
                var items = stream.open().apply(new Client(server.url(), "secret"))) {
            var itemsRead = new ArrayList<Object>();

            var exception = assertThrows(EventSourcingDbException.class, () -> items.forEach(itemsRead::add));

            assertEquals(1, itemsRead.size());
            assertEquals("failed to " + stream.name() + ", the stream ended unexpectedly", exception.getMessage());
        }
    }

    @Test
    void readsNothingAfterTheEndItemOfAStreamOfTheV2Api() {
        try (var server = FakeServer.start(ApiVersion.V2, (exchange, fakeServer) -> {
                    FakeServer.startStream(exchange);
                    FakeServer.writeLine(exchange, Lines.subject(ApiVersion.V2));
                    FakeServer.writeLine(exchange, ApiVersion.V2.end());
                    FakeServer.writeLine(exchange, Lines.subject(ApiVersion.V2));
                    fakeServer.hang();
                });
                var subjects = readSubjects(server)) {
            assertEquals(List.of("/"), subjects.toList());
        }
    }

    // Observing never ends, so the server ends it only after a failure that
    // it could not report as an error.
    @ParameterizedTest
    @EnumSource(ApiVersion.class)
    void failsIfObservingEnds(ApiVersion api) {
        try (var server = FakeServer.start(api, (exchange, fakeServer) -> {
                    FakeServer.startStream(exchange);
                    FakeServer.writeLine(exchange, Lines.event(api));
                });
                var events = new Client(server.url(), "secret").observeEvents("/", new ObserveEventsOptions(true))) {
            var eventsObserved = new ArrayList<Event>();

            var exception = assertThrows(EventSourcingDbException.class, () -> events.forEach(eventsObserved::add));

            assertEquals(1, eventsObserved.size());
            assertEquals("failed to observe events, the stream ended unexpectedly", exception.getMessage());
        }
    }

    @Test
    void failsOnAnEndItemInAStreamOfTheV1Api() {
        try (var server = FakeServer.streaming(ApiVersion.V1, ApiVersion.V1.item("end", "{}"));
                var subjects = readSubjects(server)) {
            var exception = assertThrows(EventSourcingDbException.class, subjects::toList);

            assertEquals("failed to handle unsupported line type: end", exception.getMessage());
        }
    }

    @Test
    void failsOnAnEndItemWhileObserving() {
        try (var server = FakeServer.streaming(ApiVersion.V2, ApiVersion.V2.end());
                var events = new Client(server.url(), "secret").observeEvents("/", new ObserveEventsOptions(true))) {
            var exception = assertThrows(EventSourcingDbException.class, events::toList);

            assertEquals("failed to handle unsupported line type: end", exception.getMessage());
        }
    }

    @ParameterizedTest
    @EnumSource(ApiVersion.class)
    void failsOnAnUnsupportedLineType(ApiVersion api) {
        try (var server = FakeServer.streaming(api, "{\"type\":\"something-else\"}");
                var subjects = readSubjects(server)) {
            var exception = assertThrows(EventSourcingDbException.class, subjects::toList);

            assertEquals("failed to handle unsupported line type: something-else", exception.getMessage());
        }
    }

    @ParameterizedTest
    @EnumSource(ApiVersion.class)
    void ignoresPropertiesOfALineItDoesNotKnow(ApiVersion api) {
        var subject = Lines.subject(api);

        try (var server = FakeServer.streaming(api, subject.substring(0, subject.length() - 1) + ",\"extra\":1}");
                var subjects = readSubjects(server)) {
            assertEquals(List.of("/"), subjects.toList());
        }
    }

    @ParameterizedTest
    @EnumSource(ApiVersion.class)
    void failsOnALineThatIsNoObject(ApiVersion api) {
        try (var server = FakeServer.streaming(api, "[]");
                var subjects = readSubjects(server)) {
            var exception = assertThrows(EventSourcingDbException.class, subjects::toList);

            assertEquals("failed to handle unsupported line type: ", exception.getMessage());
        }
    }

    @ParameterizedTest
    @EnumSource(ApiVersion.class)
    void failsOnAnEventWithoutData(ApiVersion api) {
        try (var server = FakeServer.streaming(api, api.item("event", "{\"id\":\"0\"}"));
                var events = new Client(server.url(), "secret").readEvents("/", new ReadEventsOptions(true))) {
            var exception = assertThrows(EventSourcingDbException.class, events::toList);

            assertEquals("failed to parse JSON, property 'data' is missing", exception.getMessage());
        }
    }

    @ParameterizedTest
    @EnumSource(ApiVersion.class)
    void failsOnAMalformedLine(ApiVersion api) {
        try (var server = FakeServer.streaming(api, "{\"type\":");
                var subjects = readSubjects(server)) {
            assertThrows(JacksonException.class, subjects::toList);
        }
    }

    @ParameterizedTest
    @EnumSource(ApiVersion.class)
    void failsIfTheConnectionBreaksInTheMiddle(ApiVersion api) {
        try (var server = FakeServer.start(api, (exchange, fakeServer) -> {
                    exchange.getResponseHeaders().set("Server", FakeServer.SERVER_HEADER);
                    exchange.sendResponseHeaders(200, 1000);
                    FakeServer.writeLine(exchange, Lines.subject(api));
                });
                var subjects = readSubjects(server)) {
            var subjectsRead = new ArrayList<String>();

            var exception = assertThrows(EventSourcingDbException.class, () -> subjects.forEach(subjectsRead::add));

            assertEquals(List.of("/"), subjectsRead);
            assertEquals("failed to read subjects", exception.getMessage());
            assertInstanceOf(IOException.class, exception.getCause());
        }
    }
}
