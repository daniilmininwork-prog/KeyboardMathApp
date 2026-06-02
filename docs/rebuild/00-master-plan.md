# 00 — Master Plan: from math-chip prototype to a production keyboard

> **What this is.** The top-level architecture and program plan for taking Tally from its current
> state — a sound math engine wrapped in a prototype keyboard and a broken overlay — to a
> production-grade general-purpose Android keyboard that also does inline math, plus a repositioned,
> best-effort overlay. It is a **plan for engineering agents to execute**, not source code.
>
> This document owns the **decisions and the program**. The deep-dives own the detail:
> `01-competitive-analysis.md` (where the bar is), `02-overlay-root-cause-and-redesign.md` (the
> overlay), `03-ime-architecture-v2.md` (the keyboard), `04-feature-matrix-and-roadmap.md` (what and
> when), `05-device-framework-compatibility.md` (devices/frameworks), `06-security-privacy-hardening.md`
> (the trust contract), `07-agent-execution-plan.md` (the task graph). Read this first, then the
> deep-dive a task points you to. Where this document and a deep-dive disagree, **this document
> wins** and the deep-dive is corrected.

---

## 1. The situation, stated plainly

The repository already contains mature *planning* (`docs/01–10`, three ADRs) and a genuinely strong
math engine. What it does **not** contain is a keyboard anyone would keep as their default, or an
overlay that works. The gap is entirely in the implementation and the product framing, not in the
intent.

| Layer | Today | Bar we are holding it to |
|---|---|---|
| `core-math` (engine) | **Strong.** BigDecimal model, `MathContext(34, HALF_EVEN)`, silent div-by-zero, additive percent, parse-first-then-veto detection, golden/property/fuzz/stress tests. | Keep. Close the gaps `A1` found (input digit cap, detector right-anchoring, veto expansion). |
| `ime` (keyboard) | **Prototype.** ~675 LOC. Single-pointer touch, no popups, no long-press alternates, no gesture typing, no prediction/autocorrect, no compose pipeline, English-only, one accessibility node for the whole keyboard, no key haptics/sound, fixed portrait height. | Apple/Gboard/Samsung-grade general keyboard. **Rebuild the keyboard layer.** |
| `overlay` | **Broken.** Clipboard insertion that wipes the user's clipboard on Android 10+, dismiss-on-any-window-event, no composing-text strategy, no secure-field guard, occluded by the keyboard on Samsung. | Repositioned to a best-effort, off-Play assist; delete the clipboard path; correct-or-silent. |
| `app`, `design-system`, CI | Workable scaffolding; CI already has the three security guards, SBOM, reproducible-build check. | Extend, not replace. |

The stakeholder ask — *"fix the overlay, make the keyboard not basic, optimize for as many
devices/frameworks as possible, make it the ultimate secure keyboard"* — resolves to **one program
with four threads**: (a) keep the engine, (b) rebuild the keyboard, (c) reposition the overlay
honestly, (d) hold a verifiable security/privacy line throughout.

---

## 2. Strategy in one page

1. **The IME is the product.** It is the only surface on Android that owns the suggestion strip and
   the composing region, so it is the only place the math feature — and a real typing experience —
   can be delivered correctly. Everything is built around the IME first. (Vindicated independently
   by the overlay root-cause analysis: `InputConnection` is the *only* API that guarantees visibility
   of in-progress text and deterministic insertion — see `02 §3`.)
2. **Rebuild the keyboard layer clean-room; keep the engine.** `core-math` and `feature-glue` are
   preserved verbatim behind the existing `KeyboardHost` seam. The keyboard *shell* (rendering,
   touch, layouts, popups, prediction, accessibility) is rebuilt from scratch into new modules. We
   **do not extend** the prototype (its single-pointer/Canvas/one-a11y-node design blocks every
   capability a real keyboard needs) and we **do not fork** an existing keyboard (the license gate,
   below, kills every viable fork).
3. **Reposition the overlay.** It becomes a best-effort assist for people on *third-party* keyboards,
   rebuilt to be correct-or-silent, shipped off-Play or deferred. It never blocks the IME and stays
   dormant whenever Tally's own IME is active.
4. **Earn trust structurally.** No `INTERNET` permission, ever; a literally empty IME permission
   list; least privilege; an independent audit. The privacy guarantee is the kind a closed-source
   keyboard can actually prove.

