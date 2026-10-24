package io.thenativeweb.eventsourcingdb;

/**
 * The codes of the errors that EventSourcingDB reports, as {@link DbApiException#code()} returns them. Each code is the
 * URL of the page in the documentation of EventSourcingDB that explains the error, e.g.
 * {@code https://docs.eventsourcingdb.io/errors/precondition-violated}, so compare it with these constants rather than
 * with a text of your own:
 *
 * <pre>{@code
 * switch (ex.code()) {
 *     case ErrorCode.PRECONDITION_VIOLATED -> {
 *         // ...
 *     }
 *     default -> {
 *         // ...
 *     }
 * }
 * }</pre>
 *
 * <p>The server may report codes that a newer release of EventSourcingDB introduces, for which there is no constant
 * yet.
 */
public final class ErrorCode {
    // A code is this URL plus the name of the error, so every code links to
    // the documentation of its error.
    private static final String BASE_URL = "https://docs.eventsourcingdb.io/errors/";

    /** The code {@code subject-missing}: a request leaves out the subject. */
    public static final String SUBJECT_MISSING = BASE_URL + "subject-missing";

    /** The code {@code subject-malformed}: a subject is malformed. */
    public static final String SUBJECT_MALFORMED = BASE_URL + "subject-malformed";

    /** The code {@code order-unknown}: the order of a read is neither chronological nor antichronological. */
    public static final String ORDER_UNKNOWN = BASE_URL + "order-unknown";

    /** The code {@code event-id-missing}: a request leaves out the ID of an event. */
    public static final String EVENT_ID_MISSING = BASE_URL + "event-id-missing";

    /** The code {@code event-id-malformed}: the ID of an event is malformed. */
    public static final String EVENT_ID_MALFORMED = BASE_URL + "event-id-malformed";

    /** The code {@code bound-type-missing}: a bound leaves out its type. */
    public static final String BOUND_TYPE_MISSING = BASE_URL + "bound-type-missing";

    /** The code {@code bound-type-unknown}: the type of a bound is neither inclusive nor exclusive. */
    public static final String BOUND_TYPE_UNKNOWN = BASE_URL + "bound-type-unknown";

    /** The code {@code range-unsatisfiable}: the bounds of a read select no range at all. */
    public static final String RANGE_UNSATISFIABLE = BASE_URL + "range-unsatisfiable";

    /** The code {@code from-latest-event-incomplete}: a {@code fromLatestEvent} leaves out one of its fields. */
    public static final String FROM_LATEST_EVENT_INCOMPLETE = BASE_URL + "from-latest-event-incomplete";

    /** The code {@code if-event-is-missing-unknown}: the {@code ifEventIsMissing} of a {@code fromLatestEvent} is unknown. */
    public static final String IF_EVENT_IS_MISSING_UNKNOWN = BASE_URL + "if-event-is-missing-unknown";

    /** The code {@code selection-conflicting}: a read combines options that exclude each other, e.g. a lower bound and a {@code fromLatestEvent}. */
    public static final String SELECTION_CONFLICTING = BASE_URL + "selection-conflicting";

    /** The code {@code event-type-missing}: a request leaves out the event type. */
    public static final String EVENT_TYPE_MISSING = BASE_URL + "event-type-missing";

    /** The code {@code event-type-malformed}: an event type is malformed. */
    public static final String EVENT_TYPE_MALFORMED = BASE_URL + "event-type-malformed";

    /** The code {@code event-type-unknown}: an event type has neither events nor a registered schema. */
    public static final String EVENT_TYPE_UNKNOWN = BASE_URL + "event-type-unknown";

    /** The code {@code source-missing}: an event leaves out its source. */
    public static final String SOURCE_MISSING = BASE_URL + "source-missing";

    /** The code {@code source-malformed}: the source of an event is malformed. */
    public static final String SOURCE_MALFORMED = BASE_URL + "source-malformed";

    /** The code {@code data-missing}: an event leaves out its data. */
    public static final String DATA_MISSING = BASE_URL + "data-missing";

    /** The code {@code traceparent-malformed}: the {@code traceparent} of an event is malformed. */
    public static final String TRACEPARENT_MALFORMED = BASE_URL + "traceparent-malformed";

