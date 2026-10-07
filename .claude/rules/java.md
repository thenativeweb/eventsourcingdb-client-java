---
paths:
  - "**/*.java"
  - "**/*.gradle.kts"
  - "gradle/libs.versions.toml"
---

# Java

## Language

- Stick to idiomatic, modern Java at the level of Java 21, the minimum version the SDK supports: records for plain data, sealed interfaces for closed sets of alternatives, pattern matching for `switch` and `instanceof`, text blocks, and `var` for local variables whose type the right-hand side already shows.
- Prefer unchecked exceptions. The exceptions the SDK throws itself extend `EventSourcingDbException`.

## Tooling

- `make qa` runs every check of the build: compiler warnings as errors, Javadoc for the public API, formatting, and full test coverage. Make code pass them by changing the code, never by relaxing a check: no `@SuppressWarnings`, no lowered threshold, no exclusion, no disabled compiler flag or build step. This also holds when an update of a tool brings new findings.
- If a single spot genuinely cannot satisfy a check, discuss it first. If it is agreed, suppress the finding at that spot only, as narrowly as possible, with a comment that says why. Never relax a check for the whole build.
- Let Spotless format the code with `make format` instead of formatting it by hand.
