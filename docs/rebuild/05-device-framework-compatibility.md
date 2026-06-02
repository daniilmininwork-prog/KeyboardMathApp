# 05 — Device, Framework & Form-Factor Compatibility

> **Scope.** This document answers the stakeholder ask *"optimize for as many devices/frameworks as physically possible"* concretely, for the Tally rebuild. It defines the SDK/version strategy, OEM quirk handling, form-factor and window-mode behavior, RTL/locale coverage, the cross-platform framework decision, and the concrete compatibility + CI test matrix.
>
> **Lane boundaries.** The overlay redesign lives in `02-overlay-root-cause-and-redesign.md`; IME service architecture, the Canvas key-plane, InputConnection discipline, and per-key accessibility live in `03-ime-architecture-v2.md`; feature scope and phasing live in `04-feature-matrix-and-roadmap.md`; privacy/security enforcement lives in `06-security-privacy-hardening.md`. This document references those by filename and does **not** re-derive their content. It owns the *compatibility surface*: what hardware/OS/window-mode/locale combinations the rebuild must run on, and how we prove it.
>
> **Hard constraints (inherited, non-negotiable).** Closed-source → permissive deps only (Apache-2.0/MIT/BSD; no GPL/LGPL). Privacy-first → **no `INTERNET` permission**, fully on-device, no telemetry. Math feature built once in `core-math`, reused by both surfaces.
>
> **Grounding (verified against the repo).** Current state: `minSdk = 26`, `compileSdk = 36`, app `targetSdk = 36`, classic Views (no Compose), Material 1.12, Kotlin 2.1.21 / AGP 8.8. The IME declares a single `<subtype>` (`en_US`, ascii-capable). The IME manifest carries no `<uses-permission>` and no `INTERNET`. The overlay a11y `service.xml` declares `canRetrieveWindowContent="true"` but **no `flagRetrieveInteractiveWindows`**, `feedbackGeneric`, `notificationTimeout="100"`. These are the baselines the recommendations below move from.

---

## 1. Android Version & minSdk Strategy

### 1.1 Decision

| Knob | Keep / Change | Value | Rationale |
|---|---|---|---|
| `minSdk` | **KEEP** | **26** (Android 8.0 Oreo) | Already ≈99% active-device reach in 2026; raising it buys marginal API convenience at real reach cost in exactly the budget/emerging-market segment a free keyboard wins. |
| `compileSdk` | KEEP | **36** (Android 16) | Compile against latest stable; required for adaptive APIs and current `WindowInsets`/`WindowManager` surfaces. |
| `targetSdk` | KEEP (and extend to all modules) | **36** | Opt **in** to the Android 16 adaptive mandate now (see §1.3). Currently only `:app` sets `targetSdk`; `:ime`/`:overlay` inherit the platform default — **set `targetSdk = 36` explicitly in every Android module** so behavior is uniform and auditable. |

**Do NOT raise `minSdk` to 28/29 for maintenance/cleanliness reasons.** The cost of supporting 26 vs 29 is a *handful* of `SDK_INT` branches (enumerated in §1.2), not a structural burden. Keyboards are a pure-reach product: every dropped device is a lost user with near-zero re-acquisition path. The trade clearly favors reach. *(See §1.5 open issue for the launch-market dependency.)*

### 1.2 API-gating approach

Gate newer capabilities behind `Build.VERSION.SDK_INT` and AndroidX backports rather than lifting the floor. The complete inventory of version branches the rebuild needs:

| Capability | Gate | Below-gate fallback |
|---|---|---|
| Multi-display / per-display IME (`updateDisplay`, follow-focus) | **API 29+** | Single-display only; no extra code path on 26–28 |
| `getSurroundingText()` single-IPC editing-context read | **API 31+** | `getTextBeforeCursor` + `getTextAfterCursor` + `getSelectedText` (see `03-ime-architecture-v2.md` for the editing-context mirror) |
| Inline autofill (`supportsInlinedSuggestions`, `onCreateInlineSuggestionsRequest`) | **API 30+** | No inline row; system autofill dropdown stays the host's responsibility |
| `WindowInsetsAnimation` cooperative show/hide | **API 30+** | Report stable height; no synchronized animation on 26–29 |
| Stylus handwriting ink window (`onStartStylusHandwriting`) | **API 34+** | Feature absent (deferred per `04-feature-matrix-and-roadmap.md`) |
| A11y composing-vs-commit (`getTextChangeTypes()`) | **API 36/17+** | Debounce + read-back verify (the universal path; see `02-overlay-root-cause-and-redesign.md`) |
| Foldable posture / hinge (`FoldingFeature`, `WindowLayoutInfo`) | **API 26+ via Jetpack WindowManager** | Library backports to 26 — no gate needed |
| Window size classes | **API 26+ via `androidx.window`** | Library-computed; no gate |

