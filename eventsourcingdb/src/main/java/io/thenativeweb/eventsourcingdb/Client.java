package io.thenativeweb.eventsourcingdb;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * A client for an EventSourcingDB instance.
 *
 * <p>Every method that sends a request throws a {@link DbApiException} if the server answers with an unexpected HTTP
 * status code, and an {@link InvalidServerHeaderException} if the server is not an EventSourcingDB. If a request fails
 * on its way, e.g. because the server can not be reached, the method throws an {@link EventSourcingDbException}.
 *
 * <p>To abort a request, interrupt the thread that makes it. The request then ends with a
 * {@link java.util.concurrent.CancellationException}. If the thread is interrupted before the request is sent, the
 * request is not sent at all.
 *
 * <p>The methods that read a stream return a {@link Stream}, which sends its request only once you start to consume
 * it, and which holds a connection to the server until it ends. Close it once you are done, e.g. using a
 * {@code try}-with-resources statement. To abort a stream from somewhere else, e.g. from another thread, close it. It
 * then ends with a {@link java.util.concurrent.CancellationException}, so that an aborted read can be told apart from a
 * complete one.
 *
 * <p>A client is safe to use from multiple threads.
 */
public final class Client {
    private static final Duration HEARTBEAT_TIMEOUT = Duration.ofSeconds(30);

    private final URI baseUrl;
    private final String apiToken;
    private final Duration heartbeatTimeout;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    /**
     * Creates a client for the EventSourcingDB instance at the given URL.
     *
     * @param baseUrl the URL of the instance, e.g. {@code http://localhost:3000}
     * @param apiToken the API token to authenticate with
     */
    public Client(URI baseUrl, String apiToken) {
        this(baseUrl, apiToken, HEARTBEAT_TIMEOUT);
    }

    Client(URI baseUrl, String apiToken, Duration heartbeatTimeout) {
        this.baseUrl = baseUrl;
        this.apiToken = apiToken;
        this.heartbeatTimeout = heartbeatTimeout;
    }

    /**
     * Checks whether the instance is reachable. This does not require authentication, so it may succeed even if the API
     * token is invalid.
     *
     * @throws EventSourcingDbException if the instance is not reachable, or does not confirm the ping
     */
    public void ping() {
        var request =
                HttpRequest.newBuilder(baseUrl.resolve("/api/v1/ping")).GET().build();

        var result = readJson("ping", send("ping", request));
        if (!"io.eventsourcingdb.api.ping-received".equals(result.path("type").asString())) {
            throw new EventSourcingDbException("failed to ping");
        }
    }

    /**
     * Verifies the API token.
     *
     * @throws DbApiException with status code 401 if the API token is invalid
     * @throws EventSourcingDbException if the instance does not confirm the API token
     */
    public void verifyApiToken() {
        var request = authorized("/api/v1/verify-api-token")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        var result = readJson("verify API token", send("verify API token", request));
        if (!"io.eventsourcingdb.api.api-token-verified"
                .equals(result.path("type").asString())) {
            throw new EventSourcingDbException("failed to verify API token");
        }
    }

    /**
     * Writes the given events. This is the same as {@link #writeEvents(List, List)} without preconditions.
     *
     * @param events the events to write
     * @return the written events, including the fields the server adds, such as their IDs
     */
    public List<Event> writeEvents(List<EventCandidate> events) {
        return writeEvents(events, List.of());
    }

    /**
     * Writes the given events, provided that all preconditions hold. The events are written all or nothing.
     *
     * <p>If the thread is interrupted after the request was sent, the server may have written the events nevertheless,
     * so the {@link java.util.concurrent.CancellationException} does not tell whether they were written.
     *
     * @param events the events to write
     * @param preconditions the preconditions that must hold, e.g. an {@link IsSubjectPristinePrecondition}
     * @return the written events, including the fields the server adds, such as their IDs
     * @throws DbApiException with status code 409 if a precondition does not hold, or if an event does not match the
     *     schema of its type, in which case the reason tells which
     */
    public List<Event> writeEvents(List<EventCandidate> events, List<Precondition> preconditions) {
        var body = Json.MAPPER.createObjectNode();

        var eventsNode = body.putArray("events");
        for (var event : events) {
            var eventNode = eventsNode.addObject();
            eventNode.put("source", event.source());
            eventNode.put("subject", event.subject());
            eventNode.put("type", event.type());
            eventNode.set("data", Json.MAPPER.valueToTree(event.data()));

            if (event.traceParent() != null) {
                eventNode.put("traceparent", event.traceParent());
            }
            if (event.traceState() != null) {
                eventNode.put("tracestate", event.traceState());
            }
        }

        if (!preconditions.isEmpty()) {
            var preconditionsNode = body.putArray("preconditions");
            for (var precondition : preconditions) {
                preconditionsNode.add(Json.MAPPER.valueToTree(toRequest(precondition)));
            }
        }

        var response = send("write events", post("/api/v1/write-events", body));

        return RawJson.elements(Http.readBody("write events", response)).stream()
                .map(Event::parse)
                .toList();
    }

