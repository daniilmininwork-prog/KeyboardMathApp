package dev.tally.overlay

import android.text.InputType
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for [OverlaySecureField.isSecure] — the AC-2 hard gate (TO.5, `06 §3.2`).
 *
 * `InputType` constants are compile-time ints in `android.jar`, so this runs as a plain JVM test
 * with no Robolectric. Each password variation and the no-suggestions flag must gate; ordinary
 * editable fields must not.
 */
class OverlaySecureFieldTest {

    @Test
    fun `isPassword flag alone gates the field`() {
        assertTrue(OverlaySecureField.isSecure(inputType = InputType.TYPE_CLASS_TEXT, isPassword = true))
    }

    @Test
    fun `text password variation gates`() {
        val it = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        assertTrue(OverlaySecureField.isSecure(it, isPassword = false))
    }

    @Test
    fun `visible password variation gates`() {
        val it = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD
        assertTrue(OverlaySecureField.isSecure(it, isPassword = false))
    }

    @Test
    fun `web password variation gates`() {
        val it = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_WEB_PASSWORD
        assertTrue(OverlaySecureField.isSecure(it, isPassword = false))
    }

    @Test
    fun `numeric PIN password variation gates`() {
        val it = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_VARIATION_PASSWORD
        assertTrue(OverlaySecureField.isSecure(it, isPassword = false))
    }

    @Test
    fun `no-suggestions flag gates`() {
        val it = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        assertTrue(OverlaySecureField.isSecure(it, isPassword = false))
    }

    @Test
    fun `ordinary text field does not gate`() {
        val it = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_NORMAL
        assertFalse(OverlaySecureField.isSecure(it, isPassword = false))
    }

    @Test
    fun `plain number field does not gate`() {
        // A normal numeric field is exactly where math is most wanted — must not be suppressed.
        val it = InputType.TYPE_CLASS_NUMBER
        assertFalse(OverlaySecureField.isSecure(it, isPassword = false))
    }

    @Test
    fun `email field does not gate`() {
        val it = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_EMAIL_ADDRESS
        assertFalse(OverlaySecureField.isSecure(it, isPassword = false))
    }
}
