# Security & privacy

A keyboard, and an accessibility overlay, both sit in the most sensitive position on the device:
they can see everything a person types. The entire credibility of this product rests on getting
this right and being able to *prove* it. This document is a hard requirement set, not advice.

## Promises we make to the user

1. **It works entirely on your device.** Math is computed locally. Nothing you type is sent
   anywhere.
2. **It cannot phone home — by construction.** The app ships **without** the `INTERNET`
   permission. With no network permission, exfiltration is not "unlikely," it's impossible for
   the app to perform. This is the single most important, and most verifiable, guarantee.
3. **It does not record what you type.** No keystroke logging, no storing of field contents.
4. **No accounts, no analytics, no ads, no third-party SDKs that collect data.**
5. **Nothing it does is hidden behind the network.** The source is private, but the most
   important guarantee is independently checkable anyway: the app carries no network permission,
   which anyone can confirm from the installed APK and the Play listing.

These map directly to the Play Data Safety form: **"No data collected, no data shared."**

## Threat model

Who/what we defend against, and how.

| Threat | Vector | Mitigation |
| --- | --- | --- |
| Data exfiltration of typed text | Network egress | **No `INTERNET` permission**; no networking deps anywhere in the tree |
| Keystroke logging / persistence | Logs, files, DB | No persistence of field text; transient buffers only; release builds strip logs |
| Malicious dependency (supply chain) | Compromised library | Minimal deps, pinned versions, checksum/signature verification, SBOM, reproducible builds |
| Tap-jacking against the overlay | Overlay over a malicious window | Ignore taps when window is obscured; minimal, well-scoped overlay |
| Over-broad permissions | Scope creep | Least privilege: IME needs only its bind permission; overlay perms are opt-in + disclosed |
| Accessibility abuse perception | Overlay uses a11y API | Narrow read scope, explicit consent, no network permission, independent audit; off-Play direct distribution for this mode |
| Clipboard leakage | Insertion via clipboard | Prefer direct insertion; if clipboard is used, restore prior contents and don't linger |
| Code-injection via input | Parsing untrusted text | Pure parser, **no `eval`/reflection**, bounded recursion/iteration, total function (never throws) |
| DoS via pathological input | Huge/nested expressions | Hard input limits + step budget (see [engine spec](05-math-engine-spec.md#limits)) |

## Permission policy (least privilege)

- **IME mode:** only the input-method bind permission the platform requires. **No** internet,
  **no** storage beyond app-private settings, **no** contacts/location/etc.
- **Overlay mode (opt-in):** the accessibility-service bind permission and draw-over-apps. Each
  is requested only when the user explicitly enables the overlay, each with a one-line plain
  explanation, each revocable, and the app is fully functional (keyboard mode) without them.
- The manifest is treated as a security artifact: a CI check fails the build if `INTERNET` (or
  any unexpected permission) appears. See [testing](08-testing-quality.md).

## Data handling rules (for the implementer)

- **Never log field content or keystrokes**, even at debug level. Any diagnostic logging is
  gated behind a debug flag, scrubbed of user text, and absent from release builds.
- **Do not persist** the text being analysed. The detector works on a transient buffer that is
  not written to disk, databases, or `SharedPreferences`.
- **Settings only.** The only thing stored is user preferences (toggles, precision), which
  contain no typed content.
- **No third-party analytics/crash SDKs** that transmit data. If crash reporting is ever added,
  it must be opt-in, on-device or self-hosted, and free of user text — and it would require its
  own ADR because it touches the network promise.
- **Overlay read scope** is limited to what's needed to find an expression near the caret; do
  not traverse or harvest unrelated UI.

## Build & supply-chain security

- **Minimal dependencies.** Every dependency is a liability for a keyboard; justify each.
- **Pinned versions + verification.** Use Gradle dependency verification (checksums/signatures);
  no dynamic version ranges.
- **Reproducible builds.** Maintain byte-for-byte reproducible release builds as an internal
  integrity control (the same source + toolchain always yields the same artifact), so a release
  can be re-derived and pipeline tampering is detectable. With closed source this is an internal
  assurance rather than something the public can check against source.
- **Signed releases**, keys never in the repo, release signing documented in
  [build-release](09-build-release.md).
- **SBOM** generated per release; dependency and static-analysis scanning in CI.
- **R8/ProGuard** configured to strip debug logging and shrink attack surface in release builds.

## Verifiability is the point

For most apps "trust us" is the norm. For a keyboard it's unacceptable. The source is closed, so
we lean on the proofs that survive that — the ones a user or auditor can check without our
cooperation, plus one we commission:

- **No `INTERNET` permission** → visible in the manifest, the Play listing's permission list, and
  by running `aapt` / an APK inspector on the installed binary. This is the load-bearing
  guarantee: an app with no network permission cannot exfiltrate, whatever its source says.
- **"No data collected" Data Safety declaration** → public on the Play listing and backed by the
  absent network permission.
- **Independent security audit** → commission a reputable third party to review the source under
  NDA and publish their report/attestation. This substitutes for open source as the credible
  outside check, and is the strongest trust signal available to a closed-source keyboard.
- **Reproducible release builds** → internal pipeline-integrity control (see above).

If a future feature can't be built without weakening one of these promises, that's a signal to
not build it, or to build it as a clearly separate, separately-consented thing — recorded as an
ADR, never slipped in.