---

## 3. The two gates that decide everything

Two constraints pre-filter every build-vs-buy choice in this program. They are not relitigated
per feature.

### 3.1 The license gate (because Tally ships closed-source)

Only **Apache-2.0 / MIT / BSD / ISC / Unicode-License** code **and data** may ship in the binary.
GPL/LGPL is excluded, including via static link and including *data* (wordlists, models). The
verified research (`V3`, `V4`, `R4`, `R7`) makes the consequences concrete:

- **No permissive, production-quality, droppable glide engine exists.** Google's is a proprietary
  `.so` (`libjni_latinimegoogle.so`) we must never bundle; CleverKeys is GPL-3.0; FlorisBoard's
  Apache glide is embedded source (and removed from current `main`), not a library. Glide is a
  **build**, deferred, behind an interface.
- **The only *maintained* AOSP-lineage keyboard (HeliBoard) is GPL** and therefore excluded as a
  base. AOSP LatinIME is Apache-2.0 but unmaintained and its glide decoder is a stub.
- **Data is a second, independent license gate.** English is clean (SCOWL/ESDB; Unicode CLDR for
  emoji). Most other languages' best dictionaries are copyleft. Per-language data provenance is
  gating work, tracked per language. **When in doubt about an asset's license, escalate — data is
  the contamination vector, not algorithms.**

**Reference, not fork.** FlorisBoard, AOSP LatinIME, AnySoftKeyboard, and Simple Keyboard
(all Apache-2.0) are used as *references for patterns/geometry* and as *port sources* for their
Apache statistical classifiers, behind our own interfaces. We own a from-scratch shell.

### 3.2 The no-network gate (because trust is the product)

No module declares `android.permission.INTERNET`; there is no networking dependency anywhere; CI
fails the build if either appears. This is the single most verifiable privacy guarantee a
closed-source keyboard can offer, and it is load-bearing. Consequences: GIF search, cloud translate,
federated learning, cloud dictation, and live-currency math are **out of scope by design** (not
"missing"). Any future network feature would require *replacing* the structural proof with a
narrower, opt-in, disclosed model under a new ADR and a Data Safety change — the default answer is
**no network, ever**.

---

## 4. Decision ledger

These resolve the open issues surfaced by the deep-dives. The architect's call is binding; the
rationale is one line. Where a call is an assumption (no stakeholder input was sought, per the
"decide and proceed" mandate), it is marked **[A]** and is safe to revisit with evidence.

