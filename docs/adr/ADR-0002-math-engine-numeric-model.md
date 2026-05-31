# ADR-0002: Numeric model — arbitrary-precision decimal, not floating point

- **Status:** accepted
- **Context date:** project kickoff

## Context

The engine evaluates user-typed arithmetic and shows the result. The obvious choice, IEEE-754
`Double`, produces results that look wrong to humans:

- `0.1 + 0.2` → `0.30000000000000004`
- Money math (`47.50 / 3`, `19.99 * 3`) accumulates binary-float error.
- Large integer products lose precision.

A keyboard that occasionally shows `0.30000000000000004` instantly loses trust. The result must
match what a careful person with a calculator expects.

## Decision

Evaluate using **arbitrary-precision decimal arithmetic** (`BigDecimal`), not `Double`.

- Working precision: a fixed `MathContext` of **34 significant digits** (decimal128-like),
  rounding **HALF_EVEN**.
- Non-terminating division (`1/3`) is computed to working precision and then formatted to display
  precision; it never loops.
- Division/modulo by zero returns **no result** (silent), never NaN/Infinity/crash.
- Display rounds to a small number of fractional digits (default ≤ 6, trailing zeros stripped);
  the exact value is retained in the suggestion object for any future "insert full precision".

Details and limits live in [math engine spec](../05-math-engine-spec.md).

## Consequences

- Results look right (`0.1 + 0.2 = 0.3`, clean money math) — directly serving the trust goal.
- Slightly more CPU than `Double`, comfortably inside the
  [performance budget](../05-math-engine-spec.md#performance-budget) for arithmetic at human
  input sizes, especially with the input [limits](../05-math-engine-spec.md#limits).
- Need explicit, tested rounding/formatting rules (we want those pinned down anyway).

## Alternatives considered

- **`Double`:** fast, simple, but produces human-wrong output; rejected.
- **Rational (exact fractions):** maximally exact, but display always needs rounding anyway and
  it complicates formatting/edge cases; `BigDecimal` at high precision is the pragmatic fit.
