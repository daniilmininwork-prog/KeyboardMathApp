package dev.tally

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * Verifies the pre-release checklist from docs/09-build-release.md.
 *
 * Each test maps to one line on the checklist and fails with an actionable message
 * when the corresponding property is violated. The goal is to make CI the enforcer
 * of release readiness, so a clean test run is evidence the checklist is green.
 */
class ReleaseEngineeringTest {

    // Project root relative to where the test JVM runs (app module working dir).
    // Gradle runs unit tests from the module dir; walk up one level to reach the root.
    private val projectRoot: File
        get() {
            // Test JVM cwd is the module directory (app/). Walk up to find the root
            // by looking for the sentinel gradle-wrapper directory.
            var dir = File(System.getProperty("user.dir") ?: ".")
            repeat(4) {
                if (File(dir, "gradle/wrapper/gradle-wrapper.properties").exists()) return dir
                dir = dir.parentFile ?: dir
            }
            return dir
        }

    private fun file(relative: String): File = File(projectRoot, relative)

    // ── 1. Manifest is INTERNET-free ─────────────────────────────────────────

    @Test
    fun manifest_noInternetPermission() {
        // The play data safety form declares "no data collected"; the absent INTERNET
        // permission is the technical proof that backs that declaration.
        val manifests = projectRoot.walkTopDown()
            .filter { it.name == "AndroidManifest.xml" }
            .filter { !it.absolutePath.contains("/build/") }
            .toList()

        assertTrue("Expected at least one AndroidManifest.xml to exist", manifests.isNotEmpty())

        manifests.forEach { manifest ->
            val text = manifest.readText()
            assertFalse(
                "INTERNET permission found in ${manifest.absolutePath}. " +
                "Removing it is what makes the Play Data Safety 'no data collected' claim true.",
                text.contains("android.permission.INTERNET"),
            )
        }
    }

    @Test
    fun manifest_noNetworkingPermissions() {
        // Belt-and-suspenders: block any other permission that implies network access.
        val networkPermissions = listOf(
            "android.permission.CHANGE_NETWORK_STATE",
            "android.permission.ACCESS_NETWORK_STATE",
            "android.permission.ACCESS_WIFI_STATE",
            "android.permission.CHANGE_WIFI_STATE",
        )
        val manifests = projectRoot.walkTopDown()
            .filter { it.name == "AndroidManifest.xml" }
            .filter { !it.absolutePath.contains("/build/") }
            .toList()

        manifests.forEach { manifest ->
            val text = manifest.readText()
            networkPermissions.forEach { perm ->
                assertFalse(
                    "Network-adjacent permission '$perm' found in ${manifest.absolutePath}.",
                    text.contains(perm),
                )
            }
        }
    }

    // ── 2. R8 log-stripping rules ─────────────────────────────────────────────

    @Test
    fun proguard_hasLogStripRule() {
        // Release builds must strip all android.util.Log calls. If this rule is missing,
        // Log.v/d/i call sites survive into the release DEX.
        val proguard = file("app/proguard-rules.pro")
        assertTrue("app/proguard-rules.pro not found", proguard.exists())
        val text = proguard.readText()
        assertTrue(
            "Missing -assumenosideeffects for android.util.Log in proguard-rules.pro. " +
            "Without this, debug log calls are retained in the release DEX.",
            text.contains("-assumenosideeffects class android.util.Log"),
        )
    }

    @Test
    fun proguard_hasPrintlnStripRule() {
        val proguard = file("app/proguard-rules.pro")
        assertTrue("app/proguard-rules.pro not found", proguard.exists())
        val text = proguard.readText()
        assertTrue(
            "Missing -assumenosideeffects for kotlin.io.ConsoleKt in proguard-rules.pro. " +
            "Without this, println() calls survive into the release DEX.",
            text.contains("-assumenosideeffects class kotlin.io.ConsoleKt"),
        )
    }

    @Test
    fun proguard_hasInputMethodServiceKeepRule() {
        // The platform binds TallyInputMethodService by name. R8 must not rename or remove it.
        val proguard = file("app/proguard-rules.pro")
        assertTrue("app/proguard-rules.pro not found", proguard.exists())
        val text = proguard.readText()
        assertTrue(
            "Missing -keep rule for TallyInputMethodService. " +
            "R8 would rename the class, breaking the platform binding.",
            text.contains("TallyInputMethodService"),
        )
    }

    // ── 3. Backup hardening ───────────────────────────────────────────────────

