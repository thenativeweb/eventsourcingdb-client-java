package io.eventsourcingdb;

public record EventCandidate(
        String source, String subject, String type, Object data, String traceParent, String traceState) {
    public EventCandidate(String source, String subject, String type, Object data) {
        this(source, subject, type, data, null, null);
    }
}
