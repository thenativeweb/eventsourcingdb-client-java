package io.thenativeweb.eventsourcingdb.springboot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import io.thenativeweb.eventsourcingdb.Client;
import io.thenativeweb.eventsourcingdb.EventCandidate;
import io.thenativeweb.eventsourcingdb.testcontainers.Container;
import java.net.URI;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class EventSourcingDbAutoConfigurationTest {
    private static final Container container = new Container().withImageTag(ImageTag.fromDockerfile());

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(EventSourcingDbAutoConfiguration.class));

    record BookAcquired(String bookTitle) {}

    @BeforeAll
    static void startContainer() {
        container.start();
    }

    @AfterAll
    static void stopContainer() {
        container.stop();
    }

    private String[] connectionProperties() {
        return new String[] {
            "eventsourcingdb.base-url=" + container.getBaseUrl(), "eventsourcingdb.api-token=" + container.getApiToken()
        };
    }

    private static String writeAndReturnDataKey(Client client) {
        var event = client.writeEvents(List.of(new EventCandidate(
                        "https://www.eventsourcingdb.io",
                        "/test",
                        "io.eventsourcingdb.test",
                        new BookAcquired("2001"))))
                .getFirst();

        return event.data().propertyNames().iterator().next();
    }

    @Test
    void createsAClientFromTheProperties() {
        contextRunner
                .withPropertyValues(connectionProperties())
                .run(context -> context.getBean(Client.class).verifyApiToken());
    }

    @Test
    void createsAClientFromConnectionDetailsOfTheApplication() {
        EventSourcingDbConnectionDetails connectionDetails = new EventSourcingDbConnectionDetails() {
            @Override
            public URI getBaseUrl() {
                return container.getBaseUrl();
            }

            @Override
            public String getApiToken() {
                return container.getApiToken();
            }
        };

        contextRunner
                .withBean(EventSourcingDbConnectionDetails.class, () -> connectionDetails)
                .run(context -> context.getBean(Client.class).verifyApiToken());
    }

    @Test
    void backsOffWhenTheApplicationDefinesAClient() {
        var client = container.getClient();

        contextRunner
                .withBean(Client.class, () -> client)
                .run(context -> assertSame(client, context.getBean(Client.class)));
    }

    @Test
    void serializesEventDataWithTheJsonMapperOfSpring() {
        contextRunner
                .withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .withPropertyValues(connectionProperties())
                .withPropertyValues("spring.jackson.property-naming-strategy=SNAKE_CASE")
                .run(context -> assertEquals("book_title", writeAndReturnDataKey(context.getBean(Client.class))));
    }

    @Test
    void serializesEventDataWithAJsonMapperOfItsOwnWithoutTheOneOfSpring() {
        contextRunner
                .withPropertyValues(connectionProperties())
                .run(context -> assertEquals("bookTitle", writeAndReturnDataKey(context.getBean(Client.class))));
    }

    @Test
    void closesTheClientWithTheContext() {
        contextRunner.withPropertyValues(connectionProperties()).run(context -> {
            var client = context.getBean(Client.class);

            context.close();

            assertThrows(IllegalStateException.class, client::ping);
        });
    }

    @Test
    void failsToStartWithoutABaseUrl() {
        contextRunner
                .withPropertyValues("eventsourcingdb.api-token=secret")
                .run(context -> assertEquals(
                        "eventsourcingdb.base-url must be set",
                        rootCause(context.getStartupFailure()).getMessage()));
    }

    @Test
    void failsToStartWithoutAnApiToken() {
        contextRunner
                .withPropertyValues("eventsourcingdb.base-url=http://localhost:3000")
                .run(context -> assertEquals(
                        "eventsourcingdb.api-token must be set",
                        rootCause(context.getStartupFailure()).getMessage()));
    }

    @Test
    void failsToStartWithABlankApiToken() {
        contextRunner
                .withPropertyValues("eventsourcingdb.base-url=http://localhost:3000", "eventsourcingdb.api-token= ")
                .run(context -> assertEquals(
                        "eventsourcingdb.api-token must be set",
                        rootCause(context.getStartupFailure()).getMessage()));
    }

    private static Throwable rootCause(@Nullable Throwable throwable) {
        assertNotNull(throwable);

        var cause = throwable;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }

        return cause;
    }
}
