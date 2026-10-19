package io.thenativeweb.eventsourcingdb;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Spliterator;
import java.util.Spliterators;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;

// LineStream hands out the items of an NDJSON stream as the caller consumes
// them. It sends its request only once the caller starts to consume, which is
// when it learns which API the server offers, and so which request to send.
// It ends the stream with an exception if it is closed from somewhere else,
// if nothing arrives from the server for the stall timeout, or if the server
// ends the stream before it is complete.
final class LineStream<T> extends Spliterators.AbstractSpliterator<T> {
    private final String action;
    private final Requests requests;
    private final TransportChooser transports;
    private final Function<Transport, StreamSpec<T>> specs;

    private volatile boolean isClosed;
    private volatile @Nullable CompletableFuture<?> pending;
    private volatile @Nullable InputStream body;
    private @Nullable Opened<T> opened;

    // A stream whose request was answered: what the stream reads, and what
    // its lines are.
    private record Opened<T>(BufferedReader reader, StreamSpec<T> spec) {}

    private LineStream(
            String action, Requests requests, TransportChooser transports, Function<Transport, StreamSpec<T>> specs) {
        super(Long.MAX_VALUE, Spliterator.ORDERED | Spliterator.NONNULL);
        this.action = action;
        this.requests = requests;
        this.transports = transports;
        this.specs = specs;
    }

    // Creates a stream that specs describes once the API is known. The
    // stream is tracked by the client, so that closing the client closes it,
    // and it stops being tracked once it has ended.
    static <T> Stream<T> of(
            String action, Requests requests, TransportChooser transports, Function<Transport, StreamSpec<T>> specs) {
        var lineStream = requests.track(new LineStream<>(action, requests, transports, specs));
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
        var opened = this.opened;
        if (opened == null) {
            opened = open();
            this.opened = opened;
        }
        var reader = opened.reader();
        var spec = opened.spec();

        while (true) {
            var line = readLine(reader);
            if (line == null) {
                if (spec.ending() != StreamSpec.Ending.WITH_RESPONSE) {
                    throw Http.endedUnexpectedly(action);
                }

                end(reader);
                return false;
            }
            if (line.isBlank()) {
                continue;
            }

            var item = spec.envelope().read(line);
            var type = item.type();

            if (type.equals("heartbeat")) {
                continue;
            }
            if (type.equals(spec.itemType())) {
                var parsed = spec.parser().parse(item.payload(), item.rawData());
                if (!spec.isWanted().test(parsed)) {
                    continue;
                }

                consumer.accept(parsed);
                return true;
            }
            if (type.equals("error")) {
                throw spec.envelope().errorItem().toException(action, item.payload());
            }
            if (type.equals("end") && spec.ending() == StreamSpec.Ending.WITH_END_ITEM) {
                end(reader);
                return false;
            }

            throw new EventSourcingDbException("failed to handle unsupported line type: " + type);
        }
    }

    private void end(BufferedReader reader) {
        Http.close(reader);
        requests.untrack(this);
    }

    // Learns which API the server offers, and sends the request of the
    // stream through it. How the server answers the request may tell that it
    // does not offer that API any longer.
    private Opened<T> open() {
        throwIfClosed();
        Http.throwIfInterrupted(action);

        var transport = transports.transport();
        pending = transport;

        StreamSpec<T> spec;
        InputStream body;
        try {
            throwIfClosed();
            var chosen = TransportChooser.await(action, transport);
            spec = specs.apply(chosen);

            var response = requests.sendForStream(spec.request());
            pending = response;
            try {
                body = Http.await(action, response, requests.stallTimeout(), spec.codes())
                        .body();
            } catch (RuntimeException ex) {
                transports.forgetIfTheV2ApiIsMissing(chosen, ex);
                throw ex;
            }
        } catch (RuntimeException ex) {
            throwIfClosed();
            throw ex;
        }
        this.body = body;

        var stallTimeout = requests.stallTimeout();
        var reader = new BufferedReader(new InputStreamReader(
                new StallGuard(body, stallTimeout, ex -> new ConnectionStalledException(action, stallTimeout, ex)),
                UTF_8));
        return new Opened<>(reader, spec);
    }

    // Returns null once the response has ended.
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

    // Ends the stream, whether it has not started yet, waits for the API to be
    // chosen or for the server to answer, or reads a line. It releases what
    // the stream holds each time it is called, not only the first time: the
    // stream may get hold of the body of its response after it was closed,
    // e.g. when it was closed after the headers had arrived, but before it
    // had the body. It then notices that it is closed with its next read,
    // which ends it, and closes it again, and so the body, too.
    void close() {
        isClosed = true;
        requests.untrack(this);

        var pending = this.pending;
        if (pending != null) {
            pending.cancel(true);
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
