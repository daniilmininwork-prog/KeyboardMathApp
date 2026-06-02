package dev.tally.ime

import android.content.Context
import android.content.SharedPreferences
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import dev.tally.keyboard.engine.FieldPolicy
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Local clipboard history store, encrypted at rest with a Keystore-backed AES-256-GCM key.
 *
 * ## Security contract
 *
 * - Entries are serialised to a private SharedPreferences file. Each value is AES-256-GCM
 *   encrypted with a key held in the Android Keystore — it never leaves secure hardware
 *   (or an OS-emulated enclave) and cannot be exported or backed up.
 * - The ciphertext is Base64-encoded before storage so the prefs file remains a plain XML blob.
 * - [record] is a no-op when [FieldPolicy.persistAllowed] is false, so no clipboard content
 *   from password or incognito fields ever touches this store.
 * - Entries older than [EXPIRY_MS] are removed on the next [getAll] or [record] call (lazy
 *   sweep). Pinned entries are exempt from TTL expiry.
 * - Sensitive entries ([ClipEntry.isSensitive]) are expired immediately during a sweep
 *   regardless of TTL, unless pinned by the user.
 *
 * ## Insertion contract
 *
 * This store has no [android.content.ClipboardManager] dependency. Text committed to the
 * field from this panel travels via [android.view.inputmethod.InputConnection.commitText]
 * only — the spec rule from R3-samsung and 04 §2.4 is enforced structurally by having no
 * ClipboardManager reference anywhere in this file or its callers.
 *
 * @param context      Application or service context; used for prefs + Keystore access.
 * @param encryption   Encryption strategy; defaults to the Keystore-backed implementation.
 *                     Overridable in tests to avoid Keystore dependency.
 * @param clockMs      Returns the current epoch millisecond. Overridable in tests.
 */
