# 06 — Security & Privacy Hardening (v2)

> **Status:** plan. **Audience:** engineering agents executing the Tally rebuild.
> **Extends, does not replace,** `docs/07-security-privacy.md`. That document states the
> *promises* and the v1 controls (no `INTERNET`, no persistence, minimal deps, strict
> verification metadata, reproducible builds, R8 log stripping). This document raises the bar
> to a **production keyboard + overlay** posture and gives an **executable checklist**. Where v1
> already covers something correctly, this doc points to it instead of repeating it.
>
> Cross-references (do not duplicate): overlay redesign is owned by `02-overlay-root-cause-and-redesign.md`;
> IME architecture by `03-ime-architecture-v2.md`; device/OEM behaviour by
> `05-device-framework-compatibility.md`; CI/build pipeline mechanics by `09-build-release.md`
> (existing) and the agent task list by `07-agent-execution-plan.md`. This document owns the
> **security/privacy contract** those documents must satisfy.

## 0. Why this is its own document

A keyboard sees every keystroke; an accessibility overlay sees on-screen content and can act on it.
This product concentrates two of Android's highest-trust capabilities into one app, so the security
posture is not a feature — it is the product's license to exist. The defensible axiom, inherited from
v1 and made binding here:

> **The keyboard cannot leak what it never transmits and never persists.**
> No `INTERNET` permission (exfiltration impossible by construction); all learning/personalization
> strictly on-device; no telemetry; sensitive-field handling enforced by *our* code, not merely
> requested via flags.

Two correctness facts from the audits drive the design and must be respected throughout:

1. **`IME_FLAG_NO_PERSONALIZED_LEARNING` and `TYPE_TEXT_FLAG_NO_SUGGESTIONS` are requests the OS does
   not enforce on us.** If our learning engine ignores them we have a real privacy bug despite a
   "compliant" manifest. Every control in §3 and §8 is *our* obligation to honour.