    private static Map<String, Object> toRequest(Precondition precondition) {
        return switch (precondition) {
            case IsSubjectPristinePrecondition pristinePrecondition ->
                Map.of("type", "isSubjectPristine", "payload", Map.of("subject", pristinePrecondition.subject()));
            case IsSubjectPopulatedPrecondition populatedPrecondition ->
                Map.of("type", "isSubjectPopulated", "payload", Map.of("subject", populatedPrecondition.subject()));
            case IsSubjectOnEventIdPrecondition onEventIdPrecondition ->
                Map.of(
                        "type",
                        "isSubjectOnEventId",
                        "payload",
                        Map.of("subject", onEventIdPrecondition.subject(), "eventId", onEventIdPrecondition.eventId()));
            case IsEventQlQueryTruePrecondition queryPrecondition ->
                Map.of("type", "isEventQlQueryTrue", "payload", Map.of("query", queryPrecondition.query()));
        };
    }

    /**
     * Reads the events of a subject.
     *
     * <p>The returned stream sends its request once you start to consume it, and holds a connection to the server until
     * it ends, so close it once you are done.
     *
     * @param subject the subject to read the events of, e.g. {@code /books/42}, or {@code /} to read all events, if the
     *     options make the read recursive
     * @param options the options, e.g. whether to read the events of nested subjects, too
     * @return a stream of the events, in chronological order unless the options say otherwise
     */
    public Stream<Event> readEvents(String subject, ReadEventsOptions options) {
        var body = Json.MAPPER.createObjectNode();
        body.put("subject", subject);

        var optionsNode = body.putObject("options");
        optionsNode.put("recursive", options.recursive());
        if (options.order() != null) {
            optionsNode.put("order", Json.value(options.order()));
        }
        if (options.lowerBound() != null) {
            optionsNode.set("lowerBound", toRequest(options.lowerBound()));
        }
        if (options.upperBound() != null) {
            optionsNode.set("upperBound", toRequest(options.upperBound()));
        }
        if (options.fromLatestEvent() != null) {
            var fromLatestEvent = options.fromLatestEvent();
            optionsNode.set(
                    "fromLatestEvent",
                    toRequest(fromLatestEvent.subject(), fromLatestEvent.type(), fromLatestEvent.ifEventIsMissing()));
        }

        return stream("read events", post("/api/v1/read-events", body), "event", Client::parseEvent, null);
    }

    /**
     * Observes the events of a subject: first the events written so far, then each new one as it is written. Observing
     * does not end on its own, so close the stream to end it, which ends it with a
     * {@link java.util.concurrent.CancellationException}.
     *
     * <p>While there are no new events, the server sends a heartbeat every second. If neither an event nor a heartbeat
     * arrives for 30 seconds, e.g. because a proxy keeps the connection open but no longer passes anything on, the stream
     * ends with a {@link HeartbeatTimeoutException}, and the client closes the connection. The time you spend on an event
     * does not count.
     *
     * @param subject the subject to observe the events of, e.g. {@code /books/42}, or {@code /} to observe all events, if
     *     the options make observing recursive
     * @param options the options, e.g. whether to observe the events of nested subjects, too
     * @return a stream of the events
     */
    public Stream<Event> observeEvents(String subject, ObserveEventsOptions options) {
        var body = Json.MAPPER.createObjectNode();
        body.put("subject", subject);

        var optionsNode = body.putObject("options");
        optionsNode.put("recursive", options.recursive());
        if (options.lowerBound() != null) {
            optionsNode.set("lowerBound", toRequest(options.lowerBound()));
        }
        if (options.fromLatestEvent() != null) {
            var fromLatestEvent = options.fromLatestEvent();
            optionsNode.set(
                    "fromLatestEvent",
                    toRequest(fromLatestEvent.subject(), fromLatestEvent.type(), fromLatestEvent.ifEventIsMissing()));
        }

        return stream(
                "observe events", post("/api/v1/observe-events", body), "event", Client::parseEvent, heartbeatTimeout);
    }

