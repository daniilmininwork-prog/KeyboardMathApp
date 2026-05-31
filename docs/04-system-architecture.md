# System architecture

## Shape of the system

The design rule is a hard separation between **what counts as math** (pure, portable, testable)
and **how it's surfaced** (Android, UI, permissions). The math brain knows nothing about
Android; the Android shells know nothing about parsing. This keeps the risky, correctness-
critical part testable in isolation and reusable across both delivery modes.

```
                         ┌──────────────────────────┐
                         │        core-math         │  pure Kotlin/JVM, no Android
                         │  lexer → parser → eval    │  deterministic, no I/O, no net
                         │  detector + formatter     │
                         └─────────────┬────────────┘
                                       │ stable API (text in → result-or-nothing out)
                 ┌─────────────────────┼──────────────────────┐
                 │                     │                       │
        ┌────────▼─────────┐  ┌────────▼─────────┐    ┌────────▼─────────┐
        │   feature-glue   │  │   feature-glue   │    │      tests       │
        │  (debounce,      │  │   (shared)       │    │  golden/fuzz/    │
        │   locale, prefs) │  │                  │    │  property        │
        └────────┬─────────┘  └────────┬─────────┘    └──────────────────┘
                 │                      │
        ┌────────▼─────────┐  ┌─────────▼────────────┐
        │       ime        │  │       overlay        │
        │ InputMethod-     │  │ AccessibilityService │
        │ Service, keyboard│  │ + draw-over window   │
        │ + suggestion bar │  │ + result chip        │
        └────────┬─────────┘  └─────────┬────────────┘
                 └───────────┬──────────┘
                       ┌─────▼──────┐
                       │    app     │  onboarding, settings, permissions, about
                       └────────────┘
```

## Modules

Gradle multi-module, Kotlin DSL. One responsibility per module; dependencies point inward
toward `core-math`.

### `core-math` — the brain (pure JVM)

No Android dependencies at all, so it runs in plain JUnit and can be fuzzed fast.
Responsibilities:

- **Lexer** — text → tokens (numbers, operators, parens, percent).
- **Parser** — tokens → AST, honouring precedence/associativity. Bounded (no unbounded
  recursion or runaway loops).
- **Evaluator** — AST → exact numeric result using the numeric model in the
  [engine spec](05-math-engine-spec.md). Reports "no result" for div-by-zero, overflow, etc.
- **Detector** — given a text buffer and a cursor position, finds the candidate expression
  span (or decides there isn't one). This is where disambiguation lives.
- **Formatter** — result → locale-aware display string.

Public surface is tiny and stable, conceptually: *"given the text before the cursor and a
locale, return either a formatted suggestion + the span it came from, or nothing."* The exact
contract is fixed in the [engine spec](05-math-engine-spec.md#public-contract). Everything else
is internal so it can be refactored freely.

### `feature-glue` — Android-aware orchestration

The thin layer that turns keystrokes into calls into `core-math`:

- Debouncing / coalescing of rapid input (timing in [interaction spec](06-interaction-spec.md)).
- Locale resolution (system locale, optional user override).
- Reading user preferences (precision, percent mode, haptics, enabled/disabled).
- Threading: keep detection/evaluation off the UI thread when prudent; results marshalled back.

No parsing logic here. No UI here.

### `ime` — the keyboard

- `InputMethodService` host with a usable keyboard (per the host strategy in
  [ADR-0001](adr/ADR-0001-delivery-architecture.md)).
- Reads context via `InputConnection` (text before/after cursor) and feeds `feature-glue`.
- Renders the math result in its **own suggestion strip**.
- On tap, inserts via `InputConnection` per the [insertion rules](06-interaction-spec.md#insertion).
- Talks to the host keyboard only through a narrow interface so the host can be swapped.

### `overlay` — the keyboard-agnostic mode

- `AccessibilityService` that observes focus/text-change events on the active field.
- Extracts the relevant text, feeds the **same** `core-math` via `feature-glue`.
- Draws a result chip in a draw-over-apps window positioned near the caret.
- On tap, inserts the result back into the field via accessibility actions / paste.
- Hardened against tap-jacking; fully optional; fully disable-able.

### `app` — the front door

- Onboarding wizards (IME enable, optional overlay enable) from
  [platform strategy](03-platform-strategy.md#hassle-free-onboarding-both-modes).
- Settings screen, "try it" field, about/privacy, permission management.
- No business logic beyond wiring.

### `design-system` (optional split)

Shared theming, the result-chip component, motion tokens, so the IME suggestion entry and the
overlay chip look and animate identically.

## Data flow

### Keyboard (IME) path

1. User types; `InputConnection` gives the text before the cursor.
2. `feature-glue` debounces and calls `core-math` detector with text + cursor + locale.
3. Detector returns a candidate span or nothing; evaluator + formatter produce a display string.
4. `ime` shows/updates/clears the suggestion chip in its strip.
5. Tap → `ime` inserts the result via `InputConnection`; optional haptic.

### Overlay path

1. `AccessibilityService` receives a text-changed/focus event for the active field.
2. It reads the node's text + selection; `feature-glue` debounces and calls the **same**
   `core-math`.
3. On a result, `overlay` positions and shows the chip near the caret; otherwise hides it.
4. Tap → insert via accessibility action / clipboard paste; optional haptic.

Both paths converge on identical math and identical formatting because they share `core-math`.
The only differences are how text comes in and how the chip is drawn.

## Why this shape

- **Correctness is isolated and cheap to verify.** The part that must never be wrong is pure and
  fuzzable without an emulator.
- **One feature, two surfaces, zero duplication.** Math and formatting can't drift between modes.
- **The host keyboard is replaceable.** If we change the underlying IME base, the feature code
  doesn't move.
- **Security follows the boundary.** Only the shells touch sensitive surfaces (InputConnection,
  Accessibility); the brain has no I/O to abuse. See [security](07-security-privacy.md).

## Cross-cutting constraints (apply to every module)

- No module declares the `INTERNET` permission. There is no networking dependency anywhere.
- No keystroke or field content is logged or persisted. Buffers are transient.
- `core-math` stays free of Android imports — enforced in CI (see [testing](08-testing-quality.md)).
- Minimum SDK and target SDK fixed in [build-release](09-build-release.md); the architecture
  assumes no API newer than the agreed minimum without a guarded fallback.
