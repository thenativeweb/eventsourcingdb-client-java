package io.thenativeweb.eventsourcingdb;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A client for an EventSourcingDB instance.
 *
 * <p>Every method that sends a request throws a {@link DbApiException} if the server reports an error, e.g. a
 * precondition that does not hold, and an {@link InvalidServerHeaderException} if the server is not an EventSourcingDB. If a request fails
 * on its way, e.g. because the server can not be reached, the method throws an {@link EventSourcingDbException}. If
 * nothing arrives from the server for the stall timeout, 30 seconds by default, it throws a
 * {@link ConnectionStalledException}; see {@link ClientOptions#withStallTimeout(Duration)}.
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
public final class Client implements AutoCloseable {
    private static final Pattern SURROUNDING_SLASHES = Pattern.compile("^/+|/+$");

    private final URI baseUrl;
    private final String apiToken;
    private final Duration stallTimeout;
    private final HttpClient httpClient;
    private final boolean isHttpClientOwned;
    private final JsonMapper dataMapper;

    // What close() needs to end what the client is doing.
    private final Object lock = new Object();
    private final Set<Runnable> openRequests = ConcurrentHashMap.newKeySet();
    private final Set<LineStream<?>> openStreams = ConcurrentHashMap.newKeySet();
    private volatile boolean isClosed;

    /**
     * Creates a client for the EventSourcingDB instance at the given URL.
     *
     * @param baseUrl the URL of the instance, e.g. {@code http://localhost:3000}
     * @param apiToken the API token to authenticate with
     */
    public Client(URI baseUrl, String apiToken) {
        this(baseUrl, apiToken, new ClientOptions());
    }

    /**
     * Creates a client for the EventSourcingDB instance at the given URL, with the given options.
     *
     * @param baseUrl the URL of the instance, e.g. {@code http://localhost:3000}
     * @param apiToken the API token to authenticate with
     * @param options the options, e.g. the HTTP client to send requests with
     */
    public Client(URI baseUrl, String apiToken, ClientOptions options) {
        this.baseUrl = baseUrl;
        this.apiToken = apiToken;
        stallTimeout = options.stallTimeout();

        // The HTTP client of the client's own has no timeouts: the stall
        // timeout bounds every request, from connecting to the end of its
        // response. A timeout for whole requests would end every stream that
        // observes events, and a connect timeout would end a request sooner
        // than the stall timeout says.
        var givenHttpClient = options.httpClient();
        httpClient = givenHttpClient != null ? givenHttpClient : HttpClient.newHttpClient();
        isHttpClientOwned = givenHttpClient == null;

        var givenDataMapper = options.dataMapper();
        dataMapper = givenDataMapper != null ? givenDataMapper : Json.MAPPER;
    }

    // Lets tests inspect the HTTP client that requests are sent with.
    HttpClient httpClient() {
        return httpClient;
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

        var result = Json.MAPPER.readTree(request("ping", request, V1ErrorCodes.ANY_REQUEST));
        if (!"io.eventsourcingdb.api.ping-received".equals(result.path("type").asString())) {
            throw new EventSourcingDbException("failed to ping");
        }
    }

    /**
     * Verifies the API token.
     *
     * @throws DbApiException with status 401 if the API token is invalid
     * @throws EventSourcingDbException if the instance does not confirm the API token
     */
    public void verifyApiToken() {
        var request = authorized("/api/v1/verify-api-token")
                .POST(HttpRequest.BodyPublishers.noBody())
                .build();

        var result = Json.MAPPER.readTree(request("verify API token", request, V1ErrorCodes.ANY_REQUEST));
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
     * <p>If writing fails after the request was sent, e.g. because the connection stalls, or because the thread is
     * interrupted, the server may have written the events nevertheless, so writing ends with an
     * {@link OutcomeUnknownException}, whose cause tells what failed.
     *
     * @param events the events to write
     * @param preconditions the preconditions that must hold, e.g. an {@link IsSubjectPristinePrecondition}
     * @return the written events, including the fields the server adds, such as their IDs
     * @throws DbApiException with the code {@link ErrorCode#PRECONDITION_VIOLATED} if a precondition does not hold, or
     *     {@link ErrorCode#EVENT_SCHEMA_VIOLATED} if an event does not match the schema of its type
     * @throws OutcomeUnknownException if writing failed in a way that leaves open whether the events were written
     */
    public List<Event> writeEvents(List<EventCandidate> events, List<Precondition> preconditions) {
        var body = Json.MAPPER.createObjectNode();

        var eventsNode = body.putArray("events");
        for (var event : events) {
            var eventNode = eventsNode.addObject();
            eventNode.put("source", event.source());
            eventNode.put("subject", event.subject());
            eventNode.put("type", event.type());
            eventNode.set("data", dataMapper.valueToTree(event.data()));

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

        return write(
                "write events",
                post("/api/v1/write-events", body),
                V1ErrorCodes.WRITE_EVENTS,
                response -> RawJson.readArray(response, "data").stream()
                        .map(cloudEvent -> Event.from(cloudEvent.tree(), cloudEvent.raw(), dataMapper))
                        .toList());
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

        return stream(
                "read events", post("/api/v1/read-events", body), "event", this::parseEvent, V1ErrorCodes.READ_EVENTS);
    }

    /**
     * Observes the events of a subject: first the events written so far, then each new one as it is written. Observing
     * does not end on its own, so close the stream to end it, which ends it with a
     * {@link java.util.concurrent.CancellationException}.
     *
     * <p>While there are no new events, the server sends a heartbeat every second. If neither an event nor a heartbeat
     * arrives for the stall timeout, 30 seconds by default, e.g. because a proxy keeps the connection open but no longer
     * passes anything on, the stream ends with a {@link ConnectionStalledException}, and the client closes the
     * connection. The time you spend on an event does not count.
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
                "observe events",
                post("/api/v1/observe-events", body),
                "event",
                this::parseEvent,
                V1ErrorCodes.ANY_REQUEST);
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
     * row nor a heartbeat arrives for the stall timeout, 30 seconds by default, the stream ends with a
     * {@link ConnectionStalledException}, and the client closes the connection. The time you spend on a row does not
     * count.
     *
     * @param query the query, e.g. {@code FROM e IN events PROJECT INTO e}
     * @return a stream of the rows, each of which matches the projection of the query
     */
    public Stream<JsonNode> runEventQlQuery(String query) {
        return runEventQlQuery(query, (payload, rawData) -> payload);
    }

    /**
     * Runs an EventQL query, and deserializes each row into the given type.
     *
     * <p>This works like {@link #runEventQlQuery(String)}, except that each row is deserialized with the data mapper
     * of the client, i.e. the one given by {@link ClientOptions#withDataMapper(JsonMapper)}, or the client's own. A
     * row that does not match the type ends the stream with the exception of the mapper.
     *
     * @param <T> the type to deserialize each row into
     * @param query the query, e.g. {@code FROM e IN events PROJECT INTO { id: e.id, type: e.type }}
     * @param rowType the type to deserialize each row into, e.g. a record that matches the projection of the query
     * @return a stream of the rows
     */
    public <T> Stream<T> runEventQlQuery(String query, Class<T> rowType) {
        return runEventQlQuery(query, (payload, rawData) -> dataMapper.treeToValue(payload, rowType));
    }

    private <T> Stream<T> runEventQlQuery(String query, LineStream.Parser<T> parser) {
        var body = Json.MAPPER.createObjectNode();
        body.put("query", query);

        return stream(
                "run EventQL query", post("/api/v1/run-eventql-query", body), "row", parser, V1ErrorCodes.ANY_REQUEST);
    }

    /**
     * Reads the subjects, i.e. the subjects that events were written to, and the subjects they are nested in.
     *
     * <p>Read recursively, the stream holds the subject itself, and every subject nested in it, e.g. {@code /books} and
     * {@code /books/42} for {@code /books}. Read not recursively, it holds the subject alone. Either way, it holds a
     * subject only if there are events within it, so it is empty for a subject that has no events, and none nested.
     *
     * @param subject the subject to read, e.g. {@code /books}, or {@code /} to read all subjects, if the options make
     *     the read recursive
     * @param options the options, i.e. whether to read the subjects nested in the subject, too
     * @return a stream of the subjects
     */
    public Stream<String> readSubjects(String subject, ReadSubjectsOptions options) {
        var body = Json.MAPPER.createObjectNode();
        body.put("baseSubject", subject);

        var subjects = stream(
                "read subjects",
                post("/api/v1/read-subjects", body),
                "subject",
                (payload, rawData) -> payload.path("subject").asString(),
                V1ErrorCodes.ANY_REQUEST);

        // The v1 API always reads recursively, so the subject alone is the
        // one subject of the stream that is the subject itself. The server
        // names it without the slashes around it, e.g. /books for /books/.
        if (options.recursive()) {
            return subjects;
        }

        var subjectItself = "/" + SURROUNDING_SLASHES.matcher(subject).replaceAll("");
        return subjects.filter(subjectItself::equals);
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
                (payload, rawData) -> EventType.fromV1(payload),
                V1ErrorCodes.ANY_REQUEST);
    }

    /**
     * Reads a single event type, including its schema, if one is registered.
     *
     * @param eventType the event type, e.g. {@code io.eventsourcingdb.library.book-acquired}
     * @return the event type
     * @throws DbApiException with the code {@link ErrorCode#EVENT_TYPE_UNKNOWN} if the event type does not exist
     */
    public EventType readEventType(String eventType) {
        var body = Json.MAPPER.createObjectNode();
        body.put("eventType", eventType);

        var response = request("read event type", post("/api/v1/read-event-type", body), V1ErrorCodes.READ_EVENT_TYPE);
        return EventType.fromV1(Json.MAPPER.readTree(response));
    }

    /**
     * Registers a JSON schema for an event type. From then on, the server rejects events of that type that do not match
     * the schema.
     *
     * @param eventType the event type, e.g. {@code io.eventsourcingdb.library.book-acquired}
     * @param schema the JSON schema, e.g. as nested maps and lists
     * @throws DbApiException with the code {@link ErrorCode#EVENT_SCHEMA_ALREADY_REGISTERED} if a schema is already
     *     registered for the event type
     * @throws OutcomeUnknownException if registering failed in a way that leaves open whether the schema was
     *     registered, e.g. because the connection stalled after the request was sent
     */
    public void registerEventSchema(String eventType, Map<String, ?> schema) {
        var body = Json.MAPPER.createObjectNode();
        body.put("eventType", eventType);
        body.set("schema", Json.MAPPER.valueToTree(schema));

        write(
                "register event schema",
                post("/api/v1/register-event-schema", body),
                V1ErrorCodes.REGISTER_EVENT_SCHEMA,
                response -> response);
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

    private String request(String action, HttpRequest request, CodeMapping codes) {
        return request(action, request, codes, response -> response, failure -> failure);
    }

    // Sends a write, and interprets the body of its response. A write that
    // fails after it was sent may have been stored nevertheless, unless the
    // failure leaves no doubt that it was not.
    private <T> T write(String action, HttpRequest request, CodeMapping codes, Function<String, T> interpretation) {
        return request(
                action,
                request,
                codes,
                interpretation,
                failure -> Outcomes.ofWrite(action, failure, Outcomes.V1_REFUSALS));
    }

    // Sends a request, and interprets the body of its response. Every failure
    // once the request was sent, the interpretation included, goes through
    // afterSending. The request is sent under the same lock under which
    // close() marks the client as closed, so that close() either sees the
    // request and ends it, or the request sees that the client is closed and
    // is not sent at all.
    private <T> T request(
            String action,
            HttpRequest request,
            CodeMapping codes,
            Function<String, T> interpretation,
            UnaryOperator<RuntimeException> afterSending) {
        Http.throwIfInterrupted(action);

        CompletableFuture<HttpResponse<InputStream>> response;
        Runnable abortion;
        synchronized (lock) {
            throwIfClosed();

            response = httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream());
            abortion = () -> Http.abort(response);
            openRequests.add(abortion);
        }

        try {
            return interpretation.apply(receive(action, response, codes));
        } catch (RuntimeException ex) {
            throw afterSending.apply(ex);
        } finally {
            openRequests.remove(abortion);
        }
    }

    private String receive(String action, CompletableFuture<HttpResponse<InputStream>> response, CodeMapping codes) {
        try {
            return Http.readBody(action, Http.await(action, response, stallTimeout, codes), stallTimeout);
        } catch (RuntimeException ex) {
            if (isClosed) {
                throw Http.cancellation(action, ex);
            }
            throw ex;
        }
    }

    private void throwIfClosed() {
        if (isClosed) {
            throw new IllegalStateException("client is closed");
        }
    }

    private <T> Stream<T> stream(
            String action, HttpRequest request, String itemType, LineStream.Parser<T> parser, CodeMapping codes) {

        synchronized (lock) {
            throwIfClosed();

            return LineStream.of(
                    action,
                    () -> httpClient.sendAsync(request, HttpResponse.BodyHandlers.ofInputStream()),
                    itemType,
                    parser,
                    stallTimeout,
                    codes,
                    openStreams);
        }
    }

    private Event parseEvent(JsonNode payload, @Nullable String rawData) {
        return Event.from(payload, rawData, dataMapper);
    }

    /**
     * Closes the client, and ends everything it is doing at once: requests that are running and streams that are open,
     * whether they have started or not, end with a {@link java.util.concurrent.CancellationException}, as if they had
     * been aborted. Every request afterwards throws an {@link IllegalStateException} without being sent.
     *
     * <p>The client closes the HTTP client it created itself, but not one that was given to it with
     * {@link ClientOptions#withHttpClient(HttpClient)}, since that one belongs to the caller. Closing a client that is
     * closed already does nothing.
     */
    @Override
    public void close() {
        synchronized (lock) {
            if (isClosed) {
                return;
            }
            isClosed = true;
        }

        openRequests.forEach(Runnable::run);
        openStreams.forEach(LineStream::close);

        if (isHttpClientOwned) {
            httpClient.shutdownNow();
        }
    }
}
