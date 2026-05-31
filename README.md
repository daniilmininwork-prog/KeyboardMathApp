# Tally

Inline math for Android keyboards. Type `12 × 9 =` and the answer is offered to you,
right where you're typing — no app switch, no calculator, no copy-paste.

This is the same small convenience Apple bakes into the iOS QuickType bar, rebuilt for
Android the only way it can be done well: as a fast, private, on-device suggestion engine
that plugs into a real keyboard.

> **Status:** design phase. This repository currently holds the product and engineering
> specification. Code lands per the [implementation plan](docs/10-implementation-plan.md).

## What it does

- Detects arithmetic as you type, inside ordinary text fields — chat, search, notes, forms.
- Evaluates it instantly, on-device, and offers the result as a tappable suggestion.
- Stays quiet when you're not doing math. No false positives on dates, versions, or phone numbers.
- Never touches the network. The app ships **without** the `INTERNET` permission.

## How it reaches you

Tally ships in two layers (see [platform strategy](docs/03-platform-strategy.md)):

1. **The Tally keyboard (primary).** A complete, pleasant Android keyboard that owns its
   suggestion strip, so the math result appears exactly like a native QuickType suggestion.
   This is the fluid, Apple-grade experience and the Play-Store-compliant path.
2. **Tally overlay (optional, power-user).** For people who want to keep Gboard or Samsung
   Keyboard, an opt-in accessibility mode draws a small result chip above whatever keyboard
   you already use. **Caveat:** Google Play restricts Accessibility-API apps, so this mode is
   unlikely to be accepted on Play. Under a Play-only model it would ship as a separate
   direct-download build, or be deferred — see [platform strategy](docs/03-platform-strategy.md).

Gboard and Samsung Keyboard are closed and expose no plugin API, so there is no way to add
this *into* them — the two layers above are the real options on Android, and we ship both.

## Repository map

| Path | What's there |
| --- | --- |
| [`docs/01-product-vision.md`](docs/01-product-vision.md) | The product, who it's for, the bar for "done well" |
| [`docs/02-apple-reference-analysis.md`](docs/02-apple-reference-analysis.md) | Exactly how Apple's feature behaves, and which behaviors we copy |
| [`docs/03-platform-strategy.md`](docs/03-platform-strategy.md) | IME vs. overlay, the Gboard/Samsung reality, what we build |
| [`docs/04-system-architecture.md`](docs/04-system-architecture.md) | Modules, boundaries, data flow |
| [`docs/05-math-engine-spec.md`](docs/05-math-engine-spec.md) | Grammar, numeric model, detection, formatting |
| [`docs/06-interaction-spec.md`](docs/06-interaction-spec.md) | Timing, insertion, motion, haptics, accessibility |
| [`docs/07-security-privacy.md`](docs/07-security-privacy.md) | Threat model and the guarantees we make |
| [`docs/08-testing-quality.md`](docs/08-testing-quality.md) | Test strategy, fuzzing, device matrix, CI gates |
| [`docs/09-build-release.md`](docs/09-build-release.md) | Build, signing, reproducibility, store delivery |
| [`docs/10-implementation-plan.md`](docs/10-implementation-plan.md) | Phase-by-phase build order with acceptance criteria |
| [`docs/adr/`](docs/adr) | Architecture decision records |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | How we work, house style, commit conventions |
| [`SECURITY.md`](SECURITY.md) | Reporting and our security posture |

## Principles

- **On-device or it doesn't ship.** Math never leaves the phone.
- **Quiet by default.** A wrong suggestion is worse than no suggestion.
- **Fast enough to feel free.** Detection and evaluation budget is single-digit milliseconds.
- **Earn trust.** A keyboard sees everything you type. The source is private, so trust is earned the way that survives closed source: the app ships with **no network permission at all** (anyone can confirm this from the APK and the Play listing), it collects nothing, and it's put through independent security review.

## Licence

Proprietary — all rights reserved. The source is private and distributed as a binary through
Google Play. See [`LICENSE`](LICENSE).
