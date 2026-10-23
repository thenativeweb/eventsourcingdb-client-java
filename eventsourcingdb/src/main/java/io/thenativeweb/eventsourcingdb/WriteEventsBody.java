package io.thenativeweb.eventsourcingdb;

import java.util.List;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

// WriteEventsBody builds the body of a write, which both APIs take alike, but
// for the options of the preconditions on a subject, which only the v2 API
// knows. A precondition is sent with options only if it has any, so that one
// without them is sent exactly as it was before there were options.
final class WriteEventsBody {
    private WriteEventsBody() {}

    static ObjectNode withPreconditionOptions(ArrayNode events, List<Precondition> preconditions) {
        return of(events, preconditions, true);
    }

    static ObjectNode withoutPreconditionOptions(ArrayNode events, List<Precondition> preconditions) {
        return of(events, preconditions, false);
    }

    // Tells whether the options of a precondition make it apply to the
    // nested subjects of its subject, too.
    static boolean isRecursive(Precondition precondition) {
        var options = optionsOf(precondition);
        return options != null && options.recursive();
    }

    private static ObjectNode of(ArrayNode events, List<Precondition> preconditions, boolean isSendingOptions) {
        var body = Json.MAPPER.createObjectNode();
        body.set("events", events);

        if (!preconditions.isEmpty()) {
            var preconditionsNode = body.putArray("preconditions");
            for (var precondition : preconditions) {
                preconditionsNode.add(toRequest(precondition, isSendingOptions));
            }
        }

        return body;
    }

    private static ObjectNode toRequest(Precondition precondition, boolean isSendingOptions) {
        var node = Json.MAPPER.createObjectNode();
        var payload = Json.MAPPER.createObjectNode();

        switch (precondition) {
            case IsSubjectPristinePrecondition pristinePrecondition -> {
                node.put("type", "isSubjectPristine");
                payload.put("subject", pristinePrecondition.subject());
            }
            case IsSubjectPopulatedPrecondition populatedPrecondition -> {
                node.put("type", "isSubjectPopulated");
                payload.put("subject", populatedPrecondition.subject());
            }
            case IsSubjectOnEventIdPrecondition onEventIdPrecondition -> {
                node.put("type", "isSubjectOnEventId");
                payload.put("subject", onEventIdPrecondition.subject());
                payload.put("eventId", onEventIdPrecondition.eventId());
            }
            case IsEventQlQueryTruePrecondition queryPrecondition -> {
                node.put("type", "isEventQlQueryTrue");
                payload.put("query", queryPrecondition.query());
            }
        }

        var options = optionsOf(precondition);
        if (isSendingOptions && options != null) {
            payload.putObject("options").put("recursive", options.recursive());
        }

        node.set("payload", payload);
        return node;
    }

    private static @Nullable PreconditionOptions optionsOf(Precondition precondition) {
        return switch (precondition) {
            case IsSubjectPristinePrecondition pristinePrecondition -> pristinePrecondition.options();
            case IsSubjectPopulatedPrecondition populatedPrecondition -> populatedPrecondition.options();
            case IsSubjectOnEventIdPrecondition onEventIdPrecondition -> onEventIdPrecondition.options();
            case IsEventQlQueryTruePrecondition queryPrecondition -> null;
        };
    }
}
