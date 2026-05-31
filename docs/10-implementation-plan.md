# Implementation plan

The build order. Each phase has a goal, the work, and **acceptance criteria** that must be met
before moving on. The criteria are the guardrails: do not advance a phase with red criteria, and
do not start UI before the engine is proven. Build inward-out — the pure brain first, then the
shells.

Read alongside the specs it references; this plan says *what to build and in what order*, the
specs say *exactly how it must behave*.

## Ground rules (apply to every phase)

- **Spec is law.** Behaviour is defined in [05-math-engine-spec](05-math-engine-spec.md),
  [06-interaction-spec](06-interaction-spec.md), and [07-security-privacy](07-security-privacy.md).
  If something is ambiguous, resolve it in the spec (and update it) before coding around it.
- **No network, ever.** No module gains the `INTERNET` permission or a networking dependency.
  The CI manifest guard enforces this from Phase 0.
- **`core-math` stays Android-free.** Enforced by the architecture guard in CI.
- **Tests land with the code that needs them**, not "later." A phase isn't done until its tests
  are green.
- **House style from commit one** (see [CONTRIBUTING.md](../CONTRIBUTING.md)) — the history must
  read as human, careful work throughout, not be cleaned up at the end.
- **One concern per change.** Small, reviewable commits and PRs.

---

## Phase 0 — Foundations

**Goal:** a buildable, governed, empty skeleton with the guardrails switched on.

**Work:**
- Initialise the Gradle multi-module project per
  [system architecture](04-system-architecture.md): `core-math` (JVM), `feature-glue`, `ime`,
  `overlay`, `app`, optional `design-system`.
- Version catalog, Kotlin/AGP pinned, ktlint + detekt + Android Lint wired.
- CI skeleton (build + lint) and the three **security guards**: manifest permission allowlist
  (no `INTERNET`), `core-math` Android-free check, no-text-logging check.
- Repo files: `LICENSE` (proprietary — all rights reserved), `CHANGELOG.md`, issue/PR templates,
  `CODEOWNERS`, `.gitignore`. The repository is private.

**Acceptance criteria:**
- `./gradlew build` succeeds on a clean checkout.
- CI runs and the security guards actually fail a deliberately-bad branch (prove they work).
- No `INTERNET` permission anywhere; `core-math` has no Android dependency.

---

## Phase 1 — `core-math`: the engine (no UI)

**Goal:** a pure, complete, bulletproof arithmetic engine. This is the product's heart; it must
be finished and proven before any keyboard work.

**Work (in order):**
1. **Lexer** — text → tokens, operator normalisation (`×`/`x`-between-digits → `*`, `÷` → `/`,
   Unicode minus), locale digit/separator awareness.
