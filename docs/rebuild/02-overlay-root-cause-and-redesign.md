# 02 — Overlay: Root-Cause Analysis & Redesign

> **Scope of this document.** Why the Tally accessibility overlay fails to insert math results
> reliably (most acutely with Samsung Keyboard / Honeyboard), the redesign that makes it
> *best-effort-correct*, the honest Google Play reality, and the **decision** on where the overlay
> sits in the product. This document covers **only** the overlay surface and its insertion
> mechanics. The IME redesign is in `03-ime-architecture-v2.md`; the device/OEM test matrix is
> shared with `05-device-framework-compatibility.md`; secure-field handling and the privacy
> contract are owned by `06-security-privacy-hardening.md`; rollout sequencing is in
> `07-agent-execution-plan.md`. Cross-reference, do not duplicate.

---

## 0. TL;DR for the executing agents

1. **The IME is the canonical insertion path. The overlay is reclassified as a best-effort
   assist for *third-party keyboards only*.** When Tally's own IME is active, the overlay is
   redundant and MUST stay dormant (see §6).
2. **An `AccessibilityService` is structurally the wrong tool for reliable insertion against a
   live IME.** It cannot see composing text, cannot read the clipboard in the background on
   Android 10+, and must guess focus/placement across windows. These are platform contracts, not
   Tally bugs we can out-engineer.
3. **The clipboard insertion path must be deleted, not patched.** Replace it with
   `ACTION_SET_TEXT` of reconstructed full-field text + `ACTION_SET_SELECTION` (§4). The current
   `ClipboardInserter` silently wipes the user's clipboard on Android 10+ — a data-loss bug.
4. **Samsung breakage is over-determined** by three independent causes (composing-region
   staleness, IME-window z-order occlusion, clipboard toasts). Fixing one does not fix Samsung.
5. **Distribution: the overlay is shippable on Play *with* a Permission Declaration + prominent
   disclosure/consent — it is NOT flatly banned — but it carries real review/rejection risk, will
   silently break for Advanced-Protection-Mode users on Android 17+, and per ADR-0001 is best
   treated as off-Play / deferred.** See §5 for the nuance and §6 for the decision.

---

## 1. Current overlay implementation summary

The `overlay` module (~399 LOC) is a `TYPE_ACCESSIBILITY_OVERLAY` chip driven by an
`AccessibilityService`. Verified flow (file:line confirmed against the repo):

| Stage | Mechanism | Source |
|---|---|---|
| Enable | User enables the service in Accessibility Settings; `OverlayConsentActivity` gates onboarding | `OverlayConsentActivity.kt`, `OverlayPermissionState.kt` |
| Observe | `onAccessibilityEvent` handles `TYPE_VIEW_FOCUSED`, `TYPE_VIEW_TEXT_CHANGED`, `TYPE_VIEW_TEXT_SELECTION_CHANGED`; `TYPE_WINDOW_STATE_CHANGED` → `clearSuggestion()` | `TallyOverlayService.kt:63–81` |
| Read | `node.text?.toString()` + `node.textSelectionEnd.coerceAtLeast(0)` → `TextExtractor.extractBeforeCursor` | `TallyOverlayService.kt:107–109`, `TextExtractor.kt:18–21` |
| Evaluate | Same `core-math` engine via `MathEvaluator` (90 ms debounce, bg executor → `mainHandler.post`) | `TallyOverlayService.kt:111–115` |
| Position | `y = fieldBounds.top − chipH − margin`, coerced to a min; `getBoundsInScreen` only | `OverlayChipWindow.kt:86–104` |
| Show | `TYPE_ACCESSIBILITY_OVERLAY`, `FLAG_NOT_FOCUSABLE | FLAG_NOT_TOUCH_MODAL | FLAG_LAYOUT_IN_SCREEN`; `filterTouchesWhenObscured = true` | `OverlayChipWindow.kt:92–103, 33` |
| Insert | `ClipboardInserter`: `setPrimaryClip(result)` → `ACTION_PASTE` → restore previous clip after **500 ms** | `ClipboardInserter.kt:28–41` |
| Service config | `canRetrieveWindowContent=true`, `feedbackGeneric`, `notificationTimeout=100`; **no** `flagRetrieveInteractiveWindows` | `overlay_accessibility_service.xml` |

