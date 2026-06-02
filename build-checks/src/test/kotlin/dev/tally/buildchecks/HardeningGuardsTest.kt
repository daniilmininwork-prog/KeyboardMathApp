package dev.tally.buildchecks

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Verifies the pure predicate functions backing the T3.3 hardening guards:
 * wrapperPinGuard, backupHardeningGuard, r8LogStripVerify, and dynamicVersionGuard.
 *
 * Each function is exercised against both a passing and a failing representative input
 * so the Gradle task's failure path is proven without spinning up a Gradle daemon.
 */
class HardeningGuardsTest {

    // ── wrapperPinGuard ───────────────────────────────────────────────────────

    @Test
    fun `valid wrapper pin is accepted`() {
        val props = """
            distributionBase=GRADLE_USER_HOME
            distributionUrl=https\://services.gradle.org/distributions/gradle-8.11.1-bin.zip
            distributionSha256Sum=f397b287023acdba1e9f6fc5ea72d22dd63669d59ed8a0b6f7759a74d869b082
        """.trimIndent()
        assertTrue(hasValidWrapperPin(props))
    }

    @Test
    fun `missing distributionSha256Sum fails`() {
        val props = """
            distributionBase=GRADLE_USER_HOME
            distributionUrl=https\://services.gradle.org/distributions/gradle-8.11.1-bin.zip
        """.trimIndent()
        assertFalse(hasValidWrapperPin(props))
    }

    @Test
    fun `truncated hash fails`() {
        // A 63-character hash (one char short) must be rejected — it is likely a copy-paste error.
        val props = "distributionSha256Sum=f397b287023acdba1e9f6fc5ea72d22dd63669d59ed8a0b6f7759a74d869b08"
        assertFalse(hasValidWrapperPin(props))
    }

    @Test
    fun `non-hex character in hash fails`() {
        // A hash containing 'g' is not a valid SHA-256 hex string.
        val props = "distributionSha256Sum=g397b287023acdba1e9f6fc5ea72d22dd63669d59ed8a0b6f7759a74d869b082"
        assertFalse(hasValidWrapperPin(props))
    }

    @Test
    fun `empty hash fails`() {
        assertFalse(hasValidWrapperPin("distributionSha256Sum="))
    }

    // ── backupHardeningGuard ──────────────────────────────────────────────────

    @Test
    fun `manifest with allowBackup false passes`() {
        val manifest = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application
                    android:allowBackup="false"
                    android:dataExtractionRules="@xml/data_extraction_rules"
                    android:fullBackupContent="@xml/backup_rules" />
            </manifest>
        """.trimIndent()
        assertTrue(manifestHasAllowBackupFalse(manifest))
        assertTrue(manifestHasDataExtractionRules(manifest))
    }

    @Test
    fun `manifest without allowBackup attribute fails`() {
        val manifest = """
            <manifest xmlns:android="http://schemas.android.com/apk/res/android">
                <application android:label="Tally" />
            </manifest>
        """.trimIndent()
        assertFalse(manifestHasAllowBackupFalse(manifest))
    }

    @Test
    fun `manifest with allowBackup true fails`() {
        // android:allowBackup="true" is the dangerous default; the guard must reject it.
        val manifest = """
            <manifest>
                <application android:allowBackup="true" />
            </manifest>
        """.trimIndent()
        assertFalse(manifestHasAllowBackupFalse(manifest))
    }

    @Test
    fun `manifest without dataExtractionRules fails`() {
        val manifest = """
            <manifest>
                <application android:allowBackup="false" android:fullBackupContent="@xml/bk" />
            </manifest>
        """.trimIndent()
        assertFalse(manifestHasDataExtractionRules(manifest))
    }

    // ── r8LogStripVerify ──────────────────────────────────────────────────────

    @Test
    fun `proguard rules with both assumenosideeffects blocks pass`() {
        val rules = """
            -assumenosideeffects class android.util.Log {
                public static int v(...);
                public static int d(...);
                public static int i(...);
            }
            -assumenosideeffects class kotlin.io.ConsoleKt {
                public static void println(...);
            }
        """.trimIndent()
        assertTrue(proguardStripsLog(rules))
        assertTrue(proguardStripsConsoleKt(rules))
    }

    @Test
    fun `missing Log assumenosideeffects is detected`() {
        val rules = "-assumenosideeffects class kotlin.io.ConsoleKt { public static void println(...); }"
        assertFalse(proguardStripsLog(rules))
        assertTrue(proguardStripsConsoleKt(rules))
    }

    @Test
    fun `missing ConsoleKt assumenosideeffects is detected`() {
        val rules = "-assumenosideeffects class android.util.Log { public static int v(...); }"
        assertTrue(proguardStripsLog(rules))
        assertFalse(proguardStripsConsoleKt(rules))
    }

    @Test
    fun `empty proguard rules file fails both checks`() {
        assertFalse(proguardStripsLog(""))
        assertFalse(proguardStripsConsoleKt(""))
    }

    // ── dynamicVersionGuard ───────────────────────────────────────────────────

    @Test
    fun `pinned version line does not trigger`() {
        assertFalse(isDynamicVersionLine("""implementation("com.example:lib:1.2.3")"""))
    }

    @Test
    fun `dynamic plus version is detected`() {
        assertTrue(isDynamicVersionLine("""implementation("com.example:lib:1.+")"""))
    }

    @Test
    fun `bare plus wildcard is detected`() {
        assertTrue(isDynamicVersionLine("""implementation("com.example:lib:+")"""))
    }

    @Test
    fun `range version notation is detected`() {
        assertTrue(isDynamicVersionLine("""implementation("com.example:lib:[1.0,2.0)")"""))
    }

    @Test
    fun `comment line with dynamic version is not flagged`() {
        // A developer comment explaining a rejected version should not trigger the guard.
        assertFalse(isDynamicVersionLine("""// was: implementation("com.example:lib:1.+")"""))
    }

    @Test
    fun `version catalog entry with pinned version does not trigger`() {
        assertFalse(isDynamicVersionLine("""kotlin = "2.1.21""""))
    }
}
