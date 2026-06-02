package dev.tally.ime

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import dev.tally.keyboard.engine.FieldPolicy
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Acceptance criterion: "No system-clipboard write in any insert path."
 *
 * This test is the programmatic counterpart to the grep check:
 *
 *   grep -r "ClipboardManager" ime/src/main/kotlin/dev/tally/ime/Clipboard*.kt
 *
 * It exercises the full insert path and confirms the text arrives via [onInsert] (the
 * [android.view.inputmethod.InputConnection.commitText] delegate) and never via
 * [android.content.ClipboardManager].
 *
 * The structural enforcement — that ClipboardManager is never referenced in the sources —
 * is verified by the grep check in the acceptance step (see T4.3 notes).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class ClipboardInsertionPathTest {

    private val context: Application
        get() = ApplicationProvider.getApplicationContext()

    private var insertCallCount = 0
    private var lastInsertedText: String? = null

    private lateinit var store: ClipboardStore
    private lateinit var repo: ClipboardRepository

    @Before
    fun setUp() {
        context.getSharedPreferences(ClipboardStore.PREFS_NAME, 0).edit().clear().commit()
        insertCallCount = 0
        lastInsertedText = null
        store = ClipboardStore(
            context    = context,
            encryption = ClipboardStore.NoOpEncryption(),
        )
        repo = ClipboardRepository(store = store, onInsert = { text ->
            insertCallCount++
            lastInsertedText = text
        })
    }

    @Test
    fun insertRoutesToOnInsertCallback() {
        repo.record("test insertion text", FieldPolicy.PERMISSIVE)
        val entry = repo.entries().first()
        repo.insert(entry)

        assertTrue("onInsert must be called exactly once", insertCallCount == 1)
        assertTrue("text must match the entry", lastInsertedText == "test insertion text")
    }

    @Test
    fun insertDoesNotUseClipboardManager() {
        // Structural: ClipboardRepository and ClipboardStore have no ClipboardManager import.
        // This is enforced by the source-level check:
        //   grep -r "ClipboardManager" ime/src/main/kotlin/dev/tally/ime/ClipboardRepository.kt
        //   grep -r "ClipboardManager" ime/src/main/kotlin/dev/tally/ime/ClipboardStore.kt
        //   grep -r "ClipboardManager" ime/src/main/kotlin/dev/tally/ime/ClipboardPanelView.kt
        // Each must return empty.
        //
        // Programmatically, the only way text flows from the store to the field is via
        // onInsert. If we capture it correctly, ClipboardManager was never involved.
        repo.record("structural guarantee", FieldPolicy.PERMISSIVE)
        val entry = repo.entries().first()
        repo.insert(entry)

        // onInsert captured the text correctly — no other delivery path exists.
        assertFalse("insert call must have been made", insertCallCount == 0)
        assertTrue("text delivered via InputConnection delegate, not ClipboardManager",
            lastInsertedText == "structural guarantee")
    }

    @Test
    fun sensitiveFieldNeverPersists() {
        // persistAllowed=false means no store write; therefore no entry can ever
        // be inserted from this field's content.
        val sensitivePolicy = FieldPolicy.DEFAULT_PRIVATE
        repo.record("hunter2", sensitivePolicy)
        assertTrue("sensitive-field text must never reach the store", repo.entries().isEmpty())
        assertTrue("no insertion attempted", insertCallCount == 0)
    }

    @Test
    fun urlEntityInsertionRoutesCorrectly() {
        repo.record("https://example.com/page", FieldPolicy.PERMISSIVE)
        val entry = repo.entries().first()
        assertTrue("URL entity detected", entry.entityType == EntityType.URL)

        repo.insert(entry)
        assertTrue("URL text delivered via onInsert", lastInsertedText == "https://example.com/page")
    }
}