    /** The code {@code tracestate-malformed}: the {@code tracestate} of an event is malformed. */
    public static final String TRACESTATE_MALFORMED = BASE_URL + "tracestate-malformed";

    /** The code {@code tracestate-without-traceparent}: an event has a {@code tracestate}, but no {@code traceparent}. */
    public static final String TRACESTATE_WITHOUT_TRACEPARENT = BASE_URL + "tracestate-without-traceparent";

    /** The code {@code events-missing}: a write leaves out the events. */
    public static final String EVENTS_MISSING = BASE_URL + "events-missing";

    /** The code {@code precondition-type-missing}: a precondition leaves out its type. */
    public static final String PRECONDITION_TYPE_MISSING = BASE_URL + "precondition-type-missing";

    /** The code {@code precondition-unknown}: the type of a precondition is unknown. */
    public static final String PRECONDITION_UNKNOWN = BASE_URL + "precondition-unknown";

    /** The code {@code payload-missing}: a precondition leaves out its payload. */
    public static final String PAYLOAD_MISSING = BASE_URL + "payload-missing";

    /** The code {@code license-invalid}: the license is invalid, e.g. because it has expired, so events can not be written. */
    public static final String LICENSE_INVALID = BASE_URL + "license-invalid";

    /** The code {@code capacity-exceeded}: a write would exceed the capacity of the database. */
    public static final String CAPACITY_EXCEEDED = BASE_URL + "capacity-exceeded";

    /** The code {@code event-schema-violated}: an event does not match the schema of its type. */
    public static final String EVENT_SCHEMA_VIOLATED = BASE_URL + "event-schema-violated";

    /** The code {@code event-schema-unregistered}: an event type requires a schema, but none is registered. */
    public static final String EVENT_SCHEMA_UNREGISTERED = BASE_URL + "event-schema-unregistered";

    /** The code {@code event-schema-missing}: a registration leaves out the schema. */
    public static final String EVENT_SCHEMA_MISSING = BASE_URL + "event-schema-missing";

    /** The code {@code event-schema-malformed}: a schema is malformed. */
    public static final String EVENT_SCHEMA_MALFORMED = BASE_URL + "event-schema-malformed";

    /** The code {@code event-schema-not-object}: a schema does not describe an object. */
    public static final String EVENT_SCHEMA_NOT_OBJECT = BASE_URL + "event-schema-not-object";

    /** The code {@code event-schema-already-registered}: a schema is already registered for the event type. */
    public static final String EVENT_SCHEMA_ALREADY_REGISTERED = BASE_URL + "event-schema-already-registered";

    /** The code {@code last-audited-event-missing}: an audit leaves out the event the previous audit ended at. */
    public static final String LAST_AUDITED_EVENT_MISSING = BASE_URL + "last-audited-event-missing";

    /** The code {@code last-audited-event-mismatched}: the event the previous audit ended at does not match the stored one. */
    public static final String LAST_AUDITED_EVENT_MISMATCHED = BASE_URL + "last-audited-event-mismatched";

    /** The code {@code last-audited-event-unknown}: the event the previous audit ended at does not exist. */
    public static final String LAST_AUDITED_EVENT_UNKNOWN = BASE_URL + "last-audited-event-unknown";

    /** The code {@code event-hash-missing}: a request leaves out the hash of an event. */
    public static final String EVENT_HASH_MISSING = BASE_URL + "event-hash-missing";

    /** The code {@code event-hash-malformed}: the hash of an event is malformed. */
    public static final String EVENT_HASH_MALFORMED = BASE_URL + "event-hash-malformed";

    /** The code {@code event-chain-broken}: the hashes of the stored events do not form an unbroken chain. */
    public static final String EVENT_CHAIN_BROKEN = BASE_URL + "event-chain-broken";

    /** The code {@code last-backed-up-event-missing}: a backup leaves out the event the previous backup ended at. */
    public static final String LAST_BACKED_UP_EVENT_MISSING = BASE_URL + "last-backed-up-event-missing";

    /** The code {@code last-backed-up-event-mismatched}: the event the previous backup ended at does not match the stored one. */
    public static final String LAST_BACKED_UP_EVENT_MISMATCHED = BASE_URL + "last-backed-up-event-mismatched";

