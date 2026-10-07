# General

## Code Style

- Write readable code.
  - Explicit is better than implicit.
  - Use the full form of words, not abbreviations (for example `configuration`, not `cfg`; `file`, not `f`). Abbreviate only when there is a good reason; if that reason is not reasonably obvious, add a comment that explains it.
  - Separate logical blocks by an empty line.
  - Comment *why* things are done in a specific way; do not comment *what* is being done.
  - Comment the present reason for the code, not its history: do not explain why something is no longer done a certain way or how it used to work, since the fact that it was once different rarely helps a future reader. Such context belongs in the commit message or the pull request, not in the code.
  - Explain your decisions in code: whenever something could reasonably be implemented in more than one way, document why you chose this approach. Do not assume the reasoning will still be obvious to someone months later.
  - Prefix boolean variables with auxiliary verbs such as `is`, `has`, `did`, or `will`.
- Follow DRY (Don't Repeat Yourself): avoid duplicating knowledge or logic; keep a single authoritative source for each piece of information.
- Use American English, not British English: `color` (not `colour`), `behavior` (not `behaviour`), `modeling` (not `modelling`), `summarize` (not `summarise`), `canceled` (not `cancelled`), `catalog` (not `catalogue`), `artifact` (not `artefact`), `enroll` / `enrollment` (not `enrol` / `enrolment`), `analyze` (not `analyse`), and so on. The plural noun `analyses` is identical in both variants and stays as is.
- In source code, use ASCII only; do not use typographic characters such as curly quotes, en/em dashes, or ellipses.
- Follow the formatting enforced by `.editorconfig` (line endings, final newline, trailing whitespace, indentation).
- Check whether the documentation needs to be extended and/or updated.

## Working Style

- Do not make any implicit assumptions; if in doubt, ask explicitly and get feedback from a human.
- Never invent facts, numbers, or quotes. If something is unknown, research it or ask.
- Do not put figures taken from a single case into text that is meant to last -- rules, skills, `CLAUDE.md`, code comments, concept documentation. That covers issue and pull request numbers, and equally the counts that came with them: how many files a change touched, how many places a pattern appeared, how many of the recently closed issues went one way. State the pattern instead; the figure was true of one occasion and gets read as though it held in general.
- Such figures belong where the artifact is dated anyway: issue bodies, pull request descriptions, commit messages. Unaffected are targets, configured amounts, and format examples, none of which reports an observation -- and a measured figure a decision rests on, which travels with the decision and carries the conditions it was taken under. Establishing those figures is still required; `.claude/rules/practices.md` says so in its `## Design` bullets.
