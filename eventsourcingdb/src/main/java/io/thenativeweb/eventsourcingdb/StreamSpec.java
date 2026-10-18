package io.thenativeweb.eventsourcingdb;

import java.net.http.HttpRequest;
import java.util.function.Predicate;

// StreamSpec describes a stream as an API sends it: the request that opens it,
// the codes of the errors that refuse it, how its items look, which type the
// items have that it hands out, and how it ends.
record StreamSpec<T>(
        HttpRequest request,
        CodeMapping codes,
        Envelope envelope,
        Ending ending,
        String itemType,
        LineStream.Parser<T> parser,
        Predicate<? super T> isWanted) {
    StreamSpec(
            HttpRequest request,
            CodeMapping codes,
            Envelope envelope,
            Ending ending,
            String itemType,
            LineStream.Parser<T> parser) {
        this(request, codes, envelope, ending, itemType, parser, item -> true);
    }

    // How a stream ends once it is complete. The v1 API ends a stream by
    // ending the response, and the v2 API sends an item of the type end first,
    // so that a stream that the server aborts can be told apart from one that
    // is complete. Observing never ends, so the end of its response always
    // means that the server aborted it.
    enum Ending {
        WITH_RESPONSE,
        WITH_END_ITEM,
        NEVER
    }
}
