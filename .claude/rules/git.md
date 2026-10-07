# Git

## Branches

- Use a separate branch; do not work in `main`.
- Use `kebab-case` to briefly describe what the branch is about (for example `update-gradle-dependencies`).

## Commits

- Use conventional commit messages.
- Use `feat`, `fix`, or `chore` as the only allowed prefixes.
- Start the message with a capital letter and end it with a period (for example `chore: Update Gradle dependencies.`).
- A bot-raised pull request keeps its own title when it is squash-merged, missing period and all. The titles are generated, they arrive several times a week, and editing each one would be handwork on every single update for a period nobody reads.
- Make one logical change per commit.

## Comparing Against main

- Whenever a comparison against `main` is needed -- a diff of the current branch, a merge base, the question of how far behind it is -- compare against `origin/main` and fetch first. The local `main` only moves when someone pulls it, so it can sit weeks behind, and a comparison against it silently takes in commits that landed long ago.

## Collecting the Working State

- A diff shows only what git already tracks, so a file that is new and not yet committed appears in none of them -- neither against `origin/main` nor against `HEAD`. Displaying such a diff costs nothing, because whoever reads it has the working copy in front of them. Where something **collects** the working state and hands it on instead -- a review, a check, a summary -- add `git ls-files --others --exclude-standard`, or whatever was written last is missing: the file a test drove into being, a new fixture, a new page. The failure is quiet, which is what makes it worth a rule: an empty diff reads as "nothing changed", not as "nothing looked at yet".

## History

- Never force push.
- Never use `git push --force-with-lease` either.
- Never amend a commit that has already been pushed (that would require a force push); before pushing, amending is fine only to fix a typo in the commit message.
- Never rebase; always integrate changes by merging.

## Pull Requests

- Open a pull request only after `make qa` passes, and merge it only once CI is green.
- Pushing a branch and opening a pull request are outward-facing steps; propose them and wait for an explicit go, rather than pushing or opening a pull request unprompted. Commit locally as the work progresses.
- Use the conventional-commit format for the pull request title (same prefixes and capitalization as commits).
- Describe in the pull request body what was changed and why.
- When you add further changes to an existing pull request, check whether the title and the description still cover the full scope, and update them if they do not.
- Work that answers a review comment ends in that comment. Reply in the thread with what changed and in which commit, then resolve it. A point that led to no change -- rejected, deferred, cleared up as a misunderstanding -- gets the reply and stays open: whether it is settled is the reviewer's call, and an open thread is how they see that it is not.
- Assign a pull request to `goloroden` and request a review from the team `thenativeweb/internal_dev` (`gh pr create --assignee goloroden --reviewer thenativeweb/internal_dev`), as in the other client SDK repositories.
