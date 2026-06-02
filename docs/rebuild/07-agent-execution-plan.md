# 07 — Agent Execution Plan

> **What this is.** The executable task graph for the rebuild. Every task is a self-contained card:
> an ID, the agent role that should own it, what it depends on, which deep-dive section is its
> source of truth, what it must deliver, and the acceptance criteria that decide "done." A single
> performant agent can walk this in dependency order; a team can fan out along the graph (see the
> waves in §7). This document decides *who does what, in what order, and when it is done* — it does
> not restate the designs (those live in `01`–`06`).

---

## 1. Operating model

**Roles** (map to agent types; one agent may wear several):

| Role | Owns | Skills/agents to use |
|---|---|---|
| **Engine** | `core-math`, `keyboard-engine` (pure JVM), `prediction` decoder/LM | pure-JVM build; property/fuzz testing |
| **IME** | `ime` shell: rendering, touch, popups, lifecycle, a11y, strip, panels | Android instrumentation; latency profiling |
| **Data** | `layouts`, dictionaries/LM assets, emoji/CLDR, license provenance | asset pipelines; license vetting |
| **Overlay** | `overlay` module (conditional track) | AccessibilityService; device labs |
| **Platform/Build** | Gradle, CI, guards, release, signing, SBOM | CI; reproducible builds |
| **Reviewer** | adversarial review gates at each epic boundary | `pr-review-toolkit:*`, security-review |

**Task-card fields.** `Depends` = task IDs that must be green first. `Source` = the authoritative
deep-dive section. `Deliverables` = artifacts to produce. `Acceptance` = the gate; if any criterion
is red the task is not done.

**Granularity.** Each card is scoped to a reviewable unit (roughly one focused PR). Where a card is
an XL workstream (T2.2 decoder, T4.1 glide), it is explicitly flagged to be split by the owning
agent into sub-PRs — but its acceptance gate is singular.

---

## 2. Global gates (inherited by every task — non-negotiable)

1. **License gate.** No GPL/LGPL code or data in the binary; permissive only (`00 §3.1`). Any new
   dependency or data asset is license-vetted **before** it lands; provenance recorded in the data
   ledger (T0.2). When in doubt, escalate — do not merge.
2. **No-network gate.** No `INTERNET` permission, no networking dependency, anywhere. CI fails
   otherwise (`00 §3.2`).
3. **Tests land with the code.** A task is not done until its tests are green in CI. Engine work
   carries golden/property/fuzz; IME work carries unit tests on the pure seams (`keyboard-engine`)
   plus instrumentation for the Android surface.
4. **Architecture guards.** `core-math` **and** `keyboard-engine` have zero Android imports
   (CI-enforced). Dependencies point inward toward `core-math`. The math feature crosses into the
   shell only as a `SuggestionSource`.
5. **Privacy seam.** Every surface that reads/persists/display text honors `FieldPolicy` (T1.13).
   No field content is logged at any level; release builds strip logs (R8).
6. **House style.** History reads as careful human work — Conventional Commits, one concern per
   change, no machine-authored residue, no AI tells in code or docs (`09-build-release.md`,
   `CONTRIBUTING.md`).
7. **Adversarial review at each epic boundary** (§6) before the milestone is declared green.

---

## 3. Dependency graph (epics)

```
M0 Foundations ─┬─► M1 Keyboard-you-can-type-on ─► M2 Predict+Math ─► M3 Polish→SHIP v1.0
                │                                                          │
                │                                                          ├─► M4 Competitive
                │                                                          └─► M5 Reach
                │
                └─► Overlay track (TO.*) ── parallel, conditional, gated by D13/D15, depends on
                                            T1.13 FieldPolicy + T1.11 insets; never blocks M1–M3

Cross-cutting (TX.*) run alongside: TX.1 KMP core-math (anytime), TX.3 audit+repro (pre-ship),
TX.4 advanced-math ADR (post-v1).
```

Critical path: **T0.5 → T1.5 (compose pipeline) → T2.1/T2.2 (decoder) → T2.3/T2.4 (strip+math) →
M3**. Protect the decoder's schedule above all.

---

## 4. Epics and task cards

### M0 — Foundations  *(Source: `00 §3,§5`, `03 §2`, `04 §3`, `05 §1`, `06`)*

