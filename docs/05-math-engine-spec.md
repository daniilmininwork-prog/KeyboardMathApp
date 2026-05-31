# Math engine specification

This is the contract for `core-math`. It is intentionally precise: the engine's correctness is
the product, and ambiguity here turns into bugs and false positives. Where Apple's exact
internal rule is unobservable, we choose the most predictable behaviour and pin it here.

> This document specifies behaviour, not implementation. It uses grammar notation and tables to
> define *what* must be true; the implementer chooses the algorithms.

## Scope and extension path

**v1 scope (Apple-parity arithmetic):** the four operations, parentheses, decimals, negative
numbers, percent. Nothing else.

**Deliberately out of scope for v1:** functions (`sqrt`, `sin`…), variables, equation solving,
unit conversion, and currency. Currency in particular would require live rates over the
network, which violates the [no-network guarantee](07-security-privacy.md); it is therefore not
a candidate without a separate, explicit design.

**Extension path (post-v1, optional):** the grammar and evaluator are designed so a later
"advanced pack" can add functions and unit conversion behind a settings toggle without
rewriting the core. Any such addition is a new ADR and must preserve the no-network rule.

## Public contract

`core-math` exposes one conceptual operation to the rest of the app:

> **Given** the text immediately before the cursor (and optionally a little after), the cursor
> position, and a locale, **return** either:
> - a **suggestion** = `{ formatted display string, the source span it was derived from, the raw
>   exact value }`, or
> - **nothing**, when there is no confident arithmetic to offer.

It must be:

- **Pure & deterministic** — same inputs always give the same output; no clock, no randomness,
  no I/O, no globals.
- **Total** — never throws on any input, however malformed or hostile; "no result" is a normal
  return, not an exception.
