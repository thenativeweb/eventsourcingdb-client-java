package io.thenativeweb.eventsourcingdb;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.MissingNode;

// LineStream hands out the items of an NDJSON stream as the caller consumes
// them. It sends the request only once the caller starts to consume, and it
// ends the stream with an exception if it is closed from somewhere else, or
// if nothing arrives from the server for the stall timeout.
final class LineStream<T> extends Spliterators.AbstractSpliterator<T> {
    private final String action;
    private final Supplier<CompletableFuture<HttpResponse<InputStream>>> request;
    private final String itemType;
    private final Parser<T> parser;
    private final Duration stallTimeout;
    private final CodeMapping codes;
    private final Set<LineStream<?>> openStreams;

    private volatile boolean isClosed;
    private volatile @Nullable CompletableFuture<HttpResponse<InputStream>> response;
    private volatile @Nullable InputStream body;
    private @Nullable BufferedReader reader;

    private LineStream(
            String action,
            Supplier<CompletableFuture<HttpResponse<InputStream>>> request,
            String itemType,
            Parser<T> parser,
            Duration stallTimeout,
            CodeMapping codes,
            Set<LineStream<?>> openStreams) {
        super(Long.MAX_VALUE, Spliterator.ORDERED | Spliterator.NONNULL);
        this.action = action;
        this.request = request;
        this.itemType = itemType;
        this.parser = parser;
        this.stallTimeout = stallTimeout;
        this.codes = codes;
        this.openStreams = openStreams;
    }

    // A stream that receives nothing for the stall timeout, not even a
    // heartbeat, ends with a ConnectionStalledException.
    // The stream adds itself to openStreams, and removes itself once it has
    // ended, so that the client can end the streams that are still open when
    // it is closed.
    static <T> Stream<T> of(
            String action,
            Supplier<CompletableFuture<HttpResponse<InputStream>>> request,
            String itemType,
            Parser<T> parser,
            Duration stallTimeout,
            CodeMapping codes,
            Set<LineStream<?>> openStreams) {
        var lineStream = new LineStream<>(action, request, itemType, parser, stallTimeout, codes, openStreams);
        openStreams.add(lineStream);
        return StreamSupport.stream(lineStream, false).onClose(lineStream::close);
    }

    @Override
    public boolean tryAdvance(Consumer<? super T> consumer) {
        try {
            return advance(consumer);
        } catch (RuntimeException ex) {
            close();
            throw ex;
        }
    }

    private boolean advance(Consumer<? super T> consumer) {
        var reader = this.reader;
        if (reader == null) {
            reader = open();
        }

        while (true) {
            var line = readLine(reader);
            if (line == null) {
                Http.close(reader);
                openStreams.remove(this);
                return false;
            }
            if (line.isBlank()) {
                continue;
            }

            var message = readMessage(line);
            var type = message.type();
            var payload = message.payload();

            if (type.equals("heartbeat")) {
                continue;
            }
            if (type.equals(itemType)) {
                consumer.accept(parser.parse(payload, message.rawData()));
                return true;
            }
            if (type.equals("error")) {
                throw DbApiException.ofAnswer(
                        action, "", payload.path("error").asString(), Json.MAPPER.createObjectNode());
            }

            throw new EventSourcingDbException("failed to handle unsupported line type: " + type);
        }
    }

    // A line of the stream: its type, its payload, and the text of the data
    // within its payload as the server wrote it, if there is any.
    private record Message(
            String type, JsonNode payload, @Nullable String rawData) {}

    // Reads a line in a single pass, since that is what most of the time of
    // reading a stream goes into.
    private static Message readMessage(String line) {
        try (var parser = Json.MAPPER.createParser(line)) {
            var type = "";
            JsonNode payload = MissingNode.getInstance();
            String rawData = null;

            if (parser.nextToken() == JsonToken.START_OBJECT) {
                while (parser.nextToken() == JsonToken.PROPERTY_NAME) {
                    var name = parser.currentName();
                    parser.nextToken();

                    var value = RawJson.readValue(parser, line, "data");
                    if (name.equals("type")) {
                        type = value.tree().asString();
                    } else if (name.equals("payload")) {
                        payload = value.tree();
                        rawData = value.raw();
                    }
                }
            }

            return new Message(type, payload, rawData);
        }
    }

    private BufferedReader open() {
        throwIfClosed();
        Http.throwIfInterrupted(action);

        var response = request.get();
        this.response = response;

        InputStream body;
        try {
            body = Http.await(action, response, stallTimeout, codes).body();
        } catch (RuntimeException ex) {
            throwIfClosed();
            throw ex;
        }
        this.body = body;

        var reader = new BufferedReader(new InputStreamReader(
                new StallGuard(body, stallTimeout, ex -> new ConnectionStalledException(action, stallTimeout, ex)),
                UTF_8));
        this.reader = reader;
        return reader;
    }

    // Returns null once the stream has ended.
    private @Nullable String readLine(BufferedReader reader) {
        throwIfClosed();

        try {
            var line = reader.readLine();
            throwIfClosed();
            return line;
        } catch (IOException ex) {
            throwIfClosed();
            throw Http.failure(action, ex);
        }
    }

    // A stream that is closed from somewhere else reports the cancellation,
    // rather than the failure that closing its body causes.
    private void throwIfClosed() {
        if (isClosed) {
            throw Http.cancellation(action, null);
        }
    }

    // Ends the stream, whether it has not started yet, waits for the server,
    // or reads a line.
    void close() {
        synchronized (this) {
            if (isClosed) {
                return;
            }
            isClosed = true;
        }
        openStreams.remove(this);

        var response = this.response;
        if (response != null) {
            response.cancel(true);
        }

        var body = this.body;
        if (body != null) {
            Http.close(body);
        }
    }

    @FunctionalInterface
    interface Parser<T> {
        T parse(JsonNode payload, @Nullable String rawData);
    }
}
