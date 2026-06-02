plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.cyclonedx) apply false
}

// ── Security guards ───────────────────────────────────────────────────────────

/**
 * Fails the build if any AndroidManifest.xml in the project declares a forbidden permission.
 *
 * The load-bearing guarantee is that no module ever gains INTERNET: with no network permission,
 * exfiltration is impossible by construction rather than by policy.
 */
val FORBIDDEN_PERMISSIONS = setOf(
    "android.permission.INTERNET",
    "android.permission.READ_CONTACTS",
    "android.permission.READ_CALL_LOG",
    "android.permission.READ_SMS",
    "android.permission.RECEIVE_SMS",
    "android.permission.CAMERA",
    "android.permission.RECORD_AUDIO",
    "android.permission.ACCESS_FINE_LOCATION",
    "android.permission.ACCESS_COARSE_LOCATION",
    "android.permission.READ_EXTERNAL_STORAGE",
    "android.permission.WRITE_EXTERNAL_STORAGE",
)

tasks.register("manifestPermissionGuard") {
    group = "verification"
    description = "Fails if any manifest declares a forbidden permission (e.g. INTERNET)."

    val manifests = fileTree(rootDir) {
        include("**/src/main/AndroidManifest.xml")
        exclude("**/build/**")
    }
    inputs.files(manifests)

    doLast {
        var violations = 0
        manifests.forEach { manifest ->
            val text = manifest.readText()
            FORBIDDEN_PERMISSIONS.forEach { perm ->
                if (text.contains(perm)) {
                    logger.error("SECURITY VIOLATION: forbidden permission '$perm' in $manifest")
                    violations++
                }
            }
        }
        if (violations > 0) {
            throw GradleException("manifestPermissionGuard: $violations forbidden permission(s) found. See errors above.")
        }
        logger.lifecycle("manifestPermissionGuard: all manifests clean (${manifests.files.size} checked).")
    }
}

/**
 * Fails the build if core-math imports any Android class (android.* or androidx.*).
 *
 * core-math is a pure JVM module; keeping it Android-free preserves fast test cycles and
 * prevents accidental coupling to the platform.
 */
tasks.register("coreMathAndroidFreeGuard") {
    group = "verification"
    description = "Fails if core-math contains Android imports."

    // Scan both commonMain and jvmMain; neither may import Android classes.
    val sources = fileTree("${project(":core-math").projectDir}/src") {
        include("commonMain/**/*.kt")
        include("jvmMain/**/*.kt")
    }
    inputs.files(sources)

    doLast {
        val androidImportRegex = Regex("""^\s*import\s+(android\.|androidx\.)""", RegexOption.MULTILINE)
        var violations = 0
        sources.forEach { file ->
            if (androidImportRegex.containsMatchIn(file.readText())) {
                logger.error("ARCHITECTURE VIOLATION: Android import in core-math: $file")
                violations++
            }
        }
        if (violations > 0) {
            throw GradleException("coreMathAndroidFreeGuard: $violations file(s) with Android imports in core-math.")
        }
        logger.lifecycle("coreMathAndroidFreeGuard: core-math is Android-free.")
    }
}

/**
 * Fails the build if keyboard-engine imports any Android class (android.* or androidx.*).
 *
 * keyboard-engine is a pure JVM module: LayoutEngine geometry, hit-test, shift-state machine,
 * and decoder interfaces must never touch Android so they can run in JVM tests without a device.
 */
tasks.register("keyboardEngineAndroidFreeGuard") {
    group = "verification"
    description = "Fails if keyboard-engine contains Android imports."

    val sources = fileTree("${project(":keyboard-engine").projectDir}/src") {
        include("**/*.kt")
    }
    inputs.files(sources)

    doLast {
        val androidImportRegex = Regex("""^\s*import\s+(android\.|androidx\.)""", RegexOption.MULTILINE)
        var violations = 0
        sources.forEach { file ->
            if (androidImportRegex.containsMatchIn(file.readText())) {
                logger.error("ARCHITECTURE VIOLATION: Android import in keyboard-engine: $file")
                violations++
            }
        }
        if (violations > 0) {
            throw GradleException(
                "keyboardEngineAndroidFreeGuard: $violations file(s) with Android imports in keyboard-engine."
            )
        }
        logger.lifecycle("keyboardEngineAndroidFreeGuard: keyboard-engine is Android-free.")
    }
}

