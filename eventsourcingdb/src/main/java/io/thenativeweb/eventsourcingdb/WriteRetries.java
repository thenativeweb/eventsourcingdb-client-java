package io.thenativeweb.eventsourcingdb;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Properties;
import java.util.function.Function;
import org.jspecify.annotations.Nullable;

// WriteRetries tells whether the JDK sends a write again over a new
// connection, if the connection it was sent over closed before the answer
// arrived, which it does if its networking property
// jdk.httpclient.enableAllMethodRetry says so. A connection that can not be
// established then may follow on a write that the server stored already.
//
// It reads the property as the JDK does: as a system property, falling back
// to conf/net.properties of the JDK, and with an empty value for true. The
// JDK reads it only once, with the first request of any HTTP client, at a
// time the client does not know of. So the property is read with each write
// that fails instead, and a value that was true once counts from then on,
// since the JDK may have read it while it was.
final class WriteRetries {
    private static final String PROPERTY = "jdk.httpclient.enableAllMethodRetry";

    // The JDK always sets java.home.
    static final WriteRetries OF_THE_JDK = new WriteRetries(
            Path.of(Objects.requireNonNull(System.getProperty("java.home")), "conf", "net.properties"),
            System::getProperty);

    private final @Nullable String defaultValue;
    private final Function<String, @Nullable String> systemProperties;
    private volatile boolean haveBeenEnabled;

    WriteRetries(Path netProperties, Function<String, @Nullable String> systemProperties) {
        defaultValue = load(netProperties).getProperty(PROPERTY);
        this.systemProperties = systemProperties;
    }

    // Like the JDK, it goes without defaults if it can not read the file.
    private static Properties load(Path netProperties) {
        var properties = new Properties();
        try (var input = Files.newInputStream(netProperties)) {
            properties.load(input);
        } catch (IOException ex) {
            return new Properties();
        }

        return properties;
    }

    boolean areEnabled() {
        if (haveBeenEnabled) {
            return true;
        }

        var value = systemProperties.apply(PROPERTY);
        if (value == null) {
            value = defaultValue;
        }
        var areEnabled = value != null && (value.isEmpty() || Boolean.parseBoolean(value));
        if (areEnabled) {
            haveBeenEnabled = true;
        }

        return areEnabled;
    }
}