    @Test
    fun manifest_allowBackupFalse() {
        // Keyboard learned state must never go to Google cloud. The declaration here is
        // the policy; backup_rules.xml and data_extraction_rules.xml are the enforcement.
        val manifest = file("app/src/main/AndroidManifest.xml")
        assertTrue("app/src/main/AndroidManifest.xml not found", manifest.exists())
        val text = manifest.readText()
        assertTrue(
            "android:allowBackup=\"false\" missing from app manifest. " +
            "Without this, Android will back up keyboard state to Google cloud.",
            text.contains("""android:allowBackup="false""""),
        )
    }

    @Test
    fun manifest_dataExtractionRulesPresent() {
        val manifest = file("app/src/main/AndroidManifest.xml")
        assertTrue("app/src/main/AndroidManifest.xml not found", manifest.exists())
        val text = manifest.readText()
        assertTrue(
            "android:dataExtractionRules missing — Android 12+ backup exclusions not set.",
            text.contains("android:dataExtractionRules"),
        )
    }

    @Test
    fun manifest_fullBackupContentPresent() {
        val manifest = file("app/src/main/AndroidManifest.xml")
        assertTrue("app/src/main/AndroidManifest.xml not found", manifest.exists())
        val text = manifest.readText()
        assertTrue(
            "android:fullBackupContent missing — pre-12 backup exclusions not set.",
            text.contains("android:fullBackupContent"),
        )
    }

    @Test
    fun backupRules_excludeAllDomains() {
        val rules = file("app/src/main/res/xml/backup_rules.xml")
        assertTrue("app/src/main/res/xml/backup_rules.xml not found", rules.exists())
        val text = rules.readText()
        listOf("sharedpref", "file", "database", "external").forEach { domain ->
            assertTrue(
                "backup_rules.xml does not exclude domain '$domain'. " +
                "Keyboard preferences for that domain can be cloud-backed.",
                text.contains(domain),
            )
        }
    }

    @Test
    fun dataExtractionRules_excludeCloudBackupDomains() {
        val rules = file("app/src/main/res/xml/data_extraction_rules.xml")
        assertTrue("app/src/main/res/xml/data_extraction_rules.xml not found", rules.exists())
        val text = rules.readText()
        assertTrue(
            "data_extraction_rules.xml missing <cloud-backup> section.",
            text.contains("cloud-backup"),
        )
        listOf("sharedpref", "file", "database", "external").forEach { domain ->
            assertTrue(
                "data_extraction_rules.xml does not exclude domain '$domain' from cloud backup.",
                text.contains(domain),
            )
        }
    }

    // ── 4. Signing config reads from environment, not source ──────────────────

    @Test
    fun signingConfig_readsFromEnvironment() {
        // The signing config must reference System.getenv() variables; the keystore file
        // itself must never appear in source control.
        val buildScript = file("app/build.gradle.kts")
        assertTrue("app/build.gradle.kts not found", buildScript.exists())
        val text = buildScript.readText()
        assertTrue(
            "app/build.gradle.kts does not reference System.getenv for the keystore path. " +
            "The keystore must live outside the repo, sourced via TALLY_KEYSTORE_PATH.",
            text.contains("System.getenv(\"TALLY_KEYSTORE_PATH\""),
        )
        assertTrue(
            "app/build.gradle.kts does not reference TALLY_KEY_ALIAS environment variable.",
            text.contains("TALLY_KEY_ALIAS"),
        )
    }

    @Test
    fun signingConfig_noKeystoreFileInRepo() {
        // No .jks or .keystore file may exist in source (the .gitignore entry is advisory;
        // this test actively enforces the absence).
        val keystoreFiles = projectRoot.walkTopDown()
            .filter { it.isFile }
            .filter { it.extension == "jks" || it.extension == "keystore" || it.extension == "p12" }
            .filter { !it.absolutePath.contains("/build/") }
            .toList()

        if (keystoreFiles.isNotEmpty()) {
            fail(
                "Keystore file(s) found in the repository tree — these must never be committed:\n" +
                keystoreFiles.joinToString("\n") { "  ${it.absolutePath}" },
            )
        }
    }

    // ── 5. CHANGELOG has a version entry ─────────────────────────────────────

    @Test
    fun changelog_exists() {
        val changelog = file("CHANGELOG.md")
        assertTrue("CHANGELOG.md not found at project root.", changelog.exists())
    }

    @Test
    fun changelog_hasVersionEntry() {
        // Keep a Changelog format: version entries look like "## [X.Y.Z] — YYYY-MM-DD".
        val changelog = file("CHANGELOG.md")
        assertTrue("CHANGELOG.md not found", changelog.exists())
        val text = changelog.readText()
        val versionEntryRegex = Regex("""## \[\d+\.\d+\.\d+\]""")
        assertTrue(
            "CHANGELOG.md has no released version entry (pattern '## [X.Y.Z]'). " +
            "A release without a changelog entry is not shippable.",
            versionEntryRegex.containsMatchIn(text),
        )
    }

    @Test
    fun changelog_hasUnreleasedSection() {
        val changelog = file("CHANGELOG.md")
        assertTrue("CHANGELOG.md not found", changelog.exists())
        val text = changelog.readText()
        assertTrue(
            "CHANGELOG.md missing '## [Unreleased]' section.",
            text.contains("## [Unreleased]"),
        )
    }

    @Test
    fun changelog_versionMatchesBuildScript() {
        // The versionName in build.gradle.kts must correspond to the latest changelog entry
        // so the two sources of truth stay in sync.
        val changelog = file("CHANGELOG.md")
        val buildScript = file("app/build.gradle.kts")
        assertTrue("CHANGELOG.md not found", changelog.exists())
        assertTrue("app/build.gradle.kts not found", buildScript.exists())

        val versionMatch = Regex("""versionName\s*=\s*"([^"]+)"""")
            .find(buildScript.readText())
        if (versionMatch == null) {
            fail("versionName not found in app/build.gradle.kts")
            return
        }
        val buildVersion = versionMatch.groupValues[1]

        assertTrue(
            "CHANGELOG.md does not contain an entry for versionName '$buildVersion' from build.gradle.kts. " +
            "Update CHANGELOG.md or align the version before releasing.",
            changelog.readText().contains("[$buildVersion]"),
        )
    }

    // ── 6. Reproducible-build configuration ──────────────────────────────────

    @Test
    fun buildScript_reproducibleArchivesConfigured() {
        // The root build script must disable file timestamps and enable reproducible ordering
        // on all archive tasks so two clean builds from the same source produce identical output.
        val root = file("build.gradle.kts")
        assertTrue("Root build.gradle.kts not found", root.exists())
        val text = root.readText()
        assertTrue(
            "isPreserveFileTimestamps = false missing from root build.gradle.kts. " +
            "Archive timestamps break reproducibility.",
            text.contains("isPreserveFileTimestamps = false"),
        )
        assertTrue(
            "isReproducibleFileOrder = true missing from root build.gradle.kts. " +
            "Non-deterministic ZIP entry order breaks reproducibility.",
            text.contains("isReproducibleFileOrder = true"),
        )
    }

    // ── 7. Gradle wrapper is pinned ───────────────────────────────────────────

    @Test
    fun gradleWrapper_hasSha256Pin() {
        val wrapperProps = file("gradle/wrapper/gradle-wrapper.properties")
        assertTrue("gradle/wrapper/gradle-wrapper.properties not found", wrapperProps.exists())
        val content = wrapperProps.readText()
        val hashLine = content.lines().firstOrNull { it.trimStart().startsWith("distributionSha256Sum") }
        assertFalse(
            "distributionSha256Sum not set in gradle-wrapper.properties. " +
            "An unpinned wrapper can be silently swapped for a malicious one.",
            hashLine == null,
        )
        val hash = hashLine!!.substringAfter('=').trim()
        assertTrue(
            "distributionSha256Sum value '$hash' is not a valid 64-character hex SHA-256.",
            hash.length == 64 && hash.all { it.isLetterOrDigit() },
        )
    }

    // ── 8. Dependency license registry is complete ────────────────────────────

    @Test
    fun dependencyLicenses_fileExists() {
        val licenseFile = file("gradle/dependency-licenses.toml")
        assertTrue(
            "gradle/dependency-licenses.toml not found. " +
            "Every dependency must be license-vetted before it lands.",
            licenseFile.exists(),
        )
    }

    @Test
    fun dependencyLicenses_noGplEntries() {
        val licenseFile = file("gradle/dependency-licenses.toml")
        if (!licenseFile.exists()) return  // caught by fileExists test
        val text = licenseFile.readText()
        val gplPattern = Regex("""(?i)\b(GPL|LGPL|AGPL|EUPL|CDDL)\b""")
        val violations = text.lines()
            .filterNot { it.trimStart().startsWith("#") }
            .filter { gplPattern.containsMatchIn(it) }
        assertTrue(
            "GPL/LGPL dependency found in gradle/dependency-licenses.toml:\n" +
            violations.joinToString("\n") { "  $it" },
            violations.isEmpty(),
        )
    }

    // ── 9. SBOM task is registered ────────────────────────────────────────────

    @Test
    fun buildScript_sbomTaskPresent() {
        // The SBOM is a release artifact; its task must be registered in the build script
        // so CI can generate and archive it alongside the APK/AAB.
        val root = file("build.gradle.kts")
        assertTrue("Root build.gradle.kts not found", root.exists())
        val text = root.readText()
        assertTrue(
            "generateSbom task not registered in root build.gradle.kts. " +
            "The SBOM must be generated and archived with every release.",
            text.contains("generateSbom"),
        )
    }

    // ── 10. Data safety: no networking deps in any module ────────────────────

    @Test
    fun buildScripts_noOkhttpOrRetrofit() {
        // OkHttp and Retrofit are the canonical networking libraries; their presence in any
        // build script would be a network policy violation regardless of whether they are used.
        val pattern = Regex("""(okhttp|retrofit|volley|ktor-client-(?!core))""", RegexOption.IGNORE_CASE)
        val violations = projectRoot.walkTopDown()
            .filter { it.name == "build.gradle.kts" }
            .filter { !it.absolutePath.contains("/build/") }
            .filter { pattern.containsMatchIn(it.readText()) }
            .toList()

        assertTrue(
            "Network library dependency found in build scripts — violates the no-network policy:\n" +
            violations.joinToString("\n") { "  ${it.absolutePath}" },
            violations.isEmpty(),
        )
    }
}