| # | Decision | Call | Why |
|---|---|---|---|
| D1 | Product framing | Tally is a **full general-purpose keyboard**; inline math is a differentiating feature, not the whole product. The IME is canonical. | The math feature only retains users if the keyboard is good enough to live with (`01`, product vision). |
| D2 | Keyboard layer | **Clean-room rebuild.** Keep `core-math`+`feature-glue`; add `keyboard-engine`, `layouts`, `prediction`, `emoji`; discard `TallyKeyboardView`/`KeyboardLayout`. | Extending the prototype is a dead end; forking trips the license gate (`03 §1`, `A2`). |
| D3 | Rendering | **Classic Views + Canvas key plane; no Compose in v1.** Promote the key plane to `SurfaceView` only if the latency gate fails. | Stack constraint, and the correct call for the latency-critical surface (`V5`, `03 §3`). |
| D4 | minSdk | **Keep minSdk 26** (Android 8.0). Treat pre-29 clipboard as untrusted. | Maximum reach; no legacy IME quirks below 26; clipboard hardening is independent (`05 §1`, `06`). **[A]** |
| D5 | Math scope (v1) | **Arithmetic only** (existing engine). Fix `A1` gaps. Live pre-`=` preview **off by default** behind a stricter detector. Functions/exponent/scientific input = **advanced pack, post-v1, own ADR.** Static unit conversion (temp/distance) = offline-safe, deferred, own ADR. **Currency = never** (network). | The engine is Apple-parity for arithmetic today; scope creep here risks the false-positive bar (`05-math-engine-spec.md`, `A1`). |
| D6 | Glide / gesture typing | **Defer to M4 (v1.1).** Build on the P0-5 decoder; port an Apache statistical classifier behind `GestureDecoder`; set expectations that day-1 accuracy trails Gboard; track the NLnet open project. Route a **patent-clearance review to legal** before commercial glide ship. | No droppable permissive engine (`V4`); glide shares ~80% with tap prediction, so build prediction first (`04 §2.1`). Swipe patents are dense. |
| D7 | Prediction / autocorrect (P0-5) | **Build an in-house n-gram decoder** (spatial scorer + lexicon + static n-gram LM + beam search + local frequency cache). English data from SCOWL/ESDB. Neural LM is a later, owned P2 R&D bet, **off the v1 critical path.** | KenLM is GPL; no permissive transformer LM; n-gram is fast in the hot loop and fully permissive once owned (`04 §2.2`, `R7`). |
| D8 | Languages | **English-only at v1.0**, but **build the data-driven layout + subtype/globe switcher in v1.0** so new languages are data, not code. Latin family at M5. RTL (Arabic/Hebrew) is the v1-era script boundary. CJK (librime, BSD) / Indic (GoVarnam, **license unconfirmed — verify**) only for committed markets. | Layouts are cheap; per-language dictionary licensing is the gating cost and the contamination risk (`04 §2.6`, `R4`). **[A]** English-first. |
| D9 | Voice typing | **Defer to v1.x, opt-in.** Delegate to the OS on-device recognizer where available; if bundling Vosk (Apache-2.0), models ship **in-APK or sideloaded, never network-fetched.** | A recognizer is the hardest non-network component; model download would break the no-network proof unless isolated (`04 §2.5`, open issues). **[A]** OS-first. |
| D10 | Emoji + clipboard | **P1 (M4).** Emoji from Unicode CLDR. Clipboard store **encrypted-at-rest, auto-expiring**; **insertion never round-trips the system clipboard.** | Clean permissive data; the system clipboard triggers Samsung toasts and composing-region corruption (`R3`, `04 §2.3–2.4`). |
| D11 | Accessibility | **Per-key `ExploreByTouchHelper` virtual view tree. Ship-blocker.** | A default keyboard a blind user cannot type on cannot ship — store-policy + legal/ethical (`03 §6`, `A2`). |
| D12 | Security seam | **One `FieldPolicy` derived from `EditorInfo` per field**, enforced at every surface; suppress learning/suggestions/preview/persist **and the math chip** in password / `NO_PERSONALIZED_LEARNING` / incognito / secure fields. Haptics via `performHapticFeedback` (**no `VIBRATE` permission**). | Operationalizes privacy-first; keeps the IME permission list literally empty (`03 §9`, `06`, `V7`). |
| D13 | Overlay | **Reposition.** Best-effort, third-party-keyboard-only, **off-Play or deferred**; delete the clipboard path; `ACTION_SET_TEXT` + read-back-verify, correct-or-silent; dormant when Tally IME is active. **Reject MediaProjection+OCR.** **Cut entirely if acceptance criteria AC-1…AC-8 are not met within its budget.** | Accessibility cannot see composing text or read the background clipboard; parity is unreachable (`02`, `V1`, `V2`, `V6`). |
| D14 | Privacy-scope widening (overlay) | **Approved as a documented trade-off:** `ACTION_SET_TEXT` requires reconstructing whole-field text, widening the overlay read scope from cursor-prefix to whole field — **gated behind the hard secure-field suppression** and disclosed in onboarding. | Reliability requires it; the secure-field gate bounds the exposure (`02 §4.4`, `06`). |
| D15 | Distribution | **IME on Google Play** (AAB; Data Safety: *no data collected/shared*). **Overlay off-Play** as a signed, checksummed direct APK, or deferred. F-Droid is unavailable (closed-source). | The IME is Play-clean; the overlay's accessibility surface must never endanger the IME listing (`02 §5`, `09-build-release.md`). |
| D16 | Cross-platform | **Kotlin-Multiplatform-ify `core-math`** (cheap, recommended) to enable a future iOS keyboard extension and reuse. Everything else stays **native Android**. Web is **marketing-only** (static page), not a product surface. | An IME is inherently platform-specific; only the pure engine ports cleanly. Kotlin/Wasm is unsuitable as a production typing surface (`05 §6`). **[A]** |
| D17 | Install-size budget | Base IME (English + emoji, no extra packs) **≤ ~25 MB**; English lexicon+LM **≤ ~5 MB**; each added language pack **≤ ~5 MB**; emoji/CLDR **≤ ~3 MB**; voice models ship only as **separate optional on-device packs**, never in the base APK. | Caps language packs, LM size, and combo assets; protects cold-install conversion. Targets, not hard limits. **[A]** |
| D18 | Trust mechanism | Commission an **NDA independent security audit**; publish **reproducible-build verification** instructions; keep the **no-`INTERNET`** manifest as the public, checkable proof. | The credible closed-source substitute for open source (`06`, `07-security-privacy.md`). |
| D19 | Trigger contract | **IME handles the live `=` keystroke** (source of truth). **Overlay is post-commit only** (chip appears after space/punctuation/suggestion-tap) due to composing-text staleness; documented as a known limitation. | The overlay cannot see or finish the composing region (`V6`, `02 §4.3`). |
| D20 | Generative / grammar | **Generative writing tools cut for v1.** Grammar check is a later P2 (rule-based + small permissive model), not part of "feature-complete" at launch. | Needs a bundled LLM; hardware-gated, large, flagship-only (`04 P2-7`). **[A]** |

