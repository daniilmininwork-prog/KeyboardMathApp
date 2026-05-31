# Platform strategy

This is the most consequential design document in the repo. It explains why Android can't
copy Apple's mechanism, what the real options are, and what we build.

## The constraint

On iOS the system keyboard, the suggestion bar, and the OS are one product, so Apple injects a
result into a surface it already owns. On Android:

- **Gboard is closed-source and has no third-party plugin/extension API.** You cannot add a
  feature into Gboard. There is no supported way to contribute a suggestion to its strip.
- **Samsung Keyboard is the same** — closed, no public plugin SDK.
- The only sanctioned way to influence the in-field suggestion experience is to **be an Input
  Method Editor (IME)** yourself, or to **draw on top** of the screen via an overlay.

So "add this feature to Gboard/Samsung" is, strictly, not possible. The honest options are:

| Option | Owns the suggestion strip? | Apple-grade fluid? | Works with Gboard/Samsung? | Permission cost | Play-policy risk |
| --- | --- | --- | --- | --- | --- |
| **Be the keyboard (IME)** | Yes | Yes | N/A (you replace them) | `BIND_INPUT_METHOD` only | Low |
| **Accessibility overlay** | No (floats above) | Close, not perfect | Yes | Accessibility + draw-over-apps | **High** (see below) |
| Clipboard/share tricks | No | No | Yes | Low | Low |

The third option is not fluid enough to bother with as a primary path. The first two are what
we ship, in that order of priority.

## Decision: ship both, IME first

Recorded in [ADR-0001](adr/ADR-0001-delivery-architecture.md). Summary:

- **The Tally keyboard (IME) is the primary product.** It's the only path that owns the
  suggestion strip and can match Apple's feel exactly, and it's the path Google Play is happy
  with. Everything in the core experience targets this.
- **The overlay is a secondary, opt-in mode** for people who refuse to switch keyboards. It is
  genuinely useful but carries real costs (below), so it's gated and clearly disclosed. Note the
  distribution problem it creates under a Play-only model — see
  [the overlay caveat](#why-the-overlay-is-secondary-not-primary).

### Why the IME, despite the switching cost

The obvious objection: nobody changes keyboards for a math trick. True — which is why the
keyboard must be **good enough to live with**, not a math toy. That raises a build-vs-base
decision:

- **Building a full, competitive keyboard from scratch** (layouts for many languages, gesture
  typing, emoji, clipboard, themes, autocorrect) is a multi-year effort and a distraction from
  the feature.
- **Basing Tally on a mature open-source keyboard** gets us a credible typing experience on day
  one, and we add the math module on top.

We therefore build the math engine as a fully independent, embeddable module (see
[system architecture](04-system-architecture.md)) and integrate it into a keyboard host. The
keyboard host strategy is its own decision in
[ADR-0001](adr/ADR-0001-delivery-architecture.md): start from a well-maintained,
**permissively licensed** open-source IME (e.g. the AOSP LatinIME lineage, Apache-2.0) rather
than reinventing typing. Because Tally ships **closed-source**, copyleft (GPL) keyboards are
*excluded* as a base — building on them would force us to release our source. Licence
compatibility is therefore a gating requirement and is resolved in the ADR before any host is
chosen.

> Implementer note: the math feature does **not** depend on which host we pick. Build and ship
> `core-math` and the suggestion integration against a thin keyboard host interface so the host
> can be swapped without touching the feature.

### Why the overlay is secondary, not primary

The overlay reads the focused text field via an `AccessibilityService` and draws a result chip
with a draw-over-apps window. It works with any keyboard — but:

- **Google Play's Accessibility policy** restricts apps that use the Accessibility API to
  genuine accessibility purposes. A math overlay that reads text fields is at real risk of
  rejection or removal from Play. **This collides with the chosen Play-only distribution
  model:** there is no sanctioned Play channel for this mode, and a closed-source app can't use
  F-Droid. The honest options are (a) ship the overlay as a *separate direct-APK download*
  outside Play — an explicit exception to "Play only" — or (b) defer/drop the overlay and ship
  the IME alone. This is an open product decision, flagged rather than buried; the IME does not
  depend on it either way.
- **Trust cost.** Accessibility + draw-over-apps are exactly the permissions malware abuses.
  Asking for them raises the bar on transparency: explicit, revocable, well-explained consent,
  the absent network permission, and ideally an independent audit — since closed source can't
  itself prove the claim "it only reads to find math."
- **Fluidity ceiling.** A floating chip positioned near the cursor can't be quite as seamless
  as a real suggestion-strip entry, especially across OEM quirks and field types.

## Hassle-free onboarding (both modes)

The single biggest UX risk is the enable flow. Design requirements:

- **IME enable:** an in-app wizard that (1) deep-links to the system "enable keyboard" screen,
  (2) deep-links to the system input-method picker to select Tally, (3) confirms success by
  detecting that Tally is the active IME. Plain language, progress shown, no dead ends. Use the
  platform intents for input-method settings and the input-method picker rather than telling
  the user to "go find it in Settings."
- **Overlay enable:** a separate, clearly-labelled flow that explains *why* each permission is
  needed in one sentence each, links straight to the Accessibility and draw-over-apps toggles,
  and works fully without them (the user can decline and keep just the keyboard).
- **First-win moment:** immediately after enabling, drop the user into a try-it field
  pre-seeded so they see `2+2=` → `4` succeed once. First correct result inside the first
  minute is a success metric in [product vision](01-product-vision.md).

## What this means for the build

- The feature is built once, in `core-math`, and consumed by both the IME and the overlay.
- The IME is the default everywhere and the only thing Play needs.
- The overlay is additive and removable without affecting the keyboard.
- No part of the plan assumes access to Gboard/Samsung internals, because there is none.
