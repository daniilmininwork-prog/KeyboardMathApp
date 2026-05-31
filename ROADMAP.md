# Roadmap

High-level milestones. The detailed, criteria-gated build order is in
[docs/10-implementation-plan.md](docs/10-implementation-plan.md); this is the map above it.

## v0.1 — Engine (internal)

The pure `core-math` library: arithmetic, percent, detection/disambiguation, locale-aware
formatting. No UI. Proven by golden tests, the false-positive corpus, property tests, and
fuzzing. Nothing ships until this is correct and quiet.

## v0.2 — Keyboard, internal alpha

The Tally IME types normally and shows the math suggestion end-to-end. Dogfood-quality. Used
daily by the team to find false positives and rough edges.

## v0.3 — Feel

Motion, haptics, dynamic colour/theming, RTL, accessibility, and the settings screen. The point
where it should start to feel like Apple's version.

## v1.0 — Public keyboard release

Onboarding/first-win flow, hardening, full test matrix, and store delivery via **Google Play**
(Data Safety: no data collected). Closed-source. This is the first version a stranger can install
and love in under a minute.

## v1.1 — Overlay mode (conditional)

The opt-in accessibility overlay for people who keep Gboard/Samsung. Same engine, keyboard-
agnostic surface. **Open question:** Play's Accessibility policy makes this a poor fit for Play,
and a closed-source app can't use F-Droid, so it would ship as a separate off-Play direct-APK
download — or be cut. Decide before committing to it; the keyboard release does not depend on it.

## Later — considered, not committed

- An opt-in advanced pack (functions, unit conversion) behind a settings toggle, designed to keep
  the [no-network guarantee](docs/adr/ADR-0003-no-network-policy.md). Each addition is its own
  ADR.
- More keyboard languages/layouts as the host base allows.

Currency conversion is **not** on the roadmap: it needs live network access, which conflicts with
the project's core privacy guarantee.
