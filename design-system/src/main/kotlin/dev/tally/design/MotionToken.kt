package dev.tally.design

import android.content.Context

/**
 * Design-system motion duration tokens.
 *
 * Reads durations from integer resources so they live alongside all other tokens in `values/`
 * and can be overridden per-product-flavour or in tests without recompiling the chip.
 *
 * All durations are in milliseconds.
 *
 * IME and overlay both use this object so the chip and strip animate identically regardless
 * of which module hosts them (per the interaction spec, §Motion).
 */
object MotionToken {

    /**
     * Duration of the chip/strip appear animation: fade + translate + scale-in.
     * Default: 140 ms.
     */
    fun appearMs(context: Context): Long =
        context.resources.getInteger(R.integer.ds_motion_appear_ms).toLong()

    /**
     * Duration of each leg of the value cross-fade (update while visible).
     * Default: 80 ms per fade leg.
     */
    fun fadeMs(context: Context): Long =
        context.resources.getInteger(R.integer.ds_motion_fade_ms).toLong()

    /**
     * Duration of the chip/strip dismiss animation: fade out.
     * Default: 100 ms.
     */
    fun dismissMs(context: Context): Long =
        context.resources.getInteger(R.integer.ds_motion_dismiss_ms).toLong()
}
