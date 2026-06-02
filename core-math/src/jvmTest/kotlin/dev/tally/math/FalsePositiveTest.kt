package dev.tally.math

import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.util.Locale

/** Every entry must produce no suggestion. A suggestion here is a false positive. */
class FalsePositiveTest {

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = [
        // ── Dates ─────────────────────────────────────────────────────────────
        "2024-01-02=",
        "2023-12-31=",
        "01/02/2024=",
        "12/25/2023=",
        "9/11=",
        "1/1=",
        "2024/01/02=",
        "2024-06=",
        "2000-01-01=",
        "31/12/1999=",
        "03-04-2025=",
        "Jan 3/4=",

        // ── Times — colon causes lexer failure ────────────────────────────────
        "10:30=",
        "9:45:00=",
        "23:59=",
        "0:00=",
        "12:00:00=",

        // ── Version numbers ───────────────────────────────────────────────────
        "1.2.3=",
        "2.10.5=",
        "iOS 17.4.1=",
        "v2.0.1=",
        "3.14.159=",
        "AGP 8.8.0=",
        "Kotlin 2.1.21=",
        "Android 15.0.1=",
        "OpenSSL 3.0.7=",

        // ── IP addresses ──────────────────────────────────────────────────────
        "192.168.1.1=",
        "10.0.0.255=",
        "172.16.254.1=",
        "127.0.0.1=",
        "255.255.255.0=",

        // ── Phone-like ────────────────────────────────────────────────────────
        "555-1234=",
        "555-123-4567=",
        "800-555-0199=",
        "+1-800-555-0100=",
        "44-20-7946-0958=",

        // ── Identifier-adjacent ───────────────────────────────────────────────
        "abc-1=",
        "x2=",
        "item3+4=",
        "v1+2=",
        "id42=",
        "node1+node2=",
        "row3-row1=",

        // ── Incomplete expressions (no binary op, or bare number) ─────────────
        "5+=",
        "42=",
        "+3=",
        "%50=",
        "-7=",
        "(5)=",

        // ── No trigger character ──────────────────────────────────────────────
        "2+2",
        "100*3",
        "let x = 5+3",
        "cost is 9.99+0.01",

        // ── URLs and paths ────────────────────────────────────────────────────
        // Colon breaks the lexer for most of these; confirm no crash and no result
        "https://example.com/path=",
        "http://10.0.0.1:8080/api=",
        "file:///home/user/doc.pdf=",
        "/usr/local/bin/app-1.2=",

        // ── Hex strings ───────────────────────────────────────────────────────
        "0xFF=",
        "deadbeef=",
        "#1A2B3C=",
        "0x1F+0x2E=",

        // ── Serial numbers / barcodes ─────────────────────────────────────────
        "SN-2024-0001=",
        "978-3-16-148410-0=",         // ISBN-13
        "4006381333931=",             // EAN-13 (no operator)

        // ── Scores and stats ──────────────────────────────────────────────────
        // These look like subtraction; veto catches them via date/phone patterns or
        // identifier-adjacency
        "Lakers 108-Celtics 103=",
        "Q1: 45-Q2: 52=",

        // ── Code snippets ─────────────────────────────────────────────────────
        "if x>0=",
        "return a+b=",
        "val x=5+3=",
        "arr[0]+arr[1]=",

        // ── Currency and unit noise ───────────────────────────────────────────
        "$100+tax=",
        "100USD+50EUR=",
        "3km+2mi=",

        // ── Single-operand with percent — no binary arithmetic ────────────────
        "10%=",
        "100%=",

        // ── Scores and ranges: single-digit A-B ───────────────────────────────
        // Bare single-digit subtraction looks like a sports score; the spec lists
        // 1-0 as a canonical veto example.
        "1-0=",
        "2-1=",
        "3-0=",
        "0-0=",

        // ── Trailing arithmetic prose — expression is not right-anchored ──────
        // An expression fragment followed by arithmetic-parseable words must not match,
        // because the user's text extends past where the arithmetic ends.
        "half of 10+5 is 10=",

        // ── Cross-locale grouping ambiguity ───────────────────────────────────
        // In en-US (`.` = decimal), N.DDD could mean N*1000 in a comma-decimal locale.
        // Suppress to avoid showing the wrong result to users who typed grouping notation.
        "1.000+5=",
        "2.000*3=",
    ])
    fun `corpus yields no suggestion`(input: String) {
        assertNull(MathEngine.evaluate(input, Locale.US), "false positive for: $input")
    }
}
