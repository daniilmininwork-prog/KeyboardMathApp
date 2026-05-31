package dev.tally.math

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.Locale

/** Every entry must produce no suggestion. A suggestion here is a false positive. */
class FalsePositiveTest {

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = [
        // Dates
        "2024-01-02=",
        "2023-12-31=",
        "01/02/2024=",
        "12/25/2023=",
        "9/11=",
        "1/1=",
        "2024/01/02=",
        "2024-06=",

        // Times — colon causes lexer failure
        "10:30=",
        "9:45:00=",
        "23:59=",

        // Version numbers
        "1.2.3=",
        "2.10.5=",
        "iOS 17.4.1=",
        "v2.0.1=",
        "3.14.159=",

        // Phone-like
        "555-1234=",
        "555-123-4567=",
        "800-555-0199=",

        // Identifier-adjacent
        "abc-1=",
        "x2=",
        "item3+4=",

        // Incomplete
        "5+=",
        "42=",
        "+3=",
        "%50=",

        // No trigger
        "2+2",
        "100*3",
    ])
    fun `corpus yields no suggestion`(input: String) {
        assertNull(MathEngine.evaluate(input, Locale.US), "false positive for: $input")
    }
}