internal class ClipboardStore(
    private val context: Context,
    private val encryption: EncryptionProvider = KeystoreEncryptionProvider(),
    private val clockMs: () -> Long = { System.currentTimeMillis() },
) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    // ── Serialisation ─────────────────────────────────────────────────────────

    /**
     * Serialises a [ClipEntry] to a pipe-separated string.
     *
     * Format: id|timestampMs|isPinned|isSensitive|entityType|base64(text)
     * Text is Base64-encoded so pipe characters in the text don't corrupt the fixed fields.
     */
    private fun serialise(entry: ClipEntry): String {
        val encodedText = Base64.encodeToString(
            entry.text.toByteArray(Charsets.UTF_8), Base64.NO_WRAP
        )
        return "${entry.id}|${entry.timestampMs}|${entry.isPinned}|" +
            "${entry.isSensitive}|${entry.entityType}|$encodedText"
    }

    private fun deserialise(raw: String): ClipEntry? {
        val parts = raw.split("|", limit = 6)
        if (parts.size != 6) return null
        return try {
            val text = Base64.decode(parts[5], Base64.NO_WRAP).toString(Charsets.UTF_8)
            ClipEntry(
                id          = parts[0],
                timestampMs = parts[1].toLong(),
                isPinned    = parts[2].toBoolean(),
                isSensitive = parts[3].toBoolean(),
                entityType  = EntityType.valueOf(parts[4]),
                text        = text,
            )
        } catch (_: Exception) {
            null
        }
    }

    // ── Index helpers ─────────────────────────────────────────────────────────

    private fun entryKey(id: String) = "$ENTRY_PREFIX$id"

    private fun readIndex(): List<String> {
        val raw = prefs.getString(PREF_INDEX, null) ?: return emptyList()
        return raw.split("|").filter { it.isNotEmpty() }
    }

    private fun writeIndex(ids: List<String>) {
        prefs.edit().putString(PREF_INDEX, ids.joinToString("|")).apply()
    }

    private fun readEntry(id: String): ClipEntry? {
        val enc = prefs.getString(entryKey(id), null) ?: return null
        val dec = try { encryption.decrypt(enc) } catch (_: Exception) { return null }
            ?: return null
        return deserialise(dec)
    }

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Records a new clipboard entry if [policy] permits persistence.
     *
     * A no-op when [FieldPolicy.persistAllowed] is false so that content typed in password
     * or incognito fields is never retained.
     *
     * Duplicate text (exact match) is collapsed: the existing entry moves to the front of
     * the list rather than creating a duplicate.
     *
     * The history is capped at [MAX_ENTRIES] after every write; pinned entries do not count
     * against the limit.
     */
    fun record(text: String, policy: FieldPolicy) {
        if (!policy.persistAllowed) return
        if (text.isBlank()) return

        val now = clockMs()
        val entityType = EntityExtractor.classify(text)
        val ids = readIndex().toMutableList()
        val editor = prefs.edit()

        // Collapse duplicates.
        val existingId = ids.firstOrNull { id -> readEntry(id)?.text == text }
        if (existingId != null) {
            ids.remove(existingId)
            editor.remove(entryKey(existingId))
        }

        val entry = ClipEntry(
            id          = now.toString(),
            text        = text,
            timestampMs = now,
            isPinned    = false,
            isSensitive = false,
            entityType  = entityType,
        )
        editor.putString(entryKey(entry.id), encryption.encrypt(serialise(entry)))
        ids.add(0, entry.id)

        // Split pinned/unpinned for the cap calculation.
        val pinnedIds   = ids.filter { readEntry(it)?.isPinned == true }
        val unpinnedIds = ids.filter { readEntry(it)?.isPinned != true }
        val keptUnpinned = unpinnedIds.take(MAX_ENTRIES - pinnedIds.size)
        (unpinnedIds - keptUnpinned.toSet()).forEach { editor.remove(entryKey(it)) }

        writeIndex(pinnedIds + keptUnpinned)
        editor.apply()

        sweep()
    }

    /**
     * Returns all non-expired entries, newest first, after applying an expiry sweep.
     */
    fun getAll(): List<ClipEntry> {
        sweep()
        return readIndex().mapNotNull { readEntry(it) }
    }

    /**
     * Toggles the pinned state of the entry with [id].
     *
     * Pinned entries are excluded from TTL expiry.
     * Returns true if the entry was found and updated.
     */
    fun togglePin(id: String): Boolean {
        val entry = readEntry(id) ?: return false
        val updated = entry.copy(isPinned = !entry.isPinned)
        prefs.edit().putString(entryKey(id), encryption.encrypt(serialise(updated))).apply()
        return true
    }

    /**
     * Removes the entry with [id].
     */
    fun remove(id: String) {
        val ids = readIndex().toMutableList()
        if (ids.remove(id)) {
            prefs.edit().remove(entryKey(id)).putString(PREF_INDEX, ids.joinToString("|")).apply()
        }
    }

    /**
     * Removes all non-pinned entries. Pinned entries survive.
     */
    fun clearUnpinned() {
        val ids = readIndex()
        val editor = prefs.edit()
        val kept = mutableListOf<String>()
        ids.forEach { id ->
            if (readEntry(id)?.isPinned == true) {
                kept.add(id)
            } else {
                editor.remove(entryKey(id))
            }
        }
        writeIndex(kept)
        editor.apply()
    }

    // ── Expiry sweep ──────────────────────────────────────────────────────────

    /**
     * Removes entries that have exceeded their TTL or are sensitive.
     *
     * - Pinned entries are never swept.
     * - Sensitive entries ([ClipEntry.isSensitive]) expire immediately.
     * - All other entries expire after [EXPIRY_MS].
     */
    private fun sweep() {
        val now = clockMs()
        val ids = readIndex().toMutableList()
        val editor = prefs.edit()
        val toRemove = mutableListOf<String>()

        ids.forEach { id ->
            val entry = readEntry(id) ?: run { toRemove.add(id); return@forEach }
            if (entry.isPinned) return@forEach
            val expired = entry.isSensitive || (now - entry.timestampMs > EXPIRY_MS)
            if (expired) { editor.remove(entryKey(id)); toRemove.add(id) }
        }

        if (toRemove.isNotEmpty()) {
            ids.removeAll(toRemove.toSet())
            writeIndex(ids)
            editor.apply()
        }
    }

    // ── Encryption abstraction ────────────────────────────────────────────────

    /**
     * Strategy interface for encrypting/decrypting entry payloads.
     *
     * The production implementation uses the Android Keystore. Tests inject an identity
     * (no-op) implementation to avoid Keystore native-library requirements.
     */
    interface EncryptionProvider {
        fun encrypt(plaintext: String): String
        fun decrypt(ciphertext: String): String?
    }

    /**
     * AES-256-GCM encryption backed by the Android Keystore.
     *
     * The key is generated on first use and stored in the Keystore — it never leaves the
     * secure enclave and cannot be exported. The IV is prepended to the ciphertext bytes
     * before Base64 encoding so decrypt can recover it.
     */
    class KeystoreEncryptionProvider : EncryptionProvider {

        private val keyStore: KeyStore =
            KeyStore.getInstance(KEYSTORE_PROVIDER).also { it.load(null) }

        private fun getOrCreateKey(): SecretKey {
            (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)
                ?.let { return it.secretKey }
            val spec = KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .setUserAuthenticationRequired(false)
                .build()
            return KeyGenerator.getInstance(
                KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER
            ).also { it.init(spec) }.generateKey()
        }

        override fun encrypt(plaintext: String): String {
            val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
            cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
            val iv = cipher.iv
            val cipherBytes = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
            val combined = ByteArray(iv.size + cipherBytes.size)
            System.arraycopy(iv, 0, combined, 0, iv.size)
            System.arraycopy(cipherBytes, 0, combined, iv.size, cipherBytes.size)
            return Base64.encodeToString(combined, Base64.NO_WRAP)
        }

        override fun decrypt(ciphertext: String): String? {
            return try {
                val combined = Base64.decode(ciphertext, Base64.NO_WRAP)
                val iv = combined.copyOfRange(0, GCM_IV_LENGTH)
                val bytes = combined.copyOfRange(GCM_IV_LENGTH, combined.size)
                val cipher = Cipher.getInstance(CIPHER_TRANSFORMATION)
                cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
                cipher.doFinal(bytes).toString(Charsets.UTF_8)
            } catch (_: Exception) {
                null
            }
        }

        private companion object {
            const val KEYSTORE_PROVIDER    = "AndroidKeyStore"
            const val KEY_ALIAS            = "tally_clipboard_key"
            const val CIPHER_TRANSFORMATION = "AES/GCM/NoPadding"
            const val GCM_IV_LENGTH        = 12
            const val GCM_TAG_BITS         = 128
        }
    }

    /**
     * No-op encryption for unit tests: stores plaintext directly without AES so tests
     * don't require the Android Keystore native implementation.
     */
    internal class NoOpEncryption : EncryptionProvider {
        override fun encrypt(plaintext: String): String = plaintext
        override fun decrypt(ciphertext: String): String = ciphertext
    }

    internal companion object {
        const val MAX_ENTRIES = 20
        /** Non-pinned entries expire after 24 hours. */
        const val EXPIRY_MS = 24L * 60 * 60 * 1000

        const val PREFS_NAME   = "tally_clipboard"
        const val PREF_INDEX   = "index"
        const val ENTRY_PREFIX = "e_"
    }
}
