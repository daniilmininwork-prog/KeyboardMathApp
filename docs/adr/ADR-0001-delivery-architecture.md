# ADR-0001: Delivery architecture — keyboard first, overlay second

- **Status:** accepted
- **Context date:** project kickoff

## Context

Apple's inline-math suggestion works because Apple owns the OS and the system keyboard and can
place a result into a suggestion surface it already controls. On Android there is no equivalent
position for a third party:

- Gboard is closed-source with no third-party plugin/extension API.
- Samsung Keyboard is closed-source with no public plugin SDK.

So we cannot add the feature *into* the popular keyboards. The only mechanisms available to a
third party that can surface an in-context suggestion are:

1. **Be an IME** (our own keyboard) and own a suggestion strip.
2. **Draw an overlay** above other apps, reading the field via an `AccessibilityService`.
3. Clipboard/share-sheet tricks (not fluid).

## Decision

Ship **both an IME and an overlay**, with the **IME as the primary product** and the **overlay
as a secondary, opt-in mode**.

- The IME is the only path that owns the suggestion strip and can match Apple's feel, and it is
  Google Play-policy-clean.
- The overlay is the only way to serve users who won't switch from Gboard/Samsung. It uses the
  Accessibility API, which Google Play restricts to accessibility purposes. Because the product
  is **Play-only and closed-source**, the overlay has no clean distribution channel (Play likely
  rejects it; F-Droid requires FOSS). It is therefore kept off the critical path: if it ships at
  all, it ships as a **separate off-Play direct-APK download**; otherwise it is deferred. This
  open decision is flagged, not hidden, and the IME never depends on it.
- Option 3 is rejected as a primary path (insufficient fluidity).

**Sub-decision — keyboard host:** to avoid spending years rebuilding a competitive typing
experience (and to make switching to Tally not feel like a downgrade), base the IME on a mature
open-source keyboard and add the math module on top, behind a thin host interface so the host is
swappable. Because Tally ships **closed-source**, the host must be **permissively licensed**
(Apache-2.0/MIT) — the AOSP LatinIME lineage (Apache-2.0) is the natural candidate. **Copyleft
(GPL) community keyboards are excluded**: their licence would force us to open-source Tally.
Licence compatibility is a gating check before adoption. The math feature is built independent of
the host so this choice can be revisited without touching the feature.

## Consequences

- We carry the cost of shipping/maintaining a real keyboard, mitigated by building on an existing
  base.
- The feature is written once in `core-math` and reused by both surfaces (see
  [system architecture](../04-system-architecture.md)), so the two modes never diverge.
- The overlay has no clean home under Play-only + closed-source; we accept it may ship only as an
  off-Play direct APK, or not at all. The IME covers Play and is the product; the overlay is a
  bonus we don't depend on.
- No part of the system depends on Gboard/Samsung internals, because none are accessible.

## Alternatives considered

- **Overlay-only:** maximises "works with any keyboard," but Play-policy risk and the trust cost
  of Accessibility make it a poor primary; rejected as the lead.
- **Build a keyboard from scratch:** unbounded scope for table stakes (gesture typing, many
  languages, emoji); rejected in favour of building on a mature base.
- **Wait for a Gboard/Samsung plugin API:** none exists; not a plan.
