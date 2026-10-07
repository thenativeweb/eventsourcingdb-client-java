package io.eventsourcingdb;

import io.eventsourcingdb.testcontainers.Container;

final class Database {
    private Database() {}

    static Container start() {
        return start(new Container());
    }

    static Container startWithSigningKey() {
        return start(new Container().withSigningKey());
    }

    private static Container start(Container container) {
        container.withImageTag(ImageTag.fromDockerfile()).start();
        return container;
    }
}