| ID | Task | Depends | Deliverables | Acceptance |
|---|---|---|---|---|
| **T0.1** | Module scaffolding | — | New Gradle modules `keyboard-engine` (pure JVM), `layouts`, `prediction`, `emoji`; version-catalog entries; dependency wiring per `03 §2`; `keyboard-engine` has no Android plugin | `./gradlew build` green on clean checkout; dependency arrows point inward; new modules compile empty |
| **T0.2** | CI guards + data ledger | T0.1 | Extend manifest no-`INTERNET` guard to all modules; add `keyboardEngineAndroidFreeGuard`; add **license-scan** gate (dependency + bundled-asset license allowlist); create the **data-provenance ledger** (per-asset: source, license, attribution) | Each guard **fails a deliberately-bad branch** (prove it); license-scan flags a planted GPL dep; ledger exists and is required for any asset PR |
| **T0.3** | Data-driven layout format | T0.1 | Declarative `LayoutDefinition` schema + parser in `layouts`; width-fraction validation; one en-US QWERTY layout as the reference asset | Parser rejects malformed/over-wide rows in CI; reference layout round-trips to `keyboard-engine` descriptors |
| **T0.4** | `core-math` A1 fixes | — | Input significant-digit cap in `Lexer`; detector right-anchoring / two-dimensional fragment search (drop trailing prose); veto expansion (bare scores/ranges, cross-locale grouping); percent-boundary golden cases; worst-case-span perf test | New golden + false-positive corpus cases green; over-cap operand returns no result; perf test pins the ceiling; existing suites stay green |
| **T0.5** | IME view-layer skeleton | T0.1, T0.3 | New `TallyInputMethodService` (lifecycle per `03 §3.2`: `onInitializeInterface`/`onStartInputView`/`onComputeInsets`/`onEvaluateFullscreenMode→false`), `KeyboardController`, empty `KeyPlaneView`, behind the preserved `KeyboardHost` seam | Installs and shows an (empty) keyboard; lifecycle reconfigures per field without recreating the controller; insets stable |

### M1 — A keyboard you can type on  *(Source: `03 §3,§6,§7,§9`, `04 P0-1…P0-15`)*

| ID | Task | Depends | Deliverables | Acceptance |
|---|---|---|---|---|
| **T1.1** | `LayoutEngine` (pure) | T0.3 | Geometry + `hitTest` as pure functions in `keyboard-engine`; unit tests | Hit-testing verified without a View; rows normalize/validate |
| **T1.2** | Multitouch + rollover (P0-1) | T0.5, T1.1 | Per-`pointerId` `PointerTracker` model; masked-action dispatch; rollover | Two-thumb fast typing drops no keys (instrumentation); `ACTION_CANCEL` tears down all trackers |
| **T1.3** | Key-preview popups (P0-2) | T1.2 | Magnified preview bubble on down; hidden on up; suppressed when `previewMasked` | Preview shows/hides correctly; never shown in masked fields |
| **T1.4** | Long-press alternates (P0-3) | T1.2, T1.3 | `Key.moreKeys`; long-press mini-keyboard; accent/secondary-symbol data (DATA, permissive) | Accents (é/ñ/ü…) reachable on every Latin layout; move/up selects an alternate |
| **T1.5** | Compose-text pipeline (P0-4) | T0.5 | `setComposingText`/`finishComposingText` path; balanced batch edits; `InputConnection` local mirror + `onUpdateSelection` reconciliation; `deleteSurroundingTextInCodePoints` | Underline/compose works; no re-entrancy on our own edits; reads use a mirror, never block the input thread |
| **T1.6** | Per-key accessibility (P0-8) | T1.1 | `ExploreByTouchHelper` virtual tree; node per key (bounds/label/click); explore-by-touch + lift-to-type | **Ship-blocker gate:** TalkBack explores and types every key; Switch Access traverses; nodes invalidate on shift/layer change |
| **T1.7** | Shift/auto-caps machine (P0-9) | T1.1 | Tri-state `off→shifted→locked` in `keyboard-engine` (unit-tested); double-tap lock; `capsMode` autocaps; latched-vs-locked visuals | State transitions unit-tested; visuals distinct; autocaps honors `EditorInfo` |
| **T1.8** | Haptics + sound (P0-10) | T0.5 | `performHapticFeedback(KEYBOARD_TAP)` + `playSoundEffect`; independent prefs; honor Silent Mode/system haptics | **No `VIBRATE` permission added**; toggles work; respects system settings |
| **T1.9** | Backspace word-delete + repeat (P0-11) | T1.2, T1.5 | Accelerating repeat; swipe-left-on-delete = word delete | Repeat accelerates and stops on up/cancel; word delete via batch edit |
| **T1.10** | Number row + layers (P0-12) | T0.3, T1.1 | Optional number-row toggle; clean numeric/symbol layers as data | Layers switch correctly; number row toggle persists; layouts are data, not code |
| **T1.11** | Landscape + insets + fullscreen (P0-13) | T0.5 | Responsive height in `onMeasure`; `onComputeInsets`; `onEvaluateFullscreenMode→false`; `onConfigurationChanged` | Doesn't cover the field; smooth host `WindowInsetsAnimation`; landscape usable. **Feeds overlay TO.4.** |
| **T1.12** | Theme model (P0-14) | T0.5 | Theme tokens in `design-system`; colors resolved per-draw (not cached in `init`) | Runtime light/dark switch without view recreate; tokens shared with overlay chip |
| **T1.13** | `FieldPolicy` seam (P0-15) | T0.5 | One `FieldPolicy` derived from `EditorInfo` in `onStartInputView`; passed to every surface | Password/`NO_PERSONALIZED_LEARNING`/incognito → suggestions+glide+preview+persist off; default most-private. **Feeds T2.* and overlay TO.5.** |

