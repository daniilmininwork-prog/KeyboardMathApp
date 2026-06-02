package dev.tally.design

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Build
import android.util.AttributeSet
import android.util.TypedValue
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo
import android.view.animation.AccelerateInterpolator
import android.view.animation.DecelerateInterpolator

/**
 * Shared chip View used in both the IME suggestion strip and the overlay.
 *
 * Shows a tappable rounded-rect pill with the math result. Handles:
 *   - Appear animation: fade + translate + scale-in (~140 ms)
 *   - Update animation: cross-fade to new value (~80 ms per leg)
 *   - Dismiss animation: fade out (~100 ms)
 *   - Reduce-motion: instant show/hide when system animations are off
 *   - Haptic feedback on tap (honours [hapticEnabled])
 *   - Accessibility: button role + "Insert result" action label
 *
 * Visual geometry is controlled by [MathResultChipConfig]. Colours are resolved from
 * [KeyTheme] on every draw so a runtime light↔dark switch takes effect on the next
 * [onDraw] call — no view recreate required (03 §7.1). Supply explicit ints in
 * [MathResultChipConfig] to override the theme tokens (e.g. host-module tinting).
 */
class MathResultChip @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    private val config: MathResultChipConfig = MathResultChipConfig.fromResources(context),
) : View(context, attrs) {

    /** Invoked when the user taps the chip. */
    var onTap: (() -> Unit)? = null

    /** Set to false to suppress haptic feedback (e.g. per user preference). */
    var hapticEnabled: Boolean = true

    private var displayText: String? = null

    // Paints are pre-allocated to avoid in-draw allocation. Colors are refreshed from the
    // theme tokens at the start of each onDraw so they track configuration changes.
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textAlign = Paint.Align.CENTER
        textSize = spToPx(config.textSizeSp)
    }

    // Resolved once at construction; these are independent of night/day mode.
    private val cornerPx  = dpToPx(config.cornerRadiusDp)
    private val hPadPx    = dpToPx(config.horizontalPaddingDp)
    private val chipVizPx = dpToPx(config.visualHeightDp)
    private val chipRect  = RectF()

    // Token provider — resolves colors from the current Context configuration on each access.
    private val theme = KeyTheme(context)

    /**
     * Returns the [KeyTheme] used by this chip.
     *
     * Callers (typically the IME) may register this theme with [TallyThemeManager] so that
     * dynamic color changes are reflected without a view recreate.
     */
    fun keyTheme(): KeyTheme = theme

    init {
        isFocusable = true
        isClickable = false
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
        visibility = GONE

        setAccessibilityDelegate(object : AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = "android.widget.Button"
                info.addAction(
                    AccessibilityNodeInfo.AccessibilityAction(
                        AccessibilityNodeInfo.ACTION_CLICK,
                        context.getString(R.string.ds_chip_action_insert),
                    )
                )
            }
        })
    }

    // ── Public API ────────────────────────────────────────────────────────────

    fun show(text: String) {
        val wasShowing = visibility == VISIBLE
        animate().cancel()

        val appearMs = MotionToken.appearMs(context)
        val fadeMs   = MotionToken.fadeMs(context)

        if (reducedMotion) {
            displayText = text
            updateDescription(text)
            isClickable = true
            alpha = 1f; translationY = 0f; scaleX = 1f; scaleY = 1f
            visibility = VISIBLE
            invalidate()
            return
        }

        if (wasShowing) {
            animate().alpha(0f).setDuration(fadeMs).withEndAction {
                displayText = text
                updateDescription(text)
                invalidate()
                animate().alpha(1f).setDuration(fadeMs).start()
            }.start()
        } else {
            displayText = text
            updateDescription(text)
            isClickable = true
            translationY = dpToPx(APPEAR_TRANSLATE_DP)
            scaleX = APPEAR_SCALE; scaleY = APPEAR_SCALE; alpha = 0f
            visibility = VISIBLE
            invalidate()
            animate()
                .alpha(1f).translationY(0f).scaleX(1f).scaleY(1f)
                .setDuration(appearMs)
                .setInterpolator(DecelerateInterpolator())
                .start()
        }
    }

    fun dismiss() {
        animate().cancel()

        val dismissMs = MotionToken.dismissMs(context)

        if (reducedMotion) {
            displayText = null
            contentDescription = null
            isClickable = false
            visibility = GONE
            alpha = 1f
            return
        }

        animate().alpha(0f).setDuration(dismissMs)
            .setInterpolator(AccelerateInterpolator())
            .withEndAction {
                displayText = null
                contentDescription = null
                isClickable = false
                visibility = GONE
                alpha = 1f
            }.start()
    }

    // ── Drawing ───────────────────────────────────────────────────────────────

    override fun onDraw(canvas: Canvas) {
        val s = displayText ?: return

        // Resolve colors on every draw so light↔dark switches take effect immediately.
        // config overrides take priority; fall back to theme tokens.
        bgPaint.color   = if (config.bgColorOverride   != 0) config.bgColorOverride   else theme.chipBg
        textPaint.color = if (config.textColorOverride != 0) config.textColorOverride else theme.chipText

        val textW = textPaint.measureText(s)
        val chipW = textW + hPadPx * 2f
        val cx = width / 2f
        val cy = height / 2f

        chipRect.set(
            cx - chipW / 2f,
            cy - chipVizPx / 2f,
            cx + chipW / 2f,
            cy + chipVizPx / 2f,
        )
        canvas.drawRoundRect(chipRect, cornerPx, cornerPx, bgPaint)

        val textY = cy - (textPaint.ascent() + textPaint.descent()) / 2f
        canvas.drawText(s, cx, textY, textPaint)
    }

    // ── Touch ─────────────────────────────────────────────────────────────────

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (event.action == MotionEvent.ACTION_UP && displayText != null) {
            performClick()
            return true
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean {
        super.performClick()
        if (displayText != null) {
            if (hapticEnabled) triggerHaptic()
            onTap?.invoke()
        }
        return true
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private val reducedMotion: Boolean get() = !ValueAnimator.areAnimatorsEnabled()

    private fun updateDescription(text: String) {
        contentDescription = context.getString(R.string.ds_chip_description, text)
    }

    @Suppress("NewApi")
    private fun triggerHaptic() {
        val constant = when {
            Build.VERSION.SDK_INT >= 30 -> HapticFeedbackConstants.CONFIRM
            Build.VERSION.SDK_INT >= 27 -> HapticFeedbackConstants.KEYBOARD_PRESS
            else                        -> HapticFeedbackConstants.VIRTUAL_KEY
        }
        performHapticFeedback(constant)
    }

    private fun dpToPx(dp: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp, resources.displayMetrics)

    private fun spToPx(sp: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, sp, resources.displayMetrics)

    private companion object {
        // Timing durations are read from resources via MotionToken so they share a single
        // source of truth with the strip and overlay (06-interaction-spec.md §Motion).
        const val APPEAR_TRANSLATE_DP = 8f
        const val APPEAR_SCALE        = 0.92f
    }
}

/**
 * Visual configuration for [MathResultChip].
 *
 * Color overrides are optional. When [bgColorOverride] / [textColorOverride] are left at
 * the default (0), [MathResultChip] resolves the color from [KeyTheme] on every draw —
 * this is the recommended default so the chip tracks light/dark switches automatically.
 *
 * Pass non-zero values only when the host module needs to tint the chip to a specific
 * color that differs from the design-system token (e.g. a branded overlay surface).
 *
 * Dimensions fall back to the design-system defaults so only colors need overriding.
 */
data class MathResultChipConfig(
    /** Override for the chip background color. 0 = use [KeyTheme.chipBg]. */
    val bgColorOverride: Int    = 0,
    /** Override for the chip text color. 0 = use [KeyTheme.chipText]. */
    val textColorOverride: Int  = 0,
    val cornerRadiusDp: Float   = 16f,
    val horizontalPaddingDp: Float = 12f,
    val visualHeightDp: Float   = 32f,
    val textSizeSp: Float       = 14f,
) {
    companion object {
        /** Default config — colors resolved from [KeyTheme] at draw time. */
        fun fromResources(@Suppress("UNUSED_PARAMETER") context: Context) = MathResultChipConfig()
    }
}