Backport vehicles (all permissive, Apache-2.0): `androidx.core`, `androidx.appcompat`, **`androidx.window`** (FoldingFeature, WindowLayoutInfo, size classes), `androidx.customview` (`ExploreByTouchHelper` for per-key a11y — owned by `03`). Centralize gates in a single `PlatformCapabilities` object so no `SDK_INT` literal is scattered through feature code:

```kotlin
// sketch only — not an implementation
object PlatformCapabilities {
    val hasMultiDisplayIme   = Build.VERSION.SDK_INT >= 29
    val hasSurroundingText   = Build.VERSION.SDK_INT >= 31
    val hasInlineAutofill    = Build.VERSION.SDK_INT >= 30
    val hasInsetAnimation    = Build.VERSION.SDK_INT >= 30
    val hasStylusHandwriting = Build.VERSION.SDK_INT >= 34
    val hasA11yTextChangeType = Build.VERSION.SDK_INT >= 36
}
```

### 1.3 Adopt the Android 16 adaptive mandate NOW

For apps targeting API 36, on displays with **smallest width ≥ 600dp** the system **ignores** `android:screenOrientation`, `setRequestedOrientation()`, `resizeableActivity=false`, and min/max aspect ratio — the app must fill the window in any orientation. The opt-out disappears entirely at `targetSdk 37`. This is a *shipping platform behavior*, not a recommendation, and it is doubly load-bearing for a keyboard:

1. **Host apps you render into** will be freely resized → the IME input view must reflow at arbitrary widths/heights.
2. **Your own companion activities** (`MainActivity`, `OnboardingActivity`, `SettingsActivity`, `AboutActivity`) must be adaptive — no fixed orientation, no `resizeableActivity=false`.

**Mandatory consequences for the rebuild:**
- The IME input view is **width-driven** off `WindowSizeClass` (compact / medium / expanded). **Never** hardcode key sizes to a fixed-dp screen. The current `ime` module's fixed `54dp` rows and single static English layout are non-compliant and must be replaced by a width-class-driven layout engine (owned by `03`).
- Re-layout on **every** `onConfigurationChanged`. Never cache pixel sizes across configuration or display changes.
- All four companion activities ship as fully resizable; verify on tablet/foldable/DeX (§7).

### 1.4 Multi-display IME (API 29+) — correctness rules

Android uses **one** system IME instance that *follows focus between displays*; there is no per-display instance. On focus change to a non-default display the system **unbinds then rebinds** the service: `attachToken()`/`updateDisplay()` refresh context, `addView()` rebuilds the keyboard for the target display, and `onConfigurationChanged()` fires with new metrics. Rules:
- Treat **`onConfigurationChanged` + re-`addView`** as the single source of truth for input-view sizing. Never cache pixel sizes across display changes.
- The system will **not** show an IME on virtual displays it does not own (anti-keylogging protection). Do not attempt to force it; handle the "no input view on this display" case gracefully.
- Verify on a secondary virtual display and on DeX (§7).

### 1.5 Open issue surfaced to master plan

- **Launch-market mix** determines how strongly the keep-26 call holds. If India/SEA/LatAm are primary, device longevity *strengthens* the case for 26. If the launch is Pixel/flagship-first in mature markets only, 28 becomes defensible. Recommend **keep 26 regardless** (reach is cheap to retain), but the architect should confirm in `00-master-plan.md`.

---

## 2. OEM Matrix & Quirks

Pixel is the AOSP reference and the **primary dev/CI baseline** (least friction, closest to documented behavior). **Samsung is the highest-volume real-world target and the highest-risk** — it is the OEM whose deviations break the current overlay (see `02-overlay-root-cause-and-redesign.md` for the full Samsung root-cause). Xiaomi/Oppo are the background-policy risk.