    private static ObjectNode toRequest(Bound bound) {
        var node = Json.MAPPER.createObjectNode();
        node.put("id", bound.id());
        node.put("type", Json.value(bound.type()));
        return node;
    }

    private static ObjectNode toRequest(String subject, String type, Enum<?> ifEventIsMissing) {
        var node = Json.MAPPER.createObjectNode();
        node.put("subject", subject);
        node.put("type", type);
        node.put("ifEventIsMissing", Json.value(ifEventIsMissing));
        return node;
    }

    /**
     * Runs an EventQL query.
     *
     * <p>While the query runs, the server sends a heartbeat every second whenever there is no row to send. If neither a
     * row nor a heartbeat arrives for 30 seconds, the stream ends with a {@link HeartbeatTimeoutException}, and the client
     * closes the connection. The time you spend on a row does not count.
     *
     * @param query the query, e.g. {@code FROM e IN events PROJECT INTO e}
     * @return a stream of the rows, each of which matches the projection of the query
     */
    public Stream<JsonNode> runEventQlQuery(String query) {
        var body = Json.MAPPER.createObjectNode();
        body.put("query", query);

        return stream(
                "run EventQL query",
                post("/api/v1/run-eventql-query", body),
                "row",
                (payload, line) -> payload,
                heartbeatTimeout);
    }

    /**
     * Lists the subjects within the given base subject. The list includes the base subject itself, unless there are no
     * events within it.
     *
     * @param baseSubject the subject to list the subjects within, e.g. {@code /} for all subjects
     * @return a stream of the subjects
     */
    public Stream<String> readSubjects(String baseSubject) {
        var body = Json.MAPPER.createObjectNode();
        body.put("baseSubject", baseSubject);

        return stream(
                "read subjects",
                post("/api/v1/read-subjects", body),
                "subject",
                (payload, line) -> payload.path("subject").asString(),
                null);
    }

    /**
     * Lists all event types, i.e. the types of the events written so far, and the types an event schema is registered
     * for.
     *
     * @return a stream of the event types
     */
    public Stream<EventType> readEventTypes() {
        return stream(
                "read event types",
                post("/api/v1/read-event-types", Json.MAPPER.createObjectNode()),
                "eventType",
                (payload, line) -> EventType.from(payload),
                null);
    }

    /**
     * Reads a single event type, including its schema, if one is registered.
     *
     * @param eventType the event type, e.g. {@code io.eventsourcingdb.library.book-acquired}
     * @return the event type
     * @throws DbApiException with status code 404 if the event type does not exist, or 400 if it is malformed
     */
    public EventType readEventType(String eventType) {
        var body = Json.MAPPER.createObjectNode();
        body.put("eventType", eventType);

        var response = send("read event type", post("/api/v1/read-event-type", body));
        return EventType.from(readJson("read event type", response));
    }

    /**
     * Registers a JSON schema for an event type. From then on, the server rejects events of that type that do not match
     * the schema.
     *
     * @param eventType the event type, e.g. {@code io.eventsourcingdb.library.book-acquired}
     * @param schema the JSON schema, e.g. as nested maps and lists
     * @throws DbApiException with status code 409 if a schema is already registered for the event type
     */
    public void registerEventSchema(String eventType, Map<String, ?> schema) {
        var body = Json.MAPPER.createObjectNode();
        body.put("eventType", eventType);
        body.set("schema", Json.MAPPER.valueToTree(schema));

        var response = send("register event schema", post("/api/v1/register-event-schema", body));
        Http.readBody("register event schema", response);
    }

    private HttpRequest post(String path, JsonNode body) {
        return authorized(path)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.MAPPER.writeValueAsString(body)))
                .build();
    }

    private HttpRequest.Builder authorized(String path) {
        return HttpRequest.newBuilder(baseUrl.resolve(path)).header("Authorization", "Bearer " + apiToken);
    }

    private HttpResponse<InputStream> send(String action, HttpRequest request) {
        Http.throwIfInterrupted(action);

        return Http.await(action, httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()));
    }

    private <T> Stream<T> stream(
            String action,
            HttpRequest request,
            String itemType,
            LineStream.Parser<T> parser,
            Duration heartbeatTimeout) {
        return LineStream.of(
                action,
                () -> httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()),
                itemType,
                parser,
                heartbeatTimeout);
    }

    private static Event parseEvent(JsonNode payload, String line) {
        return Event.parse(RawJson.property(line, "payload"));
    }

    private static JsonNode readJson(String action, HttpResponse<InputStream> response) {
        return Json.MAPPER.readTree(Http.readBody(action, response));
    }
}
