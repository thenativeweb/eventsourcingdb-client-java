package io.thenativeweb.eventsourcingdb;

// V1ErrorCodes maps the errors of the v1 API that clearly match an error of
// the v2 API to the code of the v2 API. The v1 API tells its errors apart by
// their texts only, so each is matched from the start of the exact text that
// EventSourcingDB 1.2.0 sends, which may go on with what the error is about.
final class V1ErrorCodes {
    private static final int NOT_FOUND = 404;
    private static final int CONFLICT = 409;
    private static final int SERVICE_UNAVAILABLE = 503;
    private static final int INSUFFICIENT_STORAGE = 507;

    // Every request may meet an operation mode that does not permit it, e.g.
    // a write to a database in read-only mode.
    static final CodeMapping ANY_REQUEST = (status, description) -> status == SERVICE_UNAVAILABLE
                    && description.startsWith("the current operation mode conflicts with the given request")
            ? ErrorCode.STORE_READ_ONLY
            : "";

    // A write is refused with 507 only once the license has expired.
    static final CodeMapping WRITE_EVENTS = (status, description) -> {
        if (status == CONFLICT && description.startsWith("state conflict: precondition failed")) {
            return ErrorCode.PRECONDITION_VIOLATED;
        }
        if (status == CONFLICT && description.startsWith("state conflict: exceeded max store capacity")) {
            return ErrorCode.CAPACITY_EXCEEDED;
        }
        if (status == CONFLICT && description.startsWith("schema conflict: event candidate does not match schema")) {
            return ErrorCode.EVENT_SCHEMA_VIOLATED;
        }
        if (status == INSUFFICIENT_STORAGE) {
            return ErrorCode.LICENSE_INVALID;
        }

        return ANY_REQUEST.codeOf(status, description);
    };

    // A schema that an event written before does not match is refused with
    // the ID of that event.
    static final CodeMapping REGISTER_EVENT_SCHEMA = (status, description) -> {
        if (status == CONFLICT && description.startsWith("schema conflict: schema already exists")) {
            return ErrorCode.EVENT_SCHEMA_ALREADY_REGISTERED;
        }
        if (status == CONFLICT && description.startsWith("schema conflict: event with ID")) {
            return ErrorCode.EVENT_SCHEMA_VIOLATED;
        }

        return ANY_REQUEST.codeOf(status, description);
    };

    static final CodeMapping READ_EVENTS = (status, description) -> status == CONFLICT
                    && description.startsWith(
                            "state conflict: fromLatestEvent results in an event ID greater than upperBound ID")
            ? ErrorCode.RANGE_UNSATISFIABLE
            : ANY_REQUEST.codeOf(status, description);

    static final CodeMapping READ_EVENT_TYPE = (status, description) ->
            status == NOT_FOUND ? ErrorCode.EVENT_TYPE_UNKNOWN : ANY_REQUEST.codeOf(status, description);

    private V1ErrorCodes() {}
}