### M2 — It predicts, and it does math  *(Source: `03 §4`, `04 §2.2,P0-5…P0-7`, `06`)*

| ID | Task | Depends | Deliverables | Acceptance |
|---|---|---|---|---|
| **T2.1** | English prediction data | T0.2 | Compiled lexicon + static n-gram LM from SCOWL/ESDB (permissive, with frequencies); asset format + loader in `prediction`; ledger entries | License-vetted (in ledger); asset within size budget (`00 D17`); loads off-UI-thread |
| **T2.2** | Decoder core (P0-5) **[XL — split into sub-PRs]** | T1.5, T2.1 | Spatial scorer + beam search; `WordPredictor` + `Autocorrector` impls behind `keyboard-engine` interfaces; **local** per-user frequency cache honoring `FieldPolicy` | Tap-autocorrect ≥ Gboard-adjacent on the internal corpus; decode is off-UI, bounded, cancellable; no upload/federated path |
| **T2.3** | Suggestion strip + composition (P0-6) | T2.2, T1.12 | `SuggestionSource` model; multi-source strip; **reserved leading math slot**; coalesced ≤1 update/event | Word candidates + math chip coexist; math never displaced; updates coalesced; tap commits via batch edit |
| **T2.4** | Math source re-attach (P0-7) | T2.3 | Adapter over `feature-glue.MathEvaluator` as a `SuggestionSource`; `onCommit` via `KeyboardHost` (batch-edited; optional span replace / `exactValue`) | Math chip surfaces on `=` end-to-end in the keyboard; commit on tap/space; veto cases show nothing |
| **T2.5** | Incognito/no-learning enforcement | T1.13, T2.2 | Personalization store + decoder honor `FieldPolicy.learningEnabled`/`persistAllowed` | Nothing learned/persisted from secure or no-learning fields (instrumentation) |

### M3 — Feel + first-win → ship v1.0  *(Source: `03 §7,§8`, `05`, `06`, `09-build-release.md`)*

| ID | Task | Depends | Deliverables | Acceptance |
|---|---|---|---|---|
| **T3.1** | Motion + polish | T2.4 | Appear/update/dismiss motion (chip + strip) honoring reduce-motion; dynamic-color groundwork | Matches the interaction spec; reduce-motion falls back to instant |
| **T3.2** | Onboarding + settings | T2.4 | Enable-IME wizard (deep-link + success detection, no dead-ends); seeded first-win field (`2+2=`→`4`); settings for all new toggles | New user reaches a correct result in <1 min; wizard handles back/already-enabled |
| **T3.3** | Hardening pass | T2.5 | `06` checklist: R8 log-strip, dependency verification refresh, SBOM, permission + secure-field audit | Release build strips text-capable logs; manifest minimal; IME contributes **no** runtime permissions; SBOM produced |
| **T3.4** | Samsung-broad device matrix | T3.1, T3.3 | Execute `05` + `02 §6.2` matrix (Pixel/AOSP + Samsung legacy/current/foldable; locales/RTL; form factors) | All P0 pass across the matrix; password-field typing correct on Samsung; no jank on a mid-tier device |
| **T3.5** | Release engineering | T3.4 | Play AAB; Data Safety "no data collected"; signing config (keys off-repo); CHANGELOG; reproducibility on a release branch | Pre-release checklist (`09`) green; reproducible build byte-identical; tag/version set |

### M4 — Competitive  *(Source: `04 P1-*`, `03 §3.5`, `06`)*

