package io.thenativeweb.eventsourcingdb.springboot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

// ImageTag tells which tag of the EventSourcingDB image the tests run
// against. The environment variable lets CI run the same tests against
// several tags, e.g. latest and preview. Without it, the tests use the tag in
// the Dockerfile, so that a local run needs no setup.
final class ImageTag {
    private static final String ENVIRONMENT_VARIABLE = "EVENTSOURCINGDB_IMAGE_TAG";

    private static final Pattern VERSION_PATTERN =
            Pattern.compile("^FROM\\sthenativeweb/eventsourcingdb:(.+)$", Pattern.MULTILINE);

    private ImageTag() {}

    static String get() {
        var tag = System.getenv(ENVIRONMENT_VARIABLE);
        if (tag != null && !tag.isBlank()) {
            return tag.strip();
        }

        return fromDockerfile();
    }

    private static String fromDockerfile() {
        String dockerfile;
        try {
            dockerfile = Files.readString(Path.of("..", "docker", "Dockerfile"));
        } catch (IOException ex) {
            throw new IllegalStateException("failed to read Dockerfile", ex);
        }

        var matcher = VERSION_PATTERN.matcher(dockerfile);
        if (!matcher.find()) {
            throw new IllegalStateException("failed to find image version in Dockerfile");
        }

        return matcher.group(1).strip();
    }
}
