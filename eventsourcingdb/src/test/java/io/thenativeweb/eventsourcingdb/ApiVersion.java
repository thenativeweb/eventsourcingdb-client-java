package io.thenativeweb.eventsourcingdb;

import com.sun.net.httpserver.HttpExchange;
import java.io.IOException;

// ApiVersion names the API that a FakeServer offers, and tells how that API
// answers the probe of the client, and how it wraps the items it sends.
enum ApiVersion {
    // Answers the probe as EventSourcingDB 1.2.0 does, which does not know the
    // path of the v2 API.
    V1("payload") {
        @Override
        void answerProbe(HttpExchange exchange) throws IOException {
            FakeServer.respond(exchange, 404, "404 page not found\n");
        }

        @Override
        String end() {
            return "";
        }
    },

    V2("data") {
        @Override
        void answerProbe(HttpExchange exchange) throws IOException {
            FakeServer.respond(exchange, 200, PING_RECEIVED);
        }

        @Override
        String end() {
            return item("end", "{}");
        }
    };

    static final String PING_RECEIVED = """
            {"type":"event","data":{"specversion":"1.0","id":"0","source":"https://www.eventsourcingdb.io",\
            "type":"io.eventsourcingdb.api.ping-received","subject":"/api/v2/system/ping",\
            "time":"2026-10-18T20:59:13.348809963Z","datacontenttype":"application/json",\
            "data":{"message":"My God, it's full of stars."}}}""";

    private final String payloadKey;

    ApiVersion(String payloadKey) {
        this.payloadKey = payloadKey;
    }

    abstract void answerProbe(HttpExchange exchange) throws IOException;

    // The item that ends a stream that is complete, or an empty line, which
    // the client skips, for the v1 API, whose streams just end.
    abstract String end();

    String item(String type, String payload) {
        return "{\"type\":\"" + type + "\",\"" + payloadKey + "\":" + payload + "}";
    }
}
