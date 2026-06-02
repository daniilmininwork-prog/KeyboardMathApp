package dev.tally.buildchecks

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies that [containsAndroidImport] correctly identifies violations.
 *
 * The coreMathAndroidFreeGuard and keyboardEngineAndroidFreeGuard Gradle tasks use
 * the same regex; a failure here means the guard would silently miss a violation.
 */
class AndroidFreeGuardTest {

    @Test
    fun `detects direct android import`() {
        val source = """
            package dev.tally.math
            import android.util.Log
            class Foo
        """.trimIndent()
        assertTrue(containsAndroidImport(source))
    }

    @Test
    fun `detects androidx import`() {
        val source = """
            package dev.tally.engine
            import androidx.core.content.ContextCompat
            class Bar
        """.trimIndent()
        assertTrue(containsAndroidImport(source))
    }

    @Test
    fun `detects android sub-package import`() {
        assertTrue(containsAndroidImport("import android.view.View"))
    }

    @Test
    fun `clean file passes`() {
        val source = """
            package dev.tally.math
            import java.math.BigDecimal
            import kotlin.math.abs
            class MathHelper
        """.trimIndent()
        assertFalse(containsAndroidImport(source))
    }

    @Test
    fun `commented-out android import does not trigger`() {
        // The regex anchors to line-start followed only by whitespace, so "// import android."
        // does not match: slashes are not whitespace.  Developers can safely leave disabled
        // import lines in review diffs without tripping the guard.
        val source = "// import android.util.Log"
        assertFalse(containsAndroidImport(source))
    }

    @Test
    fun `empty file passes`() {
        assertFalse(containsAndroidImport(""))
    }

    @Test
    fun `android import in a string literal does not trigger`() {
        // The regex requires the import keyword at line-start (with optional leading whitespace).
        // A string literal containing "import android." is not a line-starting import statement.
        val source = """val hint = "import android.util.Log" """
        assertFalse(containsAndroidImport(source))
    }
}
