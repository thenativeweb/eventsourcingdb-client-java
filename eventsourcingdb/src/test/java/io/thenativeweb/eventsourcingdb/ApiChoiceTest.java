package io.thenativeweb.eventsourcingdb;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;

import io.thenativeweb.eventsourcingdb.testcontainers.Container;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// A database that offers the v1 API as well as the v2 API answers the
// requests of either, so only this test tells whether the client chose the
// API it should. It asks the database on its own which API it offers, rather
// than going by the tag of the image, since a later release offers the v2 API
// under every tag.
class ApiChoiceTest {
    private Container container;

    @BeforeEach
    void startContainer() {
        container = Database.start();
    }

    @AfterEach
    void stopContainer() {
        container.stop();
    }

    @Test
    void choosesTheApiThatTheDatabaseOffers() {
        var client = container.getClient();
        var expected = Database.offersV2Api(container) ? V2Transport.class : V1Transport.class;

        client.ping();

        assertInstanceOf(expected, client.chosenTransport());
    }

    @Test
    void choosesNoApiBeforeTheFirstRequest() {
        assertNull(container.getClient().chosenTransport());
    }
}