| ID | Task | Depends | Deliverables | Acceptance |
|---|---|---|---|---|
| **T4.1** | Glide (P1-1) **[XL]** | T2.2 | `GestureDecoder` impl: ported Apache statistical classifier + resample/score/rank on the P0-5 lexicon/LM; **patent-clearance review routed to legal** before ship | Usable on the internal corpus; off-UI, cancellable; quality expectations documented; legal sign-off recorded |
| **T4.2** | Emoji panel (P1-2) | M3 | Picker (categories/search/skin-tone/recents) from Unicode CLDR; EmojiCompat rendering; local recents | Offline; search works; recents local-only; data in ledger |
| **T4.3** | Clipboard manager (P1-3) | M3, T1.13 | Local history (pin/expiry), regex entity chips; **encrypted-at-rest, auto-expiry**; **never routes insertion through `ClipboardManager`** | No system-clipboard write in any insert path (grep + test); sensitive clips expire; honors `persistAllowed` |
| **T4.4** | Cursor-control gesture (P1-4) | T1.2 | Space-bar swipe = cursor move; selection gestures (reuse pointer slide path) | Cursor/selection accurate; no accidental triggers during normal typing |
| **T4.5** | Dynamic color (P1-6) | T1.12 | System tonal palette on Android 12+ (read platform palette directly, not Compose M3); presets + custom-image; static fallback below 12 | Theming correct across versions; no network; no data concern |
| **T4.6** | Form factors (P1-7) | T1.11 | One-handed / split / floating / resizable height over one key model | Each mode usable on phone + tablet + foldable; geometry only, no ML |

### M5 — Reach  *(Source: `04 P1-5,P1-8,§2.5,§2.6`, `05 §5`, `06`)*

| ID | Task | Depends | Deliverables | Acceptance |
|---|---|---|---|---|
| **T5.1** | Multilingual Latin (P1-5) | T2.2, T0.3 | AZERTY/QWERTZ/intl layouts + subtype/globe switcher; simultaneous same-script decoding; **per-language data vetting** | First non-English language ships only with ledger-vetted permissive lexicon+LM; globe switch works |
| **T5.2** | Offline voice (P1-8, opt-in) | M3 | OS recognizer integration where present, else bundled Vosk (Apache-2.0); model packs **in-APK or sideloaded, never network** | Works fully offline; no `INTERNET`; model licenses vetted; isolated so core stays network-less |

### Overlay track — TO.*  *(parallel, conditional; Source: `02`, `06`, `05`)*

> Build only to the extent the overlay ships (D13/D15). Depends on `T1.13` (FieldPolicy) and
> `T1.11` (insets). Never blocks M1–M3. If AC-1…AC-8 cannot be met within the agreed budget, **cut
> the module** (`02 §6`).

| ID | Task | Depends | Deliverables | Acceptance |
|---|---|---|---|---|
| **TO.1** | Delete clipboard path; fix event/focus model | T0.1 | Remove `ClipboardInserter`; a11y XML flags (`flagRetrieveInteractiveWindows`, `typeWindowsChanged`, `notificationTimeout=0`); blur-on-focus-loss only; generation guard + focus token; no `recycle()` on 33+ | No clipboard reference anywhere (AC-1); chip no longer flashes on window churn; stale results dropped |
| **TO.2** | Composing-text strategy | TO.1 | Commit-triggered reads; `node.refresh()` before read; Android 17 `getTextChangeTypes()` act-on-commit | Chip appears post-commit reliably; documented commit-only limitation (AC-7) |
| **TO.3** | Insertion redesign | TO.2 | `ACTION_SET_TEXT(full reconstructed)` + `ACTION_SET_SELECTION` + read-back-verify; fallback ladder (`02 §4.8`); no-op on unsupported editors | No silent corruption (AC-3); never falls back to clipboard; Samsung pre-filled + hide/reshow repros pass |
| **TO.4** | Positioning / insets / occlusion | TO.1, T1.11 | Placement from focused-window frame + `WindowInsets`; suppress when keyboard would occlude | Never over status bar/notch; never shown un-tappable (AC-5) |
| **TO.5** | Secure-field gate + dormancy | TO.1, T1.13 | Hard suppression on password/no-learning/incognito/`FLAG_SECURE`/obscured before read+show+insert; dormant when Tally IME active; keep `filterTouchesWhenObscured` | Never reads/shows/inserts in secure fields (AC-2); zero chips while Tally IME active (AC-6); tap-to-insert only (AC-8) |
| **TO.6** | Overlay matrix + ship/cut decision | TO.3, TO.4, TO.5 | Run `02 §6.2` matrix; verify AC-1…AC-8; off-Play signed APK + checksums **or** record the cut | All AC green → ship off-Play; else cut and remove the module |

