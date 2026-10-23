package io.thenativeweb.eventsourcingdb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import tools.jackson.databind.JsonNode;

// The v2 API applies a precondition on a subject to the nested subjects, too,
// if its options say so, so the client sends the options of a precondition
// that has them, and a precondition without options as it is. The v1 API knows
// no options: the client sends a precondition that is not recursive without
// them, and refuses to write with a recursive one, without sending the write.
class PreconditionOptionsTest {
    private static final List<EventCandidate> EVENTS =
            List.of(new EventCandidate("https://x", "/a", "io.x.y", Map.of()));

    private static final String EVENTS_BODY =
            "\"events\":[{\"source\":\"https://x\",\"subject\":\"/a\",\"type\":\"io.x.y\",\"data\":{}}]";

    // Sends the write to a server that refuses it, and returns the body of the
    // write as the server received it. The body is compared as JSON, since
    // the order of the fields means nothing to the server.
    private static JsonNode bodyOf(ApiVersion api, List<Precondition> preconditions) {
        try (var server = FakeServer.respondingWith(api, 500, "refused")) {
            try {
                new Client(server.url(), "secret").writeEvents(EVENTS, preconditions);
            } catch (OutcomeUnknownException ex) {
                // The server refuses every write, so that its body is all that
                // the test looks at.
            }

            return Json.MAPPER.readTree(server.requests().getLast().body());
        }
    }

    private static JsonNode bodyWith(String precondition) {
        return Json.MAPPER.readTree("{" + EVENTS_BODY + ",\"preconditions\":[" + precondition + "]}");
    }

    static Stream<Arguments> preconditionsWithOptions() {
        return Stream.of(true, false).flatMap(recursive -> {
            var options = new PreconditionOptions(recursive);
            var optionsBody = ",\"options\":{\"recursive\":" + recursive + "}";

            return Stream.of(
                    Arguments.of(
                            new IsSubjectPristinePrecondition("/a", options),
                            "{\"type\":\"isSubjectPristine\",\"payload\":{\"subject\":\"/a\"" + optionsBody + "}}"),
                    Arguments.of(
                            new IsSubjectPopulatedPrecondition("/a", options),
                            "{\"type\":\"isSubjectPopulated\",\"payload\":{\"subject\":\"/a\"" + optionsBody + "}}"),
                    Arguments.of(
                            new IsSubjectOnEventIdPrecondition("/a", "0", options),
                            "{\"type\":\"isSubjectOnEventId\",\"payload\":{\"subject\":\"/a\",\"eventId\":\"0\""
                                    + optionsBody
                                    + "}}"));
        });
    }

    static Stream<Arguments> preconditionsWithoutOptions() {
        return Stream.of(
                Arguments.of(
                        new IsSubjectPristinePrecondition("/a"),
                        "{\"type\":\"isSubjectPristine\",\"payload\":{\"subject\":\"/a\"}}"),
                Arguments.of(
                        new IsSubjectPopulatedPrecondition("/a"),
                        "{\"type\":\"isSubjectPopulated\",\"payload\":{\"subject\":\"/a\"}}"),
                Arguments.of(
                        new IsSubjectOnEventIdPrecondition("/a", "0"),
                        "{\"type\":\"isSubjectOnEventId\",\"payload\":{\"subject\":\"/a\",\"eventId\":\"0\"}}"),
                Arguments.of(
                        new IsEventQlQueryTruePrecondition("FROM e IN events PROJECT INTO COUNT() == 0"),
                        "{\"type\":\"isEventQlQueryTrue\","
                                + "\"payload\":{\"query\":\"FROM e IN events PROJECT INTO COUNT() == 0\"}}"));
    }

    // The preconditions on a subject that are not recursive, with options that
    // say so, and the body that the v1 API takes for them, which is the one
    // for the same precondition without options.
    static Stream<Arguments> preconditionsThatAreNotRecursive() {
        var options = new PreconditionOptions(false);

        return Stream.of(
                Arguments.of(
                        new IsSubjectPristinePrecondition("/a", options),
                        "{\"type\":\"isSubjectPristine\",\"payload\":{\"subject\":\"/a\"}}"),
                Arguments.of(
                        new IsSubjectPopulatedPrecondition("/a", options),
                        "{\"type\":\"isSubjectPopulated\",\"payload\":{\"subject\":\"/a\"}}"),
                Arguments.of(
                        new IsSubjectOnEventIdPrecondition("/a", "0", options),
                        "{\"type\":\"isSubjectOnEventId\",\"payload\":{\"subject\":\"/a\",\"eventId\":\"0\"}}"));
    }

    static Stream<Precondition> recursivePreconditions() {
        var options = new PreconditionOptions(true);

        return Stream.of(
                new IsSubjectPristinePrecondition("/a", options),
                new IsSubjectPopulatedPrecondition("/a", options),
                new IsSubjectOnEventIdPrecondition("/a", "0", options));
    }

    @ParameterizedTest
    @MethodSource("preconditionsWithOptions")
    void sendsTheOptionsOfAPreconditionToTheV2Api(Precondition precondition, String expected) {
        assertEquals(bodyWith(expected), bodyOf(ApiVersion.V2, List.of(precondition)));
    }

    @ParameterizedTest
    @MethodSource("preconditionsWithoutOptions")
    void sendsAPreconditionWithoutOptionsToEitherApiAsItIs(Precondition precondition, String expected) {
        for (var api : ApiVersion.values()) {
            assertEquals(bodyWith(expected), bodyOf(api, List.of(precondition)), api.name());
        }
    }

    @ParameterizedTest
    @MethodSource("preconditionsThatAreNotRecursive")
    void sendsAPreconditionThatIsNotRecursiveToTheV1ApiWithoutItsOptions(Precondition precondition, String expected) {
        assertEquals(bodyWith(expected), bodyOf(ApiVersion.V1, List.of(precondition)));
    }

    @ParameterizedTest
    @MethodSource("recursivePreconditions")
    void refusesToWriteWithARecursivePreconditionIfTheServerDoesNotOfferTheV2Api(Precondition precondition) {
        try (var server = FakeServer.respondingWith(ApiVersion.V1, 200, "[]")) {
            var client = new Client(server.url(), "secret");

            var exception = assertThrows(
                    IncompatibleServerException.class, () -> client.writeEvents(EVENTS, List.of(precondition)));

            assertEquals(
                    "the server does not offer the v2 API, which a recursive precondition needs",
                    exception.getMessage());
            assertEquals(0, server.requestCount());
        }
    }

    // A recursive precondition among others is refused all the same, rather
    // than the others being checked without it.
    @Test
    void refusesToWriteWithARecursivePreconditionAmongOthersIfTheServerDoesNotOfferTheV2Api() {
        try (var server = FakeServer.respondingWith(ApiVersion.V1, 200, "[]")) {
            var client = new Client(server.url(), "secret");
            var preconditions = List.<Precondition>of(
                    new IsEventQlQueryTruePrecondition("FROM e IN events PROJECT INTO COUNT() == 0"),
                    new IsSubjectPristinePrecondition("/a", new PreconditionOptions(false)),
                    new IsSubjectPopulatedPrecondition("/b", new PreconditionOptions(true)));

            assertThrows(IncompatibleServerException.class, () -> client.writeEvents(EVENTS, preconditions));
            assertEquals(0, server.requestCount());
        }
    }
}
