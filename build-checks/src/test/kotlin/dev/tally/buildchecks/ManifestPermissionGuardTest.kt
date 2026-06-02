package dev.tally.buildchecks

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Verifies that [manifestContainsPermission] catches every forbidden permission.
 *
 * The manifestPermissionGuard Gradle task runs this check against each module's
 * AndroidManifest.xml; a failure here would mean the guard silently misses a violation.
 */
class ManifestPermissionGuardTest {

    private val cleanManifest = """
        <?xml version="1.0" encoding="utf-8"?>
        <manifest xmlns:android="http://schemas.android.com/apk/res/android">
            <application />
        </manifest>
    """.trimIndent()

    @Test
    fun `clean manifest passes all permission checks`() {
        val forbidden = listOf(
            "android.permission.INTERNET",
            "android.permission.READ_CONTACTS",
        )
        forbidden.forEach { perm ->
            assertFalse(
                manifestContainsPermission(cleanManifest, perm),
                "Clean manifest should not contain $perm"
            )
        }
    }

    @Test
    fun `RECORD_AUDIO is detectable by the guard function`() {
        // The guard function can detect RECORD_AUDIO, but the Gradle task no longer includes
        // it in FORBIDDEN_PERMISSIONS because the opt-in voice feature (T5.2) requires it.
        // This test confirms the detection function still works; the policy decision (allow/
        // forbid) is enforced separately in the Gradle task's FORBIDDEN_PERMISSIONS set.
        val manifest = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <uses-permission android:name="android.permission.RECORD_AUDIO" />
            </manifest>
        """.trimIndent()
        assertTrue(
            manifestContainsPermission(manifest, "android.permission.RECORD_AUDIO"),
            "Guard function must be able to detect RECORD_AUDIO when queried"
        )
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "android.permission.INTERNET",
        "android.permission.READ_CONTACTS",
        "android.permission.READ_CALL_LOG",
        "android.permission.READ_SMS",
        "android.permission.RECEIVE_SMS",
        "android.permission.CAMERA",
        "android.permission.ACCESS_FINE_LOCATION",
        "android.permission.ACCESS_COARSE_LOCATION",
        "android.permission.READ_EXTERNAL_STORAGE",
        "android.permission.WRITE_EXTERNAL_STORAGE",
    ])
    fun `each forbidden permission is detected when present`(permission: String) {
        val manifest = """
            <?xml version="1.0" encoding="utf-8"?>
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <uses-permission android:name="$permission" />
            </manifest>
        """.trimIndent()
        assertTrue(
            manifestContainsPermission(manifest, permission),
            "Guard must detect $permission"
        )
    }

    @Test
    fun `INTERNET permission caught even in inline style`() {
        // Some manifests use a single-line style; guard must still fire.
        val manifest = """<manifest><uses-permission android:name="android.permission.INTERNET"/></manifest>"""
        assertTrue(manifestContainsPermission(manifest, "android.permission.INTERNET"))
    }
}