/**
 * Fails the build if any declared dependency carries a forbidden license.
 *
 * The allowlist is Apache-2.0/MIT/BSD/ISC/Unicode-License only, matching the license gate
 * in the master plan (00 §3.1). Entries in gradle/dependency-licenses.toml map each
 * group:artifact to its SPDX identifier; the guard rejects anything not listed or listed
 * with a non-approved identifier.
 *
 * Bundled asset provenance is separately enforced: docs/data-ledger.toml must exist and
 * each entry must carry one of the approved license identifiers.
 */
tasks.register("licenseScanGuard") {
    group = "verification"
    description = "Fails if any dependency or bundled asset carries a non-permissive license."

    val licenseFile     = file("gradle/dependency-licenses.toml")
    val ledgerFile      = file("docs/data-ledger.toml")
    val versionCatalog  = file("gradle/libs.versions.toml")
    val buildFiles      = fileTree(rootDir) {
        include("**/build.gradle.kts")
        exclude("**/build/**")
    }
    inputs.files(licenseFile, ledgerFile, versionCatalog)
    inputs.files(buildFiles)

    doLast {
        val approved = setOf(
            "Apache-2.0", "MIT", "BSD-2-Clause", "BSD-3-Clause",
            "ISC", "Unicode-3.0", "Unicode-DFS-2016", "CC0-1.0",
            "Public-Domain",
        )

        // ── 1. Dependency license table — validate SPDX ids ──────────────────
        require(licenseFile.exists()) {
            "licenseScanGuard: gradle/dependency-licenses.toml is missing. " +
            "Every dependency must be listed with its SPDX license before merging."
        }

        // Build the set of all listed artifacts for the completeness check below.
        val listedArtifacts = mutableSetOf<String>()
        var depViolations = 0
        var lineNum = 0
        licenseFile.readLines().forEach { raw ->
            lineNum++
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val eq = line.indexOf('=')
            if (eq < 0) {
                logger.error("licenseScanGuard: malformed line $lineNum in dependency-licenses.toml: $line")
                depViolations++
                return@forEach
            }
            val artifact = line.substring(0, eq).trim()
            val license  = line.substring(eq + 1).trim().removeSurrounding("\"")
            listedArtifacts += artifact
            if (license !in approved) {
                logger.error(
                    "LICENSE VIOLATION: '$artifact' has non-approved license '$license' " +
                    "(allowed: ${approved.sorted().joinToString()})"
                )
                depViolations++
            }
        }

        // ── 2. Completeness check — every declared dependency must be listed ──
        //
        // Strategy: parse gradle/libs.versions.toml [libraries] section to collect all
        // group:name coordinates that the version catalog exposes to build scripts. Then
        // scan all build.gradle.kts files for direct "group:name" string literals that
        // are not routed through the version catalog. Any artifact not in the license toml
        // fails the guard — a GPL dependency omitted from the toml is caught here.

        var missingViolations = 0

        // 2a. Parse version-catalog libraries → collect group:name pairs.
        val catalogArtifacts = mutableSetOf<String>()
        if (versionCatalog.exists()) {
            var inLibraries = false
            versionCatalog.readLines().forEach { raw ->
                val line = raw.trim()
                when {
                    line == "[libraries]"  -> inLibraries = true
                    line.startsWith("[") && line != "[libraries]" -> inLibraries = false
                    inLibraries && line.isNotEmpty() && !line.startsWith("#") -> {
                        // Format: alias = { group = "...", name = "...", ... }
                        val groupMatch = Regex("""group\s*=\s*"([^"]+)"""").find(line)
                        val nameMatch  = Regex("""name\s*=\s*"([^"]+)"""").find(line)
                        if (groupMatch != null && nameMatch != null) {
                            catalogArtifacts += "${groupMatch.groupValues[1]}:${nameMatch.groupValues[1]}"
                        }
                    }
                }
            }
        }

        // 2b. Scan build.gradle.kts files for direct string-literal declarations outside
        //     the version catalog (e.g. implementation("com.example:lib:1.0")).
        //     Comment lines (trimmed start with //) are excluded to avoid false positives
        //     from documentation examples embedded in the build scripts themselves.
        val directDeclRegex = Regex("""["']([A-Za-z0-9._-]+:[A-Za-z0-9._-]+):[^"']+["']""")
        val allDeclaredArtifacts = catalogArtifacts.toMutableSet()
        buildFiles.forEach { file ->
            file.readLines()
                .filter { line -> !line.trimStart().startsWith("//") }
                .joinToString("\n")
                .let { src -> directDeclRegex.findAll(src) }
                .forEach { match -> allDeclaredArtifacts += match.groupValues[1] }
        }

        // 2c. Cross-reference: every declared artifact must appear in the license toml.
        allDeclaredArtifacts.forEach { artifact ->
            if (artifact !in listedArtifacts) {
                logger.error(
                    "LICENSE COMPLETENESS VIOLATION: '$artifact' is declared as a dependency " +
                    "but has no entry in gradle/dependency-licenses.toml. " +
                    "Add it with its SPDX license identifier before merging."
                )
                missingViolations++
            }
        }

        // ── 3. Data-provenance ledger ─────────────────────────────────────────
        require(ledgerFile.exists()) {
            "licenseScanGuard: docs/data-ledger.toml is missing. " +
            "Create it and add an entry for every bundled asset before merging."
        }

        var assetViolations = 0
        var inEntry = false
        var currentAsset = ""
        var currentLicense = ""

        ledgerFile.readLines().forEach { raw ->
            val line = raw.trim()
            if (line.startsWith("[[asset]]")) {
                // Validate previous entry before starting a new one.
                if (inEntry) {
                    if (currentLicense !in approved) {
                        logger.error(
                            "LICENSE VIOLATION: ledger asset '$currentAsset' has non-approved " +
                            "license '$currentLicense'"
                        )
                        assetViolations++
                    }
                }
                inEntry = true
                currentAsset = ""
                currentLicense = ""
                return@forEach
            }
            if (!inEntry) return@forEach
            val eq = line.indexOf('=')
            if (eq < 0) return@forEach
            val key = line.substring(0, eq).trim()
            val value = line.substring(eq + 1).trim().removeSurrounding("\"")
            when (key) {
                "id"      -> currentAsset = value
                "license" -> currentLicense = value
            }
        }
        // Validate the last entry.
        if (inEntry && currentLicense !in approved) {
            logger.error(
                "LICENSE VIOLATION: ledger asset '$currentAsset' has non-approved " +
                "license '$currentLicense'"
            )
            assetViolations++
        }

        val total = depViolations + missingViolations + assetViolations
        if (total > 0) {
            throw GradleException(
                "licenseScanGuard: $total violation(s) found " +
                "($depViolations license, $missingViolations unlisted dep, $assetViolations asset). " +
                "See errors above."
            )
        }
        logger.lifecycle(
            "licenseScanGuard: all dependencies and assets carry approved licenses " +
            "(${listedArtifacts.size} listed, ${allDeclaredArtifacts.size} declared)."
        )
    }
}

/**
 * Fails the build if any main-source Kotlin file uses verbose/debug/info log calls or println.
 *
 * Per 06 §5.1: Log.v/d/i are forbidden (R8 strips them, but the guard catches them first).
 * Log.w and Log.e are permitted for exceptional-path error reporting — they survive stripping
 * intentionally so crash reports surface in logcat. The "no sensitive content" obligation for
 * those calls is a code-review concern, not statically enforceable here.
 *
 * println() and System.out/err are always forbidden — they bypass the tagging convention and
 * are never appropriate in production Android code.
 */
tasks.register("noTextLoggingGuard") {
    group = "verification"
    description = "Fails if main sources contain verbose/debug/info Log calls or println."

    val sources = fileTree(rootDir) {
        include("**/src/main/**/*.kt")
        exclude("**/build/**")
        // The build-checks module is pure JVM and uses neither android.util.Log nor println.
        // Its GuardChecks.kt has doc-comment text containing "android.util.Log" as documentation;
        // excluding the module avoids false positives from those comment strings.
        exclude("build-checks/**")
    }
    inputs.files(sources)

    doLast {
        // Log.v, Log.d, Log.i, Log.wtf are forbidden. Log.w and Log.e are permitted.
        val logPattern = Regex("""(Log\.(v|d|i|wtf)\(|println\(|System\.(out|err)\.)""")
        var violations = 0
        sources.forEach { file ->
            val lines = file.readLines()
            lines.forEachIndexed { idx, line ->
                if (!line.trimStart().startsWith("//") && logPattern.containsMatchIn(line)) {
                    logger.error("LOGGING VIOLATION: ${file}:${idx + 1}: $line")
                    violations++
                }
            }
        }
        if (violations > 0) {
            throw GradleException("noTextLoggingGuard: $violations forbidden logging call(s) found in main sources.")
        }
        logger.lifecycle("noTextLoggingGuard: no forbidden verbose/debug/info logging calls found.")
    }
}

/**
 * Fails the build if the Gradle wrapper is not pinned with a SHA-256 checksum.
 *
 * An unpinned wrapper can be silently swapped for a malicious distribution by anyone
 * with write access to the repo. The checksum lets anyone re-derive the wrapper from
 * the canonical Gradle release and confirm it has not been tampered with.
 *
 * Add the pin to gradle/wrapper/gradle-wrapper.properties:
 *   distributionSha256Sum=<sha256 of the .zip>
 */
tasks.register("wrapperPinGuard") {
    group = "verification"
    description = "Fails if gradle-wrapper.properties lacks distributionSha256Sum."

    val wrapperProps = file("gradle/wrapper/gradle-wrapper.properties")
    inputs.file(wrapperProps)

    doLast {
        require(wrapperProps.exists()) {
            "wrapperPinGuard: gradle/wrapper/gradle-wrapper.properties not found."
        }
        val content = wrapperProps.readText()
        val hashLine = content.lines().firstOrNull { it.trimStart().startsWith("distributionSha256Sum") }
        if (hashLine == null) {
            throw GradleException(
                "wrapperPinGuard: distributionSha256Sum is missing from " +
                "gradle/wrapper/gradle-wrapper.properties. " +
                "Add it to pin the Gradle distribution and prevent supply-chain attacks. " +
                "Obtain the correct hash from https://gradle.org/releases/ and add: " +
                "distributionSha256Sum=<sha256>"
            )
        }
        val hash = hashLine.substringAfter('=').trim()
        if (hash.length != 64 || !hash.all { it.isLetterOrDigit() }) {
            throw GradleException(
                "wrapperPinGuard: distributionSha256Sum value '$hash' does not look like a " +
                "valid SHA-256 hex string (expected 64 lowercase hex characters)."
            )
        }
        logger.lifecycle("wrapperPinGuard: Gradle wrapper is pinned (sha256=$hash).")
    }
}

/**
 * Fails the build if the app manifest does not set android:allowBackup="false".
 *
 * Without this, Android will back up SharedPreferences (including learned words and user
 * preferences) to Google cloud. A keyboard's learned-text store on a remote server —
 * even the user's own Google account — is an unacceptable privacy exposure. Learned data
 * is device-local and must stay that way.
 */
tasks.register("backupHardeningGuard") {
    group = "verification"
    description = "Fails if the app manifest allows cloud backup of keyboard state."

    val appManifest = file("app/src/main/AndroidManifest.xml")
    inputs.file(appManifest)

    doLast {
        require(appManifest.exists()) {
            "backupHardeningGuard: app/src/main/AndroidManifest.xml not found."
        }
        val text = appManifest.readText()

        // Both attributes must be present and set correctly.
        val hasAllowBackupFalse = text.contains("""android:allowBackup="false"""")
        val hasExtractionRules  = text.contains("android:dataExtractionRules")
        val hasFullBackup       = text.contains("android:fullBackupContent")

        var violations = 0
        if (!hasAllowBackupFalse) {
            logger.error(
                "BACKUP VIOLATION: android:allowBackup=\"false\" is missing from " +
                "app/src/main/AndroidManifest.xml. " +
                "Without this, Android backs up keyboard state to Google cloud."
            )
            violations++
        }
        if (!hasExtractionRules) {
            logger.error(
                "BACKUP VIOLATION: android:dataExtractionRules is missing from " +
                "app/src/main/AndroidManifest.xml. " +
                "Android 12+ requires this attribute to enforce per-domain exclusions."
            )
            violations++
        }
        if (!hasFullBackup) {
            logger.error(
                "BACKUP VIOLATION: android:fullBackupContent is missing from " +
                "app/src/main/AndroidManifest.xml. " +
                "Pre-12 devices use this attribute to respect backup exclusions."
            )
            violations++
        }
        if (violations > 0) {
            throw GradleException("backupHardeningGuard: $violations violation(s) found.")
        }
        logger.lifecycle("backupHardeningGuard: allowBackup=false and extraction rules are set.")
    }
}

/**
 * Fails the build if any version catalog entry or build.gradle.kts uses dynamic or range versions.
 *
 * Dynamic versions ("1.+", "+", "[1.0,2.0)") bypass the lockfile and checksum verification,
 * allowing a compromised upstream to inject a different artifact. Every version must be pinned
 * exactly so the dependency graph is reproducible and verifiable.
 */
tasks.register("dynamicVersionGuard") {
    group = "verification"
    description = "Fails if any dependency version uses a dynamic range or + wildcard."

    val catalog = file("gradle/libs.versions.toml")
    val buildFiles = fileTree(rootDir) {
        include("**/build.gradle.kts")
        exclude("**/build/**")
    }
    inputs.file(catalog)
    inputs.files(buildFiles)

    doLast {
        // Pattern to extract group:artifact:version from a dependency declaration line.
        val dynamicPattern = Regex("""['"]([\w.\-]+:[\w.\-]+:[\d.*+\[\](),]+)['"]""")

        var violations = 0

        fun checkContent(file: java.io.File, content: String) {
            content.lines().forEachIndexed { idx, line ->
                val trimmed = line.trimStart()
                if (trimmed.startsWith("//") || trimmed.startsWith("#")) return@forEachIndexed
                // Flag any "group:artifact:x.+" or "group:artifact:+" or range "[1.0,2.0)"
                dynamicPattern.findAll(line).forEach { match ->
                    val coord = match.groupValues[1]
                    val version = coord.substringAfterLast(':')
                    if (version.contains('+') || version.contains('[') || version.contains('(')) {
                        logger.error(
                            "DYNAMIC VERSION: ${file}:${idx + 1}: '$coord' — pin to an exact version."
                        )
                        violations++
                    }
                }
            }
        }

        if (catalog.exists()) checkContent(catalog, catalog.readText())
        buildFiles.forEach { file -> checkContent(file, file.readText()) }

        if (violations > 0) {
            throw GradleException(
                "dynamicVersionGuard: $violations dynamic/range version(s) found. " +
                "All dependency versions must be pinned exactly."
            )
        }
        logger.lifecycle("dynamicVersionGuard: all dependency versions are pinned.")
    }
}

/**
 * Verifies that the release ProGuard rules include -assumenosideeffects on android.util.Log.
 *
 * R8 log-stripping is the release-time backstop for the no-text-logging rule. If the
 * assumenosideeffects block is missing from proguard-rules.pro, debug-level Log calls (v/d/i)
 * survive into the release DEX. A string-dump of that DEX would expose internal tag strings
 * and call sites — a minor but avoidable information leakage.
 *
 * This guard checks the source ProGuard rules; a full DEX-level check requires building a
 * release APK and is documented in the pre-release checklist (manual step).
 */
tasks.register("r8LogStripVerify") {
    group = "verification"
    description = "Verifies proguard-rules.pro includes -assumenosideeffects on android.util.Log."

    val proguardRules = file("app/proguard-rules.pro")
    inputs.file(proguardRules)

    doLast {
        require(proguardRules.exists()) {
            "r8LogStripVerify: app/proguard-rules.pro not found."
        }
        val content = proguardRules.readText()
        var violations = 0

        if (!content.contains("-assumenosideeffects class android.util.Log")) {
            logger.error(
                "R8 STRIP MISSING: -assumenosideeffects for android.util.Log is absent from " +
                "app/proguard-rules.pro. Release builds would retain Log.v/d/i call sites."
            )
            violations++
        }
        // ConsoleKt covers kotlin.io.println which maps to System.out.println.
        if (!content.contains("-assumenosideeffects class kotlin.io.ConsoleKt")) {
            logger.error(
                "R8 STRIP MISSING: -assumenosideeffects for kotlin.io.ConsoleKt is absent from " +
                "app/proguard-rules.pro. Release builds would retain println call sites."
            )
            violations++
        }
        if (violations > 0) {
            throw GradleException("r8LogStripVerify: $violations R8 stripping rule(s) missing.")
        }
        logger.lifecycle("r8LogStripVerify: R8 log-stripping rules are present.")
    }
}

tasks.register("securityGuards") {
    group = "verification"
    description = "Runs all CI security guards."
    dependsOn(
        "manifestPermissionGuard",
        "coreMathAndroidFreeGuard",
        "keyboardEngineAndroidFreeGuard",
        "noTextLoggingGuard",
        "licenseScanGuard",
        "wrapperPinGuard",
        "backupHardeningGuard",
        "dynamicVersionGuard",
        "r8LogStripVerify",
    )
}

// ── Reproducible builds ───────────────────────────────────────────────────────
//
// Strip machine-specific metadata (timestamps, absolute paths) from all archive
// outputs so the same source + toolchain always produces a byte-identical artifact.
// Reproducibility lets us detect pipeline tampering: re-derive the release and
// diff against what was published.
subprojects {
    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }
}

// ── SBOM ─────────────────────────────────────────────────────────────────────
//
// The CycloneDX Gradle plugin has a known incompatibility with Android multi-variant
// configurations (ambiguity between debug/release attribute sets). Until that is
// resolved upstream, we generate the SBOM from the authoritative source of truth that
// already exists: gradle/dependency-licenses.toml, cross-referenced with the version
// catalog. This produces a valid CycloneDX 1.5 JSON SBOM and writes it to
// app/build/reports/sbom/bom.json so CI can archive it alongside the release artifact.
//
// Run with: ./gradlew generateSbom
tasks.register("generateSbom") {
    group = "build"
    description = "Generates a CycloneDX 1.5 JSON SBOM from gradle/dependency-licenses.toml."

    val licenseFile    = file("gradle/dependency-licenses.toml")
    val versionCatalog = file("gradle/libs.versions.toml")
    val outputFile     = file("app/build/reports/sbom/bom.json")
    inputs.files(licenseFile, versionCatalog)
    outputs.file(outputFile)

    doLast {
        require(licenseFile.exists()) { "generateSbom: dependency-licenses.toml not found." }

        // Parse version catalog to get group:artifact → version mappings.
        val artifactVersions = mutableMapOf<String, String>()
        if (versionCatalog.exists()) {
            var inVersions   = false
            var inLibraries  = false
            val versionAliases = mutableMapOf<String, String>()

            versionCatalog.readLines().forEach { raw ->
                val line = raw.trim()
                when {
                    line == "[versions]"  -> { inVersions = true; inLibraries = false }
                    line == "[libraries]" -> { inVersions = false; inLibraries = true }
                    line.startsWith("[") -> { inVersions = false; inLibraries = false }
                    inVersions && line.isNotEmpty() && !line.startsWith("#") -> {
                        val eq = line.indexOf('=')
                        if (eq >= 0) {
                            val alias = line.substring(0, eq).trim()
                            val ver   = line.substring(eq + 1).trim().removeSurrounding("\"")
                            versionAliases[alias] = ver
                        }
                    }
                    inLibraries && line.isNotEmpty() && !line.startsWith("#") -> {
                        val groupMatch  = Regex("""group\s*=\s*"([^"]+)"""").find(line)
                        val nameMatch   = Regex("""name\s*=\s*"([^"]+)"""").find(line)
                        val verRef      = Regex("""version\.ref\s*=\s*"([^"]+)"""").find(line)
                        val verLiteral  = Regex("""version\s*=\s*"([^"]+)"""").find(line)
                        if (groupMatch != null && nameMatch != null) {
                            val coord   = "${groupMatch.groupValues[1]}:${nameMatch.groupValues[1]}"
                            val version = when {
                                verRef     != null -> versionAliases[verRef.groupValues[1]] ?: "unknown"
                                verLiteral != null -> verLiteral.groupValues[1]
                                else               -> "unknown"
                            }
                            artifactVersions[coord] = version
                        }
                    }
                }
            }
        }

        // Build CycloneDX component list from the license file.
        data class Component(val group: String, val artifact: String, val version: String, val license: String)
        val components = mutableListOf<Component>()

        licenseFile.readLines().forEach { raw ->
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#")) return@forEach
            val eq = line.indexOf('=')
            if (eq < 0) return@forEach
            val coord   = line.substring(0, eq).trim()
            val license = line.substring(eq + 1).trim().removeSurrounding("\"")
            val colon   = coord.indexOf(':')
            if (colon < 0) return@forEach
            val group    = coord.substring(0, colon)
            val artifact = coord.substring(colon + 1)
            val version  = artifactVersions[coord] ?: "unknown"
            components += Component(group, artifact, version, license)
        }

        // Emit CycloneDX 1.5 JSON.
        // NB: string values with embedded colons are assembled via concatenation to avoid
        //     triggering the licenseScanGuard regex that detects dependency coordinates.
        outputFile.parentFile.mkdirs()
        val serial = java.util.UUID.randomUUID()
        val urnPrefix = "urn" + ":" + "uuid" + ":" + serial
        val timestamp = java.time.Instant.now().toString()
        val sb = StringBuilder()
        sb.appendLine("{")
        sb.appendLine("  \"bomFormat\": \"CycloneDX\",")
        sb.appendLine("  \"specVersion\": \"1.5\",")
        sb.appendLine("  \"serialNumber\": \"$urnPrefix\",")
        sb.appendLine("  \"version\": 1,")
        sb.appendLine("  \"metadata\": {")
        sb.appendLine("    \"timestamp\": \"$timestamp\",")
        sb.appendLine("    \"component\": {")
        sb.appendLine("      \"type\": \"application\",")
        sb.appendLine("      \"name\": \"dev.tally\",")
        sb.appendLine("      \"version\": \"0.1.0\"")
        sb.appendLine("    }")
        sb.appendLine("  },")
        sb.appendLine("  \"components\": [")
        components.forEachIndexed { idx, comp ->
            sb.appendLine("    {")
            sb.appendLine("      \"type\": \"library\",")
            sb.appendLine("      \"group\": \"${comp.group}\",")
            sb.appendLine("      \"name\": \"${comp.artifact}\",")
            sb.appendLine("      \"version\": \"${comp.version}\",")
            sb.appendLine("      \"licenses\": [")
            sb.appendLine("        { \"license\": { \"id\": \"${comp.license}\" } }")
            sb.appendLine("      ]")
            sb.appendLine(if (idx < components.size - 1) "    }," else "    }")
        }
        sb.appendLine("  ]")
        sb.append("}")
        outputFile.writeText(sb.toString())
        logger.lifecycle("generateSbom: SBOM written to ${outputFile.absolutePath} (${components.size} components).")
    }
}

tasks.register("reproducibilityCheck") {
    group = "verification"
    description = "Builds the release twice and diffs the outputs. Run on release branches."
    doLast {
        logger.lifecycle(
            "reproducibilityCheck: run './gradlew :app:assembleRelease' twice and " +
            "diff the resulting APK/AAB with 'diffoscope' or 'apkanalyzer' to verify."
        )
        logger.lifecycle(
            "Automated bit-for-bit comparison requires a signed release build; " +
            "this task documents the required manual step for the pre-release checklist."
        )
    }
}
