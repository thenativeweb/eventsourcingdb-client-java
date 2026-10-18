package io.thenativeweb.eventsourcingdb;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.thenativeweb.eventsourcingdb.Operations.Operation;
import java.util.stream.Stream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

// The v1 API tells its errors apart by their texts only. The texts are those
// that EventSourcingDB 1.2.0 sends, which go on with what the error is about.
class V1ErrorCodesTest {
    private static Operation operation(String action) {
        return Operations.all().stream()
                .filter(operation -> operation.action().equals(action))
                .findFirst()
                .orElseThrow();
    }

    static Stream<Arguments> errorsWithACode() {
        return Stream.of(
                Arguments.of(
                        operation("write events"),
                        409,
                        "state conflict: precondition failed",
                        ErrorCode.PRECONDITION_VIOLATED),
                Arguments.of(
                        operation("write events"),
                        409,
                        "state conflict: exceeded max store capacity",
                        ErrorCode.CAPACITY_EXCEEDED),
                Arguments.of(
                        operation("write events"),
                        409,
                        "schema conflict: event candidate does not match schema: missing properties: 'value'",
                        ErrorCode.EVENT_SCHEMA_VIOLATED),
                Arguments.of(
                        operation("register event schema"),
                        409,
                        "schema conflict: schema already exists",
                        ErrorCode.EVENT_SCHEMA_ALREADY_REGISTERED),
                Arguments.of(
                        operation("register event schema"),
                        409,
                        "schema conflict: event with ID 0: missing properties: 'value'",
                        ErrorCode.EVENT_SCHEMA_VIOLATED),
                Arguments.of(
                        operation("read events"),
                        409,
                        "state conflict: fromLatestEvent results in an event ID greater than upperBound ID",
                        ErrorCode.RANGE_UNSATISFIABLE),
                Arguments.of(
                        operation("read event type"),
                        404,
                        "event type 'io.eventsourcingdb.test' not found",
                        ErrorCode.EVENT_TYPE_UNKNOWN),
                Arguments.of(operation("write events"), 507, "insufficient storage", ErrorCode.LICENSE_INVALID));
    }

    static Stream<Arguments> everyRequest() {
        return Operations.all().stream().map(Arguments::of);
    }

    // Each text is close to one that has a code, but either the text, the
    // status code, or the request differs.
    static Stream<Arguments> errorsWithoutACode() {
        return Stream.of(
                Arguments.of(operation("write events"), 409, "state conflict: something else"),
                Arguments.of(operation("write events"), 409, "schema conflict: schema already exists"),
                Arguments.of(operation("write events"), 400, "state conflict: precondition failed"),
                Arguments.of(operation("register event schema"), 409, "schema conflict: something else"),
                Arguments.of(operation("register event schema"), 507, "insufficient storage"),
                Arguments.of(
                        operation("read events"),
                        400,
                        "state conflict: fromLatestEvent results in an event ID greater than upperBound ID"),
                Arguments.of(operation("read events"), 404, "event type 'io.eventsourcingdb.test' not found"),
                Arguments.of(operation("read events"), 409, "state conflict: precondition failed"),
                Arguments.of(operation("read event type"), 400, "invalid event type: 'io.eventsourcingdb.'"),
                Arguments.of(operation("ping"), 503, "the database is shutting down"),
                Arguments.of(operation("ping"), 500, "the current operation mode conflicts with the given request"));
    }

    private static void assertCode(Operation operation, int status, String description, String code) {
        try (var server = FakeServer.respondingWith(ApiVersion.V1, status, description + "\n")) {
            var client = new Client(server.url(), "secret");

            var exception =
                    assertThrows(DbApiException.class, () -> operation.call().accept(client));

            assertEquals(status, exception.status());
            assertEquals(description, exception.description());
            assertEquals(code, exception.code());
        }
    }

    @ParameterizedTest
    @MethodSource("errorsWithACode")
    void setsTheCodeOfTheV2ApiForAnErrorThatClearlyMatchesOne(
            Operation operation, int status, String description, String code) {
        assertCode(operation, status, description, code);
    }

    @ParameterizedTest
    @MethodSource("everyRequest")
    void setsTheCodeForAnOperationModeThatDoesNotPermitARequest(Operation operation) {
        assertCode(
                operation,
                503,
                "the current operation mode conflicts with the given request: writing is not permitted at this time",
                ErrorCode.STORE_READ_ONLY);
    }

    @ParameterizedTest
    @MethodSource("errorsWithoutACode")
    void leavesTheCodeEmptyForEveryOtherError(Operation operation, int status, String description) {
        assertCode(operation, status, description, "");
    }
}
