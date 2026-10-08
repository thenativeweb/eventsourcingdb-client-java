package io.thenativeweb.eventsourcingdb;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

// LineStream hands out the items of an NDJSON stream as the caller consumes
// them. It sends the request only once the caller starts to consume, and it
// ends the stream with an exception if it is closed from somewhere else, or
// if neither an item nor a heartbeat arrives in time.
final class LineStream<T> extends Spliterators.AbstractSpliterator<T> {
    private static final ScheduledThreadPoolExecutor HEARTBEAT_TIMERS = heartbeatTimers();

    private final String action;
    private final Supplier<CompletableFuture<HttpResponse<InputStream>>> request;
    private final String itemType;
    private final Parser<T> parser;
    private final @Nullable Duration heartbeatTimeout;

    private volatile @Nullable Supplier<RuntimeException> abortion;
    private volatile @Nullable CompletableFuture<HttpResponse<InputStream>> response;
    private volatile @Nullable InputStream body;
    private @Nullable BufferedReader reader;

    private LineStream(
            String action,
            Supplier<CompletableFuture<HttpResponse<InputStream>>> request,
            String itemType,
            Parser<T> parser,
            @Nullable Duration heartbeatTimeout) {
        super(Long.MAX_VALUE, Spliterator.ORDERED | Spliterator.NONNULL);
        this.action = action;
        this.request = request;
        this.itemType = itemType;
        this.parser = parser;
        this.heartbeatTimeout = heartbeatTimeout;
    }

    // A heartbeatTimeout of null means that the server sends no heartbeats on
    // this stream, so it may stay silent for as long as it needs.
    static <T> Stream<T> of(
            String action,
            Supplier<CompletableFuture<HttpResponse<InputStream>>> request,
            String itemType,
            Parser<T> parser,
            @Nullable Duration heartbeatTimeout) {
        var lineStream = new LineStream<>(action, request, itemType, parser, heartbeatTimeout);
        return StreamSupport.stream(lineStream, false).onClose(lineStream::close);
    }

    private static ScheduledThreadPoolExecutor heartbeatTimers() {
        var executor = new ScheduledThreadPoolExecutor(
                1,
                Thread.ofPlatform()
                        .daemon()
                        .name("eventsourcingdb-heartbeat-timeout")
                        .factory());
        executor.setRemoveOnCancelPolicy(true);
        return executor;
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
                return false;
            }
            if (line.isBlank()) {
                continue;
            }

            var message = Json.MAPPER.readTree(line);
            var type = message.path("type").asString();
            var payload = message.path("payload");

            if (type.equals("heartbeat")) {
                continue;
            }
            if (type.equals(itemType)) {
                consumer.accept(parser.parse(payload, line));
                return true;
            }
            if (type.equals("error")) {
                throw new EventSourcingDbException("failed to " + action + ", got error: "
                        + payload.path("error").asString());
            }

            throw new EventSourcingDbException("failed to handle unsupported line type: " + type);
        }
    }

    private BufferedReader open() {
        throwIfAborted();
        Http.throwIfInterrupted(action);

        var response = request.get();
        this.response = response;

        InputStream body;
        try {
            body = Http.await(action, response).body();
        } catch (RuntimeException ex) {
            throwIfAborted();
            throw ex;
        }
        this.body = body;

        var reader = new BufferedReader(new InputStreamReader(body, UTF_8));
        this.reader = reader;
        return reader;
    }

    // Returns null once the stream has ended.
    private @Nullable String readLine(BufferedReader reader) {
        throwIfAborted();

        var timer = heartbeatTimeout == null
                ? null
                : HEARTBEAT_TIMERS.schedule(
                        () -> abort(HeartbeatTimeoutException::new), heartbeatTimeout.toNanos(), TimeUnit.NANOSECONDS);
        try {
            var line = reader.readLine();
            throwIfAborted();
            return line;
        } catch (IOException ex) {
            throwIfAborted();
            throw Http.failure(action, ex);
        } finally {
            if (timer != null) {
                timer.cancel(false);
            }
        }
    }

    private void throwIfAborted() {
        var abortion = this.abortion;
        if (abortion != null) {
            throw abortion.get();
        }
    }

    private void close() {
        abort(() -> Http.cancellation(action, null));
    }

    // Ends the stream for the given reason. The first reason wins, so that a
    // stream that is closed after it timed out still reports the timeout.
    private void abort(Supplier<RuntimeException> reason) {
        synchronized (this) {
            if (abortion != null) {
                return;
            }
            abortion = reason;
        }

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
        T parse(JsonNode payload, String line);
    }
}
