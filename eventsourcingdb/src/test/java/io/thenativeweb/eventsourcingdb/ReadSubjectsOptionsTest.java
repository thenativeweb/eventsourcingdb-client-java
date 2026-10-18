package io.thenativeweb.eventsourcingdb;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

// The v2 API reads the subject alone or recursively, as the options say. The
// v1 API always reads the subjects within a base subject, so the client reads
// recursively, and keeps the subject alone if it is not to read recursively.
class ReadSubjectsOptionsTest {
    private static FakeServer streamingSubjects(ApiVersion api) {
        return FakeServer.streaming(
                api, Lines.subject(api, "/test"), Lines.subject(api, "/test/1"), Lines.subject(api, "/test/2"));
    }

    private static List<String> readSubjects(FakeServer server, String subject, boolean recursive) {
        try (var subjects =
                new Client(server.url(), "secret").readSubjects(subject, new ReadSubjectsOptions(recursive))) {
            return subjects.toList();
        }
    }

    @ParameterizedTest
    @CsvSource({"true", "false"})
    void readsTheSubjectsWithinTheSubjectFromTheV1Api(boolean recursive) {
        try (var server = streamingSubjects(ApiVersion.V1)) {
            readSubjects(server, "/test", recursive);

            assertEquals(
                    "{\"baseSubject\":\"/test\"}", server.requests().getLast().body());
        }
    }

    @ParameterizedTest
    @CsvSource({"/test", "/test/"})
    void keepsTheSubjectAloneFromTheV1ApiIfItIsNotToReadRecursively(String subject) {
        try (var server = streamingSubjects(ApiVersion.V1)) {
            assertEquals(List.of("/test"), readSubjects(server, subject, false));
        }
    }

    @ParameterizedTest
    @CsvSource({"/test", "/test/"})
    void keepsEverySubjectFromTheV1ApiIfItIsToReadRecursively(String subject) {
        try (var server = streamingSubjects(ApiVersion.V1)) {
            assertEquals(List.of("/test", "/test/1", "/test/2"), readSubjects(server, subject, true));
        }
    }

    // The v2 API reads as the options say, so the client keeps what it
    // reads.
    @ParameterizedTest
    @CsvSource({"true", "false"})
    void asksTheV2ApiToReadAsTheOptionsSay(boolean recursive) {
        try (var server = streamingSubjects(ApiVersion.V2)) {
            assertEquals(List.of("/test", "/test/1", "/test/2"), readSubjects(server, "/test", recursive));
            assertEquals(
                    "{\"subject\":\"/test\",\"options\":{\"recursive\":" + recursive + "}}",
                    server.requests().getLast().body());
        }
    }
}
