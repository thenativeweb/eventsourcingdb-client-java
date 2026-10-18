package io.thenativeweb.eventsourcingdb;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

// The v1 API always reads the subjects within a base subject, so the client
// reads recursively, and keeps the subject alone if it is not to read
// recursively.
class ReadSubjectsOptionsTest {
    private static FakeServer streamingSubjects() {
        return FakeServer.start((exchange, fakeServer) -> {
            FakeServer.startStream(exchange);
            for (var subject : List.of("/test", "/test/1", "/test/2")) {
                FakeServer.writeLine(exchange, "{\"type\":\"subject\",\"payload\":{\"subject\":\"" + subject + "\"}}");
            }
        });
    }

    private static List<String> readSubjects(FakeServer server, String subject, boolean recursive) {
        try (var subjects =
                new Client(server.url(), "secret").readSubjects(subject, new ReadSubjectsOptions(recursive))) {
            return subjects.toList();
        }
    }

    @ParameterizedTest
    @CsvSource({"/test, true", "/test, false"})
    void readsTheSubjectsWithinTheSubjectFromTheV1Api(String subject, boolean recursive) {
        try (var server = streamingSubjects()) {
            readSubjects(server, subject, recursive);

            assertEquals(
                    "{\"baseSubject\":\"/test\"}", server.requests().getFirst().body());
        }
    }

    @ParameterizedTest
    @CsvSource({"/test", "/test/"})
    void keepsTheSubjectAloneIfItIsNotToReadRecursively(String subject) {
        try (var server = streamingSubjects()) {
            assertEquals(List.of("/test"), readSubjects(server, subject, false));
        }
    }

    @ParameterizedTest
    @CsvSource({"/test", "/test/"})
    void keepsEverySubjectIfItIsToReadRecursively(String subject) {
        try (var server = streamingSubjects()) {
            assertEquals(List.of("/test", "/test/1", "/test/2"), readSubjects(server, subject, true));
        }
    }
}
