package dev.tally.overlay

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.view.accessibility.AccessibilityManager

object OverlayPermissionState {

    /**
     * Returns true if [TallyOverlayService] is currently listed as enabled by the system.
     *
     * The user enables/disables the service through System Settings → Accessibility.
     * The app cannot modify this state programmatically.
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val am = context.getSystemService(AccessibilityManager::class.java) ?: return false
        return am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { info ->
                val si = info.resolveInfo.serviceInfo
                si.packageName == context.packageName &&
                    si.name == TallyOverlayService::class.java.name
            }
    }
}
