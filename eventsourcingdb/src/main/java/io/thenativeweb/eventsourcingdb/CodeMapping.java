package io.thenativeweb.eventsourcingdb;

// CodeMapping tells which code an error that the server reports with a status
// code other than 200 has. The v2 API gives such errors no code, since it
// reports the errors that have one in its answers. The v1 API reports every
// error with a status code, and some of them match an error of the v2 API, so
// that its requests map those to the code of the v2 API.
@FunctionalInterface
interface CodeMapping {
    CodeMapping NONE = (status, description) -> "";

    String codeOf(int status, String description);
}