    /** The code {@code last-backed-up-event-unknown}: the event the previous backup ended at does not exist. */
    public static final String LAST_BACKED_UP_EVENT_UNKNOWN = BASE_URL + "last-backed-up-event-unknown";

    /** The code {@code store-not-empty}: a restore requires an empty database, but the database holds events. */
    public static final String STORE_NOT_EMPTY = BASE_URL + "store-not-empty";

    /** The code {@code event-hash-version-unknown}: the hash of an event is of a version the server does not know. */
    public static final String EVENT_HASH_VERSION_UNKNOWN = BASE_URL + "event-hash-version-unknown";

    /** The code {@code backup-malformed}: a backup to restore is malformed. */
    public static final String BACKUP_MALFORMED = BASE_URL + "backup-malformed";

    /** The code {@code backup-incomplete}: a backup to restore was cut short. */
    public static final String BACKUP_INCOMPLETE = BASE_URL + "backup-incomplete";

    /** The code {@code backup-format-unknown}: a backup to restore is of a format the server does not know. */
    public static final String BACKUP_FORMAT_UNKNOWN = BASE_URL + "backup-format-unknown";

    /** The code {@code full-backup-missing}: the increments of a backup to restore lack the full backup they build on. */
    public static final String FULL_BACKUP_MISSING = BASE_URL + "full-backup-missing";

    /** The code {@code backup-increment-disconnected}: an increment of a backup to restore does not follow on the part before it. */
    public static final String BACKUP_INCREMENT_DISCONNECTED = BASE_URL + "backup-increment-disconnected";

    /** The code {@code backup-failed}: a backup to restore holds the error that ended it when it was created. */
    public static final String BACKUP_FAILED = BASE_URL + "backup-failed";

    /** The code {@code backup-unreadable}: a backup to restore could not be read to its end. */
    public static final String BACKUP_UNREADABLE = BASE_URL + "backup-unreadable";

    /** The code {@code event-too-large}: an event exceeds the size the server accepts. */
    public static final String EVENT_TOO_LARGE = BASE_URL + "event-too-large";

    /** The code {@code data-malformed}: the data of an event is malformed. */
    public static final String DATA_MALFORMED = BASE_URL + "data-malformed";

    /** The code {@code query-missing}: a request leaves out the EventQL query. */
    public static final String QUERY_MISSING = BASE_URL + "query-missing";

    /** The code {@code query-malformed}: an EventQL query is malformed. */
    public static final String QUERY_MALFORMED = BASE_URL + "query-malformed";

    /** The code {@code query-unevaluable}: an EventQL query can not be evaluated. */
    public static final String QUERY_UNEVALUABLE = BASE_URL + "query-unevaluable";

    /** The code {@code query-not-boolean}: the EventQL query of a precondition does not result in a boolean. */
    public static final String QUERY_NOT_BOOLEAN = BASE_URL + "query-not-boolean";

    /** The code {@code precondition-violated}: a precondition does not hold. */
    public static final String PRECONDITION_VIOLATED = BASE_URL + "precondition-violated";

    /** The code {@code store-read-only}: the database is in read-only mode, so it does not accept writes. */
    public static final String STORE_READ_ONLY = BASE_URL + "store-read-only";

    /** The code {@code read-only-mode-already-enabled}: the read-only mode is enabled already. */
    public static final String READ_ONLY_MODE_ALREADY_ENABLED = BASE_URL + "read-only-mode-already-enabled";

    /** The code {@code disk-full}: the disk of the database is full. */
    public static final String DISK_FULL = BASE_URL + "disk-full";

    /** The code {@code restore-in-progress}: a restore is in progress, so the database does not accept the request. */
    public static final String RESTORE_IN_PROGRESS = BASE_URL + "restore-in-progress";

    /** The code {@code store-failed}: the store has failed before, so it refuses writes until the database is restarted. */
    public static final String STORE_FAILED = BASE_URL + "store-failed";

    /** The code {@code indexes-rebuilding}: the database is rebuilding its indexes, so it does not answer the request yet. */
    public static final String INDEXES_REBUILDING = BASE_URL + "indexes-rebuilding";

    /** The code {@code server-shutting-down}: the server is shutting down. */
    public static final String SERVER_SHUTTING_DOWN = BASE_URL + "server-shutting-down";

    private ErrorCode() {}
}
