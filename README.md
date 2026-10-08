# eventsourcingdb

The official Java client SDK for [EventSourcingDB](https://www.eventsourcingdb.io) – a purpose-built database for event sourcing.

EventSourcingDB enables you to build and operate event-driven applications with native support for writing, reading, and observing events. This client SDK provides convenient access to its capabilities in Java.

For more information on EventSourcingDB, see its [official documentation](https://docs.eventsourcingdb.io/).

This client SDK includes support for [Testcontainers](https://testcontainers.com/) to spin up EventSourcingDB instances in integration tests. It lives in an artifact of its own, so applications that only use the client do not depend on Testcontainers. For details, see [Using Testcontainers](#using-testcontainers).

## Getting Started

The client SDK requires Java 21 or later. Add it to your build, e.g. with Gradle:

```kotlin
dependencies {
  implementation("io.thenativeweb:eventsourcingdb:<version>")
}
```

Or with Maven:

```xml
<dependency>
  <groupId>io.thenativeweb</groupId>
  <artifactId>eventsourcingdb</artifactId>
  <version><!-- version --></version>
</dependency>
```

Import the package and create an instance by providing the URL of your EventSourcingDB instance and the API token to use:

```java
import io.thenativeweb.eventsourcingdb.Client;
import java.net.URI;

// ...

var baseUrl = URI.create("http://localhost:3000");
var apiToken = "secret";

var client = new Client(baseUrl, apiToken);
```

Then call the `ping` method to check whether the instance is reachable. If it is not, the method will throw an exception:

```java
client.ping();
```

*Note that `ping` does not require authentication, so the call may succeed even if the API token is invalid.*

If you want to verify the API token, call `verifyApiToken`. If the token is invalid, the method will throw an exception:

```java
client.verifyApiToken();
```

*Note that all exceptions the client throws are unchecked. For details, see [Handling Errors](#handling-errors).*

### Writing Events

Call the `writeEvents` method and hand over a list with one or more events. You do not have to provide all event fields – some are automatically added by the server.

Specify `source`, `subject`, `type`, and `data` according to the [CloudEvents](https://docs.eventsourcingdb.io/fundamentals/cloud-events/) format.

For `data`, you may provide any object that [Jackson](https://github.com/FasterXML/jackson) can serialize to JSON, e.g. a record or a map.

The method returns the written events, including the fields added by the server:

```java
record BookAcquired(String title, String author, String isbn) {}

var event = new EventCandidate(
  "https://library.eventsourcingdb.io",
  "/books/42",
  "io.eventsourcingdb.library.book-acquired",
  new BookAcquired("2001 – A Space Odyssey", "Arthur C. Clarke", "978-0756906788")
);

var writtenEvents = client.writeEvents(List.of(event));
```

If you want to provide trace context, use the constructor that additionally takes `traceParent` and `traceState`.

#### Using the `isSubjectPristine` precondition

If you only want to write events in case a subject (such as `/books/42`) does not yet have any events, create an `IsSubjectPristinePrecondition` and pass it in a list as the second argument:

```java
var writtenEvents = client.writeEvents(
  List.of(
    // ...
  ),
  List.of(
    new IsSubjectPristinePrecondition("/books/42")
  )
);
```

#### Using the `isSubjectPopulated` precondition

If you only want to write events in case a subject (such as `/books/42`) already has at least one event, create an `IsSubjectPopulatedPrecondition` and pass it in a list as the second argument:

```java
var writtenEvents = client.writeEvents(
  List.of(
    // ...
  ),
  List.of(
    new IsSubjectPopulatedPrecondition("/books/42")
  )
);
```

#### Using the `isSubjectOnEventId` precondition

If you only want to write events in case the last event of a subject (such as `/books/42`) has a specific ID (e.g., `0`), create an `IsSubjectOnEventIdPrecondition` and pass it in a list as the second argument:

```java
var writtenEvents = client.writeEvents(
  List.of(
    // ...
  ),
  List.of(
    new IsSubjectOnEventIdPrecondition("/books/42", "0")
  )
);
```

*Note that according to the CloudEvents standard, event IDs must be of type string.*

#### Using the `isEventQlQueryTrue` precondition

If you want to write events depending on an EventQL query, create an `IsEventQlQueryTruePrecondition`:

```java
var writtenEvents = client.writeEvents(
  List.of(
    // ...
  ),
  List.of(
    new IsEventQlQueryTruePrecondition("FROM e IN events WHERE e.type == 'io.eventsourcingdb.library.book-borrowed' PROJECT INTO COUNT() < 10")
  )
);
```

*Note that the query must return a single row with a single value, which is interpreted as a boolean.*

#### Aborting Writing

To abort writing, e.g. if the server does not answer in time, interrupt the thread that writes. For example, run the call as a task, and cancel the task if it takes too long:

```java
try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
  Future<List<Event>> writing = executor.submit(() -> client.writeEvents(
    List.of(
      // ...
    )
  ));

  try {
    var writtenEvents = writing.get(5, TimeUnit.SECONDS);
  } catch (TimeoutException ex) {
    writing.cancel(true);
  }
}
```

Writing then ends with a `CancellationException`. If the thread is interrupted before the request is sent, no events are written. If it is interrupted later, the server may have written them nevertheless, so the exception does not tell whether they were written.

### Reading Events

To read all events of a subject, call the `readEvents` method with the subject and an options object. Set the `recursive` option to `false`. This ensures that only events of the given subject are returned, not events of nested subjects.

The method returns a `Stream`, which reads the events from the server as you consume them. Since the stream holds a connection to the server, close it once you are done, e.g. using a `try`-with-resources statement:

```java
try (var events = client.readEvents(
  "/books/42",
  new ReadEventsOptions(false)
)) {
  events.forEach(event -> {
    // ...
  });
}
```

To access the data of an event, call `data` with the type to deserialize it into. Alternatively, call `data` without arguments to get the data as a `JsonNode`:

```java
var bookAcquired = event.data(BookAcquired.class);
var title = event.data().path("title").asString();
```

#### Reading From Subjects Recursively

If you want to read not only all the events of a subject, but also the events of all nested subjects, set the `recursive` option to `true`:

```java
try (var events = client.readEvents(
  "/books/42",
  new ReadEventsOptions(true)
)) {
  // ...
}
```

This also allows you to read *all* events ever written. To do so, provide `/` as the subject and set `recursive` to `true`, since all subjects are nested under the root subject.

#### Reading in Anti-Chronological Order

By default, events are read in chronological order. To read in anti-chronological order, call `withOrder` on the options and pass `Order.ANTICHRONOLOGICAL`:

```java
try (var events = client.readEvents(
  "/books/42",
  new ReadEventsOptions(false)
    .withOrder(Order.ANTICHRONOLOGICAL)
)) {
  // ...
}
```

*Note that you can also use `Order.CHRONOLOGICAL` to explicitly enforce the default order.*

#### Specifying Bounds

Sometimes you do not want to read all events, but only a range of events. For that, you can call `withLowerBound` and `withUpperBound` on the options – either one of them or even both at the same time.

Specify the ID and whether to include or exclude it, for both the lower and upper bound:

```java
try (var events = client.readEvents(
  "/books/42",
  new ReadEventsOptions(false)
    .withLowerBound(new Bound("100", BoundType.INCLUSIVE))
    .withUpperBound(new Bound("200", BoundType.EXCLUSIVE))
)) {
  // ...
}
```

#### Starting From the Latest Event of a Given Type

To read starting from the latest event of a given type, call `withFromLatestEvent` on the options and specify the subject, the type, and how to proceed if no such event exists.

Possible options are `ReadIfEventIsMissing.READ_NOTHING`, which skips reading entirely, or `ReadIfEventIsMissing.READ_EVERYTHING`, which effectively behaves as if `withFromLatestEvent` was not called:

```java
try (var events = client.readEvents(
  "/books/42",
  new ReadEventsOptions(false)
    .withFromLatestEvent(new ReadFromLatestEvent(
      "/books/42",
      "io.eventsourcingdb.library.book-borrowed",
      ReadIfEventIsMissing.READ_EVERYTHING
    ))
)) {
  // ...
}
```

*Note that `withFromLatestEvent` and `withLowerBound` can not be used at the same time.*

#### Aborting Reading

If you need to abort reading, stop consuming the stream, e.g. by using `limit` or `takeWhile`, or by leaving a loop over its `iterator`. However, this only works if there is currently an event to consume.

To abort reading independently of that, close the stream from somewhere else, e.g. from another thread:

```java
try (var events = client.readEvents(
  "/books/42",
  new ReadEventsOptions(false)
)) {
  // Somewhere else, close the stream, which will cause
  // reading to end.
  scheduler.schedule(events::close, 5, TimeUnit.SECONDS);

  events.forEach(event -> {
    // ...
  });
}
```

Reading then ends with a `CancellationException`, so that an aborted read can be told apart from a complete one. Interrupting the thread that reads has the same effect.

### Running EventQL Queries

To run an EventQL query, call the `runEventQlQuery` method and provide the query as argument. The method returns a `Stream`, which you should close once you are done:

```java
try (var rows = client.runEventQlQuery(
  "FROM e IN events PROJECT INTO e"
)) {
  rows.forEach(row -> {
    // ...
  });
}
```

*Note that each row returned by the stream is of type `JsonNode` and matches the projection specified in your query.*

#### Aborting a Query

If you need to abort a query, stop consuming the stream. However, this only works if there is currently a row to consume.

To abort the query independently of that, close the stream from somewhere else:

```java
try (var rows = client.runEventQlQuery(
  "FROM e IN events PROJECT INTO e"
)) {
  // Somewhere else, close the stream, which will cause
  // the query to end.
  scheduler.schedule(rows::close, 5, TimeUnit.SECONDS);

  rows.forEach(row -> {
    // ...
  });
}
```

The query then ends with a `CancellationException`, so that an aborted query can be told apart from a complete one.

#### Detecting a Stalled Connection

While a query runs, the server sends a heartbeat every second whenever there is no row to send. If neither a row nor a heartbeat arrives for 30 seconds, e.g. because a proxy keeps the connection open but no longer passes anything on, the query ends with a `HeartbeatTimeoutException`, and the client closes the connection:

```java
try (var rows = client.runEventQlQuery(
  "FROM e IN events PROJECT INTO e"
)) {
  rows.forEach(row -> {
    // ...
  });
} catch (HeartbeatTimeoutException ex) {
  // ...
}
```

*Note that the time you spend on a row does not count, so a slow consumer does not cause a timeout.*

### Observing Events

To observe all events of a subject, call the `observeEvents` method with the subject and an options object. Set the `recursive` option to `false`. This ensures that only events of the given subject are returned, not events of nested subjects.

The method returns a `Stream`, which you should close once you are done:

```java
try (var events = client.observeEvents(
  "/books/42",
  new ObserveEventsOptions(false)
)) {
  events.forEach(event -> {
    // ...
  });
}
```

#### Observing From Subjects Recursively

If you want to observe not only all the events of a subject, but also the events of all nested subjects, set the `recursive` option to `true`:

```java
try (var events = client.observeEvents(
  "/books/42",
  new ObserveEventsOptions(true)
)) {
  // ...
}
```

This also allows you to observe *all* events ever written. To do so, provide `/` as the subject and set `recursive` to `true`, since all subjects are nested under the root subject.

#### Specifying Bounds

Sometimes you do not want to observe all events, but only a range of events. For that, you can call `withLowerBound` on the options.

Specify the ID and whether to include or exclude it:

```java
try (var events = client.observeEvents(
  "/books/42",
  new ObserveEventsOptions(false)
    .withLowerBound(new Bound("100", BoundType.INCLUSIVE))
)) {
  // ...
}
```

#### Starting From the Latest Event of a Given Type

To observe starting from the latest event of a given type, call `withFromLatestEvent` on the options and specify the subject, the type, and how to proceed if no such event exists.

Possible options are `ObserveIfEventIsMissing.WAIT_FOR_EVENT`, which waits for an event of the given type to happen, or `ObserveIfEventIsMissing.READ_EVERYTHING`, which effectively behaves as if `withFromLatestEvent` was not called:

```java
try (var events = client.observeEvents(
  "/books/42",
  new ObserveEventsOptions(false)
    .withFromLatestEvent(new ObserveFromLatestEvent(
      "/books/42",
      "io.eventsourcingdb.library.book-borrowed",
      ObserveIfEventIsMissing.READ_EVERYTHING
    ))
)) {
  // ...
}
```

*Note that `withFromLatestEvent` and `withLowerBound` can not be used at the same time.*

#### Aborting Observing

If you need to abort observing, stop consuming the stream. However, this only works if there is currently an event to consume.

To abort observing independently of that, close the stream from somewhere else:

```java
try (var events = client.observeEvents(
  "/books/42",
  new ObserveEventsOptions(false)
)) {
  // Somewhere else, close the stream, which will cause
  // observing to end.
  scheduler.schedule(events::close, 5, TimeUnit.SECONDS);

  events.forEach(event -> {
    // ...
  });
}
```

Observing then ends with a `CancellationException`. Since observing does not end on its own, this is how it usually ends.

#### Detecting a Stalled Connection

While there are no new events, the server sends a heartbeat every second. If neither an event nor a heartbeat arrives for 30 seconds, e.g. because a proxy keeps the connection open but no longer passes anything on, observing ends with a `HeartbeatTimeoutException`, and the client closes the connection:

```java
try (var events = client.observeEvents(
  "/books/42",
  new ObserveEventsOptions(false)
)) {
  events.forEach(event -> {
    // ...
  });
} catch (HeartbeatTimeoutException ex) {
  // ...
}
```

*Note that the time you spend on an event does not count, so a slow consumer does not cause a timeout.*

### Registering an Event Schema

To register an event schema, call the `registerEventSchema` method and hand over an event type and the desired schema:

```java
client.registerEventSchema(
  "io.eventsourcingdb.library.book-acquired",
  Map.of(
    "type", "object",
    "properties", Map.of(
      "title", Map.of("type", "string"),
      "author", Map.of("type", "string"),
      "isbn", Map.of("type", "string")
    ),
    "required", List.of(
      "title",
      "author",
      "isbn"
    ),
    "additionalProperties", false
  )
);
```

### Listing Subjects

To list all subjects, call the `readSubjects` method with `/` as the base subject. The method returns a `Stream`, which you should close once you are done:

```java
try (var subjects = client.readSubjects("/")) {
  subjects.forEach(subject -> {
    // ...
  });
}
```

If you only want to list subjects within a specific branch, provide the desired base subject instead:

```java
try (var subjects = client.readSubjects("/books")) {
  // ...
}
```

#### Aborting Listing

If you need to abort listing, stop consuming the stream. However, this only works if there is currently a subject to consume.

To abort listing independently of that, close the stream from somewhere else:

```java
try (var subjects = client.readSubjects("/")) {
  // Somewhere else, close the stream, which will cause
  // reading to end.
  scheduler.schedule(subjects::close, 5, TimeUnit.SECONDS);

  subjects.forEach(subject -> {
    // ...
  });
}
```

Reading then ends with a `CancellationException`, so that an aborted read can be told apart from a complete one.

### Listing Event Types

To list all event types, call the `readEventTypes` method. The method returns a `Stream`, which you should close once you are done:

```java
try (var eventTypes = client.readEventTypes()) {
  eventTypes.forEach(eventType -> {
    // ...
  });
}
```

#### Aborting Listing

If you need to abort listing, stop consuming the stream. However, this only works if there is currently an event type to consume.

To abort listing independently of that, close the stream from somewhere else:

```java
try (var eventTypes = client.readEventTypes()) {
  // Somewhere else, close the stream, which will cause
  // reading to end.
  scheduler.schedule(eventTypes::close, 5, TimeUnit.SECONDS);

  eventTypes.forEach(eventType -> {
    // ...
  });
}
```

Reading then ends with a `CancellationException`, so that an aborted read can be told apart from a complete one.

### Listing a Specific Event Type

To list a specific event type, call the `readEventType` method with the event type as argument. The method returns the detailed event type, which includes the schema:

```java
var eventType = client.readEventType("io.eventsourcingdb.library.book-acquired");
```

### Verifying an Event's Hash

To verify the integrity of an event, call the `verifyHash` method on the event instance. This recomputes the event's hash locally and compares it to the hash stored in the event. If the hashes differ, the method throws an exception:

```java
event.verifyHash();
```

*Note that this only verifies the hash. If you also want to verify the signature, you can skip this step and call `verifySignature` directly, which performs a hash verification internally.*

### Verifying an Event's Signature

To verify the authenticity of an event, call the `verifySignature` method on the event instance. This requires the public key that matches the private key used for signing on the server.

The method first verifies the event's hash, and then checks the signature. If any verification step fails, it throws an exception:

```java
PublicKey verificationKey = // public Ed25519 key

event.verifySignature(verificationKey);
```

### Handling Errors

All exceptions the client throws are unchecked. Those that come from the client itself extend `EventSourcingDbException`.

If the server answers a request with an unexpected HTTP status code, the client throws a `DbApiException`. It contains the `statusCode` and the `reason` the server gives, so you can tell failures apart without parsing the error message:

```java
try {
  client.writeEvents(
    List.of(
      // ...
    ),
    List.of(
      new IsSubjectPristinePrecondition("/books/42")
    )
  );
} catch (DbApiException ex) {
  switch (ex.statusCode()) {
    case 409 -> {
      // ex.reason() tells why, e.g. "state conflict: precondition failed".
    }
    case 401 -> {
      // ...
    }
    default -> {
      // ...
    }
  }
}
```

*Note that different failures may share a status code: a failed precondition and an event that does not match its schema are both answered with `409`, and only their reasons differ.*

If the server is not an EventSourcingDB, e.g. because a proxy answers in its place, the client throws an `InvalidServerHeaderException`:

```java
try {
  client.ping();
} catch (InvalidServerHeaderException ex) {
  // ...
}
```

If observing events or running an EventQL query receives nothing for 30 seconds, not even a heartbeat, e.g. because a proxy keeps the connection open but no longer passes anything on, the client closes the connection, and observing or the query ends with a `HeartbeatTimeoutException`.

If a request is aborted, either because its thread is interrupted or because its stream is closed from somewhere else, it ends with a `CancellationException`.

### Using Testcontainers

The test container lives in the `eventsourcingdb-testcontainers` artifact. Add it to the dependencies of your tests, e.g. with Gradle:

```kotlin
dependencies {
  testImplementation("io.thenativeweb:eventsourcingdb-testcontainers:<version>")
}
```

Create a `Container`, start it, get a client, run your test code, and stop the container eventually:

```java
import io.thenativeweb.eventsourcingdb.testcontainers.Container;

// ...

var container = new Container();
container.start();

try {
  var client = container.getClient();

  // ...
} finally {
  container.stop();
}
```

To check if the test container is running, call the `isRunning` method:

```java
var isRunning = container.isRunning();
```

#### Configuring the Container Instance

By default, `Container` uses the `latest` tag of the official EventSourcingDB Docker image. To change that, call the `withImageTag` method:

```java
var container = new Container()
  .withImageTag("1.0.0");
```

Similarly, you can configure the port to use and the API token. Call the `withPort` or the `withApiToken` method respectively:

```java
var container = new Container()
  .withPort(4000)
  .withApiToken("secret");
```

If you want to sign events, call the `withSigningKey` method. This generates a new signing and verification key pair, and hands the signing key to the container:

```java
var container = new Container()
  .withSigningKey();
```

You can retrieve the private key (for signing) and the public key (for verifying signatures):

```java
var signingKey = container.getSigningKey();
var verificationKey = container.getVerificationKey();
```

The `signingKey` is the private key EventSourcingDB signs events with. The `verificationKey` can be passed to `verifySignature` when verifying events read from the database.

#### Configuring the Client Manually

In case you need to set up the client yourself, use the following methods to get details on the container:

- `getHost()` returns the host name
- `getMappedPort()` returns the port
- `getBaseUrl()` returns the full URL of the container
- `getApiToken()` returns the API token
