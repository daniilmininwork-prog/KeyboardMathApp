# 03 — IME Architecture v2

> **Scope of this document.** This is the engineering blueprint for the *keyboard layer* of Tally: how the IME is structured, how input flows from finger to committed text, and how the math feature lives inside a full general-purpose keyboard. It is a **plan**, not source. It assumes the verdicts in `01-competitive-analysis.md` (where Tally must reach) and hands off feature scoping/sequencing to `04-feature-matrix-and-roadmap.md`, device/OEM specifics to `05-device-framework-compatibility.md`, and threat/policy enforcement to `06-security-privacy-hardening.md`. The overlay surface is owned by `02-overlay-root-cause-and-redesign.md`. Cross-module licensing gates and the build-vs-buy ledger are summarized here only where they force an architecture decision; the master ledger lives in `00-master-plan.md`.

**Non-negotiable constraints inherited by everything below:** closed-source product → permissive licenses only (Apache-2.0/MIT/BSD/Unicode; **no** GPL/LGPL in the shipped binary, including *data*); privacy-first → **no `INTERNET` permission anywhere**, fully on-device, no telemetry; stack is Kotlin 2.1.21 / AGP 8.8 / Gradle Kotlin-DSL multi-module / **classic Views (no Compose)** / minSdk 26 / compileSdk 36 / Material 1.12.

---

## 1. DECISION

### 1.1 Extend-in-place vs rebuild the keyboard layer

**Recommendation: Rebuild the keyboard layer from scratch as a clean-room implementation. Keep `core-math` and `feature-glue` verbatim; preserve the `KeyboardHost` boundary as the seam; discard `TallyKeyboardView` and `KeyboardLayout` as production code (retain them only as a throwaway reference for the math-chip wiring).**

This is the verdict of audit **A2-ime**, and it is correct. The current `ime` module is a competent *math-suggestion accessory* and an architectural dead end for a general keyboard:

| Why extend-in-place fails | Concrete blocker |
|---|---|
| Touch model is single-pointer | `onTouchEvent` tracks one `pressedKey`; every `ACTION_MOVE` overwrites it. No `ACTION_POINTER_*`. Fast two-thumb typing drops keys. A patch is a touch-layer rewrite. |
| No composing-text participation | Characters go straight to `commitText`. Autocorrect/prediction/spellcheck *require* a `setComposingText` pipeline. Retrofitting changes the entire input path. |
| Whole keyboard is one a11y node | No `AccessibilityNodeProvider`. TalkBack cannot type. **Hard Play-policy and ethical/legal blocker for a default keyboard.** |
| Layout is hard-coded en_US ASCII Kotlin literals | Every new layout/script is a recompile. No data-driven format. |
| Canvas-everything with no testable seam | All geometry/hit-testing/state behind `onDraw`/`onTouchEvent`. Every future feature (popups, glide, per-key a11y) is bespoke manual code. |

Extending in place externalizes the cost of every capability a real keyboard needs. **Rebuild.** Critically, the rebuild is *clean-room and from-scratch*, **not a fork** — see §1.2.

### 1.2 Host-base choice — apply the license gate

The tempting shortcut is "fork a permissive OSS keyboard and bolt the math chip on." **The license gate kills every viable fork as a *base*, and the verified research (V3, V4, R4, R7) is unambiguous:**

| Candidate base | License | Verdict |
|---|---|---|
| OpenBoard / HeliBoard / Unexpected Keyboard | **GPL-3.0** | **Excluded.** Cannot ship a closed proprietary fork of GPL code. (HeliBoard is the only *maintained* AOSP-lineage keyboard — and it is copyleft.) |
| AOSP LatinIME | Apache-2.0, **but unmaintained upstream** (V3) | Usable as a **reference for IME plumbing and proximity geometry only**. Its glide *decoder is a stub*; the working decoder is the proprietary `libjni_latinimegoogle.so`, which is **not redistributable** and must never be bundled (V3, V4). |
| FlorisBoard | Apache-2.0 | Usable as **reference**; current `main` has **glide removed** (returns ~v0.7). Pure-Compose, so its UI layer is not directly liftable into our Views stack. Mine its *old 0.3.x/0.4.x* Apache-2.0 statistical glide classifier as a porting seed only. |
| Simple Keyboard | Apache-2.0 | Clean structural **reference** for the IME service + key-detection plumbing. No glide, no prediction by design. |

