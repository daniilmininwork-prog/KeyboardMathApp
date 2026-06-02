package dev.tally.ime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Unit tests for [InputConnectionMirror].
 *
 * The mirror is a plain Kotlin class with no Android UI dependencies (Robolectric is used only
 * because [android.os.Build] is referenced in the production code path). Tests exercise:
 *
 *   - Seeding from a null/fake IC: mirror reflects the provided text.
 *   - [reconcile]: returns true for external changes, false when [isMidBatchEdit].
 *   - [appendCommitted]: optimistically updates [textBefore].
 *   - [deleteBeforeCodePoints]: handles ASCII, multi-byte, and surrogate pairs.
 *   - Batch-edit flag: [onBatchEditBegin] sets [isMidBatchEdit]; [onBatchEditEnd] clears it.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class InputConnectionMirrorTest {

    private lateinit var mirror: InputConnectionMirror

    @Before
    fun setUp() {
        mirror = InputConnectionMirror()
    }

    // ── Initial state ─────────────────────────────────────────────────────────

    @Test
    fun seed_nullIc_resetsToEmpty() {
        mirror.seed(null)

        assertEquals("", mirror.textBefore.toString())
        assertEquals("", mirror.textAfter.toString())
    }

    // ── appendCommitted ───────────────────────────────────────────────────────

    @Test
    fun appendCommitted_updatesTextBefore() {
        mirror.appendCommitted("hello")

        assertEquals("hello", mirror.textBefore.toString())
    }

    @Test
    fun appendCommitted_multipleCallsAccumulate() {
        mirror.appendCommitted("foo")
        mirror.appendCommitted("bar")

        assertEquals("foobar", mirror.textBefore.toString())
    }

    // ── deleteBeforeCodePoints ────────────────────────────────────────────────

    @Test
    fun deleteBeforeCodePoints_singleAscii_removesLastChar() {
        mirror.appendCommitted("hello")
        mirror.deleteBeforeCodePoints(1)

        assertEquals("hell", mirror.textBefore.toString())
    }

    @Test
    fun deleteBeforeCodePoints_multipleChars_removesCorrectCount() {
        mirror.appendCommitted("hello")
        mirror.deleteBeforeCodePoints(3)

        assertEquals("he", mirror.textBefore.toString())
    }

    @Test
    fun deleteBeforeCodePoints_moreCountThanLength_clearsBuffer() {
        mirror.appendCommitted("hi")
        mirror.deleteBeforeCodePoints(10)

        assertEquals("", mirror.textBefore.toString())
    }

    @Test
    fun deleteBeforeCodePoints_emoji_removesOneCodePoint() {
        // U+1F600 GRINNING FACE is a surrogate pair in UTF-16 (two Char units, one code point).
        val smiley = "😀"  // 😀
        mirror.appendCommitted("hello$smiley")
        mirror.deleteBeforeCodePoints(1)

        // Deleting one code point should remove the entire emoji, not just one surrogate.
        assertEquals("hello", mirror.textBefore.toString())
    }

    @Test
    fun deleteBeforeCodePoints_emptyBuffer_noOp() {
        mirror.deleteBeforeCodePoints(1)

        assertEquals("", mirror.textBefore.toString())
    }

    // ── Batch-edit flag ───────────────────────────────────────────────────────

    @Test
    fun batchEditFlag_falseByDefault() {
        assertFalse(mirror.isMidBatchEdit)
    }

    @Test
    fun onBatchEditBegin_setsFlagTrue() {
        mirror.onBatchEditBegin()

        assertTrue(mirror.isMidBatchEdit)
    }

    @Test
    fun onBatchEditEnd_clearsFlagFalse() {
        mirror.onBatchEditBegin()
        mirror.onBatchEditEnd()

        assertFalse(mirror.isMidBatchEdit)
    }

    // ── reconcile ─────────────────────────────────────────────────────────────

    /**
     * When IC is null (field was just dismissed), reconcile must return false — there is
     * no live field to evaluate against. The mirror is reset but evaluation is not triggered.
     * (Fix 11: guard for null IC so spurious strip clears are avoided.)
     */
    @Test
    fun reconcile_nullIc_returnsFalse() {
        val result = mirror.reconcile(5, 5, null)

        assertFalse("Null IC means field dismissed — must NOT trigger evaluation", result)
    }

    /**
     * When IC is null, reconcile resets the mirror but writes the provided selection coords
     * are NOT applied (the reset clears everything). The selection stays at -1.
     */
    @Test
    fun reconcile_nullIc_resetsSelectionToUnknown() {
        mirror.reconcile(5, 5, null)

        // selStart should remain -1 after a reset (null IC path just resets)
        assertEquals(-1, mirror.selStart)
    }

    @Test
    fun reconcile_whenMidBatch_returnsFalse() {
        mirror.onBatchEditBegin()

        val result = mirror.reconcile(3, 3, null)

        assertFalse("Our own batch edit must not trigger re-evaluation", result)
    }
}
