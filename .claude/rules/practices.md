# Development Practices

## Goal-Driven Execution

- Before starting, restate the task as a concrete, verifiable goal -- what must be true once it is done -- and name what would show it: a passing test, an observable behavior, a green `make qa`. Work toward that, instead of executing step by step without a finish line.

## Test-Driven Development

- Follow test-driven development without exception, using the red-green-refactor cycle:
  - Red: write a failing test first.
  - Green: make it pass with the simplest change.
  - Refactor: improve the code while the tests stay green.
- The refactor step is part of that cycle rather than an exception to it: improving existing code -- naming, structure, readability -- needs no new test, because the existing ones are what keep it honest.
- Red means running the test and seeing it fail for the reason it is meant to catch; a test you only claim would fail is not a red step, and neither is one whose failure names a missing symbol or a broken build rather than the behavior in question. Once it is green, take the production change away again and check that it goes red for that same reason. A test whose setup is too small or too simple for the defect to occur stays green at that check, which is exactly what the check is for.
- Where a test depends on a threshold that production code defines -- a file size, a chunk boundary, a limit -- derive it in the test from that definition rather than writing the value out. A test that hard-codes the number keeps passing when the definition moves, and it stops exercising the case it was written for without anyone noticing.

## Debugging and Bug Fixing

- Reproduce a bug before forming a hypothesis about its cause. A hypothesis formed first explains whatever it is pointed at. Where the bug does not occur every time, measure how often the reproduction hits, so that a fix can later be told apart from luck.
- Fix the root cause, not the symptom. Identify and address the underlying cause instead of patching the visible symptom.
- Where one report turns out to hold several defects, diagnose all of them before fixing any. Fixes found one after the other and shipped as they are found are each green on their own and can still be unstable together, to the point where the whole series has to be taken back. What the analysis produces is one plan over the whole picture, not a fix per insight.
- For a bug fix, the red step of the cycle above is a test that reproduces the bug: write that failing test first, then make it pass with the fix.
- When debugging, assume nothing: suspect your own code first (not the compiler, libraries, or OS) and prove assumptions by observation instead of guessing.

## Design

- Favor orthogonality and loose coupling: keep components independent so that a change in one place does not ripple into unrelated ones.
- Measure before designing. Much of what looks like a question for someone else is decidable from the repository: how many files are affected, how often an existing rule is actually followed, what a command really returns. Establish that first -- it settles some questions outright and turns the rest into decisions with something under them.
- A measurement that backs a decision names what it covered. A clean result from an incomplete scan is indistinguishable from a complete one, so state the scope alongside the finding, and prefer the query that cannot quietly miss: case-insensitive where case varies, the whole tree rather than a list of paths assembled by hand, and a result read against the bound it was fetched under -- one that stops exactly at the bound has probably been cut.
- A measurement covers the cases that the code handles differently. Where the same operation takes another path depending on its input or on the state it meets -- a key that is new or one that exists, a store that is empty or one that has grown -- a measurement of one path backs a claim about that path only. Name those cases before measuring, and measure each one that the claim is meant to cover.
- State cost as numbers, not adjectives. Where a decision turns on what something costs, the claim carries a complexity class and a measured figure at a size the system actually reaches -- the two answer different questions, and neither replaces the other. Keep cost per operation apart from cost over a lifetime, and say plainly where a bound is real but only bites at a scale nobody will reach.

## Rules and Conventions

- Where a rule is checked automatically -- by a script, a hook, a linter -- a false alarm is a finding about the rule, not about the case that triggered it. Sharpen the condition. If it cannot be stated sharply enough to stop misfiring, the check is the wrong instrument for that rule; drop it rather than living with noise.
- Whether a rule holds in practice shows in real work, not in a dry run. An exercise built to test it can only contain the deviations someone thought of beforehand, and it measures what would be said rather than what gets done -- so use the rule on an actual task and watch where it bends. (Whether it is *followed* is a different question, and countable: see "Measure before designing".)
- A rule brought in from elsewhere -- another project's skill, a published convention -- is checked against this repository's own history before it is adopted: is there a case here it would have caught? What has none stays out, however convincing it reads. The check is decidable from the commits and the issues, and it separates what is genuinely missing here from what merely fits somebody else's way of working. This governs rules, which apply whether anyone wants them to or not; a whole skill taken from elsewhere is a workflow somebody chooses to run, and it stands or falls on being run.
- A rule adopted while the work is running applies to what that same run has already produced: go back over the drafts and the notes written before it existed and bring them into line, in a further commit rather than by rewriting history (`.claude/rules/git.md`). The passages written moments earlier are the ones nobody rereads.

## Simplicity

- Write the minimum code that solves the problem at hand; nothing speculative.
  - No features beyond what was asked, no abstraction only a hypothetical future would need, no configurability nobody requested.
  - Before adding an abstraction, ask whether a senior engineer would call it overcomplicated. If so, simplify.

## Scope Discipline

- Follow the boy scout rule, but only within the scope of your task: leave the logical unit you are already changing (the function, method, or type) cleaner than you found it. This clean-up is the refactor step of the cycle above.
- Do not fix issues outside that scope (other functions, other files) on your own; point them out in your reply and suggest a fix instead.
- Exception: trivial, risk-free issues right next to your change (a typo in a comment, stray whitespace) may be fixed directly; mention them in your reply.