**Decision: build our own keyboard shell from scratch (IME service, rendering, layout engine, popups, a11y), referencing the Apache-2.0 bodies above for *patterns and geometry*, and *porting* (not depending on) the Apache-2.0 AnySoftKeyboard / old-FlorisBoard statistical glide classifier behind a narrow decoder interface.** Rationale:

1. **No permissive base both ships working glide AND is closed-source-licensable.** AOSP's decoder is a stub, FlorisBoard's current glide is removed, the rest are GPL or depend on a proprietary blob (V4, confirmed).
2. **Forking even an Apache base drags a large legacy AOSP-Java surface** we'd own and maintain forever, for code we'd rewrite anyway to meet our Views/multitouch/a11y bar.
3. **A from-scratch shell with thin engine seams** lets us swap decoders (corner-matching → neural) and shells without touching `core-math`. This is the migration architecture R4 recommends and it survives both license audits and engine upgrades.

> **Glide reality check (set expectations now).** There is no permissive, Gboard-quality, droppable glide engine (V4, confirmed). Day-1 glide via a ported statistical classifier will trail Gboard and needs sustained tuning. Glide is therefore a *phased* capability behind a stable interface, not a v1 table-stake. The NLnet open gesture-typing project (deadline ~today, 2026-06-01) is worth tracking but treat as unproven. Sequencing is owned by `04-feature-matrix-and-roadmap.md`.

> **Data is a second license gate, independent of code.** Shipping CC-BY-SA / CC-BY-NC / GPL *wordlists* in a closed product is as fatal as shipping GPL code (R4, R7). English: SCOWL/ESDB (permissive). Emoji: Unicode CLDR (Unicode License v3, permissive). Every other language's lexicon is vetted per-source before it ships. Provenance is tracked per language. This constrains the prediction/layout modules below.

---

## 2. Target module architecture

**Principle:** dependencies point inward toward `core-math`; the keyboard *shell* depends on engine *interfaces*, never the reverse; Android UI never reaches into `core-math`. The redesigned `ime` is split so the latency-critical input path is isolated and unit-testable.

### 2.1 Module set

| Module | Kind | Responsibility | License posture |
|---|---|---|---|
| `core-math` | pure JVM | **Unchanged.** `MathEngine.evaluate(...)`. The strongest module; do not touch beyond the gaps A1 lists (those are owned by the math-engine plan, not here). | first-party |
| `feature-glue` | Android-aware | **Unchanged in shape.** Debounce + bg executor + main-thread callback; `TallyPreferences`. Extended only to register math as a *suggestion source* (§4). | first-party |
| `design-system` | Android lib | `MathResultChip` + shared theme tokens, key-face styles, strip styles. Consumed by IME strip and overlay. | first-party |
| **`keyboard-engine`** *(new)* | pure JVM (no Android UI) | The brain of the keyboard: `LayoutEngine` (geometry/hit-testing as pure functions), shift/caps state machine, key-repeat/long-press policy, `InputAction` reduction, and the **decoder interfaces** (`GestureDecoder`, `WordPredictor`, `Autocorrector`). **Unit-testable without inflating a View.** | first-party + ported Apache classifier |
| **`layouts`** *(new)* | Android lib (assets) | Data-driven layout definitions + parser. QWERTY/AZERTY/QWERTZ/Dvorak/Colemak, number row, symbols, non-Latin scripts, RTL. Subtype registry. No code-per-layout. | first-party data |
| **`prediction`** *(new)* | Android lib | On-device n-gram LM + lexicon store + autocorrect + next-word, behind `keyboard-engine` interfaces. Owns dictionary assets and the personalization store. | first-party engine; **permissive data only** |
| **`emoji`** *(new)* | Android lib | Emoji picker (categories/search/recents/skin-tone) + clipboard manager panel. Driven by CLDR data. | first-party + CLDR (Unicode) |
| `ime` | Android app/lib | **Redesigned.** `TallyInputMethodService` (lifecycle, `InputConnection` ownership, `FieldPolicy`), the Views/Canvas **key plane** (rendering, multitouch, popups), the suggestion **strip**, and panel hosting. Wires engine modules to the platform. | first-party |
| `app` | Android app | Onboarding, settings, about. Largely unchanged; gains keyboard settings surfaces. | first-party |

> `keyboard-engine` is deliberately **pure JVM** (no `android.view`). It receives raw geometry/pointers as plain data and returns actions/candidates. This is the testability seam A2 found missing and the portability seam R4 demands. The `ime` module is the only place Android touch/draw lives.

