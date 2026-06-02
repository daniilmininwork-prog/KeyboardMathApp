package dev.tally.math

/**
 * Controls how stand-alone percent expressions are evaluated.
 *
 * [ADDITIVE] — the default and Apple-matching behaviour. `100 + 20%` means 100 plus 20% of 100,
 * giving 120. [DIRECT] — percent always means ÷100, so `100 + 20%` gives 100.2.
 */
enum class PercentMode {
    ADDITIVE,
    DIRECT,
}
