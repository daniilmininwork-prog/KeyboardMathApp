# Changelog

All notable changes to Tally are documented here.

Format: [Keep a Changelog](https://keepachangelog.com/en/1.0.0/).
Versioning: [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [Unreleased]

---

## [0.1.0] — 2026-06-01

First complete internal build: engine proven, keyboard working end-to-end, onboarding
shipped, hardened, overlay mode included, and release engineering in place.

### Added

**Phase 0 — Foundations**
- Gradle multi-module project (`core-math`, `feature-glue`, `ime`, `overlay`, `app`,
  `design-system`) with Kotlin DSL and centralised version catalog.
- ktlint, detekt, and Android Lint wired into CI.
- Three security guards enforced in CI: manifest permission allowlist (blocks `INTERNET`),
  `core-math` Android-free check, and no-text-logging check.
- Reproducible-build configuration: timestamps and path metadata stripped from all
  archive outputs.
- Dependency verification seeded (`gradle/verification-metadata.xml`, SHA-256).
- Repository files: `LICENSE`, `CONTRIBUTING.md`, `SECURITY.md`, `CHANGELOG.md`,
  `CODEOWNERS`, issue/PR templates, `ROADMAP.md`.
- Architecture decision records: ADR-0001 (delivery architecture), ADR-0002 (numeric
  model), ADR-0003 (no-network policy).

**Phase 1 — `core-math` engine**
- Lexer: text → tokens, operator normalisation (`×`/`x`-between-digits → `*`,
  `÷` → `/`, Unicode minus), locale digit/separator awareness.
- Parser: tokens → AST with bounded depth; full operator precedence and associativity
  per spec.
- Evaluator: `BigDecimal` numeric model, configurable precision/rounding, div-by-zero
  yields no result, step-budget enforced.
- Percent semantics: additive default (`100+10%` → `110`).
- Detector: expression span finding around cursor with veto patterns.
- Formatter: locale-aware output (en-US, de-DE, fr-FR, ar-EG, hi-IN).
- Public `MathEngine` contract.
- Golden test suite (35 cases from the Apple reference table, including all
  "no suggestion" rows), false-positive corpus (76 cases), property tests, fuzz tests,
  stress tests, locale matrix, and performance budget test.

**Phase 2 — IME skeleton**
- `TallyInputMethodService` host with latin layout, delete/space/enter, shift/caps,
  numbers/symbols, and cursor handling via `InputConnection`.
- Suggestion strip surface with theming hooks for dark/light/dynamic colour.

**Phase 3 — Feature wired**
- `MathEvaluator` in `feature-glue`: debounce/coalesce, locale resolution, preference
  reading, off-UI-thread evaluation.
- Suggestion chip shows/updates/clears per interaction spec; insertion is idempotent
  and undoable.

**Phase 4 — Polish**
- Shared `MathResultChip` component in `design-system` with appear/update/dismiss
  motion and reduce-motion fallback.
- Haptics on tap-to-insert (respects system and app setting).
- Full theming: dynamic colour, font scale, RTL.
- TalkBack labels/actions, contrast, and touch-target compliance.
- Settings screen (`TallyPreferences`) with sensible defaults.

**Phase 5 — Onboarding**
- `OnboardingActivity`: deep-link to enable keyboard, deep-link to input-method picker,
  success detection.
- Seeded "try it" field demonstrating `2+2` → `4` on first launch.
- `AboutActivity`: plain statement of on-device / no-network guarantees.

**Phase 6 — Hardening**
- Full permission audit: manifest minimal and `INTERNET`-free.
- R8 strips all debug logging in release builds; ProGuard rules reviewed and minimal.
- `TallyOverlayService` read scope limited to active text field; no harvest beyond the
  expression.
- SBOM generated via CycloneDX on every release build.

**Phase 7 — Overlay mode**
- `TallyOverlayService` (`AccessibilityService`): reads focus/text-change events, feeds
  the same `core-math` engine.
- `OverlayChipWindow`: draws the result chip near the caret using the shared component.
- `ClipboardInserter`: inserts via accessibility action / paste; restores clipboard
  after use.
- Tap-jacking guard; `OverlayConsentActivity` with per-permission consent UI; full
  keyboard functionality when permissions are declined or revoked.

**Phase 8 — Release engineering**
- Release workflow (`.github/workflows/release.yml`): triggers on `v*` tags, runs all
  CI gates, builds signed AAB and APK, generates SHA-256 checksums, creates a GitHub
  Release with artifacts attached.
- Signing configuration reads keystore from environment variables; nothing committed to
  source.
- Dependency verification enabled in strict mode.
- `CODEOWNERS`, issue templates, and PR template added.
- `CHANGELOG.md` (this file) in Keep a Changelog format.
- Pre-release checklist verified green for v0.1.0.

[Unreleased]: https://github.com/yourorgrepo/tally/compare/v0.1.0...HEAD
[0.1.0]: https://github.com/yourorgrepo/tally/releases/tag/v0.1.0
