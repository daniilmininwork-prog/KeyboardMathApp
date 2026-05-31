# Apple reference analysis

We are cloning a specific, narrow Apple behaviour: the QuickType inline arithmetic suggestion,
which Apple calls **"Show Math Results."** It shipped in **iOS 18 (2024)** as the keyboard-bar
sibling of Math Notes (the richer Calculator/Notes canvas with variables, graphs, and unit/
currency conversion — which we are *not* cloning). The behaviour: type an arithmetic expression
followed by `=` in almost any text field, and the answer appears as a suggestion above the
keyboard; you insert it by tapping it or pressing space. It's enabled under
*Settings ▸ General ▸ Keyboard*, where **Show Math Results** and **Predictive Text** must both be
on, and it runs entirely on-device.

This document is the behavioural contract. When we say "match Apple," this is what we mean.

## Why Apple's version feels seamless (and why we can't copy the mechanism)

Apple owns the OS *and* the system keyboard. The QuickType bar is part of the keyboard, the
keyboard is part of the OS, and the on-device language/prediction stack feeds it. So Apple can
quietly evaluate the buffer and place a result in a UI surface that already exists, with no new
permission, no new UI, and no app boundary to cross.

On Android nobody has that position except the OEM. We cannot reach into Gboard's or Samsung's
suggestion strip. The lesson we take is not the mechanism but the *principles*: own the
surface (be the keyboard), keep it on-device, and make it disappear when unused. See
[platform strategy](03-platform-strategy.md).

## Observed behaviours to match

These are the behaviours an implementer should treat as acceptance criteria for "feels like
Apple." Where Apple's exact internal rule is unobservable, we pick the most predictable
interpretation and pin it down in the [math engine spec](05-math-engine-spec.md).

### Triggering

- Fires on a recognisable arithmetic expression embedded in ordinary text — you don't have to
  be in a special mode or a calculator field.
- **Triggers on the equals sign.** Apple surfaces the result once you type a trailing `=`
  (`4 + 9 =` → suggests `13`). We match this as the default trigger — it's both Apple-parity and
  the strongest defence against false positives. A *live preview before* `=` is possible but is
  an explicit, off-by-default enhancement to be weighed against the false-positive budget (see
  [interaction spec](06-interaction-spec.md#timing)).
- Inserted by tapping the suggestion or pressing space.
- Scopes to the relevant fragment near the cursor, not the whole field. Typing a sentence and
  then `2+2 =` evaluates the `2+2`, not the prose.

### What it computes

- The four operations `+ − × ÷`, parentheses, decimals, negative numbers, and percent.
- Operator precedence and parentheses behave as a person expects (`2+3*4 = 14`).
- Sensible result formatting: integers without trailing decimals, grouping separators, and
  rounding that doesn't surface floating-point noise (`0.1 + 0.2` reads as `0.3`).

### What it refuses to compute (disambiguation)

This is where Apple's quality really lives, and where a naive clone falls apart. The feature
stays silent on text that merely contains digits and symbols but isn't arithmetic:

- Dates and times: `2024-01-02`, `10:30`, `9/11`.
- Version numbers: `iOS 17.4.1`, `1.2.3`.
- Phone numbers: `555-123-4567`.
- Ranges, scores, ratios in context: `1-0`, `16:9` (judgement call — documented in the spec).
- Lone numbers or a number with a stray symbol: `5`, `5+`.

Our rules for this live in [math engine spec → detection & disambiguation](05-math-engine-spec.md#detection-and-disambiguation).

### Presentation

- The result appears **as a suggestion**, not by silently rewriting what you typed. You stay
  in control; tapping inserts it.
- No error states. If it's not valid math, there's simply no suggestion — never a red squiggle,
  never a "can't compute."
- Instant and local. No spinner, no latency, no "thinking."

### Locale

- Respects the user's decimal separator and digit grouping (a `1.234,56` world vs `1,234.56`
  world). Apple does the right thing per region; so must we.

## Where we deliberately differ

- **Insertion detail.** Apple offers the bare number. We default to the same (insert the
  formatted result at the cursor), with an optional setting to replace the whole expression
  with its result, because Android users have asked for both in similar tools. Default matches
  Apple; the alternative is opt-in. See [interaction spec](06-interaction-spec.md#insertion).
- **The overlay mode** has no Apple analogue — it's our answer to "works with the keyboard you
  already have."

## Reference behaviour table (golden cases)

These become golden tests in [testing](08-testing-quality.md). The implementer must make these
pass before the feature is considered complete. Inputs are shown without the triggering trailing
`=` for brevity (the `=` is what surfaces the suggestion); formatting assumes a `1,234.56`-style
(en-US) locale.

| Input fragment | Suggested result | Notes |
| --- | --- | --- |
| `2+2=` | `4` | Canonical case |
| `12 × 9` | `108` | `×` and `x` both accepted |
| `47.50/3` | `15.83` | Rounded for display; see precision rules |
| `89*0.7` | `62.3` | No trailing zero noise |
| `(2+3)*4` | `20` | Parentheses |
| `200+10%` | `220` | Percent semantics — defined in engine spec |
| `1000000/8` | `125,000` | Grouping separators |
| `0.1+0.2` | `0.3` | No `0.30000000000000004` |
| `5/0` | *(no suggestion)* | Division by zero is silent |
| `iOS 17.4.1` | *(no suggestion)* | Version, not math |
| `2024-01-02` | *(no suggestion)* | Date |
| `555-1234` | *(no suggestion)* | Phone-like |
| `the answer is 6*7` | `42` | Scopes to the fragment |

> The exact rounding, percent, and disambiguation rules behind this table are specified — not
> left to interpretation — in [05-math-engine-spec.md](05-math-engine-spec.md). When this
> document and the engine spec ever seem to disagree, the engine spec wins and this table is
> updated to match.

## Sources

Apple's feature, confirmed against public documentation and reporting at time of writing:

- Apple Support — Solve math with Math Notes / enter formulas and equations on iPhone
  (the iOS 18 Math feature family).
- Reporting on the keyboard "Show Math Results" QuickType behaviour and its
  *Settings ▸ General ▸ Keyboard* toggles (requires Predictive Text), e.g. SlashGear and iMore
  QuickType coverage.

These describe behaviour, not implementation; the implementation is entirely ours.
