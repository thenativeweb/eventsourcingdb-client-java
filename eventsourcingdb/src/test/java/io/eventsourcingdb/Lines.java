package io.eventsourcingdb;

// Lines holds lines of NDJSON streams as the server sends them.
final class Lines {
    private Lines() {}

    static final String HEARTBEAT = "{\"type\":\"heartbeat\"}";

    static final String ROW = "{\"type\":\"row\",\"payload\":{\"value\":23}}";

    static final String SUBJECT = "{\"type\":\"subject\",\"payload\":{\"subject\":\"/\"}}";

    // A line that EventSourcingDB sent while reading events, captured as it
    // was, including the way the server escapes characters in the data.
    static final String EVENT = """
            {"type":"event","payload":{"source":"https://x","subject":"/a","type":"io.x.y",\
            "traceparent":"00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01","specversion":"1.0",\
            "id":"0","time":"2026-10-07T13:32:50.439461594Z","datacontenttype":"application/json",\
            "predecessorhash":"0000000000000000000000000000000000000000000000000000000000000000",\
            "data":{"b":100,"k":{"a":2,"b":1},"n":1.5,"z":"\\u003c\\u0026\\u003e é 😀"},\
            "hash":"a6015b0e09ab312b6ff506e267ad46d6e806f3a13041f948cf5c5ebbf83105f0","signature":null}}""";
}
