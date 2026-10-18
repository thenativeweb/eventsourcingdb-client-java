package io.thenativeweb.eventsourcingdb;

// Lines holds the items of NDJSON streams as the server sends them, in the
// envelope of the given API.
final class Lines {
    private Lines() {}

    // Characters that the server treats differently in the data of an event:
    // it escapes <, &, and > as <, &, and >, and passes on
    // U+00E9 (e with acute, as a single code point) and U+1F600 (grinning
    // face) as they are. They are written as escapes, so that it is clear
    // which code points are meant.
    static final String SPECIAL_CHARACTERS = "<&> é 😀";

    // An event that EventSourcingDB sent while reading events, captured as it
    // was, including the way the server escapes characters in the data. Its
    // data contains the special characters above.
    static final String EVENT_PAYLOAD = """
            {"source":"https://x","subject":"/a","type":"io.x.y",\
            "traceparent":"00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01","specversion":"1.0",\
            "id":"0","time":"2026-10-07T13:32:50.439461594Z","datacontenttype":"application/json",\
            "predecessorhash":"0000000000000000000000000000000000000000000000000000000000000000",\
            "data":{"b":100,"k":{"a":2,"b":1},"n":1.5,"z":"\\u003c\\u0026\\u003e é 😀"},\
            "hash":"a6015b0e09ab312b6ff506e267ad46d6e806f3a13041f948cf5c5ebbf83105f0","signature":null}""";

    static final String EVENT = ApiVersion.V1.item("event", EVENT_PAYLOAD);

    // Lines that EventSourcingDB sent while reading events whose hash is of
    // version 2, captured as they were. The first has a trace context, which
    // version 2 covers, and the same data as EVENT; the second has neither
    // traceparent nor tracestate, and follows on the first.
    static final String EVENT_WITH_HASH_OF_VERSION_2 = """
            {"type":"event","payload":{"source":"https://x","subject":"/a","type":"io.x.y",\
            "traceparent":"00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01","tracestate":"vendor=value",\
            "specversion":"1.0","id":"0","time":"2026-10-18T13:48:32.370154712Z","datacontenttype":"application/json",\
            "predecessorhash":"0000000000000000000000000000000000000000000000000000000000000000",\
            "data":{"b":100,"k":{"a":2,"b":1},"n":1.5,"z":"\\u003c\\u0026\\u003e é 😀"},\
            "hash":"esdb:hash:v2:e1a653d0d08fcd0d41d18b8f4082a93561e98dd6453dc9ee45ebe260b3c22ae8","signature":null}}""";

    static final String EVENT_WITH_HASH_OF_VERSION_2_WITHOUT_TRACE_CONTEXT = """
            {"type":"event","payload":{"source":"https://x","subject":"/a","type":"io.x.y","specversion":"1.0",\
            "id":"1","time":"2026-10-18T13:48:32.370154712Z","datacontenttype":"application/json",\
            "predecessorhash":"esdb:hash:v2:e1a653d0d08fcd0d41d18b8f4082a93561e98dd6453dc9ee45ebe260b3c22ae8",\
            "data":{"value":42},\
            "hash":"esdb:hash:v2:3a48e013665367cff6b0814f44cb9a1660a4c7dbe7994f94d38b4f16cad2fe93","signature":null}}""";

    // A line that EventSourcingDB sent through the v2 API while reading an
    // event without a trace context, captured as it was. The v2 API sends the
    // trace context as null then.
    static final String EVENT_OF_THE_V2_API = """
            {"type":"event","data":{"specversion":"1.0","id":"0","source":"https://x","subject":"/a",\
            "type":"io.x.y","time":"2026-10-18T20:59:26.979078761Z",\
            "predecessorhash":"0000000000000000000000000000000000000000000000000000000000000000",\
            "datacontenttype":"application/json","data":{"v":1},"traceparent":null,"tracestate":null,\
            "hash":"esdb:hash:v2:524413a3b6567f63735d87e9eb97a77cf0a3052b032b7fb06adce09de518cf11","signature":null}}""";

    static String heartbeat(ApiVersion api) {
        return api == ApiVersion.V1 ? "{\"type\":\"heartbeat\"}" : api.item("heartbeat", "{}");
    }

    static String event(ApiVersion api) {
        return api.item("event", EVENT_PAYLOAD);
    }

    // A row that holds the number 23.
    static String row(ApiVersion api) {
        return api.item("row", api == ApiVersion.V1 ? "23" : "{\"value\":23}");
    }

    static String subject(ApiVersion api, String subject) {
        return api.item("subject", "{\"subject\":\"" + subject + "\"}");
    }

    static String subject(ApiVersion api) {
        return subject(api, "/");
    }

    // An event type of which events were written.
    static String eventType(ApiVersion api) {
        return api == ApiVersion.V1
                ? api.item("eventType", "{\"eventType\":\"io.x.y\",\"isPhantom\":false}")
                : api.item("eventType", "{\"type\":\"io.x.y\",\"hasEvents\":true,\"schema\":null}");
    }

    // An error, as the API reports it within a stream.
    static String error(ApiVersion api) {
        return api == ApiVersion.V1
                ? api.item("error", "{\"error\":\"something went wrong\"}")
                : api.item(
                        "error",
                        "{\"code\":\"https://docs.eventsourcingdb.io/errors/store-failed\","
                                + "\"description\":\"something went wrong\",\"details\":{\"event\":0}}");
    }
}
