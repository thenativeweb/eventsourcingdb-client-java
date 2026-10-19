package io.thenativeweb.eventsourcingdb;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.thenativeweb.eventsourcingdb.FakeServer.Request;
import io.thenativeweb.eventsourcingdb.Operations.Operation;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

// Each operation goes to the route of the API that the server offers, with
// the method and the body that the API takes. The v2 API reads with QUERY,
// and refuses fields it does not know and null, so no body holds them.
class RoutesTest {
    private static Operation operation(String action) {
        return Operations.all().stream()
                .filter(operation -> operation.action().equals(action))
                .findFirst()
                .orElseThrow();
    }

    static Stream<Arguments> routes() {
        return Stream.of(
                Arguments.of(ApiVersion.V1, operation("ping"), new Request("GET", "/api/v1/ping", "")),
                Arguments.of(
                        ApiVersion.V1,
                        operation("verify API token"),
                        new Request("POST", "/api/v1/verify-api-token", "")),
                Arguments.of(
                        ApiVersion.V1,
                        operation("write events"),
                        new Request(
                                "POST",
                                "/api/v1/write-events",
                                "{\"events\":[{\"source\":\"https://www.eventsourcingdb.io\",\"subject\":\"/test\","
                                        + "\"type\":\"io.eventsourcingdb.test\",\"data\":{}}]}")),
                Arguments.of(
                        ApiVersion.V1,
                        operation("read events"),
                        new Request(
                                "POST", "/api/v1/read-events", "{\"subject\":\"/\",\"options\":{\"recursive\":true}}")),
                Arguments.of(
                        ApiVersion.V1,
                        operation("observe events"),
                        new Request(
                                "POST",
                                "/api/v1/observe-events",
                                "{\"subject\":\"/\",\"options\":{\"recursive\":true}}")),
                Arguments.of(
                        ApiVersion.V1,
                        operation("run EventQL query"),
                        new Request(
                                "POST",
                                "/api/v1/run-eventql-query",
                                "{\"query\":\"FROM e IN events PROJECT INTO e\"}")),
                Arguments.of(
                        ApiVersion.V1,
                        operation("read subjects"),
                        new Request("POST", "/api/v1/read-subjects", "{\"baseSubject\":\"/\"}")),
                Arguments.of(
                        ApiVersion.V1,
                        operation("read event types"),
                        new Request("POST", "/api/v1/read-event-types", "{}")),
                Arguments.of(
                        ApiVersion.V1,
                        operation("read event type"),
                        new Request("POST", "/api/v1/read-event-type", "{\"eventType\":\"io.eventsourcingdb.test\"}")),
                Arguments.of(
                        ApiVersion.V1,
                        operation("register event schema"),
                        new Request(
                                "POST",
                                "/api/v1/register-event-schema",
                                "{\"eventType\":\"io.eventsourcingdb.test\",\"schema\":{\"type\":\"object\"}}")),
                Arguments.of(ApiVersion.V2, operation("ping"), new Request("GET", "/api/v2/system/ping", "")),
                Arguments.of(
                        ApiVersion.V2,
                        operation("verify API token"),
                        new Request("POST", "/api/v2/system/verify-token", "")),
                Arguments.of(
                        ApiVersion.V2,
                        operation("write events"),
                        new Request(
                                "POST",
                                "/api/v2/data/write-events",
                                "{\"events\":[{\"source\":\"https://www.eventsourcingdb.io\",\"subject\":\"/test\","
                                        + "\"type\":\"io.eventsourcingdb.test\",\"data\":{}}]}")),
                Arguments.of(
                        ApiVersion.V2,
                        operation("read events"),
                        new Request(
                                "QUERY",
                                "/api/v2/data/read-events",
                                "{\"subject\":\"/\",\"options\":{\"recursive\":true}}")),
                Arguments.of(
                        ApiVersion.V2,
                        operation("observe events"),
                        new Request(
                                "QUERY",
                                "/api/v2/data/observe-events",
                                "{\"subject\":\"/\",\"options\":{\"recursive\":true}}")),
                Arguments.of(
                        ApiVersion.V2,
                        operation("run EventQL query"),
                        new Request(
                                "QUERY",
                                "/api/v2/data/run-eventql-query",
                                "{\"query\":\"FROM e IN events PROJECT INTO e\"}")),
                Arguments.of(
                        ApiVersion.V2,
                        operation("read subjects"),
                        new Request(
                                "QUERY",
                                "/api/v2/data/read-subjects",
                                "{\"subject\":\"/\",\"options\":{\"recursive\":true}}")),
                Arguments.of(
                        ApiVersion.V2,
                        operation("read event types"),
                        new Request("QUERY", "/api/v2/data/read-event-types", "")),
                Arguments.of(
                        ApiVersion.V2,
                        operation("read event type"),
                        new Request("QUERY", "/api/v2/data/read-event-type", "{\"type\":\"io.eventsourcingdb.test\"}")),
                Arguments.of(
                        ApiVersion.V2,
                        operation("register event schema"),
                        new Request(
                                "POST",
                                "/api/v2/data/register-event-schema",
                                "{\"type\":\"io.eventsourcingdb.test\",\"schema\":{\"type\":\"object\"}}")));
    }

    // Sends the request of the operation to a server that refuses it, and
    // returns it as the server received it.
    private static Request requestOf(ApiVersion api, Operation operation) {
        try (var server = FakeServer.respondingWith(api, 500, "refused")) {
            try {
                operation.call().accept(new Client(server.url(), "secret"));
            } catch (EventSourcingDbException ex) {
                // The server refuses every request, so that its request is all
                // that the test looks at.
            }

            return server.requests().getLast();
        }
    }

    @ParameterizedTest
    @MethodSource("routes")
    void sendsEveryOperationToTheRouteOfTheApi(ApiVersion api, Operation operation, Request request) {
        assertEquals(request, requestOf(api, operation));
    }

    @Test
    void leavesOutTheOptionsOfReadingThatAreNotSetRatherThanSendingNull() {
        var request = requestOf(
                ApiVersion.V2,
                Operation.stream(
                        "read events",
                        client -> client.readEvents(
                                "/",
                                new ReadEventsOptions(false)
                                        .withOrder(Order.ANTICHRONOLOGICAL)
                                        .withUpperBound(new Bound("7", BoundType.EXCLUSIVE)))));

        assertEquals(
                "{\"subject\":\"/\",\"options\":{\"recursive\":false,\"order\":\"antichronological\","
                        + "\"upperBound\":{\"id\":\"7\",\"type\":\"exclusive\"}}}",
                request.body());
    }

    @Test
    void sendsATraceContextOnlyIfTheEventHasOne() {
        var request = requestOf(
                ApiVersion.V2,
                Operation.request(
                        "write events",
                        client -> client.writeEvents(List.of(
                                new EventCandidate("https://x", "/a", "io.x.y", Map.of(), "00-1-2-01", null)))));

        assertEquals(
                "{\"events\":[{\"source\":\"https://x\",\"subject\":\"/a\",\"type\":\"io.x.y\",\"data\":{},"
                        + "\"traceparent\":\"00-1-2-01\"}]}",
                request.body());
    }
}
