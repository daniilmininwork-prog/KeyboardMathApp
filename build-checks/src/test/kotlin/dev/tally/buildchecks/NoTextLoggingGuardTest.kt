package dev.tally.buildchecks

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies that [containsForbiddenLogging] catches the logging patterns that the
 * noTextLoggingGuard Gradle task rejects in production source files.
 *
 * Per 06 §5.1: Log.v/d/i/wtf are forbidden (R8 strips them; guard catches them first).
 * Log.w and Log.e are permitted for exceptional-path error reporting and must not trigger.
 */
class NoTextLoggingGuardTest {

    @Test
    fun `Log_d call is detected`() {
        assertTrue(containsForbiddenLogging("""Log.d("tag", "message")"""))
    }

    @Test
    fun `Log_v call is detected`() {
        assertTrue(containsForbiddenLogging("""Log.v("tag", "verbose")"""))
    }

    @Test
    fun `Log_i call is detected`() {
        assertTrue(containsForbiddenLogging("""Log.i("tag", "info")"""))
    }

    @Test
    fun `Log_wtf call is detected`() {
        assertTrue(containsForbiddenLogging("""Log.wtf("tag", "what a failure")"""))
    }

    @Test
    fun `Log_e is permitted — error logging is allowed`() {
        // Log.e survives R8 stripping intentionally (06 §5.1).
        assertFalse(containsForbiddenLogging("""Log.e("tag", "critical error", e)"""))
    }

    @Test
    fun `Log_w is permitted — warning logging is allowed`() {
        assertFalse(containsForbiddenLogging("""Log.w("tag", "unexpected state")"""))
    }

    @Test
    fun `println is detected`() {
        assertTrue(containsForbiddenLogging("""println("debug output")"""))
    }

    @Test
    fun `System_out_println is detected`() {
        assertTrue(containsForbiddenLogging("""System.out.println("value")"""))
    }

    @Test
    fun `System_err_println is detected`() {
        assertTrue(containsForbiddenLogging("""System.err.println("error")"""))
    }

    @Test
    fun `commented-out log call does not trigger`() {
        // Production code may have disabled debug calls via comments.
        assertFalse(containsForbiddenLogging("""// Log.d("tag", "disabled")"""))
    }

    @Test
    fun `clean source file passes`() {
        val source = """
            package dev.tally.math
            class Evaluator {
                fun evaluate(expr: String): String = expr
            }
        """.trimIndent()
        assertFalse(containsForbiddenLogging(source))
    }

    @Test
    fun `multi-line source with Log_d violation is detected`() {
        val source = """
            package dev.tally.ime
            class Controller {
                fun onKey(code: Int) {
                    Log.d("Controller", "key: ${'$'}code")
                }
            }
        """.trimIndent()
        assertTrue(containsForbiddenLogging(source))
    }

    @Test
    fun `multi-line source with only Log_e and Log_w passes`() {
        val source = """
            package dev.tally.ime
            class Controller {
                fun onKey(code: Int) {
                    try { process(code) }
                    catch (e: Exception) { Log.e("Controller", "process failed", e) }
                }
                fun onWarning() { Log.w("Controller", "unexpected state") }
            }
        """.trimIndent()
        assertFalse(containsForbiddenLogging(source))
    }

    @Test
    fun `empty source passes`() {
        assertFalse(containsForbiddenLogging(""))
    }
}
