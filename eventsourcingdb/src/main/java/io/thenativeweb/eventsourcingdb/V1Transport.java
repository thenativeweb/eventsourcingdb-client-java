package io.thenativeweb.eventsourcingdb;

import java.util.List;
import java.util.function.Function;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

// V1Transport sends the requests of the client through the v1 API, for a
// server that does not offer the v2 API, such as EventSourcingDB 1.2.0. Its
// streams have no end item and no heartbeats, except those of observing and
// of EventQL queries, and it reports every error with a status code.
final class V1Transport implements Transport {
    private static final Pattern SURROUNDING_SLASHES = Pattern.compile("^/+|/+$");

    private final Requests requests;
    private final JsonMapper dataMapper;

    V1Transport(Requests requests, JsonMapper dataMapper) {
        this.requests = requests;
        this.dataMapper = dataMapper;
    }

    @Override
    public void ping() {
        var response = requests.send("ping", requests.get("/api/v1/ping"), V1ErrorCodes.ANY_REQUEST);

        if (!"io.eventsourcingdb.api.ping-received"
                .equals(Json.MAPPER.readTree(response).path("type").asString())) {
            throw new EventSourcingDbException("failed to ping");
        }
    }

    @Override
    public void verifyApiToken() {
        var response =
                requests.send("verify API token", requests.post("/api/v1/verify-api-token"), V1ErrorCodes.ANY_REQUEST);

        if (!"io.eventsourcingdb.api.api-token-verified"
                .equals(Json.MAPPER.readTree(response).path("type").asString())) {
            throw new EventSourcingDbException("failed to verify API token");
        }
    }

    // The v1 API knows no options of preconditions. A precondition that is
    // not recursive means the same without them, but a recursive one can not
    // be checked at all, so the write fails before it is sent, rather than
    // checking less than it asks for.
    @Override
    public List<Event> writeEvents(ArrayNode events, List<Precondition> preconditions) {
        if (preconditions.stream().anyMatch(WriteEventsBody::isRecursive)) {
            throw new IncompatibleServerException();
        }

        var body = WriteEventsBody.withoutPreconditionOptions(events, preconditions);

        return requests.write(
                "write events",
                requests.post("/api/v1/write-events", body),
                V1ErrorCodes.WRITE_EVENTS,
                Outcomes.V1_REFUSALS,
                response -> RawJson.readArray(response, "data").stream()
                        .map(cloudEvent -> Event.from(cloudEvent.tree(), cloudEvent.raw(), dataMapper))
                        .toList());
    }

    @Override
    public StreamSpec<Event> readEvents(ObjectNode body) {
        return new StreamSpec<>(
                requests.post("/api/v1/read-events", body),
                V1ErrorCodes.READ_EVENTS,
                Envelope.V1,
                StreamSpec.Ending.WITH_RESPONSE,
                "event",
                this::parseEvent);
    }

    @Override
    public StreamSpec<Event> observeEvents(ObjectNode body) {
        return new StreamSpec<>(
                requests.post("/api/v1/observe-events", body),
                V1ErrorCodes.ANY_REQUEST,
                Envelope.V1,
                StreamSpec.Ending.NEVER,
                "event",
                this::parseEvent);
    }

    private Event parseEvent(JsonNode payload, @Nullable String rawData) {
        return Event.from(payload, rawData, dataMapper);
    }

    // The payload of a row is the value that the query projected.
    @Override
    public <T> StreamSpec<T> runEventQlQuery(String query, Function<JsonNode, T> rowParser) {
        var body = Json.MAPPER.createObjectNode();
        body.put("query", query);

        return new StreamSpec<>(
                requests.post("/api/v1/run-eventql-query", body),
                V1ErrorCodes.ANY_REQUEST,
                Envelope.V1,
                StreamSpec.Ending.WITH_RESPONSE,
                "row",
                (payload, rawData) -> rowParser.apply(payload));
    }

    // The v1 API always reads recursively, so the subject alone is the one
    // subject of the stream that is the subject itself. The server names it
    // without the slashes around it, e.g. /books for /books/.
    @Override
    public StreamSpec<String> readSubjects(String subject, boolean recursive) {
        var body = Json.MAPPER.createObjectNode();
        body.put("baseSubject", subject);

        var subjectItself = "/" + SURROUNDING_SLASHES.matcher(subject).replaceAll("");

        return new StreamSpec<>(
                requests.post("/api/v1/read-subjects", body),
                V1ErrorCodes.ANY_REQUEST,
                Envelope.V1,
                StreamSpec.Ending.WITH_RESPONSE,
                "subject",
                (payload, rawData) -> payload.path("subject").asString(),
                readSubject -> recursive || readSubject.equals(subjectItself));
    }

    @Override
    public StreamSpec<EventType> readEventTypes() {
        return new StreamSpec<>(
                requests.post("/api/v1/read-event-types", Json.MAPPER.createObjectNode()),
                V1ErrorCodes.ANY_REQUEST,
                Envelope.V1,
                StreamSpec.Ending.WITH_RESPONSE,
                "eventType",
                (payload, rawData) -> eventTypeOf(payload));
    }

    // The v1 API names an event type by eventType, and tells by isPhantom
    // whether it has no events.
    private static EventType eventTypeOf(JsonNode eventType) {
        return new EventType(
                eventType.path("eventType").asString(),
                !eventType.path("isPhantom").asBoolean(),
                EventType.schemaOf(eventType.path("schema")));
    }

    @Override
    public EventType readEventType(String eventType) {
        var body = Json.MAPPER.createObjectNode();
        body.put("eventType", eventType);

        var response = requests.send(
                "read event type", requests.post("/api/v1/read-event-type", body), V1ErrorCodes.READ_EVENT_TYPE);
        return eventTypeOf(Json.MAPPER.readTree(response));
    }

    @Override
    public void registerEventSchema(String eventType, JsonNode schema) {
        var body = Json.MAPPER.createObjectNode();
        body.put("eventType", eventType);
        body.set("schema", schema);

        requests.write(
                "register event schema",
                requests.post("/api/v1/register-event-schema", body),
                V1ErrorCodes.REGISTER_EVENT_SCHEMA,
                Outcomes.V1_REFUSALS,
                response -> response);
    }
}