### 2.2 Dependency diagram

```
                         ┌──────────────────────────────┐
                         │           core-math          │  pure JVM — UNCHANGED
                         │  MathEngine.evaluate(...)     │  no Android imports
                         └──────────────┬───────────────┘
                                        │
                         ┌──────────────┴───────────────┐
                         │         feature-glue         │  debounce + bg exec
                         │  MathEvaluator, Prefs,        │  + SuggestionSource reg.
                         │  FieldPolicy plumbing         │
                         └───┬───────────────────────┬───┘
                             │                       │
        ┌────────────────────┴───┐        ┌──────────┴───────────────────┐
        │     keyboard-engine    │        │           ime (app)          │
        │  (pure JVM)            │◄───────┤  TallyInputMethodService     │
        │  • LayoutEngine        │ uses   │  • InputConnection owner     │
        │  • ShiftState machine  │ ifaces │  • FieldPolicy (security)    │
        │  • InputAction reducer │        │  • KeyPlane (Canvas View)    │
        │  • GestureDecoder  ────┼─┐      │    multitouch/popups/repeat  │
        │  • WordPredictor   ────┼─┤      │  • SuggestionStrip (Views)   │
        │  • Autocorrector   ────┼─┤      │  • Panels host (emoji/clip)  │
        └──────────┬─────────────┘ │impl  │  • a11y NodeProvider host    │
                   │               │      └───┬─────────────┬────────────┘
        ┌──────────┴──┐   ┌────────┴─────┐    │             │
        │   layouts   │   │  prediction  │    │             │
        │ (data/assets│   │ n-gram LM,   │    │             │
        │  + parser)  │   │ lexicon,     │    │             │
        │  RTL/scripts│   │ autocorrect  │    │             │
        └─────────────┘   │ PERMISSIVE   │    │             │
                          │ DATA ONLY    │    │             │
                          └──────────────┘    │             │
                                      ┌────────┴───┐  ┌──────┴──────┐
                                      │  design-   │  │    emoji    │
                                      │  system    │  │  + clipboard│
                                      │ (chip,     │  │  CLDR data  │
                                      │  tokens)   │  └─────────────┘
                                      └────────────┘
```

**Rules the engineering agents must hold:** (a) arrows point toward dependencies; nothing points back into `core-math`. (b) `keyboard-engine` has zero `android.view`/`android.graphics` imports — enforced in CI exactly as `core-math`'s no-Android rule is. (c) `prediction`/`layouts` are reachable only through `keyboard-engine` interfaces, so the shell never hard-couples to a specific decoder or data format. (d) The math feature crosses into the shell **only** through `feature-glue` as a `SuggestionSource` (§4) — it never owns the strip.

---

## 3. Rendering & input pipeline

### 3.1 Views vs Compose vs hybrid

**Decision: classic Views with a custom `Canvas`-drawn key plane for the hot input path; Views for the strip and panels in v1. No Compose.** This is mandated by the stack constraint, and it is also the *correct* engineering call independently (V5): the keyboard key bed + gesture trail is the most latency-critical surface in the product, Compose in an IME needs manual `LifecycleOwner`/`ViewModelStoreOwner`/`SavedStateRegistryOwner` wiring on the IME window decor view, and Compose `Popup`/`Dialog` inside an IME hits window-token fragility — exactly where key-preview and long-press popups live. The only hard jank-parity datum for Compose (1.9.0) is for *scrolling*, not keypress latency, so it does not validate the keyboard case.

- **Key plane:** a single custom `View` subclass (`KeyPlaneView`) that draws keys, the gesture trail, and pressed-state to `Canvas`, and processes raw `MotionEvent`. **All geometry and hit-testing are delegated to `keyboard-engine.LayoutEngine` (pure functions)** so they are unit-tested without a View — this is the seam A2 flagged.
- **`SurfaceView` consideration:** start with a plain `View` + dirty-rect `invalidate`. Promote the key plane to `SurfaceView`/`TextureView` only if the latency gate (§8) fails on a mid-tier device. Decide empirically, not upfront.
- **Strip & panels:** Views in v1. Compose is explicitly **out** for v1 per the stack constraint; if ever revisited it would touch only cold chrome (settings), never the key bed, and only behind the latency gate.

### 3.2 IME service lifecycle (the spine)

