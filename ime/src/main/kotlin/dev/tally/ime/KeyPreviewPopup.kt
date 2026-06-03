package dev.tally.ime

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.ColorDrawable
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.widget.PopupWindow
import android.widget.TextView
import dev.tally.keyboard.engine.ResolvedKey

/**
 * Magnified key-preview bubble shown above the finger on key-down.
 *
 * The preview is a [PopupWindow] inflated from [R.layout.key_preview] containing a single
 * [TextView] with the key label. One instance is kept per [KeyPlaneView] finger slot; the
 * same window is reused across successive key presses via [show]/[dismiss] to avoid the
 * cost of creating and discarding a window per keystroke.
 *
 * Placement: the bubble is positioned so it is horizontally centred over the pressed key and
 * its bottom edge sits [R.dimen.key_preview_vertical_offset] above the key's top edge.
 *
 * Caller responsibilities:
 *   - Call [show] from the UI thread on ACTION_DOWN.
 *   - Call [dismiss] from the UI thread on ACTION_UP, ACTION_POINTER_UP, and ACTION_CANCEL.
 *   - Pass `redacted = true` when [KeyPlaneView.previewMasked] is set so secure fields keep the
 *     tactile bubble without ever rendering the typed glyph (see [KeyPlaneView]).
 */
@SuppressLint("InflateParams")  // PopupWindow content has no parent; null root is correct.
internal class KeyPreviewPopup(private val context: Context) {

    private val popup: PopupWindow
    private val labelView: TextView

    init {
        val inflater = LayoutInflater.from(context)
        val contentView = inflater.inflate(R.layout.key_preview, null, false)
        labelView = contentView as TextView

        popup = PopupWindow(contentView, WRAP, WRAP, false).apply {
            // Transparent background lets the XML drawable (key_preview_bg) define the shape.
            setBackgroundDrawable(ColorDrawable(0))
            isOutsideTouchable = false
            isTouchable = false
            elevation = context.resources.displayMetrics.density * ELEVATION_DP
        }
    }

    /**
     * Shows the preview bubble centred above [key] relative to [anchor].
     *
     * [anchor] must be an attached view (typically [KeyPlaneView] itself) so the popup
     * window has a valid parent window token. Coordinates are in the anchor's local space.
     *
     * When [redacted] is true the bubble is shown with a neutral dot instead of the key glyph,
     * so password and no-suggestion fields keep the tactile press feedback without revealing the
     * typed character to shoulder surfers or screen-recorders.
     *
     * Does nothing when the popup window content cannot be measured (e.g., the view
     * is not yet laid out). The early return is defensive; in practice [anchor] is
     * always measured by the time the first DOWN arrives.
     */
    fun show(key: ResolvedKey, anchor: View, redacted: Boolean = false) {
        labelView.text = if (redacted) REDACTED_GLYPH else key.keyDef.label

        // Measure the popup content so we know its dimensions before placing it.
        labelView.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        val pw = labelView.measuredWidth.takeIf { it > 0 } ?: return
        val ph = labelView.measuredHeight.takeIf { it > 0 } ?: return

        val offset = context.resources.getDimensionPixelSize(R.dimen.key_preview_vertical_offset)

        // Centre the bubble horizontally over the key; place its bottom above the key top.
        val xOff = ((key.centerX - pw / 2f).toInt()).coerceAtLeast(0)
        val yOff = (key.top.toInt() - ph - offset)

        if (popup.isShowing) {
            popup.update(anchor, xOff, yOff, pw, ph)
        } else {
            popup.showAtLocation(anchor, Gravity.NO_GRAVITY, xOff, yOff)
        }
    }

    /** Hides the preview bubble immediately. Safe to call when already dismissed. */
    fun dismiss() {
        if (popup.isShowing) popup.dismiss()
    }

    /** Returns true while the popup is visible. Exposed for test assertions. */
    val isShowing: Boolean get() = popup.isShowing

    /** The text currently rendered in the bubble. Exposed for test assertions only. */
    internal val displayedText: CharSequence get() = labelView.text

    private companion object {
        const val WRAP = android.view.ViewGroup.LayoutParams.WRAP_CONTENT
        const val ELEVATION_DP = 4f

        // Neutral mark shown instead of the key glyph in redacted (masked-field) mode. A bullet
        // gives the bubble visible content for tactile feedback while leaking no character.
        const val REDACTED_GLYPH = "•"  // •
    }
}
