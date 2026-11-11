# eventsourcingdb

The official Java client SDK for [EventSourcingDB](https://www.eventfoundation.io/products/eventsourcingdb) – a purpose-built database for event sourcing.

The complete documentation of this client SDK, including preconditions, EventQL, observing events, handling errors, Testcontainers, and upgrading, is available at **[https://www.eventfoundation.io/docs/eventsourcingdb/sdks/java](https://www.eventfoundation.io/docs/eventsourcingdb/sdks/java)**.

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

Create a client with the URL of your EventSourcingDB instance and the API token to use, and check whether the instance is reachable:

```java
import io.thenativeweb.eventsourcingdb.Client;
import java.net.URI;

// ...

var baseUrl = URI.create("http://localhost:3000");
var apiToken = "secret";

var client = new Client(baseUrl, apiToken);

client.ping();
```

Write an event:

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

Read the events of a subject:

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

For everything else, see the **[complete documentation](https://www.eventfoundation.io/docs/eventsourcingdb/sdks/java)**.