The math engine itself is sound and shared; **the defect surface is entirely in event handling,
text reading, positioning, and insertion** — i.e., everything *around* `core-math`.

---

## 2. Root-cause analysis

Severity legend: **Critical** = correctness/privacy/data-loss; **High** = frequent failure on
target OEMs; **Medium** = degraded UX / edge cases; **Low** = polish.

Each row's "Evidence" is repo file:line and/or the platform contract. Where the grounding
verification downgraded a claim, the row says so explicitly — agents must not over-assert.

| # | Failure (observed) | Root cause | Severity | Evidence / verification note |
|---|---|---|---|---|
| **F1** | Canonical flow `2+2=` never produces a chip on Gboard/Samsung | **Composing-text staleness.** Modern IMEs hold in-progress input in an `InputConnection` composing region. It *is* written into the editor's `Editable` and *can* surface via `getText()`/`TYPE_VIEW_TEXT_CHANGED`, **but** Samsung Honeyboard keeps a large, persistent composing region and does **not** commit per keystroke, and `=` does not auto-commit on most keyboards. So at the moment of `=` the expression is provisional and reads are **stale/unreliable**, not strictly invisible. | **Critical** | `TallyOverlayService.kt:107`. **Verification nuance (V1a, V6):** the absolute framing "invisible until committed" is *overstated*; the real defect is **timing/staleness**, OEM-dependent. An `AccessibilityService` has no `InputConnection`, so it cannot `finishComposingText()` to force a clean read — that capability is the IME's alone. This is the load-bearing reason Samsung fails. |
| **F2** | Chip flashes then vanishes the instant the field is focused | `TYPE_WINDOW_STATE_CHANGED → clearSuggestion()` **unconditionally** (`:79`); `clearSuggestion()` cancels the evaluator and dismisses the chip (`:141–145`). Any window transition — popups, autocorrect, the Android 12+ clipboard toast — triggers blur. | **High** | `TallyOverlayService.kt:79, 141–145`. **Verification nuance (V1c):** the soft-keyboard window more reliably surfaces as `TYPE_WINDOWS_CHANGED`, not `TYPE_WINDOW_STATE_CHANGED`; emission is OEM/version-dependent. Regardless, **mapping *any* window event to "blur" is a self-inflicted, too-broad mapping** — the correct blur signal is loss of input focus on the editable node, not window churn. |
| **F3** | Insertion sometimes does nothing; user's real clipboard gets wiped | **Clipboard path is fundamentally fragile on Android 10+.** Background **read** of `primaryClip` is **blocked** (only the focused app or default IME may read), so `previousClip` is `null`, and `restoreClipboard` then calls `clearPrimaryClip()` — **silently destroying the user's clipboard**. `ACTION_PASTE` reliability varies by OEM/field. The fixed **500 ms** restore is a race. | **Critical** | `ClipboardInserter.kt:28–41`. **Verification nuance (V1b):** the **read** block is fully confirmed by Android 10 docs. The **write** half is *refuted*: `setPrimaryClip()` from background generally still works on 10–12. So the bug is precisely the **read-then-restore round-trip**: you can write the result but cannot snapshot/restore the prior clip → the `clearPrimaryClip()` on null is the data-loss path. Android 12+ also shows a clipboard toast on reads. |
| **F4** | Expression dropped on Samsung even when full text is present | `node.textSelectionEnd` is `-1` (unreported selection — common on Samsung / focus-without-caret). Code does `.coerceAtLeast(0)` → `0`; `TextExtractor` returns `""` for `cursorEnd <= 0` (`:18`). Plus `findFocus` re-query on a possibly-stale tree after the event node was `recycle()`d. | **High** | `TallyOverlayService.kt:108, 119, 131`; `TextExtractor.kt:18`. Selection fields are optional/OEM-dependent; `-1` is valid and frequent. |
| **F5** | Wrong-window focus / placement in split-screen, DeX, foldables | a11y XML declares only `canRetrieveWindowContent`; **no `flagRetrieveInteractiveWindows`**, so `getWindows()` and cross-window focus resolution are limited. `notificationTimeout=100` coalesces fast keystrokes. | **Medium** | `overlay_accessibility_service.xml`. `flagRetrieveInteractiveWindows` is the standard flag for any service positioning UI relative to focused content across windows. |
| **F6** | Chip overlaps the status bar / floats detached on One UI; **occluded by the keyboard** | Positioning uses `fieldBounds.top` only, ignores `WindowInsets` (status bar, cutout, IME). Coercion pins the chip over the notch when the field is near the top. **Separately and decisively:** `TYPE_ACCESSIBILITY_OVERLAY` is z-ordered **below the IME window**, so a tall Honeyboard (toolbar + suggestion strip + number row) covers any bottom-anchored chip and **swallows its taps**. | **Medium** (placement) / **High** (IME occlusion) | `OverlayChipWindow.kt:86–104`. IME-occlusion is a hard platform z-order fact, not a tuning problem — it is why "works on Pixel, not clickable on Samsung." |
| **F7** | **Secure-field result leakage** (privacy-contract violation) | The overlay never checks `node.isPassword`, password `InputType` variations, `IME_FLAG_NO_PERSONALIZED_LEARNING`, or incognito context **before** reading field text into the engine and **before** pushing the result through the global clipboard. For a "nothing leaves device" product, routing field-derived content through a cross-app channel near/inside password fields is a direct policy breach. | **Critical** | No guard anywhere in `TallyOverlayService.kt` / `ClipboardInserter.kt`. **Verified (V7):** `isPassword` + password input types + obscured-window detection are real APIs and the suppression obligation holds. Owned jointly with `06-security-privacy-hardening.md`. |
| **F8** | Race/cross-thread node access; insertion targets a changed editor | `MathEvaluator` posts `onResult` to the **main** thread, but `onAccessibilityEvent` runs on the **a11y callback thread**; the original event node is already `recycle()`d when the async result returns. No token ties "text I evaluated" to "node I insert into." | **High** | `MathEvaluator.kt:31–39`, `TallyOverlayService.kt:47–60, 75`. Mixing tree queries across threads with intervening `recycle()` is a lifetime/correctness hazard. |
| **F9** | Stale chip re-appears for a field the user left; stale insert proceeds | No generation/sequence guard: a result already posted to the main handler arrives after `clearSuggestion()` and re-shows. At tap time the suggestion span is not re-validated against live text. `show`/`dismiss` swallow `IllegalState`/`IllegalArgument` exceptions, masking ordering bugs. | **Medium** | `TallyOverlayService.kt:47–56, 129`; `OverlayChipWindow.kt:61–64, 75–77`. |
| **F10** | RTL / localized-digit expressions missed; `localeOverride` ignored on overlay | `TextExtractor` slices by char index with no bidi/digit-shaping awareness; the overlay path never forwards the resolved locale to the evaluator (defaults to `Locale.getDefault()`), so `TallyPreferences.localeOverride` is silently ignored. | **Low** | `TextExtractor.kt:19–21`; `TallyOverlayService.kt:111–115` (no locale arg). |
| **F11** | Onboarding misrepresents service state | `OverlayConsentActivity` sets `btn_done.isEnabled = true` unconditionally; `OverlayPermissionState` keys off `getEnabledAccessibilityServiceList(FEEDBACK_ALL_MASK)`, brittle if feedback type narrows. | **Low** | `OverlayConsentActivity.kt:45`, `OverlayPermissionState.kt`. |