`TallyInputMethodService` follows the canonical contract (R5). The single most common structural bug is misplacing per-field logic:

```
onCreate()              → one-time global resources (engine, executors)
onInitializeInterface() → rebuild Keyboard/layout descriptors on config change (NOT per field)
onCreateInputView()     → inflate KeyPlaneView + strip; cached
onStartInputView(info,  → PER-FIELD: read EditorInfo (inputType/imeOptions/capsMode),
   restarting)            derive FieldPolicy (§9), pick layout, reset shift/composing.
                          On restarting=true do NOT reset user-visible state.
onFinishInputView()     → tear down popups/repeat; finish composing
```

- Maintain a **single `KeyboardController`** created in `onInitializeInterface`/`onCreateInputView` and *reconfigured* (never recreated) in `onStartInputView`.
- Override `onEvaluateFullscreenMode()` → return `false` (honoring `IME_FLAG_NO_FULLSCREEN`/`NO_EXTRACT_UI`). The current IME never manages this; default landscape extract-mode breaks edge-to-edge apps.
- Override `onComputeInsets()` to report stable content/visible insets so host apps' `WindowInsetsAnimation` stays smooth. **Finalize keyboard height (including strip) before showing**; never change height mid-animation.

### 3.3 InputConnection discipline (correctness + latency)

Every `InputConnection` read is an IPC round-trip and is racy (R5, V6). Rules:

- **Never block the touch/render thread on a read.** Maintain a **local mirror** of the editing context: read once in `onStartInputView`, then update from `onUpdateSelection` and from our own commits. Re-reading per keystroke is the #1 cause of jank/ANRs.
- For the math context read specifically (V6): on the IME path, **call `finishComposingText()` before reading** so `getTextBeforeCursor` sees a committed, span-free buffer. Prefer `getSurroundingText(before, after, flags)` when `Build.VERSION.SDK_INT >= 31`; fall back to `getTextBeforeCursor`/`getTextAfterCursor` below 31 (we are minSdk 26). Treat `null` defensively (inactive/non-compliant editors).
- **Commit paths:** text → `commitText` / `setComposingText`; deletion → `deleteSurroundingTextInCodePoints` (surrogate/emoji-safe); action key → `performEditorAction`; cut/copy/paste/select-all → `performContextMenuAction`. Reserve `sendKeyEvent(KeyEvent)` for DPAD/Enter and `TYPE_NULL` editors only.
- **Batch edits:** wrap every compound edit (autocorrect replace, double-space→period, gesture commit, math-insert with span replacement) in `beginBatchEdit()`/`endBatchEdit()` (balanced, they nest). Guard `onUpdateSelection` against re-entrancy so our own batched edits don't recurse.

### 3.4 Touch layer — multitouch, popups, repeat, flick

The touch layer is the single biggest functional gap (A2: *critical*). Replace single-pointer with a **per-pointer tracker model** (mirroring AOSP `PointerTracker` patterns, Apache reference only):

```
PointerTracker (one per active finger, keyed by pointerId)
  ├─ downKey, downTime, lastKey (for rollover)
  ├─ slidePath  (for flick / gesture handoff)
  ├─ repeatTimer (delete/arrows: accelerating interval until up/cancel)
  └─ previewPopup, longPressPopup (own lifecycle)

KeyPlaneView.onTouchEvent dispatches by masked action:
  ACTION_DOWN / ACTION_POINTER_DOWN  → new tracker at getPointerId(getActionIndex())
  ACTION_MOVE                        → update each tracker by its pointerId (rollover allowed)
  ACTION_UP / ACTION_POINTER_UP      → resolve tracker → emit InputAction
  ACTION_CANCEL                      → tear down ALL popups/timers (parent intercept)
```

