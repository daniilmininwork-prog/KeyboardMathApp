# Testing & quality strategy

The feature's value is correctness and restraint: right answers, and silence when it isn't math.
Both are testable, and most of it can be tested fast because `core-math` is pure. Quality gates
are not optional — they're how we keep the keyboard trustworthy.

## Test pyramid

```
        e2e / manual device matrix      (few, high value)
      instrumentation: IME + overlay    (focused)
   feature-glue integration (debounce, locale, prefs)
 ───────────────────────────────────────────────────────
        core-math unit + property + golden + fuzz        (the bulk; fast, pure)
```

Most confidence comes from the bottom layer because it's pure JVM, fast, and covers the part
that must never be wrong.

## `core-math` — the heavy testing

### Golden tests (behaviour contract)

The [reference behaviour table](02-apple-reference-analysis.md#reference-behaviour-table-golden-cases)
is implemented verbatim as golden cases, plus an expanded table covering: precedence,
parentheses, percent (both rules), negatives, decimals, grouping on input, division rounding,
div-by-zero silence, and every [veto pattern](05-math-engine-spec.md#detection-and-disambiguation)
(dates, times, versions, phone numbers) asserting **no** suggestion.

These are the executable definition of "matches Apple." Changing one is a deliberate, reviewed
act.

### Property-based tests

Properties that must hold for all inputs:

- **Totality:** the engine never throws and always terminates within budget, for *any* string
  (including random/garbage/hostile).
- **Determinism:** same input + locale → same output, always.
- **Round-trip sanity:** for generated valid expressions, the evaluated result equals an
  independent reference computation (e.g. an exact rational/`BigDecimal` oracle) within the
  defined display precision.
- **Formatting invariants:** integer results have no fractional part; no trailing zeros; grouping
  applied; locale separators correct.

### Fuzzing

Continuous/random fuzzing of the lexer + parser + detector with:

- Random Unicode (operators, digits from many scripts, separators, control chars).
- Structurally near-valid input (lots of operators/parens) to probe limits and the step budget.
- Assert no crash, no hang, bounded time/memory, always a valid "result or nothing."

### False-positive corpus

A curated, growing corpus of **real text that contains digits but is not arithmetic** — dates,
times, versions, IPs, phone numbers, scores, code snippets, product names, addresses. The
detector must return **no suggestion** for every entry. New false positives found in the wild are
added here as regression tests. This corpus is the primary defence of the "reads the room" goal.

### Locale matrix

Run the golden + formatting tests across representative locales (e.g. en-US, de-DE, fr-FR,
ar-EG for RTL/native digits, hi-IN grouping) to verify separators, digits, and grouping on both
input and output.

## `feature-glue` tests

- Debounce/coalescing behaves correctly under bursts and pastes (timing per
  [interaction spec](06-interaction-spec.md#timing)).
- Locale resolution and preference reading select the right behaviour.
- Threading: results are delivered correctly and the UI thread is never blocked beyond budget.

## IME instrumentation tests

- Enable-keyboard flow reaches the right system screens and detects success.
- Typing an expression shows the chip; tapping inserts per the
  [insertion rules](06-interaction-spec.md#insertion); invalid input shows nothing.
- Insertion is undoable and doesn't corrupt surrounding text or cursor position.
- Theming (dark/light/dynamic), font scale, and RTL render correctly.

## Overlay instrumentation tests

- Service detects focus/text changes and positions the chip near the caret without stealing focus.
- Insertion via accessibility action / paste works and restores clipboard if used.
- Tap-jacking guard: taps are ignored when the window is obscured.
- Graceful, fully-functional keyboard mode when overlay permissions are declined or revoked.

## Accessibility tests

- TalkBack announces and actions the chip correctly.
- Touch-target and contrast checks pass at default and large font scales.
- Reduce-motion path verified.

## Performance tests

- Engine within the [budget](05-math-engine-spec.md#performance-budget) on a mid-range device.
- No measurable input latency / frame jank attributable to detection while typing fast.
- Battery: no background wakeups; overlay service is event-driven, not polling.

## Security checks in CI

- **Manifest guard:** build fails if `INTERNET` (or any non-allowlisted permission) is present —
  enforcing the [no-network guarantee](07-security-privacy.md).
- **Architecture guard:** build fails if `core-math` imports anything Android-specific.
- **No-logging-of-text guard:** static check / review rule against logging field content.
- Dependency verification, vulnerability scan, and SBOM generation run on every release build.

## CI gates (must pass to merge)

1. Formatting + lint (ktlint) and static analysis (detekt/Android Lint) clean.
2. All `core-math` unit/property/golden tests green; coverage above the agreed threshold for the
   engine (high — this is the critical module).
3. Instrumentation tests green on at least one emulator profile.
4. Security guards (manifest, architecture, logging) green.
5. Reproducible-build check on release branches.

## Device matrix (manual + CI emulators)

- **Android versions:** the agreed min SDK through latest (set in
  [build-release](09-build-release.md)).
- **OEM skins:** at least stock/Pixel and Samsung One UI (different IME and overlay behaviours).
- **Locales/RTL:** the locale matrix above on at least one RTL device.
- **Form factors:** phone primary; tablet/foldable sanity pass.

## Definition of done (feature-level)

A change to the feature is "done" only when: golden + false-positive corpus pass, property/fuzz
find nothing, performance is within budget, the relevant instrumentation tests pass, security
guards pass, and accessibility checks pass. Anything less ships a keyboard that's sometimes wrong
or sometimes noisy — both of which break the product's core promise.
