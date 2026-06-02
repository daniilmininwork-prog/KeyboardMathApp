# Overlay (TO.6) — Ship / Cut Decision Package

> Status: **AWAITING HUMAN DECISION + DEVICE GATE.** The code-level rebuild of the overlay
> (TO.1–TO.5) is complete and all statically-verifiable acceptance criteria are green. The
> remaining gates — the Samsung-broad physical device matrix, release signing, and the product
> ship-vs-cut call (D13/D15) — cannot be completed in an automated build environment and are
> handed to a human here. Source of truth: `docs/rebuild/02-overlay-root-cause-and-redesign.md`
> §6 (decision) and §6.2 (matrix); `docs/rebuild/07-agent-execution-plan.md` TO.6.

## 1. What is done (TO.1–TO.5)

| Task | Outcome |
|---|---|
| TO.1 | Clipboard insertion path deleted; corrected event/focus model (generation guard + focus token; blur-on-focus-loss only; no `recycle()` on API 33+). |
| TO.2 | Composing-text strategy: `CommitFilter` (commit-vs-composition), `node.refresh()` before read, Android 17 `getTextChangeTypes()` act-on-commit; commit-only limitation copy shipped in the consent screen. |
| TO.3 | Insertion redesign: explicit §4.8 Tier 1→2→3 ladder (`ACTION_SET_TEXT` + `ACTION_SET_SELECTION` + read-back verify), no-op on unsupported editors, never clipboard. |
| TO.4 | Positioning/insets/occlusion: `OverlayPlacement` clamps the chip inside the visible band; suppresses rather than showing an un-tappable chip. |
| TO.5 | Secure-field gate (`OverlaySecureField`) + dormancy (`OverlayDormancy`): no read/show/insert in sensitive fields; dormant when Tally's IME is active. |

Overlay unit suite: **77 tests, 0 failures** (`./gradlew :overlay:testDebugUnitTest`).

## 2. Acceptance criteria status (AC-1…AC-8, `02 §6.1`)

| AC | Criterion | Code/unit status | Still needs device gate? |
|---|---|---|---|
| AC-1 | No clipboard touched in any path | **GREEN** — no clipboard API in `overlay/src/main` (comment-only ref); `ClipboardInserter` absent; class-absence test | Confirm **zero** clipboard events on One UI "alert when clipboard accessed" |
| AC-2 | No read/show/insert in secure fields | **GREEN (logic)** — `OverlaySecureField` gate before `node.text`, re-checked before insert; 9 unit tests | **Yes** — binding §3.3 *instrumented* assertion: no `node.text` read / no `setPrimaryClip` with each password variation focused |
| AC-3 | No silent corruption | **GREEN (logic)** — read-back verify → FAILED on mismatch, field untouched; resolveResult tests | **Yes** — Samsung pre-filled + hide/reshow repros on real Honeyboard |
| AC-4 | No stale UI | **GREEN** — generation guard + focus-token re-validation (TO.1 tests) | Spot-check on device |
| AC-5 | Placement never over status bar/notch, never occluded | **GREEN (logic)** — `OverlayPlacement` 8 tests | **Yes** — live `WindowInsets` across chrome/floating/split/foldable/DeX |
| AC-6 | Dormant under Tally IME | **GREEN (logic)** — `OverlayDormancy` via `DEFAULT_INPUT_METHOD`; 4 tests | Spot-check: zero chips with Tally IME active |
| AC-7 | Commit-only expectation documented | **GREEN** — copy shipped in consent screen; layout-inflation test | — |
| AC-8 | Tap-to-insert only, no autonomy | **GREEN** — `filterTouchesWhenObscured` on chip; no auto-action | Tap-jacking spot-check |

**Conclusion:** every AC that *can* be settled without hardware is green. AC-2, AC-3, AC-5 carry a
binding on-device component that only the §6.2 matrix can close.

## 3. Device matrix to run (`02 §6.2`, owned by `05`)

Samsung-broad, not one phone. Minimum: Pixel (current + 1 older) + AOSP emulator; Samsung legacy
One UI (S8/S9-class), mid/current S2x (One UI 8), a Fold + a Tab; stretch Xiaomi/MIUI + Oppo/ColorOS.
Sweep per device: chrome (toolbar/number-row on/off), mode (floating/split/one-handed), field type
(plain/password/WebView/Compose `BasicTextField`), state (empty/pre-filled, insert-after-hide/reshow),
OS (pre-17 vs 17+, APM on/off), clipboard-alert on/off. Track failure signals: duplicated text,
first-letter repeat, overlay occluded, any clipboard toast, expression dropped, chip over status
bar/notch, insert into wrong window. Recommended channel: Firebase Test Lab smart sharding +
physical Samsung devices (`05 §7.2`).

## 4. Remaining human/infra actions to close TO.6

1. **Run the §6.2 device matrix** (Firebase Test Lab + physical Samsung) and record pass/fail per cell.
2. **Add the binding instrumented secure-field test** (§3.3): assert no `node.text` read and no
   `setPrimaryClip` while each password variation is focused.
3. **Run `/security-review`** over the overlay changes (accessibility + secure-field handling).
4. **Decide ship vs cut** (D13/D15). Per ADR-0001 and `02 §6`, the **recommended** path is to keep
   the overlay **off the Play build** — ship a separate off-Play signed APK + published checksums
   (Obtainium-friendly), or defer/cut. The IME is the product and must not carry the overlay's
   policy risk on its Play listing.
5. If shipping: **sign the off-Play APK** (release keystore from secret, not source) and **publish
   SHA-256 checksums**. If cutting: remove the `overlay` module and record the cut here.

## 5. Recommendation

All evidence supports the `02 §6` Option A (Reposition): the rebuilt overlay is correct-or-silent
and free of the data-loss/privacy defects, but it remains a best-effort, third-party-keyboard-only
assist. **Recommended: ship it off-Play (or defer), never on the Play build**, contingent on a clean
§6.2 matrix. Cut only if the matrix cannot be made green within budget.