**Synthesis.** F1, F3, F7 are *systemic* (the overlay's premise is unsound for reliable
insertion). F2, F4, F8, F9 are *self-inflicted* and fixable. F5, F6, F10, F11 are *correctness/UX
debt*. Samsung is where F1 + F6(occlusion) + F3(clipboard toasts) compound — none visible on a
Pixel test device.

---

## 3. Why accessibility overlays are fundamentally constrained on modern Android

These are **platform contracts**, not Tally bugs. Agents should internalize them so they do not
attempt to "fix" the unfixable.

1. **No access to composing text.** The composing region lives in the `InputConnection` between
   the IME and the target editor — *not* in the overlay's process. `AccessibilityNodeInfo` exposes
   `getText()` / `getTextSelectionStart()` / `getTextSelectionEnd()` and has **no composing-region
   concept**. *Evidence that this gap is real:* Android 17 (API 37) had to **add**
   `AccessibilityEvent.setTextChangeTypes()` (composition vs commit) and
   `TextAttribute.isTextSuggestionSelected()` specifically to surface composition state to a11y
   services — confirming pre-17 services cannot distinguish in-flight from committed text (V6).
2. **No atomic "insert at cursor."** `ACTION_SET_TEXT` is **replace-all** and moves the caret to
   the end. Insertion must be emulated: read → splice → set whole text → restore selection. This
   can desync the IME's composing region and is rejected/ignored by some WebView, Jetpack Compose
   `BasicTextField`, and OEM editors.
