/**
 * The official Java client SDK for EventSourcingDB.
 *
 * <p>Applications that use the Java module system require this module only: it passes on the modules whose types its
 * API uses.
 */
module io.thenativeweb.eventsourcingdb {
    // The API uses types of these modules, e.g. HttpClient in ClientOptions,
    // JsonNode in Event, and the nullness annotations everywhere, so
    // applications get to read them along with this module.
    requires transitive java.net.http;
    requires transitive org.jspecify;
    requires transitive tools.jackson.databind;

    exports io.thenativeweb.eventsourcingdb;
}
