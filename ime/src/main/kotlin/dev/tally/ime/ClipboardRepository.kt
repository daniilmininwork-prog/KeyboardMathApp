package dev.tally.ime

import dev.tally.keyboard.engine.FieldPolicy

/**
 * Business-logic layer for the clipboard history panel.
 *
 * Aggregates [ClipboardStore] (persistence) and exposes a stable API to the IME surface and
 * the panel view. All state mutations are synchronous from the caller's perspective; the
 * underlying store applies lazy expiry before every read.
 *
 * ## Insertion contract (binding, from R3-samsung and 04 §2.4)
 *
 * Text is inserted into the editor **only** via the [onInsert] callback, which the IME wires
 * to [android.view.inputmethod.InputConnection.commitText]. There is no reference to
 * [android.content.ClipboardManager] anywhere in this class or its callers. This is
 * enforced structurally (the class has no ClipboardManager parameter/field) and verified by
 * the acceptance-criterion grep check.
 *
 * @param store      The underlying encrypted store.
 * @param onInsert   Delivers the selected text to the active InputConnection. Called on the
 *                   main thread.
 */
internal class ClipboardRepository(
    private val store: ClipboardStore,
    val onInsert: (String) -> Unit,
) {

    /**
     * Records [text] into the history if [policy] permits persistence.
     *
     * Call this from [TallyInputMethodService] whenever the user pastes text from an
     * external source (long-press Paste action). Not called on internal IME commits —
     * those originate from the keyboard and don't need to be re-stored.
     */
    fun record(text: String, policy: FieldPolicy) = store.record(text, policy)

    /**
     * Returns current entries, newest first, after applying expiry sweeps.
     *
     * The result is a stable snapshot; subsequent pin/remove calls do not mutate it.
     */
    fun entries(): List<ClipEntry> = store.getAll()

    /**
     * Inserts [entry] into the active editor without touching the system clipboard.
     *
     * The caller (ClipboardPanelView) invokes this; this class forwards to [onInsert].
     * The indirection exists so tests can assert insertion without a live IME session.
     */
    fun insert(entry: ClipEntry) = onInsert(entry.text)

    /**
     * Toggles the pinned state of [entry].
     *
     * Pinned entries are never expired by the TTL sweep. Unpinning restores normal expiry.
     */
    fun togglePin(entry: ClipEntry) = store.togglePin(entry.id)

    /**
     * Removes [entry] from the history.
     */
    fun remove(entry: ClipEntry) = store.remove(entry.id)

    /**
     * Clears all non-pinned entries. Pinned entries survive.
     */
    fun clearHistory() = store.clearUnpinned()
}