2. **Parser** — tokens → AST per the [grammar](05-math-engine-spec.md#grammar) and
   [precedence](05-math-engine-spec.md#operator-precedence-and-associativity); bounded depth.
3. **Evaluator** — `BigDecimal` numeric model, working precision/rounding, div-by-zero → no
   result, [limits](05-math-engine-spec.md#limits) and step budget enforced.
4. **Percent** — [percent semantics](05-math-engine-spec.md#percent-semantics) (additive default).
5. **Detector** — span finding around a cursor + the [veto patterns](05-math-engine-spec.md#detection-and-disambiguation).
6. **Formatter** — [formatting rules](05-math-engine-spec.md#formatting-rules-summary), locale-aware.
7. The single **public contract** from [the spec](05-math-engine-spec.md#public-contract).

**Acceptance criteria:**
- Every row of the [reference table](02-apple-reference-analysis.md#reference-behaviour-table-golden-cases)
  passes as a golden test, including all "no suggestion" rows.
- The **false-positive corpus** ([testing](08-testing-quality.md#false-positive-corpus)) yields
  zero suggestions.
- Property tests pass: total (never throws), terminates within budget on any input, deterministic,
  matches an independent oracle within display precision.
- Fuzzing finds no crash/hang within the limits.
- Locale matrix (en-US, de-DE, fr-FR, ar-EG, hi-IN) passes for input + formatting.
- Engine within the [performance budget](05-math-engine-spec.md#performance-budget).
- Engine coverage above the agreed high threshold.

> Do not start Phase 2 until every box here is green. A wrong or noisy engine poisons everything
> built on it.

---

## Phase 2 — IME skeleton: a usable keyboard

**Goal:** a real, installable keyboard that types — no math feature yet. This proves the host
before we hang the feature on it.

**Work:**
- Stand up the `InputMethodService` host. Per
  [ADR-0001](adr/ADR-0001-delivery-architecture.md), prefer basing this on a mature,
  **permissively licensed** (e.g. Apache-2.0, like the AOSP LatinIME lineage) open-source
  keyboard rather than building typing from scratch — copyleft/GPL bases are excluded because
  Tally ships closed-source; integrate it behind a thin host interface so it's swappable.
- Basic but genuinely usable typing: a clean latin layout, delete/space/enter, shift/caps,
  numbers/symbols, cursor handling via `InputConnection`.
- A suggestion strip surface (empty for now) and theming hooks (dark/light/dynamic).

**Acceptance criteria:**
- Tally can be enabled and selected as the active keyboard and used to type normally in real apps.
- `InputConnection` read/commit/cursor handling is correct (instrumentation tests).
- Renders correctly in dark/light, at large font scale, and in RTL.
- Still `INTERNET`-free; security guards green.

---

## Phase 3 — Wire the feature into the keyboard

**Goal:** the actual product — type math, see the suggestion, tap to insert — in the IME.

**Work:**
- `feature-glue`: debounce/coalesce ([timing](06-interaction-spec.md#timing)), locale resolution,
  preference reading, off-UI-thread evaluation.
- Feed `InputConnection` text + cursor into `core-math`; render results in the suggestion strip.
- Implement [insertion](06-interaction-spec.md#insertion) (append default; replace optional) via
  `InputConnection`, idempotent and undoable.
- Show/update/clear per [when the chip shows](06-interaction-spec.md#when-the-chip-shows); no
  error/loading states.

**Acceptance criteria:**
- Golden cases reproduce **end-to-end in the keyboard**, not just in `core-math`.
- No perceptible input latency while typing fast (performance test).
- Insertion is correct, undoable, and preserves surrounding text/cursor.
- Veto cases produce no chip in the live keyboard.

---

## Phase 4 — Polish to the Apple bar

**Goal:** make it *feel* like Apple — motion, haptics, theming, accessibility, locales.

**Work:**
- [Motion](06-interaction-spec.md#motion) (appear/update/dismiss, reduce-motion fallback) and the
  shared chip component in `design-system`.
- [Haptics](06-interaction-spec.md#haptics) (tap-to-insert only), respecting system + app setting.
- Full [theming](06-interaction-spec.md#visual-design): dynamic colour, font scale, RTL.
- [Accessibility](06-interaction-spec.md#accessibility): TalkBack labels/actions, contrast,
  touch targets.
- The [settings screen](06-interaction-spec.md#settings-app) with sensible defaults.

**Acceptance criteria:**
- Accessibility tests pass (TalkBack, contrast, touch targets, reduce-motion).
- Looks and animates correctly across dark/light/dynamic, font scales, and RTL.
- The chip is visually consistent with what the overlay will use later (shared component).

---

## Phase 5 — Onboarding & first-win

**Goal:** the [hassle-free enable flow](03-platform-strategy.md#hassle-free-onboarding-both-modes).

**Work:**
- `app` onboarding wizard: deep-link to enable-keyboard, deep-link to the input-method picker,
  detect success.
- The seeded "try it" field that shows `2+2=` → `4` succeeding once.
- About/privacy screen stating the on-device / no-network guarantees plainly.

**Acceptance criteria:**
- A new user can go from install to a correct result in under a minute (usability check).
- The wizard handles the user backing out or already having it enabled, with no dead ends.

---

## Phase 6 — Hardening

**Goal:** prove the security and robustness promises.

**Work:**
- Full pass against [security](07-security-privacy.md): permission audit, R8 log stripping,
  dependency verification, SBOM, reproducible-build setup.
- Stress: huge/nested/pathological input via the keyboard; confirm limits + budget hold live.
- Expand the false-positive corpus with anything found during dogfooding.

**Acceptance criteria:**
- Manifest is minimal and `INTERNET`-free; Data Safety declaration ("no data collected") is
  accurate and backed by the manifest.
- Release build strips debug logging; no path logs user text.
- Reproducible-build check passes on a release branch.

---

## Phase 7 — Overlay mode (optional, opt-in)

**Goal:** the keyboard-agnostic mode for people who keep Gboard/Samsung.

> **Distribution caveat (decide before building):** Google Play's Accessibility policy makes this
> mode unlikely to pass review, and a closed-source app can't use F-Droid. Under the Play-only
> model it has no sanctioned channel, so it must either ship as a separate off-Play direct-APK
> download or be cut. Don't start this phase until that call is made — see
> [platform strategy](03-platform-strategy.md#why-the-overlay-is-secondary-not-primary).

**Work:**
- `AccessibilityService` reading focus/text-change events; feed the **same** `core-math`.
- Draw-over-apps result chip near the caret using the shared component; insertion via
  accessibility action / paste (restore clipboard if used).
- [Tap-jacking](07-security-privacy.md) guard; clear, per-permission consent UI; full keyboard
  functionality when declined/revoked.

**Acceptance criteria:**
- Works on top of at least Gboard and Samsung Keyboard; chip positions sensibly and doesn't steal
  focus.
- Insertion works and restores clipboard; tap-jacking guard verified.
- Read scope limited to finding the expression; nothing else harvested.
- Declining/revoking permissions leaves a fully working keyboard.

---

## Phase 8 — Release engineering

**Goal:** ship it, verifiably.

**Work:**
- Finalise CI gates, signing, versioning, CHANGELOG.
- Play listing (IME) with Data Safety; and, only if the overlay ships, a separate signed
  off-Play direct-APK download with checksums. Per [build-release](09-build-release.md) and
  [platform strategy](03-platform-strategy.md).

**Acceptance criteria:**
- The [pre-release checklist](09-build-release.md#pre-release-checklist) is fully green.
- Published artifacts are reproducible and match source.

---

## Sequencing summary

```
0 Foundations ─▶ 1 core-math ─▶ 2 IME skeleton ─▶ 3 wire feature ─▶ 4 polish
                                                                     │
                              7 overlay (optional) ◀── 6 hardening ◀─┴─ 5 onboarding
                                          │
                                          └──────────▶ 8 release
```

The non-negotiable edge: **1 before 2, and 1 fully green before 3.** Everything else has some
slack, but the engine must be proven correct and quiet before it's allowed near a real keyboard.