| OEM / Skin | Default IME & posture | Primary risks for Tally | Required mitigation (this plan) |
|---|---|---|---|
| **Pixel (AOSP)** | Gboard | None structural — reference baseline | Use as the "should always pass" gate; any Pixel-only failure is our bug, not an OEM quirk |
| **Samsung One UI** (`com.samsung.android.honeyboard`) | Samsung Keyboard | (1) Honeyboard misuses `getExtractedText()` as a live state getter → text duplication / first-letter repeat on a subset of Galaxy models; (2) aggressive composing-region save/restore → duplication when text is set programmatically; (3) tall stacked chrome (toolbar + number row + suggestion strip + split) → occludes bottom-anchored overlays (overlay z-orders *below* the IME); (4) clipboard read/write toasts (Android 13+); (5) `textSelectionEnd` frequently `-1` | Insertion path must `finishComposingText` → set selection → atomic `ACTION_SET_TEXT` → **read-back verify** (owned by `02`). Eliminate clipboard from the insertion path entirely (also a privacy win — `06`). Anchor overlay via live IME insets *above* the keyboard, never a fixed height. If we ever own an `InputConnection`, implement `getExtractedText()` to return a real snapshot. **Test Samsung-broad, not "one Samsung phone"** (§7). |
| **Xiaomi MIUI / HyperOS** | Bundled default; can revert active IME | Most aggressive background-kill (flagged on dontkillmyapp); may require "Modify system settings" / special-access toggles; can silently revert the default keyboard | Minimize background/service footprint (see §2.1); OEM-aware onboarding deep-link to the correct Settings panels + battery-exemption flow; never rely on long-lived background work |
| **Oppo / OnePlus ColorOS** | Bundled default | ColorOS 14.x tightened background permissions; some third-party keyboards need a manual battery-optimization exemption | Same minimal-footprint + setup-guidance approach as Xiaomi |

### 2.1 Cross-OEM architectural rules

1. **Minimal background footprint.** Tally has a structural advantage: it is **fully on-device with no networking**, so there is *no dictionary sync, no telemetry upload, no background work to be killed*. This neutralizes the single largest third-party-keyboard support burden (predictions/sync stopping after an OEM kill). Preserve this: any learned-dictionary or personalization persistence (per `04`) must be opportunistic/foreground and survive process death via plain on-disk state — never a long-lived background service. *(Enforcement detail: `06-security-privacy-hardening.md`.)*
2. **OEM-aware onboarding.** `OnboardingActivity` must detect manufacturer (`Build.MANUFACTURER`) and surface the correct deep-link flow for (a) setting Tally as the default keyboard and (b) battery-optimization exemption on Samsung/Xiaomi/Oppo. Treat "keyboard won't stay default" and "predictions stopped" as the dominant *real-world* ticket classes and design the setup wizard to pre-empt them.
3. **Never assume AOSP keyboard geometry.** All overlay inset math is computed from live `WindowInsets.Type.ime()` height, not a constant — Honeyboard with full chrome is materially taller than AOSP.

---

## 3. Form Factors

Each form factor breaks a *different* naive assumption (fixed width, no hinge, no cursor, soft-keyboard-always-visible). Covering them is the bulk of "as many devices as physically possible." Every layout is driven from `WindowSizeClass` + Jetpack `WindowManager`; nothing is driven from physical screen metrics.

| Form factor | What breaks naively | Required behavior |
|---|---|---|
| **Phone (compact)** | — | Baseline docked layout. |
| **Tablet / large screen (expanded)** | Phone layout stretched full-width is un-reachable by thumbs | Ship a **split keyboard** (keys mirrored left/right, à la Gboard split) at expanded width. **Never** stretch a phone layout edge-to-edge. |
| **Foldable (posture-aware)** | Drawing keys under the hinge; ignoring book/tabletop posture | Use `FoldingFeature` (`state` FLAT/HALF_OPENED, `orientation`, `isSeparating`, `occlusionType`) + `WindowLayoutInfo` to detect posture and hinge bounds. Offer **split-on-unfold**; **avoid drawing interactive keys under the hinge**. Support landscape-foldable / trifold breakpoints as expanded-width cases. |
| **Chromebook / Samsung DeX / Android 16 Desktop Mode** | Assuming touch-only, fixed window, no cursor | Android 16 Desktop Mode is built on DeX foundations. IME runs in **freely resized windows** with a real cursor, right-click, drag-drop, desktop shortcuts. Input view must resize on every `onConfigurationChanged`; never cache display-specific sizing (ties to §1.4 multi-display). |
| **Hardware / physical keyboard** | Assuming the soft IME is always visible | Android contract **hides the soft IME** when a hardware keyboard is attached (`onEvaluateInputViewShown()` returns false). **Do not fight this.** But: keep handling key events and IME actions, and offer an **optional "show suggestion strip even with hardware keyboard" affordance** (call `updateInputViewShown()` on state change). This is exactly where Tally's *math suggestion* still adds value with a physical keyboard. |
| **Multi-window / multi-display** | Caching screen metrics; assuming a persistent input view | Single-IME-follows-focus model (§1.4). Recompute from the *available window*, not the physical screen. |

