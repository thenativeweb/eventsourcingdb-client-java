package io.eventsourcingdb;

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

public final class Client {
    private static final Duration HEARTBEAT_TIMEOUT = Duration.ofSeconds(30);

    private final URI baseUrl;
    private final String apiToken;
    private final Duration heartbeatTimeout;
    private final HttpClient httpClient = HttpClient.newHttpClient();

    public Client(URI baseUrl, String apiToken) {
        this(baseUrl, apiToken, HEARTBEAT_TIMEOUT);
    }

    Client(URI baseUrl, String apiToken, Duration heartbeatTimeout) {
        this.baseUrl = baseUrl;
        this.apiToken = apiToken;
        this.heartbeatTimeout = heartbeatTimeout;
    }

    public void ping() {
        var request =
                HttpRequest.newBuilder(baseUrl.resolve("/api/v1/ping")).GET().build();

        var result = readJson("ping", send("ping", request));
        if (!"io.eventsourcingdb.api.ping-received".equals(result.path("type").asString())) {
            throw new EventSourcingDbException("failed to ping");
        }
    }

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

    public List<Event> writeEvents(List<EventCandidate> events) {
        return writeEvents(events, List.of());
    }

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
            case IsSubjectPristinePrecondition p ->
                Map.of("type", "isSubjectPristine", "payload", Map.of("subject", p.subject()));
            case IsSubjectPopulatedPrecondition p ->
                Map.of("type", "isSubjectPopulated", "payload", Map.of("subject", p.subject()));
            case IsSubjectOnEventIdPrecondition p ->
                Map.of("type", "isSubjectOnEventId", "payload", Map.of("subject", p.subject(), "eventId", p.eventId()));
            case IsEventQlQueryTruePrecondition p ->
                Map.of("type", "isEventQlQueryTrue", "payload", Map.of("query", p.query()));
        };
    }

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

    public Stream<EventType> readEventTypes() {
        return stream(
                "read event types",
                post("/api/v1/read-event-types", Json.MAPPER.createObjectNode()),
                "eventType",
                (payload, line) -> EventType.from(payload),
                null);
    }

    public EventType readEventType(String eventType) {
        var body = Json.MAPPER.createObjectNode();
        body.put("eventType", eventType);

        var response = send("read event type", post("/api/v1/read-event-type", body));
        return EventType.from(readJson("read event type", response));
    }

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