---

## 5. Target architecture (summary)

Detail in `03 §2`. The shape:

```
core-math (pure JVM, UNCHANGED)  ──►  feature-glue (debounce, prefs, SuggestionSource registry)
        │                                        │
        │                         ┌──────────────┴───────────────┐
        │                         │                              │
   keyboard-engine (pure JVM)     ime  (Android: InputMethodService, FieldPolicy,
   • LayoutEngine (geometry)      │     Canvas KeyPlane w/ multitouch+popups, strip, panels,
   • shift/caps state machine     │     a11y NodeProvider host)
   • InputAction reducer          │
   • GestureDecoder / WordPredictor / Autocorrector interfaces
        │                  │
   layouts (data)     prediction (n-gram LM, lexicon, autocorrect; PERMISSIVE DATA ONLY)
                           emoji (+ clipboard panel; CLDR data)      design-system (chip, tokens)
```

Invariants enforced in CI: arrows point inward (nothing depends back on `core-math`);
`keyboard-engine` has zero `android.view`/`android.graphics` imports (same rule as `core-math`);
the math feature crosses into the shell only through `feature-glue` as a `SuggestionSource` — it
never owns the strip; the merged manifest contains no `INTERNET` and the IME contributes no runtime
permissions.

---

## 6. Program — milestones and the cut line

Milestones map 1:1 to the epics in `07-agent-execution-plan.md` and supersede the legacy
`docs/10-implementation-plan.md` Phase 0–8 numbering.

| Milestone | Theme | Lands | Exit gate |
|---|---|---|---|
| **M0** | Foundations | New module/CI scaffolding; no-`INTERNET` + license-scan + `keyboard-engine` no-Android guards in CI; data-driven layout format; IME view-layer skeleton; **`core-math` `A1` fixes** | CI green; guards fail a deliberately-bad branch; no GPL/LGPL in the dep graph |
| **M1** | A keyboard you can type on | Multitouch+rollover, key-preview popups, long-press alternates, **compose-text pipeline**, **per-key a11y**, tri-state shift/auto-caps, key haptics/sound, word-delete, number row/layers, landscape/insets/fullscreen, theme refactor | Two-thumb typing drops no keys; TalkBack explores every key; types correctly in a password field **on a Samsung device** |
| **M2** | It predicts, and it does math | **P0-5 decoder** (autocorrect + prediction + next-word), general suggestion strip, **math chip re-attached as a source**, incognito/no-learning | Tap-autocorrect ≥ Gboard-adjacent on the internal corpus; math chip surfaces on `=` and commits on tap/space; learns nothing from secure fields |
| **M3** | Feel + first-win → **v1.0 ship** | Apple-bar polish, onboarding/enable-IME wizard, hardening, full Samsung-broad device matrix | **Cut line (below) all green;** store-ready (Play, Data Safety: no data) |
| **M4** | Competitive | Glide, emoji panel, clipboard manager, cursor-control gesture, dynamic color, alternate form factors | Glide usable on the internal corpus; emoji + clipboard shipped |
| **M5** | Reach | Multilingual (Latin family) with vetted permissive data; opt-in offline voice | First non-English language ships; voice opt-in works fully offline |
| **Later** | Delight / market | P2 items per-ADR; CJK/Indic only for committed markets; **overlay track runs in parallel**, conditional on the distribution decision (D13/D15) | Per-feature ADR |