- **Key-preview popups:** on `ACTION_DOWN`, show a magnified key bubble above the finger via `PopupWindow`/overlay layer; hide on up. *Suppress entirely for masked fields* (§9).
- **Long-press / popup alternate keys:** the `Key` model gains a `moreKeys`/`longPressAlternates` field (the current model has none — A2). On down, a `Handler.postDelayed` arms a long-press; on timeout, show a mini-keyboard popup and route subsequent move/up to select among alternates (accents é/ñ/ü, secondary symbols, long-press number row). Accents are mandatory for almost every Latin language — this is table-stakes, not polish.
- **Key repeat:** repeatable keys (delete, arrows) schedule accelerating fires via a centralized `TimerHandler` until up/cancel.
- **Flick:** record origin on down; on threshold-crossing move or up, compute a direction vector → alternate glyph (used for symbol flicks and CJK kana later). Optional in v1; the tracker carries the slide path regardless.
- **Cursor control / selection:** space-bar swipe → cursor move (`sendKeyEvent(DPAD)` or `setSelection` on the mirror); swipe-on-delete → word delete. These reuse the per-pointer slide path.
- **Shift state machine** (in `keyboard-engine`, unit-tested): tri-state `off → shifted → locked` with double-tap-to-lock, latched-vs-locked **visual** states, and auto-capitalization honoring `EditorInfo` `capsMode`. The current one-shot-only shift is a daily-friction gap.
- **Feedback:** `performHapticFeedback(KEYBOARD_TAP)` + `AudioManager.playSoundEffect(FX_KEYPRESS_*)` on `ACTION_DOWN`, user-toggleable (the current haptic flag only reaches the math chip).

### 3.5 Gesture-typing pipeline

Glide runs **off the UI thread** and delivers candidates async to the strip. Stages (R5):

1. **Capture** — accumulate `(x, y, t)` samples from `ACTION_MOVE` on the key plane; **do not commit per-key during a glide.**
2. **Normalize/resample** — fixed-count or arc-length-uniform resampling; features = positions, velocity, key-proximity.
3. **Prune** — gate the lexicon by length bounds and first/last-key proximity to gesture endpoints.
4. **Score** — distance of the gesture to each candidate's ideal key-path under a spatial (Gaussian) model, combined with a frequency/LM prior.
5. **Rank/commit** — top candidate via `setComposingText` (stays correctable); alternates fill the strip.

All of this sits behind `keyboard-engine.GestureDecoder`. The v1 implementation is the **ported Apache-2.0 statistical classifier**; a future neural decoder (ONNX/XNNPACK, CleverKeys-style — but note CleverKeys itself is GPL and cannot be embedded, V4) swaps in behind the same interface with no shell changes.

```kotlin
// keyboard-engine — interfaces only; impls live in prediction/keyboard-engine
interface GestureDecoder {
    fun setLexicon(words: WordList, freqs: FreqTable)
    /** path = sampled points; geometry = key centers/bounds. Off-UI-thread. */
    fun decode(path: GesturePath, geometry: KeyGeometry): List<Candidate>
}
```

---

## 4. Suggestion & prediction architecture

**The core integration question:** how do math results coexist with word prediction/autocorrect in one strip? **Decision: a single strip is fed by multiple `SuggestionSource`s; math is one source with a reserved, highest-priority slot when it has a result.** Math never owns the strip (today it exclusively does — A2), and word prediction never crowds it out.

### 4.1 The strip contract

```kotlin
// keyboard-engine
enum class SuggestionKind { MATH, AUTOCORRECT, PREDICTION, NEXT_WORD, EMOJI, CLIPBOARD, INLINE_AUTOFILL }

data class Suggestion(
    val kind: SuggestionKind,
    val display: CharSequence,     // what the strip shows
    val priority: Int,             // higher wins the reserved slot
    val onCommit: (InputConnection) -> Unit  // how tapping applies it (batch-edited)
)

interface SuggestionSource {
    /** Called on the bg executor with the current (mirrored) context + FieldPolicy. */
    fun query(ctx: EditingContext, policy: FieldPolicy): List<Suggestion>
}
```

- **Math source** = a thin adapter over `feature-glue.MathEvaluator` → `core-math`. It already runs on a 90ms-debounced bg executor with a main-thread callback; we keep that and register it as a `SuggestionSource`. Its `onCommit` reuses today's insert-result path (the `KeyboardHost.insertResult` seam) wrapped in a batch edit, optionally replacing the detected span (`Suggestion.span`) or inserting `exactValue` for an "insert exact" action.
- **Word sources** (`prediction` module) = autocorrect, current-word completion, next-word — produced from the composing region + n-gram LM.
- **Strip composition rule:**
  - **Reserved math slot (leading):** when the math source returns a result, it occupies a visually distinct, leading slot (the `MathResultChip`) and is **never** displaced by word candidates. This preserves the product's identity.
  - **Candidate slots (trailing):** autocorrect/prediction/next-word fill the remaining slots, ranked by source priority then score, deduped, coalesced to ≤1 update per input event.
  - When math has no result, the whole strip is word candidates — a normal modern keyboard.
