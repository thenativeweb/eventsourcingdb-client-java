package io.thenativeweb.eventsourcingdb;

import java.util.List;
import java.util.function.Function;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

// Transport sends the requests of the client through one API of the server.
// TransportChooser chooses which, so the client itself does not know about
// the APIs. The bodies that both APIs take alike are built by the client, and
// handed over as they are.
sealed interface Transport permits V1Transport, V2Transport {
    void ping();

    void verifyApiToken();

    List<Event> writeEvents(ObjectNode body);

    StreamSpec<Event> readEvents(ObjectNode body);

    StreamSpec<Event> observeEvents(ObjectNode body);

    // Hands each row to rowParser, as the value that the query projected.
    <T> StreamSpec<T> runEventQlQuery(String query, Function<JsonNode, T> rowParser);

    StreamSpec<String> readSubjects(String subject, boolean recursive);

    StreamSpec<EventType> readEventTypes();

    EventType readEventType(String eventType);

    void registerEventSchema(String eventType, JsonNode schema);
}
