# Interaction & rendering specification

How the feature feels — timing, insertion, motion, haptics, accessibility. The engine decides
*what* to suggest; this decides *how and when* it appears and behaves. The goal is the Apple
quality bar from [product vision](01-product-vision.md): invisible until useful, then faster
than you.

## Timing

The tension: evaluate often enough to feel instant, rarely enough to avoid churn and lag.

- **Default trigger: a trailing `=`.** Matching Apple, the suggestion surfaces when the user
  types `=` after a valid expression. This is the safest default against false positives.
- **Debounce** input by a short window — **target 90 ms, range 60–150 ms** after the last
  keystroke — then run detection. Tuned during dogfooding; exposed as a build constant, not a
  user setting.
- **Optional live preview (off by default):** showing a result *before* `=` is a possible
  enhancement; if enabled it must meet the same false-positive bar and re-evaluate as the
  expression changes. Ship the `=`-triggered behaviour first.
- **Clear immediately** when the expression stops being valid or the cursor leaves the fragment.
  Never leave a stale answer on screen.
- All evaluation respects the [engine performance budget](05-math-engine-spec.md#performance-budget);
  if anything runs long it yields "no result" rather than blocking input.

## When the chip shows

Show the result chip when, and only when, `core-math` returns a suggestion (per the
[detection rules](05-math-engine-spec.md#detection-and-disambiguation)). Otherwise the strip
shows whatever it normally would (IME) or nothing (overlay). There is no "calculating" state and
no error state — silence is the failure mode.

## Insertion

What a tap does. Default mirrors Apple; an alternative is opt-in.

- **Default — append result:** insert the formatted result at the cursor. If the fragment ends
  with `=`, the result lands after it, giving `2+2=4` (with a space rule consistent with the
  surrounding text). The user's original text is preserved.
- **Optional — replace expression:** a setting to replace the whole detected span with its
  result (`2+2` → `4`). Off by default.
- Insertion uses `InputConnection` in the IME and accessibility actions / clipboard paste in the
  overlay (see [architecture](04-system-architecture.md#data-flow)).
- Insertion is **idempotent and undoable** — it's a normal text edit the user can backspace; we
  never lock or rewrite beyond the single insertion.
- After insertion, clear the chip (the suggestion has been consumed).

## Motion

Subtle, fast, never distracting.

- **Appear:** quick fade + small translate/scale, ~120–160 ms, standard easing.
- **Update:** if the value changes while visible, cross-fade the number rather than re-running
  the full entrance.
- **Dismiss:** quick fade out, ~100 ms.
- Respect the system **"remove animations" / reduce-motion** setting — fall back to instant
  show/hide.
- Motion tokens live in the shared `design-system` so the IME suggestion and overlay chip move
  identically.

## Haptics

- A single, subtle haptic on **tap-to-insert** (the confirmation). Use the platform's light/
  standard tap effect.
- **No** haptic merely on the chip appearing (that would make the feature noisy — the opposite
  of the goal).
- Honour the system haptics setting and a per-app toggle. Default on, gentle.

## Visual design

- The chip matches the host's suggestion styling as closely as possible in the IME so it reads
  as a native suggestion, and uses an equivalent restrained style in the overlay.
- **Theming:** full dark/light support; **Material You / dynamic colour** where available, with a
  sensible static fallback.
- **Density & size:** respect system font scale and display size; the chip and its tap target
  meet the minimum touch-target size at all scales.
- **RTL:** full mirroring; the result reads correctly in RTL locales.

## Accessibility

- **TalkBack:** the chip is a labelled, focusable control — e.g. announced as "insert result,
  one hundred eight" — with a clear role and action description.
- **Touch targets** meet the platform minimum regardless of font scale.
- **Contrast** meets WCAG AA against the strip/overlay background in both themes.
- **Reduce motion** honoured (see [motion](#motion)).
- Accessibility is verified, not assumed — see [testing](08-testing-quality.md).

## Settings (app)

Minimal, sensible defaults; every toggle has an obvious purpose:

- Enable/disable the math suggestion.
- Display precision (fractional digits).
- Percent mode (default additive vs. always `/100`) — advanced, with an inline example.
- Insertion behaviour (append vs. replace).
- Haptics on/off.
- Locale override (default: follow system).
- Overlay mode enable + its permission management (separate, clearly disclosed).

## Overlay-specific behaviour

- The chip is positioned **near the caret/field**, not over the keys, and never obscures the
  character being typed.
- It must not steal focus from the field or the keyboard.
- It is hardened against **tap-jacking** (ignore taps when the window is obscured) — see
  [security](07-security-privacy.md).
- It disappears when the field loses focus, the app changes, or the expression becomes invalid.

## Edge interactions to get right

- **Fast typing / paste:** debounce coalesces; a pasted `12*9=` is detected on settle.
- **Cursor moves into/out of a fragment:** re-evaluate against the new cursor context; show/clear
  accordingly.
- **Multiple expressions in a field:** evaluate only the fragment around the cursor.
- **User keeps typing after the chip shows:** the chip updates or clears; it never blocks input
  or "commits" anything on its own. Insertion happens only on an explicit tap.