**Posture/size detection sketch** (drives every layout decision; not an implementation):

```kotlin
// WindowManager + size class feed one LayoutMode resolver
enum class LayoutMode { DOCKED_COMPACT, SPLIT_EXPANDED, FLOATING, ONE_HANDED, HINGE_AVOID }

fun resolveLayout(size: WindowSizeClass, fold: FoldingFeature?, mode: UserMode): LayoutMode = when {
    fold?.isSeparating == true            -> LayoutMode.HINGE_AVOID
    size.widthClass == EXPANDED           -> mode.preferredExpanded // SPLIT or FLOATING
    mode == UserMode.ONE_HANDED           -> LayoutMode.ONE_HANDED
    else                                  -> LayoutMode.DOCKED_COMPACT
}
```

---

## 4. Orientation & Window Modes

The IME must offer four window modes plus a number-row option. Landscape and large unfolded states make a docked full-width keyboard ergonomically broken; floating / one-handed / split are the accepted solutions.

| Mode | When | Behavior |
|---|---|---|
| **Docked** | Default portrait, compact width | Standard bottom-anchored layout. |
| **One-handed** | User-selected | Shrink + shift left/right for reachability (Gboard pattern); persist the chosen side. |
| **Floating** | **Auto-suggest** in landscape / unfolded; user-toggleable | Draggable, corner-resizable window. A full-width landscape keyboard is unusable — replicate Gboard's auto-switch-to-floating heuristic in landscape/expanded. |
| **Split** | Expanded width (tablet / unfolded foldable / DeX) | Keys mirrored left/right (§3). |

