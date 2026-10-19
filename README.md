# eventsourcingdb

The official Java client SDK for [EventSourcingDB](https://www.eventsourcingdb.io) – a purpose-built database for event sourcing.

EventSourcingDB enables you to build and operate event-driven applications with native support for writing, reading, and observing events. This client SDK provides convenient access to its capabilities in Java.

For more information on EventSourcingDB, see its [official documentation](https://docs.eventsourcingdb.io/).

This client SDK includes support for [Testcontainers](https://testcontainers.com/) to spin up EventSourcingDB instances in integration tests. It lives in an artifact of its own, so applications that only use the client do not depend on Testcontainers. For details, see [Using Testcontainers](#using-testcontainers).

For applications built with [Spring Boot](https://spring.io/projects/spring-boot), there is a starter that sets up the client from the configuration of the application. For details, see [Using Spring Boot](#using-spring-boot).

## Getting Started

The client SDK requires Java 21 or later. It is built against the Jackson version that the latest release of Spring Boot uses, and requires that version of Jackson or a later one. Add it to your build, e.g. with Gradle:

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

If your application uses the Java module system, require the module `io.thenativeweb.eventsourcingdb` in its `module-info.java`.

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

### Talking to Servers With and Without the v2 API

The client works with servers that offer the v2 API, and with servers that do not, such as EventSourcingDB 1.2.0. Creating a client sends no request. The first request of a client asks the server whether it offers the v2 API, and the client remembers the answer: from then on, it uses the v2 API if the server offers it, and the v1 API otherwise. If asking fails, e.g. because the server can not be reached, the first request fails, and the next one asks again.

The v2 API reads with the HTTP method `QUERY` ([RFC 10008](https://www.rfc-editor.org/rfc/rfc10008)), which carries a body, as `POST` does, but changes nothing. If a proxy, a load balancer, or a firewall sits in front of the database, it has to let `QUERY` through. The client does not fall back to `POST`.

### Configuring the HTTP Client

By default, the client creates an HTTP client of its own. If you need to configure what it does not, e.g. a shorter connect timeout, a proxy, or TLS, create a `java.net.http.HttpClient` yourself, and hand it over using `ClientOptions`:

```java
var httpClient = HttpClient.newBuilder()
  .connectTimeout(Duration.ofSeconds(5))
  .proxy(ProxySelector.of(new InetSocketAddress("proxy.example.com", 8080)))
  .build();

var client = new Client(baseUrl, apiToken, new ClientOptions()
  .withHttpClient(httpClient));
```

*Note that the HTTP client belongs to you, so the client does not close it.*

### Configuring the Stall Timeout

A request fails once nothing arrives from the server for 30 seconds, e.g. because the server can not be reached, or because a proxy keeps the connection open but no longer passes anything on. This covers every request, from connecting and waiting for the response to reading it, and the time starts over with everything that arrives, be it a byte, an event, or a heartbeat. So it does not limit how long a request may take: a read or an observation that keeps receiving events or heartbeats is never cut off, and the time you spend on an event does not count. A request that stalls ends with a `ConnectionStalledException`, and the client closes the connection. Its cause is a `java.net.http.HttpTimeoutException` if the response did not arrive in time, and the `IOException` that ended reading it if it stopped in the middle. To change the time, hand it over using `ClientOptions`:

```java
var client = new Client(baseUrl, apiToken, new ClientOptions()
  .withStallTimeout(Duration.ofSeconds(10)));
```

### Configuring the Serialization of Event Data

The client serializes and deserializes the data of events with a JSON mapper of its own. To apply the configuration of your application instead, e.g. its modules or its naming strategy, hand over your `JsonMapper` using `ClientOptions`:

```java
var client = new Client(baseUrl, apiToken, new ClientOptions()
  .withDataMapper(jsonMapper));
```

*Note that the mapper applies to the data of events only. The client reads everything else the server sends with a JSON mapper of its own, so that the configuration of your application can not break it.*

### Closing the Client

The client holds connections and threads. Close it once you are done, e.g. when your application shuts down, or use it in a `try`-with-resources statement:

```java
try (var client = new Client(baseUrl, apiToken)) {
  // ...
}
```

Closing the client ends everything it is doing at once: requests that are running and streams that are open end with a `CancellationException`, as if they had been aborted. A write that the client has started to send ends with an `OutcomeUnknownException` instead, whose cause is the `CancellationException`, since the server may have stored it nevertheless, see [Knowing Whether a Write Was Stored](#knowing-whether-a-write-was-stored). Every request afterwards throws an `IllegalStateException`.

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

If the thread is interrupted before the client starts to send the request, no events are written, and writing ends with a `CancellationException`. If it is interrupted later, even while the client is still connecting to the server, the server may have written them nevertheless, so writing ends with an `OutcomeUnknownException`, whose cause is the `CancellationException`. For details, see [Knowing Whether a Write Was Stored](#knowing-whether-a-write-was-stored).

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

To get each row as a type of your own instead, e.g. a record that matches the projection, hand over its class. The client deserializes each row with its data mapper:

```java
record BookTitle(String title) {}

try (var rows = client.runEventQlQuery(
  "FROM e IN events PROJECT INTO { title: e.data.title }",
  BookTitle.class
)) {
  rows.forEach(row -> {
    // ...
  });
}
```

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

While a query runs, the server sends a heartbeat every second whenever there is no row to send. If neither a row nor a heartbeat arrives for the [stall timeout](#configuring-the-stall-timeout), 30 seconds by default, e.g. because a proxy keeps the connection open but no longer passes anything on, the query ends with a `ConnectionStalledException`, and the client closes the connection:

```java
try (var rows = client.runEventQlQuery(
  "FROM e IN events PROJECT INTO e"
)) {
  rows.forEach(row -> {
    // ...
  });
} catch (ConnectionStalledException ex) {
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

Observing does not end on its own. If the server ends it nevertheless, which it does only after a failure it can not report within the stream, the stream ends with an `EventSourcingDbException`, so that you can observe again.

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

While there are no new events, the server sends a heartbeat every second. If neither an event nor a heartbeat arrives for the [stall timeout](#configuring-the-stall-timeout), 30 seconds by default, e.g. because a proxy keeps the connection open but no longer passes anything on, observing ends with a `ConnectionStalledException`, and the client closes the connection:

```java
try (var events = client.observeEvents(
  "/books/42",
  new ObserveEventsOptions(false)
)) {
  events.forEach(event -> {
    // ...
  });
} catch (ConnectionStalledException ex) {
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

To list subjects, call the `readSubjects` method with a subject and an options object. Set the `recursive` option to `true` to list the subject and all subjects nested in it. To list all subjects, provide `/` as the subject. The method returns a `Stream`, which you should close once you are done:

```java
try (var subjects = client.readSubjects(
  "/",
  new ReadSubjectsOptions(true)
)) {
  subjects.forEach(subject -> {
    // ...
  });
}
```

If you only want to list subjects within a specific branch, provide the desired subject instead:

```java
try (var subjects = client.readSubjects(
  "/books",
  new ReadSubjectsOptions(true)
)) {
  // ...
}
```

If you set the `recursive` option to `false`, the stream holds the subject alone, provided that there are events within it. This tells whether a subject exists:

```java
try (var subjects = client.readSubjects(
  "/books/42",
  new ReadSubjectsOptions(false)
)) {
  var exists = subjects.findAny().isPresent();
}
```

#### Aborting Listing

If you need to abort listing, stop consuming the stream. However, this only works if there is currently a subject to consume.

To abort listing independently of that, close the stream from somewhere else:

```java
try (var subjects = client.readSubjects(
  "/",
  new ReadSubjectsOptions(true)
)) {
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

Each event type tells its name with `type`, whether events of the type were written with `hasEvents`, and the schema registered for it with `schema`, if there is one. An event type without events exists only because a schema is registered for it.

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

To verify the integrity of an event, call the `verifyHash` method on the event instance. This recomputes the event's hash locally and compares it to the hash stored in the event. If the hashes differ, the method throws a `HashVerificationFailedException`:

```java
event.verifyHash();
```

The hash of an event tells which version of the hash it is: a hash of version 2 starts with `esdb:hash:v2:`, and a hash of version 1 has no prefix. EventSourcingDB writes version 2 since version 1.3, and events written before keep their hash of version 1, so `verifyHash` supports both. If the hash is of a version the client does not know, e.g. one that starts with `esdb:hash:v3:`, the method throws an `UnsupportedHashVersionException`, whose message names the version. This does not mean that the event was tampered with, only that the client can not verify it; update the client to do so.

*Note that this only verifies the hash. If you also want to verify the signature, you can skip this step and call `verifySignature` directly, which performs a hash verification internally.*

### Verifying an Event's Signature

To verify the authenticity of an event, call the `verifySignature` method on the event instance. This requires the public key that matches the private key used for signing on the server.

The method first verifies the event's hash, and then checks the signature. If the hash does not verify, it throws a `HashVerificationFailedException`, and if the signature does not, a `SignatureVerificationFailedException`. If the event has no signature, or the key is no Ed25519 key, it throws an `EventSourcingDbException`, and if the signature is not hexadecimal, an `IllegalArgumentException`:

```java
PublicKey verificationKey = // public Ed25519 key

event.verifySignature(verificationKey);
```

### Handling Errors

All exceptions the client throws are unchecked. Those that come from the client itself extend `EventSourcingDbException`.

If the server reports an error, the client throws a `DbApiException`. It contains the `code` of the error, together with its `description`, its `details`, and the HTTP `status`, so you can tell failures apart without parsing the error message. The code is the URL of the page in the documentation that explains the error, e.g. `https://docs.eventsourcingdb.io/errors/precondition-violated`. Compare it with the constants of `ErrorCode`:

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
  switch (ex.code()) {
    case ErrorCode.PRECONDITION_VIOLATED -> {
      // ...
    }
    case ErrorCode.EVENT_SCHEMA_VIOLATED -> {
      // ex.description() tells why, and ex.details() which event.
    }
    default -> {
      // ...
    }
  }
}
```

The server reports an error in one of two ways. If it accepts a request and the request fails nevertheless, e.g. because a precondition does not hold, the status is `200`, and the code names the error. If it refuses a request, e.g. because the API token is invalid, the status is the status code it answered with, e.g. `401`, and the code is empty.

A server without the v2 API reports every error with a status code, e.g. a precondition that does not hold with `409`. For those errors that clearly match an error of the v2 API, the client sets the code of the v2 API nevertheless, so that you can handle them alike, whichever API the server offers.

If the server refuses a request because the client sent too many, the status is `429`, and `retryAfter` tells how long the server asks you to wait before you send the request again, if it says so. The client does not repeat the request on its own:

```java
try {
  client.ping();
} catch (DbApiException ex) {
  if (ex.status() == 429) {
    var timeToWait = ex.retryAfter().orElse(Duration.ofSeconds(1));
    // ...
  }
}
```

If the server is not an EventSourcingDB, e.g. because a proxy answers in its place, the client throws an `InvalidServerHeaderException`:

```java
try {
  client.ping();
} catch (InvalidServerHeaderException ex) {
  // ...
}
```

If verifying an event fails, the client throws a `HashVerificationFailedException` or a `SignatureVerificationFailedException`, and if the hash of the event is of a version the client does not know, an `UnsupportedHashVersionException`. A signature that is not hexadecimal throws an `IllegalArgumentException` instead. For details, see [Verifying an Event's Hash](#verifying-an-events-hash) and [Verifying an Event's Signature](#verifying-an-events-signature).

If a request receives nothing from the server for the [stall timeout](#configuring-the-stall-timeout), 30 seconds by default, e.g. because the server can not be reached, or because a proxy keeps the connection open but no longer passes anything on, the client closes the connection, and the request ends with a `ConnectionStalledException`.

If a request is aborted, because its thread is interrupted, because its stream is closed from somewhere else, or because the client is closed, it ends with a `CancellationException`. A write that the client has started to send ends with an `OutcomeUnknownException` instead, whose cause is the `CancellationException`, see [Knowing Whether a Write Was Stored](#knowing-whether-a-write-was-stored).

If the server ends a stream before it is complete, e.g. because of a failure it can not report within the stream, the stream ends with an `EventSourcingDbException`. This includes observing events, which never ends on its own.

#### Knowing Whether a Write Was Stored

The server never stops a write halfway, so a write whose answer is lost may have been stored nevertheless. If writing events or registering an event schema fails in a way that leaves open whether it was stored, the client throws an `OutcomeUnknownException`, whose cause tells what failed, e.g. a `ConnectionStalledException`, a broken connection, a `CancellationException` if the thread was interrupted once the client had started to send the request, or a `DbApiException` with status `500`. Find out whether the write was stored, e.g. by reading the events of the subject, before you write it again:

```java
try {
  client.writeEvents(
    List.of(
      // ...
    )
  );
} catch (OutcomeUnknownException ex) {
  // The events may have been written, so check before writing them again.
}
```

A write that certainly was not stored throws the failure itself instead: an `EventSourcingDbException` if the client could not connect to the server at all, and a `DbApiException` if the server refused the write, e.g. because a precondition does not hold, or because the client sent too many requests.

*Note that the networking property `jdk.httpclient.enableAllMethodRetry` of the JDK, set as a system property or in the `conf/net.properties` of the JDK, makes the JDK send a write again if the connection closes before the answer arrives, which can store the write twice. If it is set when a client is created, or when a write fails, the client can not tell a write that could not connect from one that was sent again, so from then on, it throws an `OutcomeUnknownException` for that, too.*

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

To get a client with options of your own, e.g. the data mapper of your application, hand them to `getClient`:

```java
var client = container.getClient(new ClientOptions()
  .withDataMapper(jsonMapper));
```

`Container` is a Testcontainers `GenericContainer`, so you can use it wherever Testcontainers containers work, e.g. in a `try`-with-resources statement:

```java
try (var container = new Container()) {
  container.start();

  // ...
}
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

### Using Spring Boot

The Spring Boot support lives in the `eventsourcingdb-spring-boot-starter` artifact. It requires Spring Boot 4. Add it to your dependencies, e.g. with Gradle:

```kotlin
dependencies {
  implementation("io.thenativeweb:eventsourcingdb-spring-boot-starter:<version>")
}
```

Then set the URL of your EventSourcingDB instance and the API token to use, e.g. in `application.properties`:

```properties
eventsourcingdb.base-url=http://localhost:3000
eventsourcingdb.api-token=secret
```

The starter creates a client from them, which you can inject wherever you need it:

```java
@Service
public class BookService {
  private final Client client;

  public BookService(Client client) {
    this.client = client;
  }

  // ...
}
```

If one of the two properties is not set, the application fails to start.

To change the [stall timeout](#configuring-the-stall-timeout), which is 30 seconds by default, set `eventsourcingdb.stall-timeout`, e.g. to `10s`. A value of zero or less fails the start of the application:

```properties
eventsourcingdb.stall-timeout=10s
```

The client serializes and deserializes the data of events with the `JsonMapper` of Spring Boot, so the data follows the Jackson configuration of your application, e.g. its naming strategy. When the application shuts down, Spring Boot closes the client.

To configure the client in other ways, e.g. with an HTTP client of your own, define a bean of type `Client`. The starter then uses yours instead of creating one. To connect it to the configured instance, inject `EventSourcingDbConnectionDetails`:

```java
@Bean
Client client(EventSourcingDbConnectionDetails connectionDetails, JsonMapper jsonMapper) {
  return new Client(
    connectionDetails.getBaseUrl(),
    connectionDetails.getApiToken(),
    new ClientOptions()
      .withHttpClient(httpClient)
      .withDataMapper(jsonMapper)
  );
}
```

#### Connecting to the Test Container

In tests, the starter can take the URL and the API token from the test container instead of from the properties. Add the test container and the Testcontainers support of Spring Boot to the dependencies of your tests, e.g. with Gradle:

```kotlin
dependencies {
  testImplementation("io.thenativeweb:eventsourcingdb-testcontainers:<version>")
  testImplementation("org.springframework.boot:spring-boot-testcontainers")
}
```

Then define the container as a bean, and annotate it with `@ServiceConnection`:

```java
@TestConfiguration(proxyBeanMethods = false)
class ContainerConfiguration {
  @Bean
  @ServiceConnection
  Container eventSourcingDb() {
    return new Container();
  }
}
```

Import that configuration into your tests. Spring Boot starts the container, and connects the client to it:

```java
@SpringBootTest
@Import(ContainerConfiguration.class)
class BookServiceTest {
  // ...
}
```

## Upgrading From Version 1

Version 2 talks to servers with and without the v2 API, as described in [Talking to Servers With and Without the v2 API](#talking-to-servers-with-and-without-the-v2-api), and follows the v2 API in its own API. These are the changes that may require changes to your code.

`DbApiException` describes an error with its `code`, its `description`, its `details`, and its `status`. `statusCode()` is now `status()`, and `reason()` is now `description()`:

```java
// Before
if (ex.statusCode() == 409) {
  var reason = ex.reason();
}

// After
if (ex.code().equals(ErrorCode.PRECONDITION_VIOLATED)) {
  var description = ex.description();
}
```

A server with the v2 API reports the errors of a request it accepted with the status `200` and a code, e.g. a precondition that does not hold, which a server without it reports with `409`. So tell errors apart by their `code` rather than by their `status`. For details, see [Handling Errors](#handling-errors).

An error that the server reports within a stream is a `DbApiException` with the status `200`, instead of a plain `EventSourcingDbException`, which it extends. Code that catches `EventSourcingDbException` keeps working.

`HeartbeatTimeoutException` is removed. Every stall ends with a `ConnectionStalledException`, also once observing events or running an EventQL query has started:

```java
// Before
} catch (HeartbeatTimeoutException ex) {

// After
} catch (ConnectionStalledException ex) {
```

Writing events and registering an event schema throw an `OutcomeUnknownException` if they fail in a way that leaves open whether the server stored the write, e.g. after a stall, a broken connection, or a `500`. Its cause is the failure that the methods threw before. A write whose thread is interrupted once the client has started to send the request throws it, too, even while the client is still connecting, with the `CancellationException` as its cause:

```java
// Before
} catch (CancellationException ex) {
  // The events may have been written.
}

// After
} catch (OutcomeUnknownException ex) {
  // The events may have been written.
}
```

For details, see [Knowing Whether a Write Was Stored](#knowing-whether-a-write-was-stored).

`EventType` names the type `type`, and tells with `hasEvents` whether events of the type were written, which is the opposite of `isPhantom`:

```java
// Before
var name = eventType.eventType();
var isPhantom = eventType.isPhantom();

// After
var name = eventType.type();
var hasEvents = eventType.hasEvents();
```

`readSubjects` takes the subject and options, whose `recursive` you have to set. Set it to `true` to read what `readSubjects` read before:

```java
// Before
client.readSubjects("/books");

// After
client.readSubjects("/books", new ReadSubjectsOptions(true));
```

Observing events ends with an `EventSourcingDbException` if the server ends the stream without an error, which it does only after a failure it can not report within the stream. Before, observing ended as if it were complete.

The v2 API reads with the HTTP method `QUERY`. If a proxy, a load balancer, or a firewall sits in front of the database, make sure that it lets `QUERY` through.