3. **Background clipboard read is blocked (Android 10+); reads toast (Android 12+).** A background
   a11y service is neither the focused app nor the default IME, so `getPrimaryClip()` returns
   empty. You can *write* but not *read-verify or restore* — making any clipboard round-trip
   unsafe (V1b).
4. **Z-order below the IME.** `TYPE_ACCESSIBILITY_OVERLAY` sits above app windows but **below** the
   IME (and status bar). A tall keyboard occludes anything bottom-anchored and eats its taps.
5. **Sensitive fields are going dark.** Android 14+ `accessibilityDataSensitive`
   (`ACCESSIBILITY_DATA_PRIVATE_YES`) lets apps hide login/2FA/payment fields from any service not
   declared `isAccessibilityTool=true`. The addressable surface **shrinks over time**.
6. **Event churn.** `TYPE_WINDOW_CONTENT_CHANGED`/`TYPE_WINDOWS_CHANGED` fire at high frequency
   (~100+/min observed), forcing aggressive debounce and read-back verification rather than
   reaction-per-event.

**Conclusion (vindicates ADR-0001):** `InputConnection` is the *only* API contract that guarantees
visibility of in-progress text and deterministic insertion. Everything the overlay does is a lossy
approximation. **The IME is the source of truth; the overlay is, at best, a degraded assist for
*other* keyboards.**

---

## 4. Redesign — recommended architecture (best-effort overlay)

> Build this **only** to the extent the overlay ships at all (see §5–§6). Even fully implemented,
> it will not reach Gboard/Samsung parity. Engineer it to be **correct-or-silent**, never
> data-destructive.

### 4.1 Service configuration (a11y XML)

```xml
<accessibility-service
    android:accessibilityEventTypes="typeViewFocused|typeViewTextChanged|typeViewTextSelectionChanged|typeWindowsChanged"
    android:accessibilityFeedbackType="feedbackGeneric"
    android:accessibilityFlags="flagRetrieveInteractiveWindows|flagReportViewIds"
    android:canRetrieveWindowContent="true"
    android:notificationTimeout="0" />
```

- Replace `typeWindowStateChanged` with `typeWindowsChanged` for IME-window awareness; **remove
  the blanket blur-on-window-event mapping** entirely.