- **Math + composing coexistence:** math reads a *committed, finished* buffer (§3.3, V6); word prediction drives the *composing* region. These do not collide because math triggers on its detector (default trailing `=`) over committed text, while prediction operates on the in-flight word. A live-preview math mode (pre-`=`, off by default per A1/spec) would use a stricter detector profile and the same reserved slot.
- **Threading:** all sources are queried on the bg executor; results post to the strip on the main thread. Synchronous suggestion computation on the input thread is forbidden (re-introduces the IPC/jank problem).
- **Tap → commit:** every `Suggestion.onCommit` performs a batch-edited composing-region replacement (`setComposingRegion`/delete + `commitText`), then advances. This is composing-aware so it cannot corrupt the editor.

> **Inline autofill (API 30+)** lands in the *same strip* as a distinct `SuggestionKind.INLINE_AUTOFILL`: declare `supportsInlinedSuggestions`, implement `onCreateInlineSuggestionsRequest` (theme `InlinePresentationSpec` to the keyboard) and `onInlineSuggestionsResponse` (inflate `InlineContentView`s into reserved strip cells). The IME never sees suggestion contents (privacy by design). Gated on API 30; deferral owned by `04-feature-matrix-and-roadmap.md`.

---

## 5. Layouts, languages & i18n model

**Decision: data-driven layouts loaded from assets; one parser, zero code-per-layout.** The current four hard-coded Kotlin literals (with hand-balanced widths and in-line arithmetic comments — A2) are replaced by a declarative format the `layouts` module parses into `keyboard-engine` descriptors.

### 5.1 Layout data model

```
LayoutDefinition
  ├─ id, script, direction (LTR | RTL), defaultLocale
  ├─ rows: [ Row(keys: [KeyDef]) ]            // widths are *fractions*, validated to sum ≤ 1.0
  └─ KeyDef(primaryCode, label, moreKeys[], widthFraction, isSpecial, flickMap?)
```

- **Width validation/normalization** at parse time (the current grid silently misaligns if a row ≠ 10 units). Rows that don't fit are rejected in CI, not at runtime.
- **Layout coverage (v1 → growth):** QWERTY/AZERTY/QWERTZ/Dvorak/Colemak for Latin; then Cyrillic, Greek, Arabic, Hebrew, Devanagari, and the CJK path (librime, 3-clause BSD, deferred per R7). Adding a layout = adding a data file, not a recompile.
- **Subtype registry:** `method.xml` today declares a single `en_US` subtype. Move to a **subtype-per-(language, layout)** model with a globe-key cycler and language switcher. The subtype list is generated from the layout assets + the languages that have vetted permissive dictionaries.

### 5.2 RTL & complex scripts

- **RTL** (Arabic/Hebrew): layout `direction = RTL` mirrors key ordering and the strip; the IME respects the editor's bidi via standard text APIs. Cursor/selection gestures invert direction.
- **i18n correctness already partly solved in `core-math`:** the math detector/formatter is locale-aware (decimal/grouping separators, percent). The keyboard's chosen locale (active subtype, with `TallyPreferences.localeOverride`) is the locale passed to `MathEngine.evaluate(...)`. Keep these consistent so `1,5` (de-DE) vs `1.5` (en-US) resolves the same way the user's layout implies.
- **Per-language dictionaries gate language enablement:** a language ships its layout *and* prediction only when a permissively-licensed lexicon exists (R4/R7). Layouts are cheap; **data provenance is the gating work** and is tracked per language. English seeds from SCOWL/ESDB.

---

## 6. Accessibility

**Decision: implement a per-key virtual view hierarchy via `ExploreByTouchHelper` (the `AccessibilityNodeProvider` it supplies). This is a launch blocker, not an enhancement.** A Canvas key plane is one opaque node to TalkBack/Switch Access today (A2: *critical*); blind users physically cannot type, and it is a Play-policy and ethical/legal exposure.

`KeyPlaneView` attaches one `KeyboardExploreByTouchHelper` (via `ViewCompat.setAccessibilityDelegate`) and forwards `dispatchHoverEvent`. It maps each key to a **stable virtual id**:

```
getVisibleVirtualViews(list)              → enumerate current key ids (from LayoutEngine)
getVirtualViewAt(x, y)                    → hit-test to a key id (reuses LayoutEngine.hitTest)
onPopulateNodeForVirtualView(id, node)    → contentDescription (incl. long-press alternates),
                                            className, setBoundsInParent/Screen = key rect,
                                            addAction(ACTION_CLICK), focusable/clickable
onPerformActionForVirtualView(id, action) → ACTION_CLICK routes to the SAME path as a real tap
```

- **Explore-by-touch:** dragging a finger announces each key; lift-to-type. Node bounds stay in sync with `LayoutEngine` geometry; a key's node is invalidated when its label/state changes (shift, layer switch).
- **Strip a11y:** keep the strip-level `contentDescription` (`chip_insert_description`) and per-candidate descriptions; the math chip already exposes its own.
- **Validation gate:** TalkBack explore-by-touch + Switch Access traversal are acceptance criteria for the rebuild, owned by the testing plan and `04`'s definition-of-done.

---

## 7. Theming / Material You / form factors

### 7.1 Theming

- **Theme model resolved per-draw**, not captured at construction (the current view caches colors in `init`, so a runtime theme change needs a view recreate — A2). `design-system` owns theme tokens (key bg/special/pressed/text, strip bg, popup styles).
- **Material You / dynamic color (Apache-2.0, Material 1.12):** derive tonal palettes from wallpaper on Android 12+ (graceful static fallback below 12). User-selectable themes, adjustable opacity, optional key-background images. No network, no data concern.

### 7.2 Form factors

The current keyboard is fixed 54dp rows, portrait-only, no inset/fullscreen management (A2: *high*). Target form-factor matrix:

| Mode | Behavior | API/notes |
|---|---|---|
| Portrait / landscape | Responsive row sizing from screen metrics in `onMeasure`; landscape compaction; honor `onConfigurationChanged` | all |
| Number row | Optional dedicated top row (toggle) | all |
| Split | Two half-keyboards for large screens/tablets | width-gated |
| One-handed | Shift the key plane left/right with a return affordance | all |
| Floating / movable | Detached, draggable window | gated on size |
| Resizable height | User-adjustable height persisted in prefs | all |
| Insets/fullscreen | `onComputeInsets` stable height; `onEvaluateFullscreenMode → false` | edge-to-edge cooperation |

Tablets and landscape are first-class on competitors; a fixed-height portrait grid is unusable on large screens. Device/OEM specifics (One UI quirks, foldables) are owned by `05-device-framework-compatibility.md`.

---

## 8. Performance budget & threading

**Threading model:**