- **Bounded** — runs within the [performance budget](#performance-budget) on any input up to the
  [input limits](#limits), including adversarial input.

## Grammar

Arithmetic grammar in EBNF. This defines the language the parser accepts; the lexer produces the
terminals. `DIGIT`, decimal separators, and grouping separators are **locale-parameterised**
(see [numbers and locale](#numbers-and-locale)).

```
expression   = term , { ( "+" | "-" ) , term } ;
term         = factor , { ( "*" | "/" ) , factor } ;
factor       = unary , [ "%" ] ;                 (* postfix percent binds tightest *)
unary        = [ "+" | "-" ] , primary ;
primary      = number | "(" , expression , ")" ;
number       = digits , [ decimal_sep , digits ]
             | decimal_sep , digits ;
digits       = DIGIT , { DIGIT | grouping_sep , DIGIT } ;  (* grouping is tolerated on input *)
```

Notes:

- **Operators accepted on input** (lexer normalises to the canonical set above):
  `+`, `-` (also Unicode minus `−`), `*` (also `×` and the letter `x`/`X` *only between digits*),
  `/` (also `÷`). The `x`-as-times rule is narrow on purpose to avoid eating words.
- **Implicit multiplication is NOT supported** in v1 (`2(3)` does not mean `6`). Keeps
  disambiguation tractable; revisit only with evidence.
- **Percent** is postfix and defined in [percent semantics](#percent-semantics).

## Operator precedence and associativity

| Level | Operators | Associativity |
| --- | --- | --- |
| Highest | postfix `%` | — |
| | unary `+ -` | right |
| | `* /` | left |
| Lowest | `+ -` (binary) | left |

Parentheses override precedence. `2 + 3 * 4 = 14`. `(2 + 3) * 4 = 20`. `-2 ^ ...` — no
exponent in v1.

## Percent semantics

Percent is the classic place clones disagree. We define two rules and pick one as default:

1. **Standalone percent:** `n%` evaluates to `n / 100`. So `50% = 0.5`, `200 * 15% = 30`.
2. **Additive percent (calculator-style):** in `a + b%` and `a - b%`, the percent is taken
   *relative to `a`*: `a ± (a * b/100)`. So `200 + 10% = 220`, `80 - 25% = 60`.

**Default behaviour:** apply additive percent for `+`/`-` when the right operand is a bare
percent term (rule 2), and rule 1 everywhere else. This matches the common phone-calculator
expectation and the [reference table](02-apple-reference-analysis.md#reference-behaviour-table-golden-cases)
(`200+10% → 220`).

This rule is exact and testable; the golden cases lock it in. If user testing shows confusion,
changing it is a documented decision, not a silent tweak.

## Numbers and locale

- The **decimal separator** and **grouping separator** come from the active locale
  (e.g. `.`/`,` in en-US; `,`/`.` in de-DE; `,`/space in fr-FR).
- **On input**, grouping separators are tolerated and ignored inside a number (`1,000+5` works
  in en-US). Ambiguous cases (where the same character is both grouping and decimal across
  locales) resolve in favour of the active locale's roles.
- **Digits**: ASCII `0-9` always; additionally accept the active locale's native digit set if it
  has one. Output uses the locale's conventional digits.

## Detection and disambiguation

The detector decides whether the text near the cursor *is* arithmetic worth offering. This is
the single most important quality lever — see
[Apple analysis](02-apple-reference-analysis.md#what-it-refuses-to-compute-disambiguation).

**A candidate is offered only if all hold:**

1. The span parses cleanly under the [grammar](#grammar) with no leftover characters.
2. It contains **at least one binary operator** and **at least two operands** (a lone number or
   `5+` never qualifies).
3. The span is bounded by the current fragment around the cursor — delimited by whitespace and
   sentence boundaries, not the whole field. Leading prose like `the answer is 6*7` yields the
   `6*7` span.
4. It is not better explained by a **non-math pattern**. The detector runs a set of *vetoes*
   before offering:

**Veto patterns (must NOT produce a suggestion):**

| Pattern | Examples | Why |
| --- | --- | --- |
| Date | `2024-01-02`, `01/02/2024`, `9/11` | Calendar, not division |
| Time | `10:30`, `9:45:00` | `:` is not an operator |
| Version | `1.2.3`, `iOS 17.4.1` | Multiple dots / letter-led |
| Phone-like | `555-123-4567`, `+1 555 1234` | Dash groups, length |
| Identifier-adjacent | `abc-1`, `x2` | Letters touching the expression |
| Ranges/scores in context | `1-0`, `16:9` | Heuristic; documented, tested |

Implementation guidance: the cleanest approach is "parse first, then veto" — only run veto
checks on spans that already parse as arithmetic, and reject the span if a veto matches the
*surrounding context* (adjacent letters, multiple `.`/`:`/`-` separators forming a date/version/
phone shape). The exact veto set is a living list backed by the
[false-positive corpus](08-testing-quality.md).

**Trailing `=`:** a trailing `=` (optionally with spaces) is stripped before parsing and is, by
default, **required** to surface a suggestion — it is the trigger, matching Apple, and the primary
guard against false positives. The parser still evaluates `=`-less spans internally so an optional
live-preview mode can be built on top (see [interaction spec](06-interaction-spec.md#timing)).

## Numeric model

Correct, human-looking arithmetic — not raw IEEE floats.

- **Internal representation:** arbitrary-precision decimal (Java/Kotlin `BigDecimal`), **not**
  `Double`. This is what makes `0.1 + 0.2` read as `0.3` and avoids binary-float surprises.
- **Working precision:** evaluate with a fixed `MathContext` — **34 significant digits**
  (decimal128-like) with rounding **HALF_EVEN** (banker's rounding) for intermediate steps.
- **Division:** non-terminating results (`1/3`) are computed to working precision then formatted
  down to display precision; they never loop forever.
- **Division by zero / modulo by zero:** return **no result** (silent), never infinity or a crash.
- **Display precision:** round to a sensible number of fractional digits for the suggestion
  (default **up to 6**, trailing zeros stripped), configurable in settings. The *exact* value is
  retained in the suggestion object in case a future "insert full precision" option is added.

## Limits

Hard caps so hostile or accidental input can't hang the keyboard:

- **Max scanned span length:** e.g. 256 characters (tunable). Longer fragments aren't scanned.
- **Max parenthesis nesting depth:** e.g. 32. Beyond → no result.
- **Max operand magnitude / digit count:** cap total significant digits (e.g. 10,000 input
  digits) → beyond which no result.
- **Total evaluation step budget:** bounded; if exceeded → no result. (Belt-and-braces against
  pathological inputs even within length limits.)

All limits produce a clean "no result," never an error surfaced to the user.

## Performance budget

- Detection + parse + evaluate + format for a typical expression: **target < 2 ms**, **ceiling
  5 ms**, measured on a mid-range device. This is what makes the suggestion feel "already there."
- Combined with input debouncing (see [interaction spec](06-interaction-spec.md#timing)), the
  engine must never cause perceptible keyboard lag. The budget is enforced by performance tests
  in [testing](08-testing-quality.md).

## Formatting rules (summary)

1. Use the locale decimal + grouping separators.
2. Integers display with no fractional part (`108`, not `108.0`).
3. Strip trailing zeros after rounding (`62.3`, not `62.30`).
4. Apply grouping to the integer part (`125,000`).
5. Very large/small magnitudes beyond a threshold may use scientific notation (threshold
   documented and tested) rather than an unreadable digit wall.
6. Negative results use the locale's minus sign.

The [reference behaviour table](02-apple-reference-analysis.md#reference-behaviour-table-golden-cases)
is the canonical set of worked examples and is implemented as golden tests.
