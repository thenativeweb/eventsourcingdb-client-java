package io.thenativeweb.eventsourcingdb;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

// V2Transport sends the requests of the client through the v2 API. It reads
// with the HTTP method QUERY, and wraps every answer in an envelope. Every
// stream but that of observing ends with an end item, and so do the answers to
// writes, which are streams, too, so that heartbeats keep a long write alive.
// An error that the server reports in an answer has a code, and the status
// code 200.
final class V2Transport implements Transport {
    private final Requests requests;
    private final JsonMapper dataMapper;

    V2Transport(Requests requests, JsonMapper dataMapper) {
        this.requests = requests;
        this.dataMapper = dataMapper;
    }

    // The ping is the one answer that tells a server with the v2 API apart
    // from one without, which is why TransportChooser asks it, too.
    static boolean isPingReceived(JsonNode answer) {
        return isEvent(answer, "io.eventsourcingdb.api.ping-received");
    }

    private static boolean isEvent(JsonNode answer, String eventType) {
        return answer.path("type").asString().equals("event")
                && answer.path("data").path("type").asString().equals(eventType);
    }

    @Override
    public void ping() {
        var response = requests.send("ping", requests.get("/api/v2/system/ping"), CodeMapping.NONE);

        if (!isPingReceived(Json.MAPPER.readTree(response))) {
            throw new EventSourcingDbException("failed to ping");
        }
    }

    @Override
    public void verifyApiToken() {
        var response =
                requests.send("verify API token", requests.post("/api/v2/system/verify-token"), CodeMapping.NONE);

        if (!isEvent(Json.MAPPER.readTree(response), "io.eventsourcingdb.api.token-verified")) {
            throw new EventSourcingDbException("failed to verify API token");
        }
    }

    @Override
    public List<Event> writeEvents(ArrayNode events, List<Precondition> preconditions) {
        var action = "write events";
        var body = WriteEventsBody.withPreconditionOptions(events, preconditions);

        return requests.write(
                action,
                requests.post("/api/v2/data/write-events", body),
                CodeMapping.NONE,
                Outcomes.V2_REFUSALS,
                response -> readToEnd(action, response, this::parseEvent));
    }

    @Override
    public StreamSpec<Event> readEvents(ObjectNode body) {
        return new StreamSpec<>(
                requests.query("/api/v2/data/read-events", body),
                CodeMapping.NONE,
                Envelope.V2,
                StreamSpec.Ending.WITH_END_ITEM,
                "event",
                this::parseEvent);
    }

    @Override
    public StreamSpec<Event> observeEvents(ObjectNode body) {
        return new StreamSpec<>(
                requests.query("/api/v2/data/observe-events", body),
                CodeMapping.NONE,
                Envelope.V2,
                StreamSpec.Ending.NEVER,
                "event",
                this::parseEvent);
    }

    private Event parseEvent(JsonNode payload, @Nullable String rawData) {
        return Event.from(payload, rawData, dataMapper);
    }

    // A row holds the value that the query projected as its value.
    @Override
    public <T> StreamSpec<T> runEventQlQuery(String query, Function<JsonNode, T> rowParser) {
        var body = Json.MAPPER.createObjectNode();
        body.put("query", query);

        return new StreamSpec<>(
                requests.query("/api/v2/data/run-eventql-query", body),
                CodeMapping.NONE,
                Envelope.V2,
                StreamSpec.Ending.WITH_END_ITEM,
                "row",
                (payload, rawData) -> rowParser.apply(payload.path("value")));
    }

    @Override
    public StreamSpec<String> readSubjects(String subject, boolean recursive) {
        var body = Json.MAPPER.createObjectNode();
        body.put("subject", subject);
        body.putObject("options").put("recursive", recursive);

        return new StreamSpec<>(
                requests.query("/api/v2/data/read-subjects", body),
                CodeMapping.NONE,
                Envelope.V2,
                StreamSpec.Ending.WITH_END_ITEM,
                "subject",
                (payload, rawData) -> payload.path("subject").asString());
    }

    @Override
    public StreamSpec<EventType> readEventTypes() {
        return new StreamSpec<>(
                requests.query("/api/v2/data/read-event-types"),
                CodeMapping.NONE,
                Envelope.V2,
                StreamSpec.Ending.WITH_END_ITEM,
                "eventType",
                (payload, rawData) -> eventTypeOf(payload));
    }

    private static EventType eventTypeOf(JsonNode eventType) {
        return new EventType(
                eventType.path("type").asString(),
                eventType.path("hasEvents").asBoolean(),
                EventType.schemaOf(eventType.path("schema")));
    }

    // The answer is a single envelope, which holds either the event type or
    // the error that the server reports.
    @Override
    public EventType readEventType(String eventType) {
        var action = "read event type";

        var body = Json.MAPPER.createObjectNode();
        body.put("type", eventType);

        var response = requests.send(action, requests.query("/api/v2/data/read-event-type", body), CodeMapping.NONE);
        var item = Envelope.V2.read(response);

        return switch (item.type()) {
            case "eventType" -> eventTypeOf(item.payload());
            case "error" -> throw Envelope.V2.errorItem().toException(action, item.payload());
            default -> throw new EventSourcingDbException("failed to handle unsupported line type: " + item.type());
        };
    }

    @Override
    public void registerEventSchema(String eventType, JsonNode schema) {
        var action = "register event schema";

        var body = Json.MAPPER.createObjectNode();
        body.put("type", eventType);
        body.set("schema", schema);

        requests.write(
                action,
                requests.post("/api/v2/data/register-event-schema", body),
                CodeMapping.NONE,
                Outcomes.V2_REFUSALS,
                response -> readToEnd(action, response, (payload, rawData) -> payload));
    }

    // Reads the items of the answer to a write, which is a stream, up to its
    // end item. The answer arrives as a whole, since a write answers with no
    // more items than it wrote events.
    private static <T> List<T> readToEnd(String action, String response, LineStream.Parser<T> parser) {
        var items = new ArrayList<T>();

        for (var line : response.split("\n", -1)) {
            if (line.isBlank()) {
                continue;
            }

            var item = Envelope.V2.read(line);
            switch (item.type()) {
                case "heartbeat" -> {}
                case "event" -> items.add(parser.parse(item.payload(), item.rawData()));
                case "error" -> throw Envelope.V2.errorItem().toException(action, item.payload());
                case "end" -> {
                    return items;
                }
                default -> throw new EventSourcingDbException("failed to handle unsupported line type: " + item.type());
            }
        }

        throw Http.endedUnexpectedly(action);
    }
}
