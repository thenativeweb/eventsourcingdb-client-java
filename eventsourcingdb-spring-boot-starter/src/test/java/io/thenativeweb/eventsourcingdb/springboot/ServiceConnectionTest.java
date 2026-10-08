package io.thenativeweb.eventsourcingdb.springboot;

import io.thenativeweb.eventsourcingdb.Client;
import io.thenativeweb.eventsourcingdb.testcontainers.Container;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

// Sets up the test container the way the README describes it.
@SpringBootTest(classes = ServiceConnectionTest.Application.class)
@Import(ServiceConnectionTest.ContainerConfiguration.class)
class ServiceConnectionTest {
    @SpringBootConfiguration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class Application {}

    @TestConfiguration(proxyBeanMethods = false)
    static class ContainerConfiguration {
        @Bean
        @ServiceConnection
        Container eventSourcingDb() {
            return new Container().withImageTag(ImageTag.fromDockerfile());
        }
    }

    @Autowired
    Client client;

    @Test
    void connectsTheClientToTheContainer() {
        client.verifyApiToken();
    }
}
