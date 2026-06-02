package dev.tally.buildchecks

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource

/**
 * Verifies that the license-scan guard predicates correctly identify approved and
 * forbidden licenses, and that the approved-license set is coherent.
 *
 * The licenseScanGuard Gradle task applies [parseLicenseLine] and [isApprovedLicense]
 * to every line in gradle/dependency-licenses.toml and every [[asset]] in
 * docs/data-ledger.toml; these tests prove the predicates are correct before the
 * Gradle task ever runs against real files.
 */
class LicenseScanGuardTest {

    // ── isApprovedLicense ─────────────────────────────────────────────────────

    @ParameterizedTest
    @ValueSource(strings = [
        "Apache-2.0",
        "MIT",
        "BSD-2-Clause",
        "BSD-3-Clause",
        "ISC",
        "Unicode-3.0",
        "Unicode-DFS-2016",
        "CC0-1.0",
        "Public-Domain",
    ])
    fun `all allowed licenses are approved`(spdx: String) {
        assertTrue(isApprovedLicense(spdx), "$spdx must be in the approved set")
    }

    @ParameterizedTest
    @ValueSource(strings = [
        "GPL-2.0",
        "GPL-2.0-only",
        "GPL-2.0-or-later",
        "GPL-3.0",
        "GPL-3.0-only",
        "GPL-3.0-or-later",
        "LGPL-2.0",
        "LGPL-2.1",
        "LGPL-3.0",
        "AGPL-3.0",
        "EUPL-1.2",
        "CDDL-1.0",
        "MPL-2.0",       // weak copyleft — excluded from this project
        "CC-BY-SA-4.0",  // copyleft creative-commons
        "",              // empty string is not approved
        "UNKNOWN",
    ])
    fun `forbidden and unknown licenses are rejected`(spdx: String) {
        assertFalse(isApprovedLicense(spdx), "$spdx must NOT be in the approved set")
    }

    // ── parseLicenseLine ──────────────────────────────────────────────────────

    @Test
    fun `blank line returns null`() {
        assertNull(parseLicenseLine(""))
        assertNull(parseLicenseLine("   "))
    }

    @Test
    fun `comment line returns null`() {
        assertNull(parseLicenseLine("# this is a comment"))
        assertNull(parseLicenseLine("  # indented comment"))
    }

    @Test
    fun `valid line parses artifact and license`() {
        val result = parseLicenseLine("""org.junit.jupiter:junit-jupiter-api = "Apache-2.0"""")
        assertNotNull(result)
        val (artifact, license) = result!!
        assertTrue(artifact == "org.junit.jupiter:junit-jupiter-api")
        assertTrue(license == "Apache-2.0")
    }

    @Test
    fun `license without quotes parses correctly`() {
        val result = parseLicenseLine("junit:junit = Apache-2.0")
        assertNotNull(result)
        val (_, license) = result!!
        assertTrue(license == "Apache-2.0")
    }

    @Test
    fun `gpl entry parses and is correctly rejected`() {
        val result = parseLicenseLine("""com.example:bad-lib = "GPL-3.0"""")
        assertNotNull(result)
        val (artifact, license) = result!!
        assertTrue(artifact == "com.example:bad-lib")
        assertFalse(isApprovedLicense(license), "GPL-3.0 must fail the license gate")
    }

    @Test
    fun `line without equals sign returns null`() {
        // Guard emits an error for malformed lines — parseLicenseLine returns null
        // and the caller counts it as a violation.
        assertNull(parseLicenseLine("org.example:lib Apache-2.0"))
    }

    // ── Approved set completeness ────────────────────────────────────────────

    @Test
    fun `approved set is non-empty`() {
        assertTrue(APPROVED_LICENSES.isNotEmpty())
    }

    @Test
    fun `approved set contains the four primary open-source licenses`() {
        // These are the most common licenses in the Android ecosystem;
        // their absence would be a configuration error.
        assertTrue("Apache-2.0" in APPROVED_LICENSES)
        assertTrue("MIT" in APPROVED_LICENSES)
        assertTrue("BSD-2-Clause" in APPROVED_LICENSES)
        assertTrue("BSD-3-Clause" in APPROVED_LICENSES)
    }
}
