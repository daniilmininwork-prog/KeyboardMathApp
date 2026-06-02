package dev.tally.ime

import android.content.Context
import android.util.Log
import dev.tally.layouts.LayoutParseException
import dev.tally.layouts.LayoutParser
import java.io.IOException

/**
 * Loads keyboard layer definitions from bundled JSON assets and converts them to the
 * [KeyRow] / [Key] view model used by [KeyPlaneView].
 *
 * Assets live under `layouts/src/main/assets/layouts/` and are accessed at runtime via
 * [Context.assets]. Parsed results are cached so repeated layer switches do not re-read
 * the asset or re-parse the JSON.
 *
 * The number row asset is a single-row layout that is prepended to the alphabetic layers
 * when the toggle is on; it is not a complete keyboard layout on its own.
 *
 * **Thread safety:** all methods must be called on the main thread. The cache is a plain
 * [HashMap] with no synchronisation — this is safe as long as callers do not access the
 * loader from background threads.
 *
 * **Error handling:** load failures are NOT permanently cached. If an asset fails to load
 * (e.g. transient corruption on first access), the next call to the same asset will retry.
 * On failure, a hardcoded fallback row list from [KeyboardLayout] is returned so the keyboard
 * remains usable rather than rendering a blank layer.
 */
internal class LayoutLoader(context: Context) {

    private val assets = context.applicationContext.assets

    /**
     * Cache for successfully-loaded layout rows.
     *
     * Only successful results are stored here. Failed loads are NOT cached so a retry
     * on the next layer switch is possible (guards against transient first-access failures).
     */
    private val successCache = HashMap<String, List<KeyRow>>()

    /**
     * Tracks which asset paths have already logged a load failure.
     *
     * Without this guard the same error would be logged on every layer switch (every time
     * the user taps 123 or ABC), flooding logcat with identical messages. The error is logged
     * once; subsequent failures for the same path silently return the fallback.
     */
    private val failureLogged = HashSet<String>()

    /**
     * Returns the rows for the numeric layer.
     *
     * Loaded from `layouts/numeric.json` on first access; subsequent calls return the
     * cached result. Falls back to [KeyboardLayout.NUMERIC] on asset failure.
     */
    // Must be called on the main thread; see class KDoc for thread-safety contract.
    fun numericRows(): List<KeyRow> =
        load("layouts/numeric.json", fallback = KeyboardLayout.NUMERIC)

    /**
     * Returns the rows for the symbols layer.
     *
     * Loaded from `layouts/symbols.json` on first access; subsequent calls return the
     * cached result. Falls back to [KeyboardLayout.SYMBOLS] on asset failure.
     */
    // Must be called on the main thread; see class KDoc for thread-safety contract.
    fun symbolRows(): List<KeyRow> =
        load("layouts/symbols.json", fallback = KeyboardLayout.SYMBOLS)

    /**
     * Returns the single number row (digits 1–9, 0) that is prepended to the alpha layers
     * when the number row toggle is enabled.
     *
     * Loaded from `layouts/number_row.json` on first access. Falls back to an empty list
     * (no number row visible) on asset failure.
     */
    // Must be called on the main thread; see class KDoc for thread-safety contract.
    fun numberRow(): List<KeyRow> =
        load("layouts/number_row.json", fallback = emptyList())

    /**
     * Returns the alpha (QWERTY/AZERTY/QWERTZ) rows for the given asset path.
     *
     * Loaded and cached on first access for each distinct [assetPath]. Falls back to
     * [KeyboardLayout.ALPHA_LOWER] on asset failure so the keyboard stays usable.
     *
     * @param assetPath Asset-relative path, e.g. "layouts/fr_FR_AZERTY.json".
     */
    // Must be called on the main thread; see class KDoc for thread-safety contract.
    fun alphaRows(assetPath: String): List<KeyRow> =
        load(assetPath, fallback = KeyboardLayout.ALPHA_LOWER)

    // ── Internal ──────────────────────────────────────────────────────────────

    /**
     * Loads [assetPath] from the asset bundle, caching successes.
     *
     * Failures are NOT cached — they return [fallback] so the keyboard renders with a
     * reasonable default, and the next call will retry the asset load. This prevents
     * a transient first-access failure from permanently blanking a keyboard layer.
     */
    // Must be called on the main thread; see class KDoc for thread-safety contract.
    private fun load(assetPath: String, fallback: List<KeyRow>): List<KeyRow> {
        successCache[assetPath]?.let { return it }
        return try {
            val json = assets.open(assetPath).bufferedReader().readText()
            val def  = LayoutParser.parse(json)
            val rows = LayoutConverter.toKeyRows(def)
            successCache[assetPath] = rows
            rows
        } catch (e: IOException) {
            if (failureLogged.add(assetPath)) {
                // Log only on the first failure for this path to avoid logcat flooding on
                // repeated layer switches (e.g. every tap of the 123/ABC toggle).
                Log.e(TAG, "Failed to open layout asset '$assetPath': ${e.message}; " +
                    "falling back to hardcoded layout for this layer (further failures suppressed)", e)
            }
            fallback
        } catch (e: LayoutParseException) {
            if (failureLogged.add(assetPath)) {
                Log.e(TAG, "Failed to parse layout asset '$assetPath': ${e.message}; " +
                    "falling back to hardcoded layout for this layer (further failures suppressed)", e)
            }
            fallback
        }
    }

    private companion object {
        const val TAG = "LayoutLoader"
    }
}