**The v1.0 cut line.** Ship only when **all of P0-1…P0-15** (`04 §1`) are green plus onboarding,
hardening, and the device matrix. Deferred-not-cut for v1.0: glide, emoji, clipboard, multilingual,
voice, dynamic color, alternate form factors. **Three non-negotiable ship-blockers inside the line:**
per-key accessibility (P0-8), multitouch/rollover (P0-1), prediction/autocorrect (P0-5, the
critical-path XL — protect its schedule).

---

## 7. "Optimize for as many devices/frameworks as possible" — the concrete answer

Detail in `05`. The honest reading: an IME is platform-specific, so "as many devices" means
**Android breadth done rigorously**, and "frameworks" means **a clean engine boundary**, not
chasing cross-platform UI.

- **Versions:** minSdk 26 → latest, with explicit API-gating and graceful fallback (D4).
- **OEMs:** Pixel/AOSP baseline plus a **Samsung-broad** matrix (legacy + current + foldable),
  because every Samsung-specific failure is invisible on a Pixel; Xiaomi/Oppo as a stretch.
- **Form factors:** phone, tablet/large-screen, foldable (posture/resizable), Chromebook/desktop &
  Samsung DeX, hardware keyboards, multi-window/multi-display — first-class, not afterthoughts.
- **Orientation/window modes:** portrait/landscape, number row, split, one-handed, floating,
  resizable height.
- **i18n:** RTL and locale/script coverage built into the layout/data model from M1.
- **Frameworks:** **KMP-ify `core-math`** for a future iOS keyboard extension and reuse; stay native
  for the IME; web is marketing-only (D16).

---

## 8. Risks and how the program de-risks them

| Risk | Severity | Mitigation |
|---|---|---|
| Prediction/autocorrect (P0-5) slips and drags v1.0 | High | It is the critical-path XL; staff it as a dedicated workstream from M0; build the decoder so glide reuses it (one engine, four uses). |
| Glide ships weak and reviews badly | Medium | Deferred to M4; expectations set explicitly; behind a swappable `GestureDecoder`; track NLnet for a future upgrade. |
| Data licensing contamination (a GPL wordlist slips into a release) | High | Per-language provenance tracking; **CI license-scan from M0**; escalate-on-doubt rule. |
| Samsung-specific breakage shipped because testing was Pixel-only | High | Samsung-broad device matrix is an M3 exit gate (`05`, `02 §6.2`); not optional. |
| Overlay endangers the IME's Play listing | Medium | Overlay off the Play build entirely (D15); dormant when Tally IME active; cut if AC-1…AC-8 unmet. |
| Accessibility treated as polish and deferred | High | P0-8 is a hard ship-blocker with a TalkBack/Switch-Access acceptance gate. |
| Compose adopted for the key bed and adds latency | Medium | Views/Canvas mandated for v1; Compose only behind a measured latency gate, and never for the key bed in v1 (D3). |
| The no-network proof quietly eroded by a convenience feature | High | Manifest guard in CI; any network feature requires an ADR that explicitly replaces the structural proof (D17). |

---

## 9. Definition of done (program level)

A release is shippable only when, together: the v1.0 cut line is green; the false-positive corpus
and golden/property/fuzz suites pass; performance is within the `03 §8` latency budget on a mid-tier
device; per-key accessibility passes TalkBack and Switch Access; the Samsung-broad device matrix
passes; the security guards (no-`INTERNET`, `core-math`/`keyboard-engine` Android-free, no-text-logging,
license-scan) are green; the manifest is minimal and the IME contributes no runtime permissions; the
Data Safety declaration is accurate; the independent audit is commissioned; and reproducible-build
verification is documented. Anything less ships a keyboard that is sometimes wrong, sometimes noisy,
or not trustworthy — each of which breaks the core promise.

---

## 10. How to execute this

`07-agent-execution-plan.md` is the task graph: epics aligned to M0–M5, each task a self-contained
card with inputs, deliverables, dependencies, and acceptance criteria, plus the verification gates
and the parallelization guidance. A single performant agent can walk it in dependency order; a team
can fan out along the graph. Either way, every task inherits the global gates in `07 §2` — the
license gate, the no-network gate, tests-with-the-code, and the house style (the history must read
as careful human work; no machine-authored residue, per `09-build-release.md`).
