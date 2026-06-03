package dev.tally.design

import android.app.WallpaperManager
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper

/**
 * Owns the keyboard's active dynamic color scheme and propagates it to registered [KeyTheme]s.
 *
 * An IME lives as a [android.inputmethodservice.InputMethodService], not an Activity, so the
 * Material library's `DynamicColors.applyToActivityIfAvailable` is unavailable. Instead this
 * manager reads the system wallpaper palette directly (API 31+) and fans out the result to every
 * [KeyTheme] that has registered via [addKeyTheme].
 *
 * ## Lifecycle
 *
 * Call [attach] inside `onCreateInputView` (or equivalent). Call [detach] inside
 * `onDestroy`/`onFinishInputView` to release the wallpaper listener and avoid leaks.
 *
 * ## Preset selection
 *
 * Set [preset] to change the active palette source. Changes take effect immediately: all
 * registered [KeyTheme]s are refreshed synchronously so that views reading tokens on their next
 * `onDraw` see the new colors without a view recreate.
 *
 * ## Thread safety
 *
 * All public methods must be called on the main thread. The wallpaper callback ([onColorsChanged])
 * is delivered on the main thread by the OS, so no synchronization is required.
 */
class TallyThemeManager {

    private val keyThemes = mutableListOf<KeyTheme>()

    // The listener reference is kept so it can be unregistered in detach().
    private var colorsListener: Any? = null  // WallpaperManager.OnColorsChangedListener on API 31+

    /** Currently active [ThemePreset]. Defaults to [ThemePreset.Wallpaper]. */
    var preset: ThemePreset = ThemePreset.Wallpaper
        set(value) {
            field = value
            applyResolved(value)
        }

    /** The most recently resolved wallpaper scheme; cached so preset switches can reference it. */
    private var lastWallpaperScheme: DynamicColorScheme.Scheme? = null

    /** The scheme currently applied to all registered [KeyTheme]s. */
    private var pendingScheme: DynamicColorScheme.Scheme? = null

    /**
     * The full-palette override currently applied to all registered [KeyTheme]s, or null when the
     * active preset uses the dynamic-scheme / static-token path. A palette (base variant or
     * high-contrast) takes total priority over [pendingScheme] — see [KeyTheme.applyPalette].
     */
    private var pendingPalette: KeyPalette? = null

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    /**
     * Attaches to the system wallpaper manager on API 31+ and resolves the initial scheme.
     *
     * Safe to call multiple times — duplicate attach calls are no-ops.
     *
     * @param context any live [Context] (application or service).
     */
    @Suppress("NewApi")  // guarded by Build.VERSION.SDK_INT check
    fun attach(context: Context) {
        if (Build.VERSION.SDK_INT < 31) return
        if (colorsListener != null) return  // already attached

        val wm = context.getSystemService(WallpaperManager::class.java) ?: return

        val listener = WallpaperManager.OnColorsChangedListener { colors, _ ->
            lastWallpaperScheme = colors?.let {
                DynamicColorScheme.Scheme(
                    primarySeed   = it.primaryColor.toArgb(),
                    secondarySeed = it.secondaryColor?.toArgb() ?: it.primaryColor.toArgb(),
                    neutralSeed   = it.tertiaryColor?.toArgb()  ?: it.primaryColor.toArgb(),
                )
            }
            if (preset == ThemePreset.Wallpaper) {
                applyResolved(preset)
            }
        }
        colorsListener = listener
        // Deliver callbacks on the main thread; passing a main-thread Handler is required
        // because Handler(null) is not a valid argument on this API.
        wm.addOnColorsChangedListener(listener, Handler(Looper.getMainLooper()))

        // Resolve the current wallpaper palette immediately.
        lastWallpaperScheme = DynamicColorScheme.resolve(context)
        applyResolved(preset)
    }

