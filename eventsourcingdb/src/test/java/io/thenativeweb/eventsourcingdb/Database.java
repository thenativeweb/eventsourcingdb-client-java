package io.thenativeweb.eventsourcingdb;

import io.thenativeweb.eventsourcingdb.testcontainers.Container;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

final class Database {
    private Database() {}

    static Container start() {
        return start(new Container());
    }

    static Container startWithSigningKey() {
        return start(new Container().withSigningKey());
    }

    private static Container start(Container container) {
        container.withImageTag(ImageTag.get()).start();
        return container;
    }

    // Tells whether the database in the container offers the v2 API, which
    // depends on the image the tests run against. It asks the database itself
    // rather than the client, so that a test that depends on the answer does
    // not take it from the code under test.
    static boolean offersV2Api(Container container) {
        try (var httpClient = HttpClient.newHttpClient()) {
            var request = HttpRequest.newBuilder(container.getBaseUrl().resolve("/api/v2/system/ping"))
                    .build();
            return httpClient
                            .send(request, HttpResponse.BodyHandlers.discarding())
                            .statusCode()
                    == 200;
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(ex);
        }
    }
}