- Add `flagRetrieveInteractiveWindows` (cross-window focus/placement, fixes F5) and
  `flagReportViewIds`. `notificationTimeout=0` stops keystroke coalescing.
- **Do NOT declare `isAccessibilityTool=true`** — a math overlay does not qualify and the false
  claim invites takedown (see §5).

### 4.2 Event handling & focus tracking (fixes F2, F8, F9)

- **Blur only on real focus loss.** Track the focused editable node identity (`packageName` +
  window id + a stable signature). Clear the suggestion only when input focus leaves an editable
  node, or the focused package changes — never on a bare window event.
- **Generation guard.** Tag every evaluation with a monotonically increasing request id; apply a
  result only if its id is still current. A late callback after a clear is dropped.
- **Single-thread discipline.** Resolve focus and read/insert on **one** consistent thread (post
  tree queries back to the service's handler). Do not query the tree from the main-handler
  continuation of a background evaluation.
- **Do not call `recycle()` on API 33+** (it is a deprecated no-op and a use-after-recycle hazard).

```
on a11y event (editable focus / text / selection change):
    node = resolveFocusedEditable()            // findFocus(FOCUS_INPUT), verify isEditable & isFocused
    if node == null: clear(); return
    token = FocusToken(pkg, windowId, node.signature)
    reqId = ++generation
    text  = readReconciledText(node)           // §4.3
    evaluate(text, reqId)                       // bg

on evaluation result(suggestion, reqId):
    if reqId != generation: return             // stale
    re-resolve focus; if token mismatch: clear(); return
    position & show chip (§4.5)
```

### 4.3 Composing-text strategy (mitigate F1 — cannot fully fix)

The overlay **cannot** read or finish the composing region. Strategy:

- **Trigger on committed text, not the live `=` keystroke.** Re-read on
  `TYPE_VIEW_TEXT_SELECTION_CHANGED` and after a debounce, and **always `node.refresh()`** before
  reading rather than trusting cached event text.
- On **Android 17+**, subscribe to `getTextChangeTypes()` and act on **commit** changes only;
  ignore composition-only updates. Use `isTextSuggestionSelected()` for CJKV candidate selection.
- **Set product expectations:** on composing-heavy keyboards the chip will reliably appear only
  **after** the user commits (space/punctuation/suggestion tap). Document this as a known
  limitation; the IME path is the one that handles the live `=`.

### 4.4 Insertion strategy — **delete the clipboard path** (fixes F3, F7; mitigates Samsung corruption)

Replace `ClipboardInserter` entirely with a tiered, clipboard-free strategy:

```
insert(node, result):
    if isSecure(node): return                    // §4.6 hard gate — never reached for secure fields
    node.refresh()
    full      = node.text ?: ""
    selEnd    = node.textSelectionEnd; if selEnd < 0: selEnd = full.length   // F4: append at end
    selStart  = node.textSelectionStart; if selStart < 0: selStart = selEnd

    // Samsung-safety: collapse any composing region before mutating (R3).
    // We cannot finishComposingText() from a11y, but a SET_SELECTION to a collapsed
    // caret reduces the "restore composing region" duplication path.
    node.performAction(ACTION_SET_SELECTION, args(selEnd, selEnd))

    newText   = splice(full, selStart, selEnd, result.display)   // replace matched span or insert
    ok        = node.performAction(ACTION_SET_TEXT, arg(SET_TEXT_CHARSEQUENCE, newText))
    if !ok: return                               // F3 rule: NEVER fall back to clipboard-clear
    node.refresh()
    if node.text != newText: return              // read-back verify; bail on mismatch
    node.performAction(ACTION_SET_SELECTION, args(caretAfterInsert, caretAfterInsert))
```

Rules the agents MUST honor:

- **No clipboard, ever, in the insertion path.** This removes F3's data-loss, the Android 12+
  toast, the One UI clipboard alert, and the privacy leak in one move.
- **`ACTION_SET_TEXT` is replace-all** → always reconstruct the **full** field text. *Trade-off:
  this widens the read scope from "cursor-prefix only" to "whole field," which is a deliberate
  privacy-scope change that must be approved (see Open Issues).*
- **Never silently clip-wipe.** If `SET_TEXT` is unsupported/ignored, **no-op** (the chip simply
  does nothing) — do not degrade to clipboard.
- **Read-back verify** (`node.refresh()` + compare) defends against Samsung's `getExtractedText`
  misuse and composing-region duplication (R3): trust the field's value, not the keyboard's view.
- **Samsung corruption guard:** collapse selection before `SET_TEXT`; add explicit regression
  checks for "insert into pre-filled field" and "insert after hide/reshow keyboard"
  (flutter#31512 / #51893 repro patterns) in `05-device-framework-compatibility.md`.

### 4.5 Positioning / insets / occlusion (fixes F6)

- Compute placement from the focused **window's frame** + current `WindowInsets` (status bar,
  cutout, **IME inset**), constrained to that window (needs `flagRetrieveInteractiveWindows`).
- **Never place the interactive chip in the region a keyboard can occupy.** If there is no room
  above the field within the visible content rect, render **below**; if the keyboard would still
  occlude it, suppress the chip rather than show an un-tappable one.
- Subscribe to bounds changes (scroll/selection) to reposition. In split-screen/DeX/foldable,
  suppress if focus is ambiguous or in a system/IME window (fixes F5 mis-render).
- Do not rely on `FLAG_LAYOUT_IN_SCREEN` to be inset-aware — account for system bars explicitly.

### 4.6 Secure-field suppression & tap-jacking (F7; co-owned with `06`)

- **Hard gate, checked before *both* read and show, and again before insert:** suppress entirely
  if `node.isPassword`, the input type is a password/visible-password variation,
  `IME_FLAG_NO_PERSONALIZED_LEARNING` is set, the field/window is incognito, or the window carries
  `FLAG_SECURE` / is obscured. **Do not read, do not show, do not insert.**
- **Tap-jacking:** `TYPE_ACCESSIBILITY_OVERLAY` is a *trusted* window and is exempt from Android
  12's untrusted-touch blocking, so the chip's taps land normally. Conversely, target screens with
  `setFilterTouchesWhenObscured(true)` / `FLAG_SECURE` will (correctly) resist us — detect and
  disable rather than fight. Keep `filterTouchesWhenObscured = true` on the chip as defense in
  depth. Treat banking/2FA/payment as out of scope.

### 4.7 Lifecycle & threading

- Capture a stable focus token at event time; re-resolve and re-validate at insert time.
- Serialize `addView`/`removeView` instead of swallowing `IllegalState`/`IllegalArgument` (F9).
- Forward the resolved locale (honoring `localeOverride`) and normalize localized digits before
  detection; operate on logical text for bidi safety (F10).

### 4.8 Fallback ladder (insertion)

| Tier | Mechanism | Use when | On failure |
|---|---|---|---|
| 0 | **Tally IME `commitText`** (no overlay) | Tally is the active keyboard | n/a — overlay dormant (§6) |
| 1 | `ACTION_SET_TEXT(full reconstructed)` + `ACTION_SET_SELECTION`, read-back verified | Standard native `EditText`, focus unambiguous | → Tier 2 |
| 2 | `ACTION_SET_TEXT` without selection restore (accept caret-at-end) | Editor accepts SET_TEXT but selection actions fail | → Tier 3 |
| 3 | **No-op + leave chip's value visible for manual copy via long-press** (user-initiated, not background) | WebView / Compose / OEM editor rejects SET_TEXT | Show "couldn't insert" affordance; **never** auto-clipboard |
| — | ~~`setPrimaryClip` + `ACTION_PASTE`~~ | **REMOVED** | Data-loss + privacy + toast |

---

## 5. Distribution & Google Play policy reality

The grounding research and verification correct a common over-statement. Be precise:

- **Play does NOT ban non-accessibility uses of `AccessibilityService` outright (V2).** Per Play
  Console policy (`answer/10964491`), a non-accessibility app **may** use the API if it (a)
  completes the **Permission Declaration Form** and is approved, and (b) implements **prominent
  in-app disclosure + affirmative consent** (not buried in a privacy policy). Only one category is
  *strictly* prohibited: any use that **"autonomously initiates, plans, and executes actions or
  decisions."** A **passive, deterministic** "read the field, draw a result chip, insert on tap"
  overlay is rule-based, not autonomous → **policy-permissible with declaration.**
- **But the friction is real:** more rigorous (human) review, tightened enforcement from **Jan 28,
  2026**, and suspension/account-termination risk for undeclared or deceptive use. **Do not declare
  `isAccessibilityTool=true`** — a math overlay does not qualify, and a false claim is a takedown
  vector.
- **OS-level erosion (Android 17, ~2026):** non-accessibility apps are blocked from the
  `AccessibilityService` API **when the user has Advanced Protection Mode enabled** — not all users
  by default, but a growing cohort. A compliant overlay will **silently break for APM users**.
- **F-Droid is out** (requires fully FLOSS; Tally is closed-source). **Off-Play direct APK /
  Obtainium** is the friction-tolerant channel and the one ADR-0001 already anticipates.

**Recommendation (Play):** If the overlay ships on Play at all, ship it **passive, declared, with a
prominent disclosure/consent gate**, and accept APM breakage. **Preferred per ADR-0001 and this
audit: keep the overlay off the Play build** (separate off-Play APK) or defer it, so the
policy-sensitive surface never endangers the IME's Play listing. The IME covers Play and **is the
product**.

> A note on the verified alternative (V2): re-architecting off `AccessibilityService` entirely
> (e.g., `MediaProjection` + on-device OCR) is *Play-clean and APM-proof*, but it (a) is a far
> heavier engine, (b) is **incompatible with Tally's privacy posture and minimalism** (continuous
> screen capture for a math chip is disproportionate and a worse trust story than a11y), and (c)
> still cannot insert at the cursor any better than a11y. **Rejected for Tally.** Flagged as an
> Open Issue only so the architect can record the explicit "no."

