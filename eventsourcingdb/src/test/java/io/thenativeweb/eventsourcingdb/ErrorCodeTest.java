package io.thenativeweb.eventsourcingdb;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class ErrorCodeTest {
    private static final Pattern SLUG_PATTERN =
            Pattern.compile("^\\s*Slug\\w+\\s*=\\s*\"([a-z-]+)\"$", Pattern.MULTILINE);

    // Reads the codes from the list of the server, and names each the way
    // ErrorCode names it, e.g. precondition-violated as PRECONDITION_VIOLATED.
    private static Map<String, String> codesOfTheServer() {
        String slugs;
        try (var resource = ErrorCodeTest.class.getResourceAsStream("server-error-slugs.txt")) {
            assertNotNull(resource);
            slugs = new String(resource.readAllBytes(), UTF_8);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }

        var codes = new TreeMap<String, String>();
        var matcher = SLUG_PATTERN.matcher(slugs);
        while (matcher.find()) {
            var slug = matcher.group(1);
            codes.put(
                    slug.toUpperCase(Locale.ROOT).replace('-', '_'), "https://docs.eventsourcingdb.io/errors/" + slug);
        }

        return codes;
    }

    private static Map<String, String> codesOfTheClient() {
        return Arrays.stream(ErrorCode.class.getFields())
                .filter(field -> Modifier.isStatic(field.getModifiers()))
                .collect(Collectors.toMap(
                        Field::getName, ErrorCodeTest::valueOf, (first, second) -> first, TreeMap::new));
    }

    private static String valueOf(Field field) {
        try {
            return (String) field.get(null);
        } catch (IllegalAccessException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Test
    void hasAConstantForEveryCodeTheServerLists() {
        var codesOfTheServer = codesOfTheServer();

        // The list of the server is not empty, so that a list that fails to
        // parse can not pass for one without codes.
        assertFalse(codesOfTheServer.isEmpty());
        assertEquals(codesOfTheServer, codesOfTheClient());
    }
}
