package io.thenativeweb.eventsourcingdb;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

// The JDK sends writes again if its networking property
// jdk.httpclient.enableAllMethodRetry says so. It reads the property once,
// as a system property, falling back to conf/net.properties of the JDK, and
// takes an empty value for true.
class WriteRetriesTest {
    private static final String PROPERTY = "jdk.httpclient.enableAllMethodRetry";

    @TempDir
    Path directory;

    private Path netProperties(String content) throws IOException {
        return Files.writeString(directory.resolve("net.properties"), content);
    }

    private Path missingNetProperties() {
        return directory.resolve("net.properties");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "true", "TRUE"})
    void areEnabledByTheSystemProperty(String value) {
        assertTrue(new WriteRetries(missingNetProperties(), name -> value).areEnabled());
    }

    @ParameterizedTest
    @ValueSource(strings = {"false", "yes"})
    void areNotEnabledByAnotherValueOfTheSystemProperty(String value) {
        assertFalse(new WriteRetries(missingNetProperties(), name -> value).areEnabled());
    }

    @Test
    void areNotEnabledWithoutTheProperty() {
        assertFalse(new WriteRetries(missingNetProperties(), name -> null).areEnabled());
    }

    @Test
    void areEnabledByTheNetPropertiesOfTheJdk() throws IOException {
        var netProperties = netProperties(PROPERTY + "=true\n");

        assertTrue(new WriteRetries(netProperties, name -> null).areEnabled());
    }

    @Test
    void takeTheSystemPropertyOverTheNetPropertiesOfTheJdk() throws IOException {
        var netProperties = netProperties(PROPERTY + "=true\n");

        assertFalse(new WriteRetries(netProperties, name -> "false").areEnabled());
    }

    @Test
    void areNotEnabledByOtherNetProperties() throws IOException {
        var netProperties = netProperties("jdk.httpclient.disableRetryConnect=true\n");

        assertFalse(new WriteRetries(netProperties, name -> null).areEnabled());
    }

    // The JDK goes on with the properties it read before a line that is
    // malformed.
    @Test
    void keepTheNetPropertiesThatWereReadBeforeALineThatIsMalformed() throws IOException {
        var netProperties = netProperties(PROPERTY + "=true\nbroken=\\u00zz\n");

        assertTrue(new WriteRetries(netProperties, name -> null).areEnabled());
    }

    // The JDK keeps the value it read with the first request, so clearing the
    // property afterwards does not stop it from sending writes again.
    @Test
    void stayEnabledOnceTheyWereEnabled() {
        var systemProperties = new HashMap<String, String>();
        var writeRetries = new WriteRetries(missingNetProperties(), systemProperties::get);

        systemProperties.put(PROPERTY, "true");
        assertTrue(writeRetries.areEnabled());

        systemProperties.remove(PROPERTY);
        assertTrue(writeRetries.areEnabled());
    }
}
