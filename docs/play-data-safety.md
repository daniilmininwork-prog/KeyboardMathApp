# Play Store — Data Safety Declaration

This document is the source of truth for the Google Play Data Safety form. It is reviewed
on every release; any change that could affect data collection or sharing must update this
document and be reviewed before the release is submitted.

## Data collected

**None.** Tally does not collect, share, or transmit any data.

The technical basis for this claim:

- The app has no `INTERNET` permission. The device OS enforces that no data can be sent
  off-device, regardless of what code attempts it.
- No networking library (OkHttp, Retrofit, Volley, or similar) appears in any module's
  dependency graph.
- No analytics SDK, crash reporting SDK, or advertising SDK is included.
- The `ci.yml` and `build.gradle.kts` security guards fail the build if `INTERNET` is
  added — the claim is enforced by construction, not by convention.

## Data shared

**None.** With no network path, data cannot be shared with third parties.

## Security practices

| Practice | Status |
|---|---|
| Data is encrypted in transit | Not applicable — no data is transmitted |
| Data is encrypted at rest | Shared-preferences (learned words, settings) use default Android storage — not additionally encrypted. The data never leaves the device. |
| Users can request deletion | Not applicable — no data is collected |
| App committed to Play Families policy | No |
| Independent security audit | Planned for TX.2 (post-v1.0) |

## Form completion guidance

When filling out the Google Play Console Data Safety form, answer as follows:

1. **Does your app collect or share any of the required data types?** → **No**
2. **Is all of the user data collected by your app encrypted in transit?** → Not applicable
   (no data collected)
3. **Do you provide a way for users to request that their data is deleted?** → Not applicable

The `INTERNET` permission is absent from the manifest. Play Console may verify this; the
absence is the enforceable proof that backs the "no data collected" declaration.

## Review history

| Version | Date | Reviewer | Outcome |
|---|---|---|---|
| 0.1.0 | 2026-06-02 | T3.5 release-engineering pass | No data collected — declaration accurate |