---

## 6. DECISION — reposition: IME canonical, overlay best-effort

**Recommended decision (do this):** **Reposition.** Make the **Tally IME the single canonical
insertion surface**; demote the overlay to a **best-effort assist for third-party keyboards only**,
rebuilt per §4, and **excluded from the Play build** (off-Play APK or deferred per ADR-0001).

This beats the two alternatives:

| Option | What it means | Verdict |
|---|---|---|
| **A. Reposition (RECOMMENDED)** | IME canonical; overlay rebuilt as best-effort, third-party-keyboard-only, off-Play | **Chosen.** Honest, ships value via the IME, removes data-loss/privacy bugs, keeps the overlay's risk off the Play listing. |
| B. Fix overlay to parity | Engineer the overlay to Gboard/Samsung reliability | **Rejected.** Composing-text and z-order are platform contracts; effort is unbounded; cannot reach parity. |
| C. De-scope (cut entirely) | Delete the overlay module | **Acceptable fallback**, not first choice. Keep the rebuilt overlay as an off-Play extra for users who won't switch keyboards; cut only if §6 acceptance criteria can't be met. |

**Trade-off of the recommendation:** users who refuse to switch from Gboard/Samsung get a
degraded, commit-only experience (no live-`=`) and no Play presence for the overlay. We accept this:
the reliable ROI is the IME (`03-ime-architecture-v2.md`), and the overlay never blocks it.

