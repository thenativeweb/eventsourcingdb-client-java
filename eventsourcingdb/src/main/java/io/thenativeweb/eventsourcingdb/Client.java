package io.thenativeweb.eventsourcingdb;

import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * A client for an EventSourcingDB instance.
 *
 * <p>The client talks to every supported version of EventSourcingDB, whether it offers the v2 API or not. Creating a
 * client sends no request. The first request of a client asks the server whether it offers the v2 API, and the client
 * uses the v2 API from then on if it does, and the v1 API otherwise. If asking fails, e.g. because the server can not
 * be reached, that first request fails, and the next request asks again. If the server answers a path of the v2 API
 * with 404 or 405, e.g. after it was rolled back to a version without it, the next request asks again, too. The v2 API
 * reads with the HTTP method {@code QUERY}, so a proxy in front of the database has to let it through.
 *
 * <p>Every method that sends a request throws a {@link DbApiException} if the server reports an error, e.g. a
 * precondition that does not hold, and an {@link InvalidServerHeaderException} if the server is not an
 * EventSourcingDB. If a request fails on its way, e.g. because the server can not be reached, the method throws an
 * {@link EventSourcingDbException}. If nothing arrives from the server for the stall timeout, 30 seconds by default, it
 * throws a {@link ConnectionStalledException}; see {@link ClientOptions#withStallTimeout(Duration)}. A write that fails
 * in a way that leaves open whether it was stored throws an {@link OutcomeUnknownException}.
 *
 * <p>To abort a request, interrupt the thread that makes it. The request then ends with a
 * {@link java.util.concurrent.CancellationException}. If the thread is interrupted before the client starts to send
 * the request, the request is not sent at all. Once the client has started to send a write, even if it is still
 * connecting to the server, the write ends with an {@link OutcomeUnknownException} instead, whose cause is the
 * {@link java.util.concurrent.CancellationException}.
 *
 * <p>The methods that read a stream return a {@link Stream}, which sends its request only once you start to consume
 * it, and which holds a connection to the server until it ends. Close it once you are done, e.g. using a
 * {@code try}-with-resources statement. To abort a stream from somewhere else, e.g. from another thread, close it. It
 * then ends with a {@link java.util.concurrent.CancellationException}, so that an aborted read can be told apart from a
 * complete one. A stream that the server ends before it is complete ends with an {@link EventSourcingDbException}.
 *
 * <p>A client is safe to use from multiple threads.
 */
public final class Client implements AutoCloseable {
    private final JsonMapper dataMapper;
    private final Requests requests;
    private final TransportChooser transports;

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
        this(baseUrl, apiToken, options, WriteRetries.OF_THE_JDK);
    }

    // Lets tests tell whether the JDK sends writes again.
    Client(URI baseUrl, String apiToken, ClientOptions options, WriteRetries writeRetries) {
        // The HTTP client of the client's own has no timeouts: the stall
        // timeout bounds every request, from connecting to the end of its
        // response. A timeout for whole requests would end every stream that
        // observes events, and a connect timeout would end a request sooner
        // than the stall timeout says.
        var givenHttpClient = options.httpClient();
        var httpClient = givenHttpClient != null ? givenHttpClient : HttpClient.newHttpClient();
        requests = new Requests(
                baseUrl, apiToken, options.stallTimeout(), httpClient, givenHttpClient == null, writeRetries);

        // The JDK reads whether it sends writes again with the first request
        // of any HTTP client, so the client reads it before it sends one, too,
        // and keeps it, should it be cleared afterwards.
        writeRetries.areEnabled();

        var givenDataMapper = options.dataMapper();
        dataMapper = givenDataMapper != null ? givenDataMapper : Json.MAPPER;

        transports = new TransportChooser(
                requests, new V1Transport(requests, dataMapper), new V2Transport(requests, dataMapper));
    }

    // Lets tests inspect the HTTP client that requests are sent with.
    HttpClient httpClient() {
        return requests.httpClient();
    }

    // Lets tests tell which API the client chose, if it has chosen one yet.
    @Nullable
    Transport chosenTransport() {
        return transports.chosen();
    }

    // Learns which API the server offers, which only the first request of a
    // client asks the server for, and sends the request through it. How the
    // request fails may tell that the server does not offer that API any
    // longer.
    private <T extends @Nullable Object> T send(String action, Function<Transport, T> request) {
        Http.throwIfInterrupted(action);
        var choice = TransportChooser.await(action, transports.transport());

        try {
            return request.apply(choice.transport());
        } catch (RuntimeException ex) {
            transports.forgetIfTheV2ApiIsMissing(choice, ex);
            throw ex;
        }
    }

    // Sends a request that returns nothing, as send does.
    private void run(String action, Consumer<Transport> request) {
        send(action, transport -> {
            request.accept(transport);
            return null;
        });
    }

    private <T> Stream<T> stream(String action, Function<Transport, StreamSpec<T>> specs) {
        return LineStream.of(action, requests, transports, specs);
    }

    /**
     * Checks whether the instance is reachable. This does not require authentication, so it may succeed even if the API
     * token is invalid.
     *
     * @throws EventSourcingDbException if the instance is not reachable, or does not confirm the ping
     */
    public void ping() {
        run("ping", Transport::ping);
    }

    /**
     * Verifies the API token.
     *
     * @throws DbApiException with status 401 if the API token is invalid
     * @throws EventSourcingDbException if the instance does not confirm the API token
     */
    public void verifyApiToken() {
        run("verify API token", Transport::verifyApiToken);
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
     * <p>If writing fails once the client has started to send the request, e.g. because the connection stalls, or
     * because the thread is interrupted, even while the client is still connecting to the server, the server may have
     * written the events nevertheless, so writing ends with an {@link OutcomeUnknownException}, whose cause tells what
     * failed.
     *
     * @param events the events to write
     * @param preconditions the preconditions that must hold, e.g. an {@link IsSubjectPristinePrecondition}
     * @return the written events, including the fields the server adds, such as their IDs
     * @throws DbApiException with the code {@link ErrorCode#PRECONDITION_VIOLATED} if a precondition does not hold, or
     *     {@link ErrorCode#EVENT_SCHEMA_VIOLATED} if an event does not match the schema of its type
     * @throws OutcomeUnknownException if writing failed in a way that leaves open whether the events were written
     * @throws IncompatibleServerException if a precondition is recursive, and the server does not offer the v2 API,
     *     which a recursive precondition needs; the events are not written then
     */
    public List<Event> writeEvents(List<EventCandidate> events, List<Precondition> preconditions) {
        var eventsNode = Json.MAPPER.createArrayNode();
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

        return send("write events", transport -> transport.writeEvents(eventsNode, preconditions));
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

        return stream("read events", transport -> transport.readEvents(body));
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
     * <p>The server ends the stream itself only after a failure it can not report within the stream, so the stream then
     * ends with an {@link EventSourcingDbException}.
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

        return stream("observe events", transport -> transport.observeEvents(body));
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
        return runEventQlQuery(query, row -> row);
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
        return runEventQlQuery(query, row -> dataMapper.treeToValue(row, rowType));
    }

    private <T> Stream<T> runEventQlQuery(String query, Function<JsonNode, T> rowParser) {
        return stream("run EventQL query", transport -> transport.runEventQlQuery(query, rowParser));
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
        return stream("read subjects", transport -> transport.readSubjects(subject, options.recursive()));
    }

    /**
     * Lists all event types, i.e. the types of the events written so far, and the types an event schema is registered
     * for.
     *
     * @return a stream of the event types
     */
    public Stream<EventType> readEventTypes() {
        return stream("read event types", Transport::readEventTypes);
    }

    /**
     * Reads a single event type, including its schema, if one is registered.
     *
     * @param eventType the event type, e.g. {@code io.eventsourcingdb.library.book-acquired}
     * @return the event type
     * @throws DbApiException with the code {@link ErrorCode#EVENT_TYPE_UNKNOWN} if the event type does not exist
     */
    public EventType readEventType(String eventType) {
        return send("read event type", transport -> transport.readEventType(eventType));
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
        var schemaNode = Json.MAPPER.valueToTree(schema);
        run("register event schema", transport -> transport.registerEventSchema(eventType, schemaNode));
    }

    /**
     * Closes the client, and ends everything it is doing at once: requests that are running and streams that are open,
     * whether they have started or not, end with a {@link java.util.concurrent.CancellationException}, as if they had
     * been aborted, and so does a write that was sent, as the cause of an {@link OutcomeUnknownException}. Every
     * request afterwards throws an {@link IllegalStateException} without being sent.
     *
     * <p>The client closes the HTTP client it created itself, but not one that was given to it with
     * {@link ClientOptions#withHttpClient(HttpClient)}, since that one belongs to the caller. Closing a client that is
     * closed already does nothing.
     */
    @Override
    public void close() {
        requests.close();
    }
}