2. **The overlay cannot read IME composing text and cannot reliably use the clipboard on Android
   10+** (see `02-overlay-root-cause-and-redesign.md`). The security consequence: the clipboard-based
   insertion path is *also* a privacy defect (it routes field-derived text through a cross-app
   channel and can silently wipe the user's clipboard). This document **forbids** clipboard-based
   insertion as the primary strategy.

---

## 1. Threat model v2 (keyboard + overlay)

Actors and surfaces, with the **single named control** the rebuild must implement for each. "v1"
marks rows already covered in `docs/07-security-privacy.md`; the rest are new or sharpened.

| # | Actor / threat | Surface & vector | Concrete attack precedent | Primary control (this plan) | Owner doc |
|---|---|---|---|---|---|
| T1 | Network exfiltration of typed text | Any socket opened by app or a transitive SDK | Gboard/SwiftKey per-word telemetry (Leith et al.) | **No `INTERNET`/`ACCESS_NETWORK_STATE`; CI gate on the *merged* manifest of the release AAB** (§4) | this |
| T2 | Keystroke logging / persistence leak | logcat, crash dumps, files, `SharedPreferences`, cloud backup | Android "Log Info Disclosure"; default `allowBackup=true` ships prefs to cloud | No sensitive logging at any level; R8 strip `Log.v/d/i`; **`allowBackup=false` + `dataExtractionRules`** (§5, §8) | this |
| T3 | Learning from secure input | IME learns passwords/OTP into the user LM | SwiftKey 2016 cross-user suggestion bleed | **`isSensitiveField()` gate** disables learning/suggestions/clipboard/preview (§3) | 03 / this |
| T4 | Cross-app dictionary harvest | Other app injects `KeyEvent`s to read our personalized dictionary | ESORICS'15 (Diao et al.), Android ≤6 IMEs | Encrypted app-private learning store; never expose learned data via any exported/queryable surface (§8) | this |
| T5 | Supply-chain poisoning | Compromised/typosquatted dependency = a keylogger | SwiftKey **CVE-2015-2865** (HTTP language-pack MITM RCE, 600M+ devices) | Strict checksum **+ signature** verification, lockfiles, SBOM + CVE scan, minimal pinned deps (§5) | this / 09 |
| T6 | Screen-capture keystroke recovery | Key-preview popups, gesture trails, `show_touches` captured by recorder | Guardsquare keyboard analysis | Suppress previews/trails in secure fields; `FLAG_SECURE` on in-app screens showing learned data (§3, §6) | 03 / this |
| T7 | Tap-jacking / overlay deception | Malicious overlay over our consent screen steals an affirmative tap | TapTrap (animation-based, 76.3% of apps vulnerable); Anubis/Alien | `setFilterTouchesWhenObscured` + reject `FLAG_WINDOW_IS_OBSCURED` + `setHideOverlayWindows` on consent/sensitive screens (§7) | this |
| T8 | Overlay reads/leaks secure-field content | A11y service reads `node.text` of a password field; clipboard insertion routes result cross-app | Audit NEW DEFECT A | **Hard secure-field gate in the overlay**: no read, no chip, no clipboard touch (§3, §6, §7) | 02 / this |
| T9 | Silent clipboard destruction / leakage | Background a11y `clearPrimaryClip` on null read; result written to global clip | Audit confirmed bug #3 + NEW DEFECT A | Replace clipboard insertion with `ACTION_SET_TEXT`; `EXTRA_IS_SENSITIVE` + auto-clear if clipboard is ever written (§6) | 02 / this |
| T10 | Reviewer / OS perceives a11y abuse | Play accessibility policy; Android 17 APM block | Banking-trojan parallels | Optional, off-by-default, prominent in-app disclosure + affirmative consent; **prefer non-a11y redesign** (§7) | this / 02 |
| T11 | Code injection / DoS via parsed text | Pathological expression input | — (covered v1) | Pure parser, no `eval`/reflection, bounded recursion + step budget | 05 |
| T12 | Post-install asset/update tampering | Future signed dictionary/model/theme update over the wire | SwiftKey CVE-2015-2865 (again) | If ever added: cryptographically **signed + verified** payloads, never transport-trust, no background polling (§5, §10) | this |

**Non-goals / explicitly out of scope (state so reviewers don't expect them):** defeating a
fully root/system-compromised device; defeating a malicious *system* IME chosen by the user; and
"high-assurance PIN entry" guarantees (we mitigate screen-capture but do not claim to defeat it on
Android ≤11 where `FLAG_SECURE` is ~70% reliable).

---

## 2. Least-privilege permission policy (IME vs overlay)

The v1 policy (IME: bind permission only; overlay: opt-in, disclosed) stands. v2 makes it
**enforceable** and splits the two surfaces by distribution.

### 2.1 Target permission sets (the allowlist)

| Surface | Permission | Justification | Notes |
|---|---|---|---|
| IME (`:ime`, shipped in `:app`) | `BIND_INPUT_METHOD` (service-level `android:permission`) | Platform-required to be an IME | Declared on the service, not a `<uses-permission>` |
| IME | *(none else)* | — | No `INTERNET`, no `VIBRATE`† , no storage |
| Overlay (`:overlay`, **off-Play artifact** — see §2.3) | `BIND_ACCESSIBILITY_SERVICE` (service-level) | Platform-required to bind the a11y service | User-enabled only, never programmatic |
| Overlay | `SYSTEM_ALERT_WINDOW` *(only if the non-a11y redesign in §7.4 is chosen)* | Draw the result chip as a normal overlay | **Currently absent** — adding it requires an explicit decision (OQ-3) |

† **Haptics decision:** `VIBRATE` is a normal (non-dangerous) permission but is still a declared
capability users see. Recommended: use `HapticFeedbackConstants` / `View.performHapticFeedback`,
which does **not** require `VIBRATE`, instead of `Vibrator`. This keeps the keyboard's permission
list literally empty. Trade-off: `performHapticFeedback` is coarser than custom `VibrationEffect`
patterns; acceptable for a keyboard. If custom patterns are later required, that is a permission
addition gated by §10's allowlist guard.

### 2.2 Make the policy *enforced*, not aspirational

The current `manifestPermissionGuard` is a **denylist** over the **pre-merge** source manifest
(audit A5-build, severity high). Two defects: (a) any dangerous permission not on the hardcoded list
passes silently (`SYSTEM_ALERT_WINDOW`, `GET_ACCOUNTS`, `QUERY_ALL_PACKAGES`, `POST_NOTIFICATIONS`,
…); (b) a permission injected by a **library's merged manifest** is never seen. Rebuild requirement:

- **Invert to deny-by-default (allowlist).** Define `ALLOWED_PERMISSIONS` (effectively the
  service-bind permissions only; `SYSTEM_ALERT_WINDOW` only if §7.4 is adopted). Fail the build on
  **any** `<uses-permission>` not in the allowlist.
- **Parse XML, not substring.** Read declared permissions as parsed `<uses-permission android:name>`
  nodes, not `text.contains`.
- **Assert against the MERGED manifest of the release artifact** (`processReleaseManifest` output and
  a dump from the final AAB/APK), so library-injected permissions are caught. The source-manifest
  check is kept as a fast pre-check but is **not** the gate of record.

```
# Allowlist (deny-by-default). Anything else fails the build.
ALLOWED_PERMISSIONS = {
    # NB: the two BIND_* are service-level android:permission, not <uses-permission>,
    # so the uses-permission allowlist for the Play (:app) artifact is effectively EMPTY.
}
# Overlay off-Play artifact may add SYSTEM_ALERT_WINDOW iff §7.4 redesign is approved.
```

### 2.3 Distribution split (security consequence, not just packaging)

Per ADR-0001 and audit A5-build: there is currently **no separately-installable overlay artifact** —
`:overlay` is an android-library folded into the single `dev.tally` APK, which couples a sensitive
`canRetrieveWindowContent=true` service to the Play release. From a *permission-minimization and
review-risk* standpoint the rebuild must **physically separate** the surfaces:

- **Play artifact (`:app` + `:ime` + `core-math` + glue + design-system):** zero `uses-permission`,
  no a11y service, no `SYSTEM_ALERT_WINDOW`. This is the artifact the no-network Data Safety claim is
  made against.
- **Overlay artifact (off-Play direct APK, separate application module/flavor):** carries the a11y
  service (or the §7.4 non-a11y redesign). Built, signed, SBOM'd, and CVE-scanned **separately**
  (§5, §10), with its **own** Data Safety reasoning if ever listed anywhere.

The choice of *which* overlay architecture ships (a11y-declared-on-Play vs off-Play a11y vs non-a11y
redesign) is an open product decision — see **OQ-1** and `02-overlay-root-cause-and-redesign.md`.

---

## 3. Secure / password / no-personalized-learning field handling

This is the most direct privacy obligation of a keyboard and the canonical breach point. **Verdict
V7-secure-fields: confirmed** — the APIs and obligations below are real and binding. Build **one**
gate, share it, test it.

### 3.1 The single sensitive-field gate (IME)

Compute once in `onStartInput`/`onStartInputView` from `EditorInfo` and re-evaluate on every restart
(input type can change). A field is **sensitive** if **any** of these hold:

| Signal | API | Field |
|---|---|---|
| Text password | `TYPE_TEXT_VARIATION_PASSWORD` | mask `inputType & TYPE_MASK_VARIATION` under class `TYPE_CLASS_TEXT` |
| Visible password | `TYPE_TEXT_VARIATION_VISIBLE_PASSWORD` | same |
| Web password | `TYPE_TEXT_VARIATION_WEB_PASSWORD` | same |
| Numeric password (PIN) | `TYPE_NUMBER_VARIATION_PASSWORD` | mask under class `TYPE_CLASS_NUMBER` |
| No suggestions requested | `TYPE_TEXT_FLAG_NO_SUGGESTIONS` | flag on `inputType` |
| Incognito / no learning requested | `IME_FLAG_NO_PERSONALIZED_LEARNING` (API 26+) | `EditorInfo.imeOptions` |

```
fun isSensitiveField(info: EditorInfo): Boolean {
    val cls  = info.inputType and InputType.TYPE_MASK_CLASS
    val varn = info.inputType and InputType.TYPE_MASK_VARIATION
    val isPw =
        (cls == TYPE_CLASS_TEXT &&
            (varn == TYPE_TEXT_VARIATION_PASSWORD ||
             varn == TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
             varn == TYPE_TEXT_VARIATION_WEB_PASSWORD)) ||
        (cls == TYPE_CLASS_NUMBER && varn == TYPE_NUMBER_VARIATION_PASSWORD)
    val noSuggest = info.inputType and TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0
    val noLearn   = info.imeOptions and IME_FLAG_NO_PERSONALIZED_LEARNING != 0
    return isPw || noSuggest || noLearn
}
```

When `isSensitiveField(...) == true`, the IME **must**:

1. **Not learn** — no write to the user dictionary / n-gram / autocorrect history. (Binding obligation
   for `NO_PERSONALIZED_LEARNING`, not just password variations.)
2. **Not suggest / not autocorrect** — the math result *chip* may still be shown (it is computed, not
   stored, and never learned) **but** the general candidate strip shows no text; no next-word.
   Trade-off note: the math chip itself is allowed in password fields only if the result is **not**
   inserted via clipboard and **not** learned. **Recommended default: suppress the math chip entirely
   in sensitive fields** — the privacy clarity outweighs the rare "compute inside a password box" case.
3. **No key-preview popups, no gesture/glide trails** (T6).
4. **No clipboard write** of field-derived content; **no clipboard panel/history** surfaced (§6).
5. **No logging** of any field content or the fact-of-value (only a boolean "sensitive=true" at most).
6. **Show the incognito affordance** (Gboard-style indicator) so the user can see learning is off when
   `NO_PERSONALIZED_LEARNING` is set.

### 3.2 Secure-field handling (overlay)

The overlay has **no `InputConnection`** and therefore relies on `AccessibilityNodeInfo`. Hard gate
(audit NEW DEFECT A, T8): if **any** of the following are true, the overlay does **not read**
`node.text`, does **not show a chip**, and **never touches the clipboard**:

- `node.isPassword == true`, **or**
- the focused node's input type resolves to any password variation in §3.1, **or**
- `IME_FLAG_NO_PERSONALIZED_LEARNING` / incognito context is detectable on the field, **or**
- the field type cannot be confidently classified (fail closed).

This gate is independent of the insertion-strategy fix in `02-…` — it is a privacy requirement even if
the overlay is later cut.

### 3.3 Test obligation (binding)

An **instrumented test asserts zero writes** to the learning store / user dictionary while a password
field (each variation) is active, and asserts no candidate text and no clipboard write. This is a
release gate (§10), not an optional check. Mirror it in the overlay: an instrumented test asserts no
`node.text` read and no `setPrimaryClip` while a password field is focused.

---

## 4. No-network proof & Play Data Safety

The structural guarantee (no `INTERNET`) is v1's load-bearing claim. v2 makes the **proof** a gate and
fixes the merge-injection gap.

### 4.1 The proof is multi-layered and CI-enforced

| Layer | What it proves | How (rebuild requirement) |
|---|---|---|
| Source manifests clean | Our code declares no network perm | Fast pre-check (§2.2) |
| **Merged manifest of release AAB clean** | No *transitive* SDK injected `INTERNET`/`ACCESS_NETWORK_STATE`/network-capable perms | **CI gate parses the final AAB's merged manifest and FAILS the build** if any network permission appears (the real gate) |
| No network-capable SDK present | No analytics/crash SDK that *could* transmit | SBOM review (§5) + dependency audit; forbid analytics/crash SDKs outright |
| Runtime: zero egress under instrumentation | App opens no socket | Pen-test / MASTG run with proxy + (optionally) `StrictMode.detectNetwork()` in debug as a tripwire; expect zero connections (§9) |
| User-verifiable | Anyone can confirm post-install | OS permission screen shows no network; a firewall app shows no traffic |

**StrictMode note:** a debug-only `VmPolicy`/`ThreadPolicy` with `detectNetwork()` is a cheap
self-test tripwire (it will flag any accidental socket during instrumentation). It is **not** a
release control and must not appear in release builds.

### 4.2 Play Data Safety (must be literally true)

Declare **"No data collected"** and **"No data shared"** for the Play (`:app`) artifact. This is only
true if it includes **third-party SDK behaviour** — Google requires reflecting any SDK data
collection. A keyboard that ships an analytics/crash SDK with network access **cannot** make this
claim; therefore such SDKs are forbidden (§5). Keep the declaration audit-true against the SBOM.

> The off-Play overlay artifact, if it reads screen content, must have its **own** honest privacy
> narrative even though it is not on Play. It still collects no data and transmits nothing, but the
> disclosure framing differs because it uses the accessibility API (§7).

---

## 5. Supply-chain hardening

A poisoned transitive dependency in a keyboard *is* a keylogger (T5, CVE-2015-2865). v1 already
ships strict checksum verification, lockfiles, reproducible builds, an SBOM, and R8 log stripping.
v2 closes the audited gaps and makes the SBOM *actionable*.

### 5.1 Current state vs required (from audit A5-build)

| Control | Current | Required (rebuild) |
|---|---|---|
| Dependency checksum verification | ✅ strict, `verify-metadata=true` | keep |
| Dependency **signature** verification | ❌ `verify-signatures=false` (TOFU checksums only) | **Enable `verify-signatures=true`** with a `<trusted-keys>` block for publishers that sign (AndroidX, Google, JetBrains, Kotlin); keep checksum fallback for unsigned artifacts. Trade-off: more maintenance on dependency bumps — accept it for a keyboard. |
| Dependency locking | ✅ lockfiles | keep; **no dynamic `+`/range versions anywhere** (CI grep gate) |
| Reproducible builds | ✅ flags set, CI byte-diffs **unsigned** APK | keep; **document** that reproducibility is verified pre-signing and ship the strip-and-compare recipe (`apksigner`/`zipalign` + `diffoscope`) so external verifiers can re-derive the published signed artifact |
| SBOM | ✅ CycloneDX for `:app` | keep; **add SBOM for the off-Play overlay artifact** when it exists |
| **CVE / vulnerability scan** | ❌ none | **Add OSV-Scanner (or Trivy) over the CycloneDX SBOM in CI; fail on HIGH/CRITICAL.** The SBOM is already produced and currently unused. |
| Gradle wrapper pinning | ❌ no `distributionSha256Sum` | **Add `distributionSha256Sum`** to `gradle-wrapper.properties` (`gradle wrapper --gradle-version <v> --gradle-distribution-sha256-sum <hash>`) |
| R8 log stripping | ✅ `-assumenosideeffects` on `Log.v/d/i` + `ConsoleKt` | keep; extend to any custom logger wrapper; **keep `Log.w/Log.e`** but enforce the no-sensitive-content rule (those survive stripping) |
| `allowBackup` / data extraction | ❌ absent → defaults `true` (prefs cloud-backed) | **Set `android:allowBackup="false"`** + `dataExtractionRules`/`fullBackupContent` excluding all keyboard state; add to a guard so it cannot regress |

### 5.2 Minimal, pinned, justified

Every dependency is a liability. Keep the dependency set as small as possible, prefer first-party /
AOSP-lineage permissively-licensed code (Apache-2.0/MIT/BSD — **GPL/LGPL excluded**, closed-source
product), pin exact versions, and **audit every dependency for `INTERNET` capability** as part of the
merged-manifest gate (§4). Any library that pulls a network transport is rejected on principle.

### 5.3 Post-install update channel (future-proofing T12)

If the product *ever* ships updatable assets (dictionaries, models, themes) post-install, each payload
**must be cryptographically signed and verified before use** — transport security alone is
insufficient (CVE-2015-2865), and there must be **no background update polling**. This is a future
constraint to record now, not a current feature. Adding network for updates also breaks the
no-`INTERNET` claim and is a separate ADR (§4, OQ-4).

### 5.4 License compliance is a security gate too

The closed-source constraint means a copyleft dependency is a legal *and* trust failure. Add a CI
license-scan (e.g. over the SBOM) that **fails on any non-permissive license** (anything outside
Apache-2.0 / MIT / BSD / similarly permissive). This includes any reused IME/dictionary lineage
(ADR-0001's Apache-2.0 AOSP LatinIME base).

---

## 6. Clipboard hygiene

Keyboards grow clipboard features (history, suggestions) that quietly become exfiltration/exposure
surfaces for passwords and OTP codes copied from password managers. Rules:

1. **`minSdk` decision.** v1/current `minSdk` is **26**. minSdk **29** is *recommended* because it
   blocks background apps from reading the foreground clipboard (pre-29 they can) and aligns with
   several platform mitigations. **Trade-off:** raising to 29 drops Android 8.0–9 devices.
   **Recommendation: keep minSdk 26 for reach, but treat pre-29 clipboard as untrusted** — never read
   the clipboard on pre-29 except on explicit user action, and never auto-surface clipboard history.
   This is **OQ-2** for the master plan.
2. **Insertion strategy (overlay):** clipboard-paste insertion is **forbidden** as the primary path
   (audit bug #3, T9). Use `ACTION_SET_TEXT` with reconstructed field text + `ACTION_SET_SELECTION`
   (owned by `02-…`). The security wins: no cross-app channel, no clipboard wipe, no Android 12+
   clipboard-access toast. **Never** call `clearPrimaryClip()` on a null previous read (the silent
   user-clipboard destruction bug).
3. **If the IME ever writes sensitive content to the clipboard** (e.g. a user-initiated "copy result"):
   flag the `ClipData` with `ClipDescription.EXTRA_IS_SENSITIVE` (API 33+ constant; string key
   `"android.content.extra.IS_SENSITIVE"` for older) so the OS obfuscates the preview, and
   **auto-clear** after a short timeout (`clearPrimaryClip` on API 28+, or overwrite with empty
   `ClipData`) since the OS only auto-expires on 13+.
4. **Never** persist clipboard content, learn from it, feed it to suggestions, or log it.
5. **Suppress clipboard previews/history while a secure field is focused** (§3).
6. **No silent reads.** Android 12+ shows a clipboard-access toast; design so the keyboard never reads
   the clipboard except on explicit user action — an unexpected toast is itself a trust failure.

---

## 7. Overlay trust, consent & tap-jacking

The accessibility overlay is the highest-scrutiny, highest-perceived-risk capability. **Verdict
V2-play-policy: partly-true** — Play does **not** flatly ban non-accessibility uses; a *passive,
rule-based, read-and-display* math overlay is **declarable**, not prohibited. But it faces a tougher
review (Jan 28 2026 enforcement tightening), suspension risk if undeclared/deceptive, and an
**Android 17 OS-level block for Advanced-Protection-Mode users**. Plan accordingly.

### 7.1 Consent & disclosure (binding)

- Overlay/a11y is **optional and OFF by default**. The keyboard is fully functional without it.
- **Prominent in-app disclosure + affirmative consent** *before* enabling — inside the app, in normal
  usage, stating exactly what is accessed, why, and how to revoke. **Not** buried in a privacy policy.
- **Obvious in-app revoke** + deep link to Accessibility settings.
- **Do not set `isAccessibilityTool="true"`.** A math-result overlay does not genuinely serve a
  disability need; claiming the exemption invites takedown. Take the **declaration path** instead.
- Keep the service **strictly passive / deterministic** — it reads and draws; it never "autonomously
  initiates, plans, and executes actions" (the only category Play *strictly* prohibits).

### 7.2 Tap-jacking defense for the consent screen and any sensitive screen (T7)

The consent screen is itself an attack target (TapTrap). On the consent activity and any in-app screen
showing learned/personal data:

- `android:filterTouchesWhenObscured="true"` (or `setFilterTouchesWhenObscured(true)`) on the affirmative
  controls — drops touches delivered through an overlay.
- **Reject taps flagged** `FLAG_WINDOW_IS_OBSCURED` / `FLAG_WINDOW_IS_PARTIALLY_OBSCURED` in the touch
  handler.
- `Window.setHideOverlayWindows(true)` (API 31+) while the consent screen is foreground.
- On Android 16+, set `android:accessibilityDataSensitive="true"` on the affirmative views so a
  malicious a11y service cannot read/act on them.
- Do **not** export activities that don't need it (the consent activity is already `exported="false"`
  — keep it).

### 7.3 Minimal a11y service configuration

The current `overlay_accessibility_service.xml` lacks flags and uses `canRetrieveWindowContent=true`
broadly. For correctness see `02-…`; the **security** requirements here:

- Request the **minimum** event types needed; drop `typeWindowStateChanged` as a blur signal (it is
  also the source of the chip-churn bug).
- Keep `canRetrieveWindowContent` only if genuinely required, and document that the tree is read
  **only** to find the expression near the caret — never traversed/harvested.
- Add `flagRetrieveInteractiveWindows` (multi-window focus correctness) — note this is a *capability*
  flag, so re-confirm it is the minimum needed.

### 7.4 Preferred architecture: avoid the accessibility API entirely (recommended)

Per V2-play-policy's implications, the lowest-risk production option for a *math-result* overlay is to
**not use `AccessibilityService` at all**: accept input via share-sheet/clipboard (user-initiated) and
draw the result via a normal `SYSTEM_ALERT_WINDOW` overlay, **or** use `MediaProjection` + on-device
OCR. This is fully Play-compliant, survives the Android 17 APM block, and sidesteps the entire a11y
review gauntlet. **Trade-off:** it loses the "automatic, in-place, while you type" magic and adds
`SYSTEM_ALERT_WINDOW` (a visible permission) and/or a `MediaProjection` consent. Because Tally's IME is
the *real* insertion surface (it owns the `InputConnection`), the recommendation is:

> **Make the IME the sole automatic insertion surface; ship the overlay only as a best-effort,
> off-Play, opt-in assist for *other* keyboards, and prefer the non-a11y redesign if the overlay
> ships at all.** The choice between (a) off-Play a11y overlay, (b) non-a11y redesign, and (c) cutting
> the overlay is **OQ-1** for the master plan.

### 7.5 Screen-capture hygiene (T6)

Independent of the overlay: suppress key-preview popups and gesture trails while a secure field is
focused; apply `FLAG_SECURE` to any in-app screen that displays learned/personal data (e.g. a
"manage learned words" screen). Note `FLAG_SECURE` is incomplete (does not stop overlays — pair with
`setHideOverlayWindows`; ~70% reliable on Android ≤11) so it is a mitigation, not a guarantee.

---

## 8. On-device personalization privacy

Personalization (user dictionary, n-gram/learning model, autocorrect history) is the most sensitive
**at-rest** data the keyboard holds — effectively a derived record of what the user typed. For a
no-network keyboard the entire ML lifecycle stays on-device. Requirements:

1. **At rest, encrypted, app-private.** Store learning data in app-private storage encrypted with a
   Keystore-backed key (Jetpack Security `EncryptedFile` / Keystore AES). **Never** on shared/external
   storage. **Excluded from cloud backup** via `allowBackup=false` + `dataExtractionRules` (§5).
   *(License note: confirm the chosen crypto library is permissive; the platform `Keystore` + AOSP
   crypto avoid third-party-license risk entirely and are preferred.)*
2. **Never learn from sensitive fields** (§3) — the cross-app harvest (ESORICS'15) and SwiftKey's
   cross-user bleed are the cautionary cases.
3. **User controls:** view, **export**, and **delete** all learned data; a "disable learning" toggle;
   a one-tap "clear typing history" action. These are product features but are **privacy-binding**.
4. **No cross-user / cross-device sync.** Per-device model only. App-private storage clears on
   uninstall — keep it that way.
5. **No federated learning / DP without a separate ADR.** The privacy-preserving server pattern
   (DP-FTRL) requires network and breaks the clean no-`INTERNET` story. If ever needed it must be
   opt-in, separately disclosed, and trigger a Data Safety update (OQ-4).
6. **Protect learned data from cross-app reads (T4):** never expose it via any exported component,
   content provider, or queryable surface; never include it in logs or crash artifacts.

---

## 9. Audit & pen-test plan

Anchor verification to **OWASP MASVS (L2 sensitive-data app + R resilience)**, executed via **OWASP
MASTG** test cases (Storage, Crypto, Network, Platform, Code). Keyboard/overlay-specific scope:

| Area | Test | Pass criterion |
|---|---|---|
| Network | Merged manifest of release AAB + runtime capture (proxy/Frida) under instrumentation | **Zero** network permission; **zero** outbound connections |
| Secure fields (IME) | UI-automate each password variation; observe learning store, candidate strip, clipboard, logs | No learning write, no candidate text, no clipboard write, no log entry |
| Secure fields (overlay) | Focus password field; observe node reads + clipboard | No `node.text` read, no chip, no `setPrimaryClip` |
| Logging | String-dump the release DEX; capture logcat + crash artifacts during fuzz | No keystroke/field/clipboard strings; `Log.v/d/i` calls stripped |
| Tap-jacking | Craft a `TYPE_APPLICATION_OVERLAY` over the consent screen | Affirmative taps are filtered/rejected |
| A11y consent/revoke | Walk enable → use → revoke; inspect service event/feedback config | Optional, off by default, disclosed, revocable, least-privilege events |
| At-rest storage | Inspect learning store on device | Encrypted, app-private, excluded from backup |
| Supply chain | SBOM → OSV/Trivy scan; confirm dependency verification enforced | No HIGH/CRITICAL CVE; verification strict (+signatures) |
| Reproducibility | Re-derive unsigned APK; strip-and-compare published signed APK | Byte-identical pre-signing |

**Cadence:** third-party pen-test **before first release** and on any change to the
network / learning / accessibility surface; **continuous SBOM/CVE scanning in CI**; an
**independent security audit under NDA** (v1's substitute for open source) published as an
attestation. Consider reproducible-build verification as the public trust mechanism the closed source
otherwise denies.

---

## 10. Security CI gates (must all pass to release)

Each gate **fails the build** (not warns). Wire into `09-build-release.md`'s pipeline and the
`securityGuards` aggregate task. Gates marked **(new)** do not exist yet (audit A5-build).

| Gate | Asserts | Notes |
|---|---|---|
| `manifestPermissionGuard` v2 **(new behaviour)** | **Allowlist** over **merged** manifest of release AAB, parsed as XML | Replaces the denylist/substring/pre-merge guard (§2.2) |
| `noNetworkGuard` **(new)** | No `INTERNET`/`ACCESS_NETWORK_STATE`/network-capable perm in merged AAB; no network-transport dependency in SBOM | The load-bearing claim (§4) |
| `secureFieldTest` **(new)** | Instrumented: zero learning/candidate/clipboard activity in each password variation (IME **and** overlay) | §3.3 — device/emulator job |
| `noTextLoggingGuard` (harden) | No `Log.*`/`println` of field text; close block-comment / aliased-logger evasions | R8 strip is the real backstop |
| `r8LogStripVerify` **(new)** | Release DEX contains no `Log.v/d/i` calls / debug strings | String-dump check |
| `dependencyVerification` (harden) | Strict checksum **+ signature** (`verify-signatures=true`) | §5.1 |
| `cveScan` **(new)** | OSV/Trivy over CycloneDX SBOM; fail HIGH/CRITICAL | SBOM already produced |
| `licenseScan` **(new)** | No non-permissive (copyleft) license | §5.4 |
| `wrapperPinGuard` **(new)** | `distributionSha256Sum` present and matches | §5.1 |
| `backupHardeningGuard` **(new)** | `allowBackup="false"` + data-extraction rules present in merged manifest | §5.1 |
| `signedReleaseGuard` **(new)** | Release artifact is signed (`apksigner verify`); **no** fallback to unsigned APK | Off-Play APK's only trust anchor |
| `reproducibilityCheck` (keep) | Unsigned APK byte-identical across two builds | Document pre-signing scope |
| `dynamicVersionGuard` **(new)** | No `+`/range versions in lockfiles/catalog | §5.1 |

---

## 11. Hardening CHECKLIST (executable by agents)

Ordered, concrete, each item independently verifiable. "Owner: 02/03/09" means coordinate with that
document; the security contract is owned here.

1. **Invert the manifest guard to deny-by-default**: parse `<uses-permission>` as XML; allowlist
   (effectively empty for `:app`); fail on anything else. (§2.2)
2. **Make the guard run against the MERGED manifest** of the release AAB/APK, not the source manifest.
   Keep the source check as a fast pre-check only. (§2.2, §4.1)
3. **Add `noNetworkGuard`**: fail the build if `INTERNET`/`ACCESS_NETWORK_STATE`/any network-capable
   permission appears in the merged AAB, or any network-transport dependency appears in the SBOM. (§4.1)
4. **Set `android:allowBackup="false"`** + `android:dataExtractionRules`/`fullBackupContent` excluding
   all keyboard state on `:app`'s `<application>`; add `backupHardeningGuard`. (§5.1, §8)
5. **Implement `isSensitiveField(EditorInfo)`** (all password variations + `NO_SUGGESTIONS` +
   `NO_PERSONALIZED_LEARNING`) as the single IME gate. (§3.1) — Owner: 03
6. **In sensitive mode (IME):** disable learning, suggestions, autocorrect, key-preview popups,
   gesture trails, clipboard write/history, and (recommended) suppress the math chip; show the
   incognito indicator. (§3.1) — Owner: 03
7. **Add the secure-field gate to the overlay**: if `isPassword` / password input type / no-learning /
   unclassifiable → no read, no chip, no clipboard touch. (§3.2) — Owner: 02
8. **Forbid clipboard-paste insertion in the overlay**; use `ACTION_SET_TEXT` + `ACTION_SET_SELECTION`;
   never `clearPrimaryClip()` on a null read. (§6) — Owner: 02
9. **If the IME ever writes sensitive clipboard content:** set `EXTRA_IS_SENSITIVE` and auto-clear after
   a timeout. (§6)
10. **Never read the clipboard except on explicit user action**; suppress clipboard history in secure
    fields; never persist/learn/log clipboard content. (§6)
11. **Enable dependency signature verification** (`verify-signatures=true` + `<trusted-keys>` for signing
    publishers; checksum fallback for unsigned). (§5.1)
12. **Add `cveScan`** (OSV-Scanner/Trivy over the CycloneDX SBOM; fail HIGH/CRITICAL). (§5.1, §10)
13. **Add `licenseScan`** (fail on any non-permissive/copyleft license). (§5.4, §10)
14. **Pin the Gradle wrapper** via `distributionSha256Sum`. (§5.1)
15. **Forbid dynamic/range dependency versions** (`dynamicVersionGuard`). (§5.1)
16. **Harden `noTextLoggingGuard`** (block comments, aliased loggers) and **add `r8LogStripVerify`**
    (string-dump the release DEX). (§5.1, §10)
17. **Extend R8 `-assumenosideeffects`** to any custom logger wrapper; keep `Log.w/e` but enforce the
    no-sensitive-content rule. (§5.1)
18. **Physically split distribution**: Play artifact carries no a11y service / no `SYSTEM_ALERT_WINDOW`;
    overlay is a separate signed off-Play artifact with its own SBOM + CVE/license scans. (§2.3) — Owner: 09
19. **Add `signedReleaseGuard`** (hard-fail if release secrets absent; `apksigner verify`; remove any
    unsigned-APK fallback in checksum/release steps). (§10) — Owner: 09
20. **Overlay consent screen hardening**: `filterTouchesWhenObscured`, reject `FLAG_WINDOW_IS_OBSCURED`
    taps, `setHideOverlayWindows`, (16+) `accessibilityDataSensitive`; keep consent activity
    `exported="false"`. (§7.2)
21. **Overlay consent flow**: optional, OFF by default, prominent in-app disclosure + affirmative
    consent, obvious revoke + settings deep link; **do not** set `isAccessibilityTool="true"`. (§7.1)
22. **Minimize the a11y service config**: minimum event types, drop `typeWindowStateChanged` as blur,
    justify `canRetrieveWindowContent`, add only the flags needed. (§7.3) — Owner: 02
23. **Suppress key-preview popups / gesture trails in secure fields; `FLAG_SECURE`** on in-app screens
    showing learned data; pair with `setHideOverlayWindows`. (§7.5)
24. **Encrypt learning data** (Keystore-backed) in app-private storage, excluded from backup; **never
    learn from sensitive fields**; ship view/export/delete + disable-learning + clear-history controls;
    no cross-user/device sync. (§8) — Owner: 03
25. **Add the instrumented secure-field tests** (IME + overlay) as a release gate (`secureFieldTest`),
    plus a debug-only `StrictMode.detectNetwork()` tripwire. (§3.3, §4.1, §10)
26. **Record future-proofing constraints**: any post-install asset/model update must be signed +
    verified with no background polling; federated learning/DP requires a new ADR + Data Safety update.
    (§5.3, §8)
27. **Run a MASVS-L2+R / MASTG assessment** before first release and on any change to the
    network/learning/accessibility surface; keep CVE/SBOM/no-network checks continuous in CI;
    commission and publish an independent NDA audit attestation. (§9)

---

## 12. Open issues for the master plan (`00-master-plan.md`)

- **OQ-1 — Overlay architecture & distribution.** Choose: (a) off-Play a11y overlay (declaration path),
  (b) **non-a11y redesign** (share-sheet/`SYSTEM_ALERT_WINDOW` or `MediaProjection`+OCR — recommended,
  Play-compliant, APM-proof), or (c) cut the overlay. This determines the entire Play-compliance path,
  whether `SYSTEM_ALERT_WINDOW` enters the allowlist, and the SBOM/signing topology. (§2.3, §7.4)
- **OQ-2 — `minSdk`.** Keep 26 (reach) and treat pre-29 clipboard as untrusted, or raise to 29 for
  background-clipboard protection and platform-mitigation alignment (drops Android 8.0–9). Recommended:
  keep 26 + untrusted-clipboard policy. (§6)
- **OQ-3 — Haptics permission.** Use `performHapticFeedback` (no `VIBRATE`, recommended) vs custom
  `VibrationEffect` (requires `VIBRATE`, richer). (§2.1)
- **OQ-4 — Any future network feature** (cloud sync, voice, federated learning, telemetry, signed asset
  updates). If ever yes, the structural no-`INTERNET` proof is replaced by a narrower opt-in, disclosed
  model + Data Safety change + ADR. Default: **no**. (§4, §5.3, §8)
- **OQ-5 — Independent audit & public trust mechanism.** Commission an NDA audit attestation; decide
  whether to publish reproducible-build verification instructions as the closed-source substitute for
  open-source trust. (§9)
- **OQ-6 — Math chip in password fields.** Recommended default: **suppress entirely** in sensitive
  fields. Confirm vs. allowing a compute-only, no-learn, no-clipboard chip. (§3.1)