**Hard dependency:** when Tally's own IME is the active keyboard, the overlay **MUST stay dormant**
(it is redundant and the IME path is strictly better). The overlay activates only when a
*third-party* IME is active.

### 6.1 Acceptance criteria (overlay, if it ships)

The rebuilt overlay is "good enough to ship off-Play" only if **all** hold:

- **AC-1 (no data loss):** clipboard is never touched in any path; verified by grep + test.
- **AC-2 (secure suppression):** chip never appears, reads, or inserts on password /
  no-personalized-learning / incognito / `FLAG_SECURE` / obscured fields (§4.6); co-verified with
  `06`.
- **AC-3 (no silent corruption):** insertion is read-back-verified; on mismatch it bails with no
  field mutation. Passes the Samsung pre-filled-field and hide/reshow regression repros.
- **AC-4 (no stale UI):** generation guard + focus-token re-validation; no chip for a field the
  user has left; no stale insert.
- **AC-5 (placement):** chip never renders over the status bar/notch and is never occluded by the
  keyboard (suppressed instead of shown un-tappable).
- **AC-6 (dormancy):** overlay produces no chip while Tally's IME is the active keyboard.
- **AC-7 (commit-only expectation documented):** known-limitation copy ships in onboarding/about.
- **AC-8 (no autonomy):** strictly tap-to-insert; no auto-action — preserves the §5 policy posture
  even off-Play.

