# Product vision

## The one-sentence version

When you type something that is obviously arithmetic, the answer should already be waiting
for you — without leaving the field you're typing in.

## The moment we're designing for

You're in a chat splitting a bill: `47.50 / 3 =`. You're in a search box working out a
discount: `89 * 0.7`. You're filling a form and need a subtotal. Today on Android you stop,
leave the app, open a calculator, type it again, copy the result, come back, paste. Tally
removes that entire detour: the result appears as a suggestion the instant the expression is
recognisable, and one tap drops it into place.

This is deliberately a *small* feature. The whole point is that it is invisible until the
exact second it's useful, and then it's faster than you. Anything that makes it noticeable
when you're **not** doing math is a bug.

## Who it's for

- Everyone who types numbers into a phone: shoppers, students, anyone splitting costs,
  anyone who reaches for the calculator a few times a day.
- People who already have a keyboard they like (Gboard, Samsung) and don't want to give it
  up — hence the overlay mode.
- Privacy-conscious users who will not install a keyboard that phones home — hence the
  no-network guarantee (provable from the APK even though the source is closed) and an
  independent audit.

## What "done well" means

We are explicitly chasing parity with the *feel* of Apple's iOS implementation, not just the
function. The bar:

1. **Latency you can't perceive.** The suggestion is there as fast as the next character.
2. **No false positives.** `iOS 17.4.1`, `2024-01-02`, `555-1234`, `1.2.3` must never compute.
3. **Right answer, formatted like a human would write it.** `1000000/8` shows `125,000`,
   not `125000.0`. Respect the user's locale separators.
4. **Reads the room.** Dark mode, Material You colour, RTL, large fonts, TalkBack — all
   handled, none bolted on.
5. **Costs nothing to ignore.** If you didn't mean to do math, you never notice it ran.

## Non-goals (at least for v1)

- Not a scientific or graphing calculator. No variables, no equation solving, no calculus.
  (Apple's Math Notes does more; the *keyboard suggestion* we're cloning does not.)
- Not a units/currency converter in v1 (currency needs the network; see
  [scope decision](05-math-engine-spec.md#scope-and-extension-path)).
- Not trying to be your everyday keyboard's equal on every axis at launch — it must be
  *good enough* to live with, and great at the one thing.
- No accounts, no cloud, no sync, no ads, no telemetry.

## Success signals

- A user can enable Tally and get a correct, well-formatted result within their first minute.
- In day-to-day typing, the math chip appears only when intended (measured against a false-positive
  corpus, see [testing](08-testing-quality.md)).
- The keyboard is retained — people don't switch back the same day — which means the keyboard
  itself has to be genuinely usable, not a math gimmick wrapped around a poor typing experience.

## The hard part, named up front

The feature is easy. The *adoption* is the hard part: on Android, the only fully fluid way to
own the suggestion strip is to be the keyboard, and asking someone to change keyboards is a
real ask. Two design responses run through everything that follows:

- Make the keyboard good enough that switching is not a sacrifice (see
  [platform strategy](03-platform-strategy.md) on building atop a mature base rather than
  from zero).
- Offer the overlay so committed Gboard/Samsung users get the feature without switching at all.
