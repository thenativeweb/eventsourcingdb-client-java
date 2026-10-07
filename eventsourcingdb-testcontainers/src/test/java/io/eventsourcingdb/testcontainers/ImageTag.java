package io.eventsourcingdb.testcontainers;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Pattern;

final class ImageTag {
    private static final Pattern VERSION_PATTERN =
            Pattern.compile("^FROM\\sthenativeweb/eventsourcingdb:(.+)$", Pattern.MULTILINE);

    private ImageTag() {}

    static String fromDockerfile() {
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