### 6.2 Samsung-inclusive device test matrix

Pixel/emulator testing **misses every Samsung-specific failure** — coverage must be Samsung-broad,
not "one Samsung phone." This matrix is **owned in full by `05-device-framework-compatibility.md`**;
reproduced here only as the overlay's minimum gate.

**Devices (minimum):**

| Class | Examples | Why |
|---|---|---|
| Pixel / AOSP baseline | Pixel (current + 1 older), AOSP emulator | Control; SET_TEXT happy path |
| Samsung legacy One UI | S8/S9-class | flutter#31512/#51893 composing-region duplication era |
| Samsung mid/current | mid S2x, current S2x / One UI 8 | Honeyboard composing buffering, current chrome |
| Samsung large/foldable | a Fold + a Tab | split/floating layouts, DeX, multi-window placement |
| Other aggressive OEM (stretch) | Xiaomi/MIUI, Oppo/ColorOS | background limits, clipboard/SET_TEXT variance |

**Per-device variables to sweep (each a pass/fail signal):**

- Keyboard chrome: toolbar **on/off**, number row **on/off** (changes IME height → occlusion).
- Keyboard mode: **floating / split / one-handed** (changes window geometry/insets).
- Field type: **plain** vs **password** vs **WebView** vs **Compose `BasicTextField`**.
- Field state: **empty** vs **pre-filled**; **insert-after-hide/reshow-keyboard**.
- OS: pre-17 vs **Android 17+** (composing-vs-commit a11y APIs); **APM on/off** (overlay should
  degrade gracefully when blocked).
- One UI **"alert when clipboard accessed"** on/off — must produce **zero** clipboard events
  (proves AC-1).

**Explicit failure signals to track:** *duplicated text*, *first-letter repeat*, *overlay occluded
by keyboard*, *any clipboard toast shown*, *expression dropped when present*, *chip over status
bar/notch*, *insert into wrong window* (split-screen).

---

## 7. Hand-off notes

- **Delete, don't patch:** `ClipboardInserter.kt` is removed in this redesign. Any future
  reference to clipboard insertion is a regression.
- **`core-math` is untouched** — it is shared and correct; all changes are in event handling,
  reading, positioning, insertion, and config.
- **Cross-references:** IME-side `finishComposingText()` + `getTextBeforeCursor()` /
  `getSurroundingText()` (API 31+) strategy lives in `03-ime-architecture-v2.md`; secure-field and
  privacy-scope decisions in `06-security-privacy-hardening.md`; full device matrix in
  `05-device-framework-compatibility.md`; ship sequencing in `07-agent-execution-plan.md`.