    /** Unregisters the wallpaper listener. Call from the IME's destroy/detach path. */
    @Suppress("NewApi")  // guarded by Build.VERSION.SDK_INT check
    fun detach(context: Context) {
        if (Build.VERSION.SDK_INT < 31) return
        val listener = colorsListener as? WallpaperManager.OnColorsChangedListener ?: return
        val wm = context.getSystemService(WallpaperManager::class.java) ?: return
        wm.removeOnColorsChangedListener(listener)
        colorsListener = null
    }

    // ── KeyTheme registry ─────────────────────────────────────────────────────

    /**
     * Registers a [keyTheme] and immediately applies the current scheme.
     *
     * Call once per view that holds a [KeyTheme] — typically inside the view's constructor or
     * inside `onCreateInputView` right after constructing the view.
     */
    fun addKeyTheme(keyTheme: KeyTheme) {
        if (keyTheme !in keyThemes) {
            keyThemes += keyTheme
        }
        // Apply the full current state (palette wins over scheme) so a late-registered view matches
        // the others immediately, including any active high-contrast border.
        keyTheme.applyPalette(pendingPalette)
        keyTheme.applyDynamicColors(pendingScheme)
    }

    /** Removes a [KeyTheme] from the registry (e.g. when the view is destroyed). */
    fun removeKeyTheme(keyTheme: KeyTheme) {
        keyThemes -= keyTheme
    }

    /**
     * Applies a scheme derived from a custom image to all registered [KeyTheme]s.
     *
     * Switches the active [preset] to a transient custom state — subsequent wallpaper changes
     * will NOT override this until [preset] is set back to [ThemePreset.Wallpaper].
     *
     * Callers are responsible for extracting the scheme via [ThemeImageExtractor.extract] and
     * passing null when the extraction yields no chromatic content (the keyboard reverts to static
     * resource tokens in that case).
     *
     * @param scheme the scheme derived from the custom image, or null to revert to static tokens.
     */
    fun applyCustomImageScheme(scheme: DynamicColorScheme.Scheme?) {
        // A custom image takes priority over the wallpaper preset but the ThemePreset enum does
        // not have a "Custom" entry — instead we update pendingScheme directly and leave preset
        // unchanged. The next preset assignment will overwrite this, which is the right behavior
        // (user explicitly chooses a preset → their image choice is superseded). A custom image is
        // a dynamic accent, so it also clears any active full-palette override.
        pendingPalette = null
        pendingScheme = scheme
        broadcast()
    }

    // ── Private ───────────────────────────────────────────────────────────────

    /**
     * Resolves [preset] into the pending palette + scheme and fans the result out. A preset
     * resolves to EITHER a full [KeyPalette] (base variants, high-contrast) or a
     * [DynamicColorScheme.Scheme] (wallpaper, builtin) — never both — so exactly one of the two
     * pending fields is non-null after this call. [ThemePreset.Static] resolves to neither, which
     * means "static day/night resource tokens".
     */
    private fun applyResolved(preset: ThemePreset) {
        when (preset) {
            ThemePreset.Wallpaper -> {
                pendingPalette = null
                pendingScheme  = lastWallpaperScheme
            }
            ThemePreset.Static -> {
                pendingPalette = null
                pendingScheme  = null
            }
            is ThemePreset.BaseVariant -> {
                pendingPalette = preset.palette
                pendingScheme  = null
            }
            is ThemePreset.HighContrast -> {
                pendingPalette = preset.palette
                pendingScheme  = null
            }
            is ThemePreset.Builtin -> {
                pendingPalette = null
                pendingScheme  = DynamicColorScheme.Scheme(
                    primarySeed   = preset.seed,
                    secondarySeed = preset.seed,   // builtin presets use the same seed for all three
                    neutralSeed   = preset.seed,
                )
            }
        }
        broadcast()
    }

    // Pushes the current palette + scheme to every registered theme. Order matters only in that
    // both are always set so a theme never keeps stale state from a previous preset.
    private fun broadcast() {
        for (theme in keyThemes) {
            theme.applyPalette(pendingPalette)
            theme.applyDynamicColors(pendingScheme)
        }
    }
}