### Cross-cutting — TX.*

| ID | Task | Depends | Deliverables | Acceptance |
|---|---|---|---|---|
| **TX.1** | KMP-ify `core-math` | T0.4 | Restructure `core-math` as a KMP module (JVM + iOS targets), API unchanged | JVM consumers unaffected; iOS target compiles; tests run on both. Enables a future iOS extension (`05 §6`) |
| **TX.2** | Independent audit + repro docs | T3.3 | Commission an NDA security audit; publish reproducible-build verification instructions | Audit engaged; repro instructions reproduce a release byte-for-byte |
| **TX.3** | Advanced-math pack ADR | T0.4 (post-v1) | ADR for functions/exponent/scientific input + static unit conversion (temp/distance); preserves no-network | ADR accepted before any advanced-math code; currency remains excluded |

---

## 5. Acceptance criteria reference (overlay)

The overlay's AC-1…AC-8 are defined in `02 §6.1` and are the gate for TO.6: no data loss; secure
suppression; no silent corruption; no stale UI; correct placement; dormancy under Tally IME;
documented commit-only expectation; no autonomy (tap-to-insert only).

---

## 6. Verification strategy (per epic boundary)

Before a milestone is declared green, run an **adversarial review gate**, not just CI:

1. **Code review** — `pr-review-toolkit:code-reviewer` over the epic's diff for guideline/style/bug
   adherence.
2. **Silent-failure hunt** — `pr-review-toolkit:silent-failure-hunter` on every `catch`/fallback,
   especially insertion paths and `InputConnection`/accessibility error handling (the overlay's old
   `swallow IllegalState` pattern is exactly what this catches).
3. **Type/interface review** — `pr-review-toolkit:type-design-analyzer` on new public seams
   (`SuggestionSource`, `GestureDecoder`, `FieldPolicy`, `LayoutDefinition`).
4. **Test analysis** — `pr-review-toolkit:pr-test-analyzer` for coverage of edge/veto/secure cases.
5. **Security review** — the `security-review` skill on any change touching permissions, the
   manifest, persistence, the clipboard, or accessibility.
6. **CI gates** (every PR): lint/detekt; `core-math` + `keyboard-engine` Android-free guards;
   no-`INTERNET` manifest guard; **license-scan**; no-text-logging guard; unit + instrumentation
   tests; release-build/R8 smoke; reproducibility on release branches.
7. **Device matrix** at M3 (and for any overlay PR): Samsung-broad per `05`/`02 §6.2` — Pixel-only
   testing is not acceptance.

A milestone is green only when its tasks' acceptance criteria **and** the gate above are all green.

---

## 7. Parallelization waves

Within the dependency graph, these run concurrently:

- **Wave 0 (M0):** T0.1 → then T0.2/T0.3/T0.5 in parallel; **T0.4 (engine) and TX.1 (KMP) run
  independently start-to-M0.** Overlay TO.1 can also start (depends only on T0.1).
- **Wave 1 (M1):** after T0.5, the touch chain (T1.2→T1.3→T1.4, T1.9), the engine-seam chain
  (T1.1, T1.7), and the independent surfaces (T1.5, T1.6, T1.8, T1.10, T1.11, T1.12, T1.13) fan out.
  T1.5 (compose) and T1.13 (FieldPolicy) are on the critical path — prioritize them.
- **Wave 2 (M2):** T2.1 (data) ∥ starts during M1; T2.2 (decoder, XL) is the long pole; T2.3/T2.4
  follow; T2.5 needs T1.13.
- **Wave 3 (M3):** T3.1/T3.2/T3.3 in parallel after M2; T3.4 (matrix) gates T3.5 (release).
- **Waves 4–5 (M4/M5):** branch off M3; T4.1 (glide) is gated only by T2.2, so it can begin once the
  decoder is stable even before M3 ships.
- **Overlay track** advances in parallel throughout, gated by T1.11 + T1.13, decided at TO.6.

---

## 8. First three tasks to dispatch now

To start the program immediately, dispatch in this order:

1. **T0.1** (module scaffolding) — unblocks everything.
2. **T0.4** (engine A1 fixes) and **TX.1** (KMP core-math) — independent, no blockers, high value.
3. **T0.2 + T0.3 + T0.5** once T0.1 lands — the guards, the layout format, and the IME skeleton that
   M1 builds on.

Everything thereafter follows the graph in §3 and the waves in §7. Each task card in §4 is a
complete brief: hand an agent the card plus its `Source` deep-dive section and it has what it needs.