| Thread | Owns | Must never |
|---|---|---|
| Main/UI | `Canvas` draw, `MotionEvent`, popup/repeat timers, `InputConnection` *writes* (commits), strip updates | block on `InputConnection` reads; run decode/eval/LM |
| Bg single-thread executor (reuse `feature-glue`'s) | math eval (90ms debounce), suggestion-source queries | touch the View tree |
| Decoder worker | gesture decode, n-gram scoring, autocorrect | post results except via main-thread handler |

**Latency budget (acceptance gates, measured on a mid-tier device, R8/release build):**

| Path | Budget |
|---|---|
| Touch-down → key-preview render | ≤ 1 frame (~16 ms) |
| Key-down → committed char visible | ≤ 1 frame |
| Key-repeat interval jitter | within frame budget, no dropped frames |
| Gesture end → candidates in strip | async; target < ~150 ms, never blocks input |
| Math eval debounce → chip | 90 ms debounce (existing), eval off-UI |

- **Draw discipline:** dirty-rect `invalidate` per changed key, not full-view; pre-allocate paints; no allocation in `onDraw`/`onTouchEvent`.
- **Read discipline:** local mirror only (§3.3); coalesce suggestion updates to ≤1 per input event.
- **Decoder budget:** decode is bounded and cancellable; a newer gesture cancels an in-flight decode.
- **Inset stability:** finalize height before show; animate internal sections within a fixed window height so host `WindowInsetsAnimation` stays smooth (§3.2).

---

## 9. Security hooks

**Decision: a single `FieldPolicy` derived from `EditorInfo` in `onStartInputView` is the one source of truth, enforced at every surface (decoder, strip, preview popup, persistence). Default to most-private when uncertain.** This is the privacy-first contract made operational and is the hook `06-security-privacy-hardening.md` builds on. Leaking a password into a suggestion bar, learned dictionary, key-preview, or log is a serious defect and a Play-policy risk (R5).

```kotlin
// derived once per field; passed to every SuggestionSource and the key plane
data class FieldPolicy(
    val learningEnabled: Boolean,     // false on IME_FLAG_NO_PERSONALIZED_LEARNING
    val suggestionsEnabled: Boolean,  // false on password / NO_SUGGESTIONS
    val glideEnabled: Boolean,        // false when suggestions disabled
    val previewMasked: Boolean,       // true → no key-preview popup of typed glyph
    val persistAllowed: Boolean,      // false → no clipboard/learning/recents writes
    val mathEnabled: Boolean,         // optionally gate math in secure fields
)
```

Derivation rules:

- **`IME_FLAG_NO_PERSONALIZED_LEARNING`** (incognito/private modes) → `learningEnabled = false`: no LM/history updates, no personalized suggestions for that field. Best-effort but respected.
- **Password fields** (`TYPE_TEXT_VARIATION_PASSWORD` / `VISIBLE_PASSWORD` / `NUMBER_VARIATION_PASSWORD`) and **`TYPE_TEXT_FLAG_NO_SUGGESTIONS`** → `suggestionsEnabled = false`, `glideEnabled = false`, `previewMasked = true`, `persistAllowed = false`. Do not show typed text in preview/strip; never persist or log content.
- **Clipboard manager / recents / personalization store** honor `persistAllowed`; sensitive clips encrypt-at-rest and auto-expire (R7).
- **The math feature inherits the policy too:** in masked fields, suppress the chip unless `mathEnabled` is explicitly allowed (open product question — see open issues). Math eval is on-device and stateless, so it leaks nothing by computation; the risk is *display* in a masked context.
- **No-network guarantee stays load-bearing:** no module in this architecture introduces `INTERNET`. CI asserts the merged manifest has no `INTERNET` permission. Any future asset download (e.g., voice models, R7) must be isolated from the keyboard process and is out of scope here.

---

## Appendix A — Interfaces touched/added (sketch only)

```kotlin
// PRESERVED seam (today): the only crossing point for the math feature.
interface KeyboardHost {
    fun getTextBeforeCursor(maxLength: Int): CharSequence?
    fun insertResult(text: String)     // → wrap in batch edit; optional span replace / exactValue
    fun clearSuggestion()
}

// keyboard-engine (new, pure JVM, unit-testable):
interface LayoutEngine {
    fun layoutFor(def: LayoutDefinition, width: Int, height: Int): KeyGeometry
    fun hitTest(geometry: KeyGeometry, x: Float, y: Float): KeyId?
}
interface WordPredictor { fun predict(ctx: EditingContext, policy: FieldPolicy): List<Suggestion> }
interface Autocorrector { fun correct(word: CharSequence, ctx: EditingContext): Suggestion? }
// + GestureDecoder, SuggestionSource (shown in §3/§4)
```

These are *minimal sketches to remove ambiguity*, not specifications. Concrete signatures are the implementing agents' to finalize within these boundaries.

---

## Open issues for `00-master-plan.md`

The following are genuine product/architecture decisions above this document's pay grade; the recommended option is stated first where one exists.

1. **Math in secure/password fields.** Recommend: suppress the math chip when `previewMasked` (treat math display like any other suggestion display). Confirm whether silent on-device math (no display) in masked fields is ever desirable.
2. **Glide in v1 or deferred.** Recommend: defer glide out of the v1 critical path; ship the ported Apache statistical classifier as a phased, behind-interface capability with explicit "trails Gboard" expectations. Master plan must set the launch bar.
3. **Live pre-`=` math preview.** A1/spec mark it off-by-default with a stricter detector profile. Confirm it is in scope at all for the rebuild, and which detector gates (stability window, min confidence) apply.
4. **Launch language/script set.** Determines how much of `layouts`/`prediction`/data-vetting is v1. CJK (librime/BSD) and Indic (GoVarnam, license TBC) are deep per-language programs — in or out?
5. **Inline autofill & stylus handwriting timing.** Both are independent, API-gated modules (30 and 34). Confirm whether either is required at launch or deferred to `04`.
6. **`SurfaceView` vs `View` for the key plane.** Recommend: start with `View`; promote only if the §8 latency gate fails. Master plan should ratify the empirical gate rather than pre-committing.
7. **Distribution split.** ADR-0001 flags that the overlay likely cannot pass Play's Accessibility policy → off-Play APK or cut. The IME ships on Play. Confirm the split so the IME architecture does not depend on overlay co-presence (it does not, by design here).