**Cross-cutting rules:**
- **Split-screen / multi-window:** recompute keyboard height from the **available window**, not the physical screen. The current `ime` module has no split/one-handed/floating/resizable layouts and fixed `54dp` rows — all of this is net-new (owned by `03`, gated by the compatibility requirements here).
- **Survive transitions:** every mode must survive rotation and fold/unfold via `onConfigurationChanged`. **Persist the user's chosen mode per orientation *and* per posture** (e.g., docked in portrait, floating in landscape-unfolded) in `TallyPreferences`.
- **Number row:** offer an optional persistent number row (most-requested keyboard affordance, and directly synergistic with Tally's math identity). Toggle in settings; it changes IME height, so all inset math (§2, §3) must read live height, not assume a row count.
- **Fullscreen extract mode:** override `onEvaluateFullscreenMode()` to return false (honor `IME_FLAG_NO_FULLSCREEN`/`NO_EXTRACT_UI`) — the default landscape extract view is dated UX and breaks edge-to-edge hosts. *(IME-lifecycle detail owned by `03`; flagged here because it is a window-mode behavior.)*

---

## 5. RTL + Locale / Script Coverage

RTL is a layout-mirroring + subtype-declaration problem, **not** an SDK-version problem — native RTL mirroring has existed since API 17, so everything ≥ 26 is clean.

### 5.1 Platform RTL hygiene (mandatory across all UI)
- Set `android:supportsRtl="true"` on every Android module's `<application>` (currently **absent** — none of the manifests declare it; add it).
- Use **start/end**, never **left/right**, in all layouts and key geometry.
- Provide `ldrtl` resource qualifiers where mirroring needs override.
- Rely on the Unicode Bidi Algorithm for mixed LTR/RTL editing. **Math is a special bidi case:** arithmetic expressions and results are LTR runs even inside RTL text — the math chip/suggestion must force LTR directionality on the expression and result string so `12+3=15` never renders reversed inside an Arabic field. This is a `core-math`/`design-system` rendering contract, validated by the bidi screenshot tests in §7.

### 5.2 Keyboard language coverage via `InputMethodSubtype`

The IME currently declares **one** subtype (`en_US`). Multi-language is declared in `method.xml`, one `<subtype>` per language/layout, switchable via the globe key:

```xml
<!-- method.xml — one subtype per language/layout (sketch) -->
<input-method ...>
  <subtype android:label="@string/subtype_en" android:imeSubtypeLocale="en_US"
           android:languageTag="en" android:imeSubtypeMode="keyboard" android:isAsciiCapable="true"/>
  <subtype android:label="@string/subtype_ar" android:languageTag="ar"
           android:imeSubtypeMode="keyboard"/>  <!-- RTL -->
  <!-- ... -->
</input-method>
```

### 5.3 Tiered coverage plan

| Tier | Scripts / languages | Engine need | Notes |
|---|---|---|---|
| **Tier 1** | Latin (en, es, pt, fr, de, it + diacritics) **+ Arabic + Hebrew** | Layout + long-press accents; full RTL mirroring, RTL number/punctuation, **contextual Arabic shaping** | The big RTL pair ships in Tier 1 — RTL is where "physically as many devices" becomes "as many *users*". |
| **Tier 2** | Cyrillic, Greek, high-volume Indic, CJK | **CJK/Indic need composition/transliteration engines, not just layouts** | Materially larger engine effort; scope explicitly (see open issue §5.4). |
| **Tier 3** | Long tail | Layout subtypes | Lowest priority. |

**Permissive-license constraint applies to dictionaries/IME bases too:** any reused layout data, dictionary, or composition/transliteration engine must be Apache-2.0/MIT/BSD (GPL/LGPL excluded) — consistent with ADR-0001's AOSP-LatinIME-lineage choice. CJK/Indic composition engines are the highest license-risk to source; treat sourcing as a gating task in `04`/`07`.

### 5.4 Open issue surfaced to master plan
- **Which CJK/Indic scripts are in scope, and at which phase?** Each requires a dedicated, permissively-licensed composition/transliteration engine — not a layout subtype — and materially expands `core` effort. This is a genuine product+licensing decision for `00-master-plan.md`; Tier 1 (Latin + Ar/He) is the defensible v1 boundary.

---

## 6. Cross-Platform Framework Decision

Two independent questions, answered separately: **(A)** what UI framework renders the *Android* keyboard, and **(B)** how much code is shared *off* Android.

### 6.1 (A) Android typing surface: classic Views / Canvas — **KEEP, do not move to Compose for the key plane**

**Recommendation: the latency-critical typing surface (key bed, gesture trail, key-preview/long-press popups) stays classic Views / Canvas. Compose is permitted only for cold/structural chrome (settings app, onboarding, themes, and — *later, after a latency gate* — the candidate strip and emoji/clipboard panels).** This matches the current stack (no Compose) and is the lower-risk default.

Reasoning, weighing the *verified* evidence honestly:
- Compose **can** render IME UI in 2026 (confirmed: `ComposeView`/`AbstractComposeView` from `onCreateInputView()`), but requires non-trivial, easy-to-get-wrong boilerplate: the `InputMethodService` must implement `LifecycleOwner` + `ViewModelStoreOwner` + `SavedStateRegistryOwner`, manually drive lifecycle events mapped to IME callbacks, `performRestore(null)`, and set all three `ViewTree*Owner`s on the decor view. Getting this wrong leaks windows / crashes on rotation.
- Compose `Popup`/`Dialog` inside an IME hit **window-token** problems — and popups (key preview, long-press accent grid, candidate surfaces) are *exactly* the hot, fragile parts of a keyboard.
- The "Compose == Views jank parity since 1.9.0" claim is **real but about list *scrolling***, not key-down-to-render latency — it does **not** validate Compose for the key bed. Per-keystroke wide recomposition was historically a hot loop (mitigated by `TextFieldState`); the key bed and gesture path are the most latency-sensitive surfaces in the product.

**Therefore:** keep the key plane on Canvas/Views (the established AOSP-LatinIME pattern, owned by `03`). **Do not block the rebuild on a Compose migration of the typing surface.** Promote the candidate strip / panels / key bed to Compose *only if* an explicit latency gate passes (touch-down-to-key-render and key-repeat latency within the ~16ms frame budget on a mid-tier device, release build + baseline profiles). That gate is an acceptance criterion in §7 and `08`-lineage testing docs.

### 6.2 (B) Off-Android code sharing: **Kotlin Multiplatform for the portable core; native IME shell; Compose Multiplatform optional for companion UI only**

The IME *shell* is inherently platform-specific and **cannot** be meaningfully abstracted across OSes: on Android it is `InputMethodService` (system-bound); on iOS it is a separate **Keyboard Extension** (UIKit/SwiftUI in an app-extension sandbox with its own lifecycle, strict memory limits, and "Allow Full Access" model). There is no portable IME abstraction. So host-binding and key rendering stay native per platform.

**What SHOULD live in a `:core` KMP module** (KMP is stable / Google-backed, in production at Netflix, Cash App, McDonald's):

| Shareable in `:core` (KMP) | Native per platform |
|---|---|
| **`core-math`** (already pure JVM — the natural first KMP candidate; Tally's strongest module) | Android IME `InputMethodService` + Canvas key plane |
| Layout geometry & key-hit math | iOS Keyboard Extension UI (later) |
| Autocorrect / prediction / language model (when added per `04`) | Host binding / `InputConnection` (Android) |
| Transliteration / composition engines (Tier 2, §5) | Window/insets/posture handling |
| Settings model & persistence logic | OEM-specific onboarding flows |

**Recommendation, decisively:**
1. **`core-math` is the KMP beachhead.** It is already pure-JVM with golden/property/fuzz tests and a clean public API (`MathEngine.evaluate(...)`). Converting it to a KMP module with a JVM/Android target now (and an iOS target later) is **low-risk, high-ROI** and honors "build the math once, reuse on both surfaces" — *both surfaces* being the Android IME and Android overlay today, and an iOS keyboard extension later. **Do this regardless of whether iOS ships.**
2. **Android IME UI stays native now** (§6.1). An iOS keyboard extension, *if* committed, consumes the same `:core`. Keep `:core` **memory-frugal and degrading gracefully** — iOS extensions are jetsammed under memory pressure (this constrains whether on-device neural prediction can be reused unchanged on iOS; see open issue).
3. **Compose Multiplatform** is optional and lower priority: usable for the *settings/onboarding* companion app and an optional Kotlin/Wasm **web demo** — **not** for the live key-rendering surface, and **not** for web as a production typing surface (Kotlin/Wasm web is still Beta → demo/marketing only).

This maximizes "frameworks reached" (Android native + future iOS extension + optional web demo) while capturing the expensive, correctness-critical logic write-once, without betting input latency on a shared UI runtime.

### 6.3 Open issues surfaced to master plan
- **Is an iOS keyboard a committed roadmap item or speculative?** Determines how hard to draw the `:core` KMP boundary *now* vs. later. (Recommendation holds either way: KMP-ify `core-math` now — it's cheap; defer the rest of `:core` until iOS is committed.)
- **Prediction engine class: on-device neural (heavy) vs n-gram/statistical (light)?** Drives the iOS-extension memory budget and whether `:core` is reusable on iOS unchanged. Statistical is the safe default for cross-platform reuse.
- **Is web a real product surface or marketing?** If marketing only, a static page is cheaper than CMP/Wasm.

---

## 7. Concrete Compatibility + Test Matrix

A keyboard's failure modes are **combinatorial** (OS × OEM × form-factor × input × locale × window-mode); only a managed-device + Test-Lab matrix with smart sharding catches them affordably. Tooling: **Gradle Managed Devices** for deterministic local/CI AVDs + **Firebase Test Lab** (smart sharding) for the physical OEM/foldable matrix, both wired into **GitHub Actions** on PR.

> **Privacy/CI note.** Firebase Test Lab is a *test-time* tool only; it changes nothing about the shipped product, which carries no `INTERNET` permission. Keep this boundary explicit in `06`/`09`-lineage docs.

### 7.1 Axes

| Axis | Coverage |
|---|---|
| **OS / API** | 26 (min, low-end emulator) · 30/31 (high-volume mid) · 34 · 36 (target) |
| **OEM (physical, Firebase Test Lab)** | **Pixel** (AOSP reference) · **Samsung Galaxy S2x + a Fold/Flip** (One UI + DeX) · **Xiaomi/HyperOS** · **Oppo/ColorOS** (background-kill behavior). **Samsung-broad, not one device** — span One UI generations (an S8/S9-class legacy-bug device, a mid S2x, a current One UI 8 device) because composing-region bugs are device-firmware-specific. |
| **Form factor (AVDs)** | phone (compact) · 7"/10" tablet (expanded, split) · **Pixel Fold AVD** (book/tabletop posture + hinge avoidance) · resizable/Desktop AVD **+ a secondary virtual display** (multi-display + DeX) · Chromebook/Chrome OS target |
| **Input** | touch · **hardware Bluetooth/USB keyboard** (assert IME hides per contract + suggestion-strip option works) · stylus (if §1.2 stylus phase active) |
| **Locale** | en · es · **RTL pseudo-locale** · **Arabic** · **Hebrew** · one CJK · one Indic (Tier-dependent, §5) |
| **Window mode** | portrait/landscape · split-screen · one-handed · floating · number-row on/off |
| **Samsung chrome (per Samsung device)** | toolbar on/off · number row on/off · floating/split/one-handed · **plain vs password field** · **pre-filled vs empty field** · insert-after-hide/reshow-keyboard · clipboard-alert on/off |

### 7.2 CI gate ordering

1. **Gate 0 — `:core` unit tests (JVM/native).** Fast, deterministic; runs first. `core-math` golden/property/fuzz/locale suites are the cheapest, highest-signal gate.
2. **Gate 1 — Instrumented tests on Gradle Managed Devices.** Espresso/UIAutomator via `AndroidJUnitRunner` across the AVD matrix (API 26/30/34/36 + phone/tablet/foldable/desktop AVDs).
3. **Gate 2 — Screenshot tests** across window size classes (compact/medium/expanded) **+ RTL pseudo-locale** (validates §5.1 bidi, including the LTR-math-in-RTL-field contract).
4. **Gate 3 — Firebase Test Lab (smart sharding)** for the physical OEM/foldable matrix on PR — the only place Samsung/Xiaomi/Oppo-specific failures surface (they are invisible on Pixel/emulators).

### 7.3 Explicit Samsung pass/fail signals (track per device)
- "duplicated text" / "first-letter repeat" → **fail**
- "overlay occluded by keyboard" → **fail**
- "clipboard toast shown" during insertion → **fail** (insertion path must not touch clipboard)
- "text correctly inserted and read-back verified" → **pass**

### 7.4 Acceptance gates owned by this document
- **Adaptive compliance:** every companion activity *and* the IME input view fill the window in all orientations at width ≥ 600dp (no letterboxing) on tablet/foldable/DeX AVDs.
- **Posture:** no interactive key rendered under the hinge on the Fold AVD in tabletop/book posture.
- **Multi-display:** correct input-view sizing on a secondary virtual display with no cached-metrics artifacts.
- **Hardware keyboard:** soft IME hides per contract; optional suggestion strip still functional.
- **Latency gate (governs §6.1 Compose promotion):** touch-down-to-key-render and key-repeat latency within the ~16ms frame budget on a mid-tier device, release build + baseline profiles.

---

## Appendix — Net changes vs. current repo state

| Area | Current | Required by this document |
|---|---|---|
| `targetSdk` | only `:app` (=36) | set `=36` in **every** Android module |
| `supportsRtl` | absent in all manifests | add `android:supportsRtl="true"` |
| IME subtypes | single `en_US` | Tier-1 multi-subtype (Latin + Ar + He) via `method.xml` |
| Key sizing | fixed `54dp` rows, one static English layout | `WindowSizeClass`-driven layout engine; reflow on `onConfigurationChanged` (owned by `03`) |
| Window modes | docked only | docked + one-handed + floating + split; persist per orientation/posture (owned by `03`) |
| `core-math` | pure-JVM Android-tree module | convert to KMP module (JVM/Android target now; iOS target when committed) |
| A11y service xml | no `flagRetrieveInteractiveWindows` | richer flags/feedback (owned by `02`) |
| CI | (per `08`) | Gradle Managed Devices + Firebase Test Lab smart sharding + RTL/size-class screenshot tests, GitHub Actions |
