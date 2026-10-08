/**
 * Runs EventSourcingDB in a Docker container for integration tests, using
 * <a href="https://testcontainers.com/">Testcontainers</a>.
 *
 * <p>It lives in an artifact of its own, so that applications that only use the client do not depend on
 * Testcontainers.
 */
@NullMarked
package io.thenativeweb.eventsourcingdb.testcontainers;

import org.jspecify.annotations.NullMarked;
