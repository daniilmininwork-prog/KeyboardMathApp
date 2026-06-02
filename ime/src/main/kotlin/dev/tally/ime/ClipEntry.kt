package dev.tally.ime

/**
 * A single entry in the local clipboard history.
 *
 * Entries are app-private: never written to the system clipboard during creation
 * and never routed through [android.content.ClipboardManager] during insertion.
 * Privacy contract: sensitive entries (captured while FieldPolicy.persistAllowed is
 * false) are marked [isSensitive] so callers can schedule early expiry.
 *
 * @param id          Unique identifier; used as the storage key.
 * @param text        The clipped text.
 * @param timestampMs Wall-clock millisecond when the entry was captured.
 * @param isPinned    Pinned entries are excluded from TTL-based expiry.
 * @param isSensitive Captured from a field where persist was disallowed (e.g. password manager paste).
 * @param entityType  Detected entity class, or [EntityType.NONE] if plain text.
 */
internal data class ClipEntry(
    val id: String,
    val text: String,
    val timestampMs: Long,
    val isPinned: Boolean = false,
    val isSensitive: Boolean = false,
    val entityType: EntityType = EntityType.NONE,
)

/**
 * Broad entity categories identified by [EntityExtractor].
 *
 * Used to render the chip icon and label in the clipboard panel, not for routing.
 */
internal enum class EntityType {
    NONE,
    URL,
    EMAIL,
    PHONE,
    ADDRESS,
}
