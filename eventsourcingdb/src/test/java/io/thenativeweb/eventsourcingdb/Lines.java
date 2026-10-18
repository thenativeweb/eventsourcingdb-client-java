package io.thenativeweb.eventsourcingdb;

// Lines holds lines of NDJSON streams as the server sends them.
final class Lines {
    private Lines() {}

    static final String HEARTBEAT = "{\"type\":\"heartbeat\"}";

    static final String ROW = "{\"type\":\"row\",\"payload\":{\"value\":23}}";

    static final String SUBJECT = "{\"type\":\"subject\",\"payload\":{\"subject\":\"/\"}}";

    // Characters that the server treats differently in the data of an event:
    // it escapes <, &, and > as \u003c, \u0026, and \u003e, and passes on
    // U+00E9 (e with acute, as a single code point) and U+1F600 (grinning
    // face) as they are. They are written as escapes, so that it is clear
    // which code points are meant.
    static final String SPECIAL_CHARACTERS = "<&> \u00e9 \ud83d\ude00";

    // A line that EventSourcingDB sent while reading events, captured as it
    // was, including the way the server escapes characters in the data. Its
    // data contains the special characters above.
    static final String EVENT = """
            {"type":"event","payload":{"source":"https://x","subject":"/a","type":"io.x.y",\
            "traceparent":"00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01","specversion":"1.0",\
            "id":"0","time":"2026-10-07T13:32:50.439461594Z","datacontenttype":"application/json",\
            "predecessorhash":"0000000000000000000000000000000000000000000000000000000000000000",\
            "data":{"b":100,"k":{"a":2,"b":1},"n":1.5,"z":"\\u003c\\u0026\\u003e \u00e9 \ud83d\ude00"},\
            "hash":"a6015b0e09ab312b6ff506e267ad46d6e806f3a13041f948cf5c5ebbf83105f0","signature":null}}""";

    // Lines that EventSourcingDB sent while reading events whose hash is of
    // version 2, captured as they were. The first has a trace context, which
    // version 2 covers, and the same data as EVENT; the second has neither
    // traceparent nor tracestate, and follows on the first.
    static final String EVENT_WITH_HASH_OF_VERSION_2 = """
            {"type":"event","payload":{"source":"https://x","subject":"/a","type":"io.x.y",\
            "traceparent":"00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01","tracestate":"vendor=value",\
            "specversion":"1.0","id":"0","time":"2026-10-18T13:48:32.370154712Z","datacontenttype":"application/json",\
            "predecessorhash":"0000000000000000000000000000000000000000000000000000000000000000",\
            "data":{"b":100,"k":{"a":2,"b":1},"n":1.5,"z":"\\u003c\\u0026\\u003e \u00e9 \ud83d\ude00"},\
            "hash":"esdb:hash:v2:e1a653d0d08fcd0d41d18b8f4082a93561e98dd6453dc9ee45ebe260b3c22ae8","signature":null}}""";

    static final String EVENT_WITH_HASH_OF_VERSION_2_WITHOUT_TRACE_CONTEXT = """
            {"type":"event","payload":{"source":"https://x","subject":"/a","type":"io.x.y","specversion":"1.0",\
            "id":"1","time":"2026-10-18T13:48:32.370154712Z","datacontenttype":"application/json",\
            "predecessorhash":"esdb:hash:v2:e1a653d0d08fcd0d41d18b8f4082a93561e98dd6453dc9ee45ebe260b3c22ae8",\
            "data":{"value":42},\
            "hash":"esdb:hash:v2:3a48e013665367cff6b0814f44cb9a1660a4c7dbe7994f94d38b4f16cad2fe93","signature":null}}""";
}
