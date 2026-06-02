# 04 — Feature Matrix & Roadmap

> **Scope of this document.** What Tally must do to be a credible general-purpose keyboard, tiered and prioritized, with build-vs-buy, license, and on-device/privacy impact for each capability, plus a phased roadmap with an explicit "minimum to ship" cut line. This document decides *what* and *when*. The *how* lives in `03-ime-architecture-v2.md` (IME internals), `02-overlay-root-cause-and-redesign.md` (overlay), `05-device-framework-compatibility.md` (device matrix), and `06-security-privacy-hardening.md` (privacy enforcement). The phase numbering here is shared with `07-agent-execution-plan.md`; the legacy `docs/10-implementation-plan.md` phases predate the general-keyboard pivot and are superseded.

## 0. Constraints that gate every row

These are non-negotiable and pre-filter the build-vs-buy menu. Engineers: if a candidate library or asset violates any of these, it is off the table — do not relitigate per feature.

| Constraint | Consequence for this matrix |
|---|---|
| **Closed-source binary** | Only Apache-2.0 / MIT / BSD / ISC / Unicode-License code and data may ship *in the binary*. GPL/LGPL is excluded — including via static link. (LGPL dynamic-link is theoretically allowed but operationally brittle on Android; we treat it as excluded to keep the legal story clean.) This kills KenLM (LGPL/GPL), CleverKeys (GPL-3.0), Google's `libjni_latinimegoogle.so` glide blob (proprietary), and any GPL-only per-language dictionary. |
| **No `INTERNET` permission** | The manifest must not contain `android.permission.INTERNET`. CI guards this (see `06`). Every feature must run fully on-device. This kills GIF search, cloud translate, federated learning, and cloud dictation by construction — list them as *out of scope by design*, not "missing." |
| **No telemetry** | No usage analytics, no crash-phone-home, no keystroke logging that leaves the process. All personalization is a local store. |
| **Math built once** | The `core-math` engine is the strongest existing module and is surface-agnostic. Both the IME suggestion strip and the overlay consume it via `MathEngine.evaluate(...)`. No feature in this matrix re-implements math. |
| **Stack** | Kotlin 2.1.21, classic Android Views (no Compose), Gradle multi-module, minSdk 26 / compileSdk 36, Material 1.12. Features that assume Compose or a higher minSdk must degrade gracefully or be gated. |

**Reference architecture (decided).** Per `R7-features` and `A2-ime`, **FlorisBoard (Apache-2.0)** is the template and the only permissively-licensed source that covers both glide and statistical prediction on-device. The AOSP **LatinIME lineage (Apache-2.0)** is the secondary permissive source for IME plumbing and layout/subtype mechanics. We **port/learn from** these — we do **not** fork GPL descendants (OpenBoard, HeliBoard, CleverKeys). This is settled in `ADR-0001` and `03`; this document assumes it.

---

## 1. Feature inventory (tiered)

Tiers express **switching cost**, not nice-to-have ranking:

- **P0 — table-stakes.** Below this bar a user who installs Tally as their *default* keyboard hits a wall within minutes and uninstalls. This is the "good enough to live with" line. **Everything P0 must ship in v1.0.**
- **P1 — competitive.** Present on Gboard/Samsung/Apple; their absence makes Tally feel like a downgrade but not unusable. Target v1.x.
- **P2 — delight.** Differentiators and polish. Opportunistic; several are explicitly deferred or cut by the no-network constraint.

Effort scale (one senior engineer, calendar): **S** ≈ days · **M** ≈ 1–2 weeks · **L** ≈ 3–6 weeks · **XL** ≈ multi-month workstream.

Build-vs-buy legend: **BUILD** (first-party) · **PORT** (lift/adapt permissive source) · **DATA** (build code, source permissive data) · **OS** (delegate to platform API) · **CUT** (out of scope under constraints).

### P0 — Table-stakes (must ship in v1.0; this is the switching-cost bar)

