package dev.tally.buildchecks

/**
 * Pure functions that encode the CI guard predicates.
 *
 * Extracting the predicate logic here lets us unit-test the guards themselves
 * without invoking Gradle, so T0.2's "tests land with the code" requirement is
 * satisfied by fast JVM tests rather than slow Gradle integration runs.
 *
 * The Gradle tasks in build.gradle.kts apply the same patterns to the real source
 * tree; any discrepancy between the task and these functions is a bug in the task.
 */

/** SPDX identifiers allowed by the license gate (00 §3.1). */
val APPROVED_LICENSES: Set<String> = setOf(
    "Apache-2.0",
    "MIT",
    "BSD-2-Clause",
    "BSD-3-Clause",
    "ISC",
    "Unicode-3.0",
    "Unicode-DFS-2016",
    "CC0-1.0",
    "Public-Domain",
)

/**
 * Returns true if [sourceText] contains an import statement from Android or AndroidX.
 *
 * Used by [coreMathAndroidFreeGuard] and [keyboardEngineAndroidFreeGuard] in build.gradle.kts.
 */
fun containsAndroidImport(sourceText: String): Boolean {
    val pattern = Regex("""^\s*import\s+(android\.|androidx\.)""", RegexOption.MULTILINE)
    return pattern.containsMatchIn(sourceText)
}

/**
 * Returns true if [manifestText] contains a use-permission element for [permission].
 */
fun manifestContainsPermission(manifestText: String, permission: String): Boolean =
    manifestText.contains(permission)

/**
 * Returns true if [sourceText] contains a logging call forbidden in production code.
 *
 * Log.v, Log.d, Log.i, and Log.wtf are forbidden — R8 will strip them, but the guard catches
 * them first so they never reach code review unnoticed. Log.w and Log.e are permitted for
 * exceptional-path error reporting (per 06 §5.1). println() and System.out/err are always
 * forbidden in production Android code.
 *
 * Commented-out lines are excluded so developers may leave notes with log examples.
 */
fun containsForbiddenLogging(sourceText: String): Boolean {
    val logPattern = Regex(
        """(Log\.(v|d|i|wtf)\(|println\(|System\.(out|err)\.)"""
    )
    return sourceText.lines().any { line ->
        !line.trimStart().startsWith("//") && logPattern.containsMatchIn(line)
    }
}

/**
 * Parses a line from dependency-licenses.toml (key = "value" format, skipping comments)
 * and returns [Pair<artifact, spdxId>] or null for blank/comment lines.
 */
fun parseLicenseLine(line: String): Pair<String, String>? {
    val trimmed = line.trim()
    if (trimmed.isEmpty() || trimmed.startsWith("#")) return null
    val eq = trimmed.indexOf('=')
    if (eq < 0) return null
    val artifact = trimmed.substring(0, eq).trim()
    val license  = trimmed.substring(eq + 1).trim().removeSurrounding("\"")
    return artifact to license
}

/**
 * Returns true if [spdxId] is in the approved license set.
 */
fun isApprovedLicense(spdxId: String): Boolean = spdxId in APPROVED_LICENSES

/**
 * Returns true if [wrapperProperties] content contains a valid SHA-256 distribution pin.
 *
 * The pin must be present as a `distributionSha256Sum=<64 hex chars>` line. Without it the
 * Gradle wrapper download is verified only by URL, which allows MITM replacement.
 */
fun hasValidWrapperPin(wrapperProperties: String): Boolean {
    val line = wrapperProperties.lines()
        .firstOrNull { it.trimStart().startsWith("distributionSha256Sum") }
        ?: return false
    val hash = line.substringAfter('=').trim()
    // SHA-256 hex: exactly 64 characters from 0-9 and a-f only.
    return hash.length == 64 && hash.all { it in '0'..'9' || it in 'a'..'f' }
}

/**
 * Returns true if [manifestText] contains `android:allowBackup="false"`.
 *
 * Without this flag the OS may back up keyboard state (learned words, preferences) to
 * Google cloud, which violates the on-device-only privacy contract.
 */
fun manifestHasAllowBackupFalse(manifestText: String): Boolean =
    manifestText.contains("""android:allowBackup="false"""")

/**
 * Returns true if [manifestText] references `android:dataExtractionRules`.
 *
 * Required on Android 12+ to enforce per-domain cloud-backup exclusions. A manifest
 * that lacks this attribute may ignore fine-grained exclusion rules on newer devices.
 */
fun manifestHasDataExtractionRules(manifestText: String): Boolean =
    manifestText.contains("android:dataExtractionRules")

/**
 * Returns true if [proguardRules] contains the `-assumenosideeffects` block for Log stripping.
 *
 * R8 must be instructed to remove Log.v/d/i call sites from the release DEX. If the rule is
 * absent those calls survive minification and their string arguments remain in the binary.
 */
fun proguardStripsLog(proguardRules: String): Boolean =
    proguardRules.contains("-assumenosideeffects class android.util.Log")

/**
 * Returns true if [proguardRules] contains the `-assumenosideeffects` block for ConsoleKt.
 *
 * kotlin.io.ConsoleKt is the JVM target of `println()`; stripping it eliminates the bytecode
 * call site even though the platform-level symbol differs from android.util.Log.
 */
fun proguardStripsConsoleKt(proguardRules: String): Boolean =
    proguardRules.contains("-assumenosideeffects class kotlin.io.ConsoleKt")

/**
 * Returns true if [line] (a single line from a build file) declares a dynamic/range version.
 *
 * Dynamic versions (`1.+`, `+`, range notation `[1.0,2.0)`) bypass checksum verification and
 * allow upstream regressions or supply-chain attacks to silently replace a dependency artifact.
 */
fun isDynamicVersionLine(line: String): Boolean {
    val trimmed = line.trimStart()
    if (trimmed.startsWith("//") || trimmed.startsWith("#")) return false
    // Match group:artifact:version where version contains +, [, or (
    val coordPattern = Regex("""["']([A-Za-z0-9._-]+:[A-Za-z0-9._-]+):([^"']+)["']""")
    return coordPattern.findAll(line).any { match ->
        val version = match.groupValues[2]
        version.contains('+') || version.contains('[') || version.contains('(')
    }
}
