package dev.tally.math

internal sealed interface Token {
    data object Plus : Token
    data object Minus : Token
    data object Times : Token
    data object Divide : Token
    data object LParen : Token
    data object RParen : Token
    data object Percent : Token
    // value carries the parsed number; originalScale preserves input fractional digits
    data class Num(val value: BigDecimal, val originalScale: Int) : Token
}