| # | Feature | What it is | Build-vs-buy + license | Effort | Dependencies | Privacy / on-device impact |
|---|---|---|---|---|---|---|
| P0-1 | **Multitouch + key rollover** | Per-`pointerId` state map; second key registers before first releases; fast two-thumb typing doesn't drop keys | BUILD (touch layer rewrite; current single-pointer model is a dead end per `A2-ime`) | M | New IME view layer (`03`) | None. Pure local input. |
| P0-2 | **Key-preview popups** | Magnified bubble above the pressed key | BUILD (PopupWindow/overlay layer) | S | P0-1 | None |
| P0-3 | **Long-press alternates ("more keys")** | Hold a key → tray of accents/secondary symbols (é, ñ, secondary punctuation, long-press number row) | BUILD (extend `Key` model + popup mini-keyboard); layouts are DATA | M | P0-1, P0-2 | None. Accent data is static. |
| P0-4 | **Compose-text pipeline** | `setComposingText`/`finishComposingText` instead of raw `commitText`; enables underline, spell-check, correction, Samsung-safe insertion | BUILD (core IME plumbing) | M | New IME (`03`) | None. **Prerequisite for P0-5 and for the Samsung composing-region fix in `03`/`05`.** |
| P0-5 | **Tap autocorrect + word prediction + next-word** | The decoder: spatial model + lexicon + n-gram LM via beam search; 3-candidate strip (center-bold default) | BUILD decoder (FlorisBoard-style); n-gram LM is BUILD (KenLM is GPL — excluded); English lexicon is DATA (SCOWL/ESDB, permissive, has frequencies) | XL | P0-4, English lexicon+LM asset | All on-device. Local personalization table only — **no upload, no federated learning** (that's a cloud pipeline, irrelevant offline). |
| P0-6 | **Suggestion strip (general)** | 3 word candidates from the decoder; **math chip coexists here** (does not own the strip) | BUILD; reuse `design-system` `MathResultChip` | S | P0-5, `core-math` | Local |
| P0-7 | **Math suggestion (existing feature)** | Detect trailing `=`, surface result as a chip in the strip; tap or space to commit | REUSE `core-math` + `feature-glue`; re-attach as a suggestion provider | S | P0-6 | Already on-device. Strongest module — do not rebuild. |
| P0-8 | **Per-key accessibility (TalkBack)** | `ExploreByTouchHelper` exposing one virtual node per key (bounds, label, click) | BUILD (mandatory; current single-node Canvas is a **hard store/legal blocker** per `A2-ime`) | M | New IME view layer | None. **Ship-blocker — a default keyboard a blind user cannot type on cannot ship.** |
| P0-9 | **Auto-capitalization + tri-state shift** | Honor `EditorInfo` capsMode; off/shifted/**locked** shift with distinct visuals; double-tap caps-lock | BUILD | S | New IME | None |
| P0-10 | **Key haptics + click sound** | `performHapticFeedback(KEYBOARD_TAP)` + `AudioManager.playSoundEffect`; independent user toggles; honor Silent Mode / system haptics | BUILD | S | New IME; `TallyPreferences` | None. Battery/UX only. |
| P0-11 | **Backspace word-delete + repeat** | Long-press repeat (exists) + swipe-left-on-delete erases a word | BUILD | S | New IME | None |
| P0-12 | **Number row (toggle) + symbols/numeric layers** | Optional dedicated 0–9 row; clean numeric and symbol layers | BUILD (current static layers exist but are brittle hand-balanced literals; move to data-driven) | S | Data-driven layout engine (`03`) | None |
| P0-13 | **Landscape + insets + fullscreen-extract handling** | `onComputeInsets`, `onEvaluateFullscreenMode`, `onConfigurationChanged`; responsive height; don't cover the field | BUILD | M | New IME | None. **Load-bearing for the overlay's inset math (`02`/`05`).** |
| P0-14 | **Light/dark theme (system-following)** | Already present as color resources; resolve per-draw so runtime theme change works | BUILD (small refactor) | S | Theme model | None |
| P0-15 | **Incognito / no-learning mode** | Honor `IME_FLAG_NO_PERSONALIZED_LEARNING` and password fields → suspend the local personalization store | BUILD | S | P0-5 personalization store | **Privacy-positive.** Don't learn from secure fields. |

> **Why P0-5 is XL and unavoidable.** Per `V4-glide-engine` and `R7-features`, there is no droppable permissive decoder; FlorisBoard's is embedded source, not a library. Tap autocorrect/prediction is the core thing users mean by "a real keyboard." It is the single largest build in the program and the critical-path item. Treat it as a dedicated workstream, not a phase line-item.

### P1 — Competitive (target v1.1–v1.3)

| # | Feature | What it is | Build-vs-buy + license | Effort | Dependencies | Privacy / on-device impact |
|---|---|---|---|---|---|---|
| P1-1 | **Glide / gesture typing** | Continuous trace → word via spatial scoring + LM rescoring; trails; pause-to-lock | BUILD on the P0-5 decoder (resample path → score vs ideal key-paths → rank with same n-gram LM). PORT FlorisBoard's Apache-2.0 glide as a starting point | L | P0-5 (shares the decoder) | All local. **Accuracy will trail Gboard at launch** — the high-quality engine is the proprietary blob we can't ship (`V4-glide-engine`). Budget ongoing tuning. |
| P1-2 | **Emoji panel** | Categories, search-by-keyword, skin tones, recents | BUILD UI; DATA = Unicode CLDR + cldr-emoji-annotation (Unicode License V3, permissive, commercial-OK) | M | EmojiCompat rendering; recents store | Local. Recents are local-only state. |
| P1-3 | **Clipboard manager** | History with pin/expiry; on-device entity extraction (phone/URL/email/address via regex) chips | BUILD (no library, no network needed; best effort-to-value ratio in the program) | S–M | New IME | **Privacy-sensitive store** — encrypt-at-rest, auto-expiry of sensitive clips, never sync. See `06`. **Do not route any insertion through the system clipboard** (Samsung toast + composing-region corruption, `R3-samsung`). |
| P1-4 | **Cursor-control gesture** | Space-bar swipe = trackpad cursor move; selection gestures | BUILD | S–M | New IME | Local |
| P1-5 | **Multilingual layouts (Latin family)** | AZERTY/QWERTZ/QWERTY-intl + regional; globe-key / subtype switching; simultaneous same-script decoding | BUILD layouts (author or seed from AOSP LatinIME, Apache-2.0); subtype switcher BUILD; **gate each language on permissive lexicon+LM data** | L (engine S, **data is the cost**) | P0-5 decoder; per-language data | Local. **Per-language dictionary licensing is the landmine** (German Hunspell data is GPL — reject). Vet every wordlist individually (`R7-features`). |
| P1-6 | **Dynamic color / Material You theming** | Extract system tonal palette (Android 12+); preset + custom-image themes; key-border/opacity controls | BUILD; Material 3 dynamic color is Apache-2.0 (note: our base stack is Material 1.12 + Views — read the platform palette directly, don't pull Compose Material 3) | M | Theme model (P0-14) | Local |
| P1-7 | **Form factors: one-handed / split / floating / resizable** | View-layout transforms over one key model | BUILD (pure geometry; no network, no ML) | M | P0-13 insets work | None |
| P1-8 | **Offline voice typing** | On-device dictation | OS first (host recognizer where present) **or** PORT/bundle Vosk (Apache-2.0, ~50MB/lang). Whisper.cpp (MIT) is heavier | L | Model asset pipeline; mic permission | Inference on-device — **strong differentiator**. **Model files must ship/sideload bundled, never network-fetch, or it breaks the no-INTERNET claim.** Isolate so core keyboard stays network-less. See `openIssues`. |

### P2 — Delight (opportunistic; several cut by constraints)

| # | Feature | What it is | Build-vs-buy + license | Effort | Dependencies | Privacy / on-device impact |
|---|---|---|---|---|---|---|
| P2-1 | **On-device grammar check** | Underline errors + one-tap fix | BUILD rule-based + small bundled grammar model (DATA must be permissive) | L | P0-5 pipeline | Local |
| P2-2 | **Inline gray next-word prediction** | Apple-style space-to-accept inline completion | BUILD on decoder | M | P0-5 | Local |
| P2-3 | **Stickers (bundled / user packs)** | Local sticker catalog; user-imported packs | BUILD; bundled assets only | M | Emoji panel (P1-2) | Local |
| P2-4 | **Curated emoji-combo pack** | Emoji-Kitchen-style merged stickers | DATA = ship your **own** small curated merged-PNG pack (Google's library is proprietary) | M | P1-2; storage budget | Local; install-size cost |
| P2-5 | **CJK input** | Pinyin/shape Chinese, etc. | PORT librime (3-clause BSD); **exclude GPL submodules/OpenCC data**; per-language schema DATA | XL | Market decision | Local; deep per-script QA |
| P2-6 | **Indic transliteration** | Manglish→Malayalam etc. | PORT GoVarnam **(license unconfirmed — verify before adopt)** or BUILD transliteration tables | L | Market decision | Local |
| P2-7 | **Generative writing tools** (rephrase/tone) | LLM rewrite | **CUT for v1.** Needs a bundled small LLM; hardware-gated, large install size, flagship-only. Revisit only if a permissive quantized model proves worth the size | XL | — | Would be local, but not worth it now |
| P2-8 | **GIF search** | — | **CUT (by design).** Inherently network. | — | — | Violates no-INTERNET |
| P2-9 | **Translate-as-you-type** | — | **CUT (by design).** Cloud MT; bundling offline MT is too heavy | — | — | Violates no-INTERNET / size |
| P2-10 | **Currency conversion in math** | Live FX rates | **CUT (by design).** Needs live network (consistent with existing `ROADMAP.md`). Static unit conversion (temp/distance) is a *possible* `core-math` advanced-pack item, separate ADR | — | — | Violates no-INTERNET |

---

## 2. The hard features — recommended approach now vs later

These six drive most of the build risk. For each: the **recommendation first**, then the trade-off.

### 2.1 Glide / gesture typing — **defer to v1.1; build on the P0 decoder**
- **Now (v1.0):** ship without glide. **Later (v1.1):** build it as a second traversal of the same decoder you already built for tap prediction.
- **Why:** Per `V4-glide-engine` (confirmed), no permissive, production-quality, droppable glide engine exists. Google's quality is a proprietary `.so` we cannot bundle; CleverKeys is GPL-3.0; FlorisBoard's Apache glide is embedded source and sub-Gboard. So glide is a **build**, and it shares ~80% of its machinery with tap prediction — which means building tap prediction *first* (P0-5) de-risks glide. Shipping a credible tap keyboard without glide is acceptable for v1.0; shipping glide on top of a weak decoder is not.
- **Trade-off:** Glide is table-stakes-adjacent in 2026 and its absence is the most-noticed v1.0 gap. We accept a known, time-boxed gap rather than a low-quality day-1 glide. **Set the expectation explicitly:** Tally glide accuracy will trail Gboard at launch and improve by tuning, because the quality ceiling of a statistical (non-neural-spatial) decoder is lower. Watch the NLnet open gesture-typing project, but treat it as unproven until released and license-verified.

### 2.2 Prediction / autocorrect — **build the n-gram decoder now; it is P0 and critical-path**
- **Now (v1.0):** BUILD an in-house decoder: spatial scorer + compiled lexicon + static n-gram LM (your own ARPA/FST loader with Kneser-Ney-style smoothing) + beam search, plus a **local** per-user frequency cache for personalization. English lexicon from SCOWL/ESDB (permissive, includes frequencies). **Later:** a small quantized on-device neural LM as P2 polish you train and own.
- **Why:** KenLM is GPL/LGPL — excluded. There is no permissive drop-in transformer LM. The n-gram baseline is proven, fast in the typing hot-loop, and fully permissive once you own the engine and source clean data. Federated learning is a *cloud-training* mechanism — irrelevant and impossible offline; replace it with the local frequency table.
- **Trade-off:** N-gram prediction is slightly less context-aware than Gboard's neural LM. Acceptable for v1.0; the neural upgrade is a later, owned R&D bet — do **not** put it on the v1.0 critical path (schedule-slip risk per `R7-features`).

### 2.3 Emoji — **build the picker now (P1, early); data is permissive**
- **Now/early v1.1:** BUILD picker UI (categories, keyword search, skin tones, local recents); DATA from Unicode CLDR + annotations (Unicode License V3, explicitly commercial/closed-source OK with attribution). EmojiCompat for rendering.
- **Why:** Clean permissive data, fully offline, high-frequency user expectation. No reason to defer past early v1.1.
- **Trade-off:** None material. Emoji-Kitchen-style combos (P2-4) are a separate, bundled curated-asset effort — Google's library is proprietary, so ship a smaller own pack only if storage budget allows.

### 2.4 Clipboard — **build now (P1, high priority); never touch the system clipboard for insertion**
- **Now (v1.1, pull earlier if capacity):** BUILD a local history store with pin/expiry and regex entity-extraction chips. No library, no network.
- **Why:** Best effort-to-value ratio in the program; trivially permissive (first-party). Strong privacy story when encrypted-at-rest and auto-expiring.
- **Trade-off / hard constraint:** This is also a **security surface** (`06`) and a **Samsung hazard**: per `R3-samsung`, any insertion path that writes the system clipboard triggers an un-suppressible One UI toast and risks composing-region corruption. **Rule: the keyboard's own insertion never round-trips through `ClipboardManager`.** (This also informs the overlay redesign in `02` — the existing overlay's clipboard-paste insertion is a root-cause bug.)

### 2.5 Voice — **defer to v1.x; outsource the recognizer; never build one**
- **Now:** out of scope. **Later (v1.x, opt-in):** delegate to the OS on-device recognizer where available, else bundle Vosk (Apache-2.0). Do **not** build an ASR engine.
- **Why:** Per `R2-gboard`/`R7-features`, a recognizer is the single hardest non-network component. Vosk is permissive and offline; Whisper.cpp is MIT but heavier. Both satisfy no-INTERNET *for inference*.
- **Trade-off:** Model files are large (~50MB/lang) and must be **bundled or sideloaded, never network-downloaded**, or the no-INTERNET guarantee breaks. Quality trails Gboard's RNN-T. Confirm per-language model licenses (separate from toolkit license) before bundling. **This needs an architect ruling — see `openIssues`.**

### 2.6 Multilingual — **layouts early (P1); per-language prediction gated on permissive data**
- **Now (v1.0):** English only, but build the **data-driven layout engine and subtype/globe switcher** so adding languages is data, not code. **Later (v1.1+):** add Latin-family layouts and simultaneous same-script decoding (load multiple lexicon+LM FSTs into one search space — directly enabled by the decoder design).
- **Why:** Layout geometry is cheap and permissive. The decoder design makes multilingual a packaging feature, not new ML. Same-script (Latin) combinations first, exactly as Gboard does.
- **Trade-off / landmine:** **Per-language dictionary data is the gating cost and the licensing risk.** Many Hunspell dictionaries are GPL-only (German GPL-2.0, etc.). Every language must have its lexicon+LM legally vetted before that language ships. Cross-script (Cyrillic/CJK/Indic) is a much deeper per-market investment (P2-5/P2-6) — only for committed markets.

---

## 3. Phased roadmap

Phases align with `03-ime-architecture-v2.md` and `07-agent-execution-plan.md`. Milestones map to the public versions in the existing `ROADMAP.md` (v0.2 → M2, v0.3 → M3, v1.0 → M3 ship, v1.x → M4+). The legacy `docs/10-implementation-plan.md` Phase 0–8 numbering is **superseded** by this rebuild plan.

| Milestone | Phase (per `03`/`07`) | Theme | Features landed | Exit gate |
|---|---|---|---|---|
| **M0** | Phase 0 | Foundations | Module/CI scaffolding; **manifest no-INTERNET guard**; license-scan in CI; data-driven layout format defined; new IME view-layer skeleton | CI green; manifest guard active; no GPL/LGPL in dep graph |
| **M1** | Phase 1–2 | A keyboard you can type on | P0-1 multitouch, P0-2 previews, P0-3 long-press alternates, P0-4 compose pipeline, P0-8 **a11y nodes**, P0-9 shift/auto-caps, P0-10 haptics/sound, P0-11 backspace, P0-12 number row/layers, P0-13 insets/landscape, P0-14 theme | Two-thumb typing drops no keys; TalkBack can explore every key; types correctly in a password field on a Samsung device |
| **M2** | Phase 3 | It predicts, and it does math | **P0-5 decoder (autocorrect + prediction + next-word)**, P0-6 strip, **P0-7 math chip re-attached**, P0-15 incognito | ≥ Gboard-adjacent tap-autocorrect on the internal corpus; math chip surfaces on `=` and commits on tap/space; learns nothing from secure fields |
| **M3** | Phase 4–5 | Feel + first-win → **v1.0 SHIP** | Polish to the Apple bar (motion, dynamic color groundwork), onboarding/enable-IME wizard, hardening, full device matrix (`05`) | **Cut line below.** All P0 green; passes the Samsung-broad matrix; store-ready (Play, Data Safety: no data collected) |
| **M4** | Phase 6 | Competitive | **P1-1 glide**, P1-2 emoji, P1-3 clipboard, P1-4 cursor gesture, P1-6 dynamic color, P1-7 form factors | Glide usable on the internal corpus; emoji + clipboard shipped |
| **M5** | Phase 6+ | Reach | P1-5 multilingual (Latin), **P1-8 voice (opt-in)** | First non-English language with vetted permissive data; voice opt-in works fully offline |
| **Later** | — | Delight / market | P2 items as justified; CJK/Indic only for committed markets; **overlay mode is a parallel track**, conditional on the Play-policy decision (`02`, `ROADMAP.md`) | Per-feature ADR |

### The cut line — "minimum to ship a credible keyboard" (M3 / v1.0)

**Ship v1.0 when, and only when, all of P0-1 … P0-15 are green** (plus onboarding, hardening, and the device matrix in `05`). That set is the switching-cost bar: multitouch, previews, long-press alternates, the **compose pipeline**, **tap autocorrect + prediction + next-word**, the suggestion strip with the **math chip**, **per-key accessibility**, shift/auto-caps, haptics/sound, word-delete, number row, landscape/insets, light/dark theme, and incognito.

**Explicitly NOT required to ship v1.0 (deferred, not cut):** glide, emoji panel, clipboard manager, multilingual, voice, dynamic color, alternate form factors. Each is P1+ and lands post-launch.

**The three non-negotiable ship-blockers inside the cut line** (any one red blocks release):
1. **P0-8 per-key accessibility** — store-policy + legal/ethical blocker. A blind user must be able to type.
2. **P0-1 multitouch/rollover** — without it, real two-thumb typing visibly drops keys; un-fixable post-hoc without a rewrite.
3. **P0-5 prediction/autocorrect** — without it, it is a button grid, not a keyboard. (Critical-path XL; protect its schedule.)

**Cross-cutting, every milestone:** no `INTERNET` permission (CI-guarded), no telemetry, all data local, Samsung-broad device testing (per `R3-samsung`/`05`), and the math feature consuming the unchanged `core-math` engine.

---

## 4. Hand-off notes for executing agents

- **Build order is dependency-driven, not tier-driven within a milestone.** P0-4 (compose pipeline) gates P0-5; P0-1 gates P0-2/P0-3; P0-13 insets gate both form factors (P1-7) and the overlay's positioning fix (`02`). Respect the dependency column.
- **The decoder (P0-5) is one engine reused four ways:** tap autocorrect, word completion, next-word, and (later) glide. Build it with that in mind; do not let glide become a second engine.
- **Never** bundle `libjni_latinimegoogle.so`, KenLM, CleverKeys, OpenBoard/HeliBoard forks, or any GPL-only dictionary. When in doubt about a data asset's license, escalate — data is the contamination vector, not algorithms.
- **Never** route keyboard text insertion through the system clipboard (`R3-samsung`).
- License-scan and manifest no-INTERNET checks are **CI gates from M0**, not end-of-project audits.
