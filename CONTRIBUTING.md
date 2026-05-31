# Engineering guide

Tally's source is private; this is the internal guide every change follows. The bar is high for
two reasons: it's a keyboard (trust matters) and it's chasing a specific quality feel (Apple-grade
restraint). Read the [docs](docs) before starting — the specs there are the source of truth for
behaviour.

## Workflow

1. Pick up work from the [implementation plan](docs/10-implementation-plan.md). Respect the phase
   ordering — especially "engine before keyboard."
2. Branch from the default branch. Keep changes focused — one concern per branch/PR.
3. Write the tests with the code. A change isn't done until the relevant tests in
   [the testing strategy](docs/08-testing-quality.md) are green.
4. Run lint, the engine tests, and the security guards locally before pushing.
5. Open a PR using the template; explain *why*, not just *what*, and note how you tested.

## House style

We want the codebase to read like the work of one careful team with consistent taste.

### Kotlin

- Follow the ktlint/detekt configuration in the repo; CI enforces it. Don't hand-format around it.
- Idiomatic, conventional naming. No abbreviations that aren't already standard in the domain.
- Prefer clarity over cleverness; prefer small, well-named functions over comments explaining a
  big one.

### Comments

- Comment **why**, not **what**. Code that needs a comment to explain *what* it does usually wants
  to be rewritten instead.
- No redundant comments on self-evident code, no boilerplate banners, no decorative section
  headers, no uniform docstrings on trivial members.
- A `TODO` must reference an issue. No orphan TODOs.

### Commits

- [Conventional Commits](https://www.conventionalcommits.org/), imperative mood
  (`fix: clear stale suggestion when cursor leaves fragment`).
- Logical, self-contained commits with real messages. Not one giant dump; not a trail of
  "wip"/"fix typo".
- **No machine-generated trailers or attribution footers** of any kind. Commit messages describe
  the change, nothing else. The history should read as ordinary human engineering work.

### Pull requests

- Describe the intent and trade-offs. Link the spec/section the change implements.
- Include screenshots or screen recordings for any UI/motion change.
- Note testing done and any device-matrix coverage.

## Non-negotiables

These fail CI or review automatically:

- **No `INTERNET` permission** and no networking dependency, anywhere
  ([ADR-0003](docs/adr/ADR-0003-no-network-policy.md)).
- **`core-math` stays Android-free** ([system architecture](docs/04-system-architecture.md)).
- **No logging or persistence of typed text / keystrokes**, even at debug level
  ([security](docs/07-security-privacy.md)).
- New runtime permissions require an ADR and explicit justification.
- Behaviour changes to the engine require updating the golden cases and the
  [reference table](docs/02-apple-reference-analysis.md), reviewed deliberately.

## Adding behaviour to the engine

If you change what counts as math or how it's detected/formatted:

1. Update [05-math-engine-spec.md](docs/05-math-engine-spec.md) first.
2. Update the [reference table](docs/02-apple-reference-analysis.md) and golden tests.
3. Add false-positive cases for anything new that might mis-trigger.
4. Keep it within the [no-network](docs/adr/ADR-0003-no-network-policy.md) and performance
   constraints.

## Reporting security issues

See [SECURITY.md](SECURITY.md). Don't open public issues for vulnerabilities.
