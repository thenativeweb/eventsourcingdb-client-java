# Collaboration

## User-Facing Design Requires Explicit Sign-Off

- User-facing design choices always require explicit sign-off: never decide them autonomously, not even under auto mode. For this SDK that covers the public API surface (public type, method, and option names; method signatures and argument shapes), default values, default behavior, the wording of exception messages surfaced to callers, and what the SDK requires of the applications that use it, such as its dependencies and the minimum Java version.
- A "go ahead" or similar greenlight only authorizes what was explicitly discussed beforehand. Open design questions still require explicit sign-off regardless of the surrounding workflow mode.
- When an unspecified user-facing aspect surfaces during implementation, stop and ask. The friction of one extra clarification is far smaller than the friction of reworking an interface afterwards. If unsure whether a topic was clarified, default to asking.
