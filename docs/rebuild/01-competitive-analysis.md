# 01 — Competitive Analysis: Apple, Gboard, Samsung, OSS → the Tally design language

> **Role of this document in the set.** This is the *why* layer. It interrogates the design
> choices of the keyboards we are benchmarking against, explains the rationale behind each, and
> ends with a single synthesized decision table that the execution agents treat as binding intent.
> It does **not** specify the overlay fix (see [`02-overlay-root-cause-and-redesign.md`](02-overlay-root-cause-and-redesign.md)),
> the IME internals (see [`03-ime-architecture-v2.md`](03-ime-architecture-v2.md)), the
> feature roadmap/sequencing (see [`04-feature-matrix-and-roadmap.md`](04-feature-matrix-and-roadmap.md)),
> device/OEM compatibility (see [`05-device-framework-compatibility.md`](05-device-framework-compatibility.md)),
> or security/privacy hardening (see [`06-security-privacy-hardening.md`](06-security-privacy-hardening.md)).
> When this document says "adopt X," the *how* and *when* live in those documents. Do not duplicate them here.

**Hard constraints that gate every recommendation below (non-negotiable):**

- **Closed-source.** Reused code, engines, dictionaries, and bundled data must be **permissively
  licensed** (Apache-2.0 / MIT / BSD / Unicode). **GPL/LGPL and copyleft data (CC-BY-SA, CC-BY-NC)
  are excluded** from the shipped binary. This is a *license gate*, applied last to every "adopt" below.
- **Privacy-first, no network.** `android.permission.INTERNET` is **intentionally absent** and is a
  load-bearing product guarantee. Everything runs on-device. No telemetry. Any feature that
  *requires* the network (GIF search, cloud translate, federated training, cloud LLM rewrite) is
  **rejected by construction**, not deferred.
- **Stack.** Kotlin 2.1.21, AGP 8.8, Gradle multi-module (Kotlin DSL), **classic Android Views (no
  Compose)**, minSdk 26, compileSdk 36, Material 1.12. The math feature is built **once** in
  `core-math` and reused by both the IME and overlay surfaces.

---

## 1. Method & the quality bar

### 1.1 What we are actually comparing

We benchmark four references, each for a different reason:

| Reference | What it teaches Tally | What we extract |
| --- | --- | --- |
| **Apple iOS keyboard + "Show Math Results"** | The *interaction philosophy* — invisible, confidence-building, reversible input; and the exact mechanics of our flagship math feature | Design *principles* + the math-suggestion contract |
| **Google Gboard** | The *engineering architecture* of a world-class keyboard (one decoder powering every feature) and the *privacy mechanism* (on-device + data minimization) | The decoder model, the suggestion-strip conventions, the offline feature set |
| **Samsung Keyboard (Honeyboard) / One UI** | The *constraints* — the OEM the overlay must survive and the IME must coexist with | Failure modes that define our compatibility bar |
| **OSS keyboard ecosystem** | What we can *legally reuse* under the closed-source gate, and what we must *build* | The license-gated build-vs-adopt map for typing engines and data |

### 1.2 What "Apple/Gboard/Samsung-grade" concretely means

The stakeholder ask — "elevate Tally to Apple/Gboard/Samsung quality" — is otherwise unfalsifiable.
We make it concrete. **"Grade" is met when a credible typing test passes all of the following,
measured on a mid-range device, not just a flagship:**

| Dimension | Concrete bar | Source of the standard |
| --- | --- | --- |
| **Latency** | Key-down to glyph render **< 30 ms**; suggestion recompute imperceptible; no jank at 60 fps during fast typing | Apple's "instant per-key feedback"; Gboard's ~50% decode-latency program |
| **Touch accuracy** | **Multi-touch** with rollover (next key accepted before previous released); fat-finger handled by a spatial model, not exact hit-testing | Gboard spatial model; current IME single-pointer touch is the #1 typing defect |
| **Per-key feedback** | Optional key-preview popup; optional independent sound + haptic tick; long-press alternate-character trays | Apple Character Preview + accent popups; Gboard popups |
| **Forgiveness** | Autocorrect/normalization is **visible and one-tap reversible**; suggestions are **low-commitment** (ignorable, space-to-accept) | Apple blue-underline revert; Gboard center-bold default |
| **Reach / ergonomics** | One-handed, split, floating, resizable; optional number row; landscape | Gboard + Samsung layout modes |
| **Accessibility** | Every key is an explorable TalkBack node via `AccessibilityNodeProvider`; high-contrast; honors Reduce Motion | Samsung high-contrast; baseline platform expectation; current IME is a single a11y node (fails) |
| **Coexistence** | The math feature works *in our own IME*; the overlay survives Honeyboard's composing-region and window-layering behavior | Samsung research (§4) |
| **Privacy posture** | No INTERNET permission; nothing leaves device; verifiable by manifest inspection | The differentiator the constraints hand us |

**These eight rows are the acceptance criteria** that [`04`](04-feature-matrix-and-roadmap.md)
sequences and that QA in the testing plan validates. "Grade" is not a vibe; it is this table.

### 1.3 The honest asymmetry (read before benchmarking against Apple)

Apple's inline-math feature is seamless because **Apple owns the OS, the keyboard, and the
suggestion bar as one product.** A third party on Android has no equivalent position: Gboard and
Samsung Keyboard are closed with **no plugin/extension API** — you cannot add a suggestion to their
strips. This is settled in [ADR-0001](docs/adr/ADR-0001-delivery-architecture.md) and
[`docs/03-platform-strategy.md`](docs/03-platform-strategy.md): the only sanctioned ways to surface
an in-field suggestion are (a) **be the IME** ourselves, or (b) **draw an overlay** above the field.
Therefore, **we benchmark Apple for *behavior and feel*, but we reproduce that feel inside our own
IME** — we never assume access to anyone else's strip. Keep this asymmetry in mind for every "adopt
from Apple" below: the *interaction* is the target; the *mechanism* is always "in Tally's own surfaces."

---

## 2. Apple iOS keyboard + "Show Math Results"

Apple is the standard for **invisible, confidence-building input**. Two clusters matter.

### 2.1 The math feature — the contract Tally already half-implements

iOS 18's "Show Math Results" (Settings → General → Keyboard, requires Predictive Text) surfaces the
answer to an expression terminating in `=` **as a suggestion in the existing QuickType bar** — no
calculator panel, no mode, no button.

| Apple choice | Why it exists | Tally decision |
| --- | --- | --- |
| Result appears in the **existing** suggestion bar, not a new surface | Zero added cognitive load, zero screen cost; the feature is purely additive and invisible until relevant | **KEEP.** Tally already renders math in its strip via `MathResultChip`. This is correct; do not invent a dedicated math panel. |
| Trigger is the natural terminator the user already types (`=`) | The feature is discovered accidentally and costs nothing when unused | **KEEP.** `core-math` already requires a trailing `=`. Matches Apple exactly. |
| Insertion is **dual-path**: tap the chip **or** press space | Overloads the existing "accept suggestion" affordance — no new gesture to learn | **ADOPT.** Bind math-chip acceptance to the same commit gesture as a normal word prediction (tap and/or space). No bespoke "insert result" button. |
| **No error / loading / empty states** — failure is silent absence | On-device + instantaneous → no latency, no loading; silent failure removes all downside risk and never punishes prose that happens to contain `=` | **KEEP & ENFORCE.** `core-math` already fails silently (returns `null`). Never surface a parse error or spinner. This is load-bearing, not polish. |
| Scope is the **trailing fragment**, not the whole field | Coexists with prose containing stray numbers/`=`; keeps parsing cheap | **KEEP.** `core-math`'s span + vetoes (dates/versions/phones/ISBN) already implement fragment-scoping. Tally is *ahead* of Apple here in documented robustness. |
| **Locale-aware** separators and currency | Showing `13.5` to a comma-decimal user breaks the illusion and is literally wrong | **KEEP.** `MathEngine.evaluate(..., locale, ...)` already drives this. Confirm the formatter path on both input and output. |

> **Net for the math feature:** Apple validates the design Tally *already has*. The work is not
> redesigning math — it is making the **host keyboard** good enough that users live in it, and making
> the **overlay** reliable enough that users who won't switch still get the chip. `core-math` is the
> strongest module; treat it as frozen contract and build the surfaces up to it.

### 2.2 The keyboard philosophy — the throughline we adopt as design law

These are the principles, with the rationale that makes them non-optional:

1. **Every input action gets immediate, proportionate feedback.** *Why:* on glass the finger
   occludes the key it presses, removing the tactile confirmation physical keys give for free.
   → **ADOPT** key-preview popups (toggleable, suppressed on the space bar), long-press accent/variant
   trays (progressive disclosure keeps the default layout large-targeted), and **independent**,
   optional sound + light haptic ticks (honor Silent Mode for sound, system haptics setting for
   haptics; default conservative). The current IME has *none* of these — this is core build work.
2. **Every automated change is visible and one-tap reversible.** *Why:* automation only earns trust
   when transparent and cheap to undo; Apple's blue underline turns a silent change into a noticed,
   correctable one. → **ADOPT** a transient marker + one-tap revert for *every* auto-normalization
   (autocorrect, and — critically — the optional "replace expression with result" math behavior the
   `replaceExpression` pref already exposes). Never auto-change without an undo affordance.
3. **Suggestions are always low-commitment.** *Why:* ignoring a suggestion must never penalize the
   user; per-word acceptance avoids over-committing to a wrong long completion. → **ADOPT** ignorable,
   space-to-accept, per-token suggestions.
4. **Motion is brief, functional, reducible.** *Why:* flashy/slow motion in a high-frequency surface
   becomes irritating and *feels* slower. → **ADOPT** short animations tied to concrete state changes
   (key press, suggestion appear/insert, correction); fully honor Reduce Motion.
5. **Mode switches are in-context and discoverable.** *Why:* burying switches in Settings makes them
   unswitchable mid-task; tap-for-common / long-press-for-menu serves both speed and depth with one
   control. → **ADOPT** the globe-key pattern (only shown when >1 option exists), and the tap/long-press
   duality for layout/dictation switches.
6. **Reachability on large surfaces.** One-handed shift, floating/repositionable input. → **ADOPT**
   on phones (one-handed) and tablets/foldables (floating, resizable) — see [`05`](05-device-framework-compatibility.md).

**Confidence note (carry into execution):** Apple does **not** document the exact fragment-boundary
parsing rules, the full supported-unit/function list, or the precise haptic curve. Treat those as
*observed behavior, not spec.* Tally's own `core-math` boundary rules are the authority; do not
reverse-engineer Apple's edge cases.

### 2.3 What we do **not** copy from Apple

- **Unit/currency/temperature conversion in v1.** Apple supports `16 Celsius =`, `$` amounts, etc.
  This adds locale-data and source complexity for a feature most math-keyboard users won't hit.
  **DEFER** to a later milestone (flag for the roadmap), keeping arithmetic-only at launch — this is
  a roadmap decision, owned by [`04`](04-feature-matrix-and-roadmap.md).
- **An on-device transformer LM for predictions/autocorrect.** Apple's QuickType uses a learned
  transformer. We do **not** chase model parity at v1 (see §3.3); an n-gram baseline meets the bar.

---

## 3. Google Gboard

Gboard is the **architecture** benchmark. Its decisive insight is that **one primitive powers nearly
every typing feature**, which is *why* Google can add languages and input methods cheaply. We adopt
the architecture; we reject the network-bound and proprietary parts.

### 3.1 The unifying primitive — build this first

Gboard's decoder composes three weighted finite-state transducers (FSTs), explored by **beam search**:

1. a **spatial model** (likelihood a touch point maps to a given key),
2. a **lexicon** transducer (key-sequence → word), and
3. an **n-gram language model** (probability of a word sequence).

Tap autocorrect, word completion, next-word prediction, glide typing, and multilingual input are all
**different traversals of the same graph**. *Why this matters:* one decoder = one place to tune
quality, one code path to optimize for latency, trivial language extension. Gboard reports ~50%
decode-latency reduction and >10% fewer manual corrections from this design.

> **ADOPT as Tally's typing foundation.** FSTs + beam search are a public, well-understood technique.
> Build: a compiled lexicon per bundled language, a static quantized n-gram LM, and a **geometric/Gaussian
> spatial scorer** (the neural spatial model is Google's proprietary edge — approximate it). This single
> component delivers tap autocorrect, completion, next-word, and glide from one codebase. The
> *engineering* of this lives in [`03-ime-architecture-v2.md`](03-ime-architecture-v2.md); here we only
> fix the intent: **one decoder, not five feature-specific engines.**

### 3.2 Suggestion-strip conventions — copy exactly, they are free wins

| Gboard choice | Why | Tally decision |
| --- | --- | --- |
| **Three** candidates | A deliberate cognitive-load ceiling for a glanceable strip | **ADOPT.** Tally's `SuggestionStripView` shows 3. |
| **Most-likely centered and bolded**; that center word is the silent auto-apply default if typing continues | Exploits visual centrality; signals model confidence; makes implicit autocorrect predictable | **ADOPT exactly.** Wire to the top-3 decoder output. |
| **Long-press a suggestion to forget** a learned word | User control over personalization | **ADOPT.** Drives the local personalization table (below). |
| **Math chips coexist** in this same strip | Reuse, not a new surface (matches Apple §2.1) | **KEEP.** Math chip is the highest-priority entry when present, else normal predictions. |

### 3.3 The LM stack — adopt the n-gram pillar, **skip federated learning entirely**

Gboard's prediction rests on n-gram LMs, small neural LMs (historically a ~1-layer LSTM), and
on-device personalization — and the neural LMs are *trained* via **federated learning + differential
privacy + secure aggregation**. *Why those exist:* to get the quality of training on real user text
**without centralizing keystrokes** — data minimization as a privacy principle.

> **Tally decision.** **ADOPT** the n-gram pillar and a **local, on-device personalization layer**
> (a per-user frequency table / cache updated purely on-device, never uploaded, with long-press-to-forget).
> **SKIP federated learning, DP, and secure aggregation entirely** — they are a *cloud-training pipeline*,
> categorically incompatible with NO-NETWORK and unnecessary for a closed product. Their entire purpose
> was *safe cloud training*; with no cloud, there is nothing to make safe. Ship a **static, high-quality
> n-gram LM** trained offline on **permissively-licensed** corpora (license gate — see §5/§3.7). A small
> bundled neural LM is *optional polish*, not v1.

This is the single most important "do less" decision in the document: **Tally's privacy posture makes
Gboard's hardest engineering (the federated pipeline) not just out of scope but irrelevant.**

### 3.4 Glide / gesture typing — a decoder feature, with a hard license reality

Glide is *not* a separate system: the decoder matches the continuous trace against candidate words
using the same spatial + lexicon + LM machinery, and **leans on the LM, not the literal path**, which
is what makes a sloppy swipe resolve correctly.

**But the license/availability reality is the most important finding in the OSS survey (verified):**

- There is **no permissively-licensed, production-quality, droppable glide engine.** *(Verdict:
  confirmed.)*
- Gboard-grade glide lives in Google's **proprietary `libjni_latinimegoogle.so`** blob. It is **not
  redistributable** and must never be bundled. HeliBoard/OpenBoard *side-load* it precisely because no
  open equivalent exists.
- AOSP LatinIME's native gesture tree is a **stub** — the real decoder was never open-sourced. "AOSP
  gives you free glide" is **false.** *(Verdict: confirmed.)*
- The only genuinely reusable permissive glide code is a pure-Java **statistical corner-matching
  classifier** (AnySoftKeyboard's `GestureTypingDetector`, Apache-2.0; and the older FlorisBoard
  0.3.x/0.4.x derivative). It returns words but is **accuracy-limited vs Gboard.**
- CleverKeys (a modern, production-quality neural swipe keyboard) is **GPL-3.0 → excluded.**

> **Tally decision.** **ADOPT glide as a decoder feature, but BUILD the engine** by porting the
> Apache-2.0 statistical classifier as the v0 seed, then improving it (geometric path scoring +
> resampling + DTW-style template distance, ranked by the same n-gram LM). **Set the expectation
> explicitly: day-1 glide accuracy trails Gboard** and requires tuning with real touch data. **Never
> bundle `libjni_latinimegoogle.so`. Never fork CleverKeys/HeliBoard/OpenBoard (GPL).** Glide is a
> *build-it-yourself, multi-week workstream* — it is **not** a v1 table-stakes blocker; sequence it in
> [`04`](04-feature-matrix-and-roadmap.md) after multi-touch tap typing is solid. (The NLnet open
> gesture-typing project, deadline mid-2026, may change this calculus later; treat as unproven until
> released and license-verified.)

### 3.5 Offline features to adopt wholesale (high value, zero network, no ML)

| Feature | Why it exists | Tally decision |
| --- | --- | --- |
| **Clipboard manager** (history + pin + entity extraction of phone/URL/email) | Mobile copy/paste is painful; structured clipboard removes app-switching | **ADOPT.** Pure on-device. Entity extraction = on-device regex, **no ML, no network**. Best effort-to-value feature available. (Security note: encrypt-at-rest, auto-expire sensitive clips — see [`06`](06-security-privacy-hardening.md).) |
| **Emoji picker** (recents, categories, keyword search, skin tones) + **context suggestions** | Keyboards are the primary expression surface | **ADOPT.** Data is **Unicode CLDR** (Unicode License — permissive, commercial-OK with attribution). Build the picker; bundle CLDR annotations. |
| **Layout modes**: one-handed, floating, split, resizable, **number row** | Phones outgrew comfortable thumb reach; number row trades height for fewer layer switches | **ADOPT all.** Pure view-geometry transforms over one key model, zero network/ML. Table stakes. |
| **Material You dynamic color** + custom/preset themes | Native feel + personalization drive attachment | **ADOPT.** Read the system dynamic-color palette (Android 12+) and map to key/bg/accent roles; ship presets. Material 3 dynamic color is **Apache-2.0**. Honor light/dark. *(Implement in classic Views — no Compose.)* |
| **Simultaneous multilingual typing** (same-script, up to ~3 langs, no manual switch) | Multilingual users code-switch mid-sentence | **ADOPT (decoder-config feature).** Load multiple lexicon+LM FSTs into one search space; constrain to **same-script** sets first (Latin family). Gated on **permissively-licensed dictionaries per language** (§3.7). |
| **Emoji combos (Emoji Kitchen-style)** | Novelty/engagement | **PARTIAL.** Combos are pre-rendered *assets*. Google's artwork is proprietary → ship a **small curated first-party pack** if desired, or omit. Low priority. |

### 3.6 What is proprietary or network-bound — reject or approximate

| Gboard feature | Status under our constraints | Tally decision |
| --- | --- | --- |
| **GIF search** | Inherently network (remote corpus) | **REJECT by construction.** Support only user-imported/bundled GIFs if anything. |
| **Cloud translate / translate-as-you-type** | Network; offline MT is heavy | **REJECT** (or stub). |
| **Voice typing (RNN-T)** | Google's model + training data are proprietary; building ASR from scratch is the single hardest non-network component | **APPROXIMATE, optional.** Do **not** build a recognizer. Either call the host OS on-device recognizer, or bundle a permissive offline engine (**Vosk, Apache-2.0**, ~50 MB/lang). Opt-in, model bundled/sideloaded so the core keyboard process stays network-less. **DEFER** past v1. |
| **Smart Compose / Proofread / rephrase / tone (Gemini Nano)** | Hardware-gated, Google-proprietary on-device LLM; cannot ship it | **MOSTLY REJECT.** Generative rewrite is out of scope for a closed, no-network v1. Optionally, a *rule-based + small bundled grammar model* "Grammar Check" is a fair later target (Gboard's grammar check genuinely runs on-device). Not v1. |
| **Federated learning / DP / secure aggregation** | Cloud-training pipeline | **REJECT** (see §3.3 — irrelevant with no cloud). |

### 3.7 The data license trap (applies to §3.4 and §3.5 multilingual)

Even when all *code* is permissive, **dictionary/frequency DATA is a separate, fatal trap.** Most
high-quality frequency lists (hermitdave FrequencyWords, wordfreq, much of the aosp-dictionaries
collection) are **CC-BY-SA / GPL / CC-BY-NC** — ShareAlike and NonCommercial both kill closed
commercial use. Tooling (AOSP `dicttool`, FlorisBoard dictionary-tools) is Apache-2.0 and **clean to
use**, but the **wordlists must come from permissive/public-domain corpora**:

- **English:** SCOWL/ENABLE/12dicts (MIT-like / public domain, includes frequency tiers) — **clean.**
- **Emoji:** Unicode CLDR (Unicode License) — **clean.**
- **Every other language:** **legally vet per source**; reject CC-BY-SA / CC-BY-NC / GPL data.

> **Binding rule for execution:** keep **auditable, per-language, per-source provenance** for every
> wordlist. A language ships prediction/spell-check **only** when it has vetted permissive data.
> Layout geometry is free to author; *data* is the gating constraint. (The license-gate detail and the
> third-party attribution screen live in [`06`](06-security-privacy-hardening.md).)

---

## 4. Samsung Keyboard (Honeyboard) / One UI — the constraints, not the features

Samsung's *features* are conventional (floating/split/one-handed, optional number row, a customizable
≤7-item toolbar, up to 9 predictions, high-contrast mode). **The compatibility risk is not in those
features — it is in Honeyboard's deviations from AOSP IME semantics and One UI's window/clipboard
behavior.** This section exists because the stakeholder explicitly named the **broken overlay that
fails with Samsung Keyboard.** The *fix* is owned by [`02-overlay-root-cause-and-redesign.md`](02-overlay-root-cause-and-redesign.md)
and the *device matrix* by [`05`](05-device-framework-compatibility.md); here we record the constraints
that any Tally surface must respect.

| Samsung/One UI behavior | Why it breaks naive overlays/insertion | Constraint it imposes on Tally |
| --- | --- | --- |
| Honeyboard **misuses `getExtractedText()` as a live state getter** (Flutter engine PR #17426) | A stale/empty `ExtractedText` triggers Samsung-only text duplication and typing lag | If Tally ever owns an `InputConnection`, **implement `getExtractedText()` to return a real, current snapshot.** |
| **Composing-region save/restore duplicates text / repeats first letter** on a subset of Galaxy devices (flutter#31512, #51893) | Programmatically setting text into a field with an active composing region is exactly what the overlay does → garbled text. **Invisible on Pixel.** | **Before any insertion: `finishComposingText()` / collapse the composing span.** Treat any insertion that leaves a composing region as unsafe on Samsung. |
| **`TYPE_ACCESSIBILITY_OVERLAY` renders *below* the IME window** | A bottom-anchored chip is occluded by the (often tall) Honeyboard and **cannot receive touches** in the keyboard region | **Anchor overlays above the IME using live `WindowInsets` (ime inset)** — never assume a fixed AOSP keyboard height; never place interactive elements where a keyboard can sit. |
| **One UI clipboard toasts fire on read *and* write** (Android 13+; un-suppressible without ADB) | Any copy/paste-based insertion shows a visible toast every time and may trip the user's clipboard-access alert | **Eliminate the clipboard from the insertion path.** (The current overlay's `ClipboardInserter` set-primary-clip + `ACTION_PASTE` approach is exactly this anti-pattern — see [`02`](02-overlay-root-cause-and-redesign.md).) |
| **Secure/password fields** suppress suggestions/toolbar and have sticky secure-flag transitions | The suggestion strip may not exist; insertion timing/anchoring breaks on the fields we'd most care about | Don't rely on the strip existing; **re-query field state and re-anchor after `restartInput`/inputType transitions.** |
| **A11y events are high-volume, laggy**, and only on **Android 17+** distinguish composing vs commit (`getTextChangeTypes()`) | Reacting per-`TEXT_CHANGED` causes premature/duplicated insertions; pre-17 One UI can't tell composing from commit at the a11y layer | **Debounce, then verify by reading the node text after acting** — never trust the event stream as truth. On 17+, use `getTextChangeTypes()` to ignore composing-only updates. |
| Toolbar + number row + suggestion strip **stack tall**; floating/split/Fold change geometry | More vertical occlusion of any bottom overlay; inset math varies | **Test matrix must vary** toolbar on/off, number row on/off, floating/split/one-handed, phone/Fold/Tab — see [`05`](05-device-framework-compatibility.md). |

> **Synthesis of the Samsung constraint.** The overlay's "works on Pixel, breaks on Samsung" is
> **over-determined** by three independent causes: (1) overlay z-order is below the IME; (2) inserting
> over a live composing region duplicates text on specific Galaxy models; (3) clipboard/custom-`InputConnection`
> paths add toasts and `getExtractedText` corruption. The corrected insertion contract is:
> **`finishComposingText` → set selection → atomic `ACTION_SET_TEXT` → read back node text to verify**,
> with the overlay anchored above the keyboard via live insets and the clipboard removed entirely. This
> is intent only; the implementation is [`02`](02-overlay-root-cause-and-redesign.md).

**Caveat to honor:** several Samsung specifics are inferred from Flutter maintainer statements and
device reports, not Samsung documentation, and behavior **varies by One UI/Honeyboard version and even
by model within a version.** Coverage must be Samsung-**broad**, not "one Samsung phone."

---

## 5. Open-source bases compared (with the closed-source license gate applied)

The license gate is applied **first and absolutely**: anything GPL/LGPL is excluded from the shipped
binary regardless of quality. Reading the architecture for ideas is fine; no copyleft code/strings/
resources/data enter the Tally tree.

| Base / engine | License | Maturity (2026) | Glide? | Languages / data | Fit for Tally (closed-source) |
| --- | --- | --- | --- | --- | --- |
| **AOSP LatinIME** | **Apache-2.0** ✅ | Upstream **maintenance-only**, effectively unmaintained | **No working glide** — native decoder is a **stub**; real one is the proprietary blob | Apache tooling; quality dicts not in AOSP | **Reference only** for IME plumbing + proximity geometry. **Not a glide source.** |
| **FlorisBoard** | **Apache-2.0** ✅ | Active (~0.5.x); rewrite in progress | **Glide currently REMOVED** (returning ~v0.7); reusable classifier is in **older 0.3.x/0.4.x history** | Own statistical NLP provider | **Best permissive code source.** Mine the **archived** classifier as a porting seed; **no runtime dependency** on it. |
| **AnySoftKeyboard** | **Apache-2.0** ✅ | Active | **Yes** — `GestureTypingDetector` (corner-matching, pure Java); accuracy-limited | — | **Adopt the glide classifier** as the v0 engine seed (§3.4). |
| **Simple Keyboard** | **Apache-2.0** ✅ | Active, minimal | **None (by design)** | None | **Reference** for the IME-service/layout shell. No glide/prediction value. |
| **OpenBoard** | **GPL-3.0** ❌ | Unmaintained/archived (Dec 2022) | Side-loads proprietary blob | — | **EXCLUDED** (copyleft). |
| **HeliBoard** | **GPL-3.0** ❌ | **Active** (the live AOSP-lineage fork) | Side-loads proprietary blob; no built-in glide | Dictionaries as separate downloads | **EXCLUDED** (copyleft). Read for ideas only; verify before *any* code touches Tally. |
| **Unexpected Keyboard** | **GPL-3.0** ❌ | Active | "Swipe" = corner symbol entry, **not** word glide | — | **EXCLUDED** + irrelevant to glide. |
| **CleverKeys** | **GPL-3.0** ❌ | Production-quality neural swipe | **Yes** (transformer, ONNX) | Own model/data | **EXCLUDED** (copyleft) even though it's the best open glide. |
| `libjni_latinimegoogle.so` | **Proprietary** ❌ | Google blob | The "real" Gboard glide | — | **NEVER bundle.** Non-redistributable, moving target. |

**Data sources (separate gate, §3.7):** AOSP/FlorisBoard dictionary **tooling** = Apache-2.0 ✅;
SCOWL/ENABLE/12dicts (English) = permissive ✅; Unicode CLDR (emoji) = Unicode License ✅;
FrequencyWords/wordfreq/much of aosp-dictionaries = **CC-BY-SA / GPL / CC-BY-NC ❌**;
KenLM (n-gram toolkit) = **GPL/LGPL ❌ → build our own n-gram/FST scorer**;
Vosk (offline ASR) = Apache-2.0 ✅ (models per-language, confirm each).

> **Decisive conclusion (matches [ADR-0001](docs/adr/ADR-0001-delivery-architecture.md)):** **No
> permissive base ships working glide *and* is licensable for closed-source.** Therefore **build the
> keyboard shell from scratch** (referencing Simple Keyboard / AOSP LatinIME for Apache plumbing
> patterns), **port only the focused Apache-2.0 statistical glide classifier** as the engine seed,
> **source data only from permissive corpora**, and keep the decoder behind a **narrow host interface**
> (`GlideDecoder { setLexicon(words, freqs); decode(path, keyGeometry) → ranked candidates }`) so the
> shell and the engine evolve independently. `core-math` stays decoupled behind the same boundary,
> exactly as it is today.

---

## 6. SYNTHESIS — the unified Tally design language + keep/adopt/approximate/reject

### 6.1 The Tally design language (one paragraph the agents internalize)

**Tally is an Apple-grade *feel* on a Gboard-grade *architecture*, shipped under a privacy posture
neither can match, surviving Samsung's OEM reality.** Concretely: a **single FST + beam-search decoder**
powers tap, completion, prediction, and (later) glide; the **suggestion strip** shows three candidates
with the top one centered, bolded, and the silent default — and the **math chip** rides that same strip
(Apple's "no new surface" rule). **Every input action gets immediate, proportionate feedback; every
automatic change is visible and one-tap reversible; every suggestion is low-commitment; motion is brief
and reducible.** Math fails **silently**, scopes to the **trailing fragment**, and is **locale-correct** —
the contract `core-math` already meets. Everything is **on-device with no INTERNET permission**, which
is both the privacy guarantee and the reason Gboard's hardest engineering (federated learning) is
irrelevant to us. The overlay and IME consume the **same `core-math`**; insertion is **composing-region-safe
and clipboard-free** so it survives Honeyboard.

### 6.2 The combine-into-one-product decision table (binding intent)

> Legend — **KEEP**: already correct in the repo, do not regress. **ADOPT**: build to match the
> reference. **APPROXIMATE**: build a permissive/offline substitute for a proprietary/network feature.
> **REJECT**: out by constraint. Sequencing (v1 vs later) is owned by [`04`](04-feature-matrix-and-roadmap.md);
> this table fixes *what* and *why*, not *when*.

| Capability | Decision | Source | Rationale (one line) |
| --- | --- | --- | --- |
| Math chip in the existing strip; `=` trigger; tap/space insert; silent fail; fragment scope; locale-correct | **KEEP** | Apple | `core-math` already implements the contract; freeze it. |
| Visible + one-tap-reversible normalization (incl. `replaceExpression`) | **ADOPT** | Apple | Automation earns trust only when reversible. |
| Multi-touch tap typing with rollover + spatial model | **ADOPT** | Gboard | Fixes the #1 current defect (single-pointer touch). |
| One FST + beam-search decoder (tap/completion/prediction/glide) | **ADOPT** | Gboard | One engine to tune; the architecture, not five engines. |
| Static n-gram LM + **local-only** personalization (forgettable) | **ADOPT** | Gboard | Predictive feel without any cloud. |
| 3-candidate strip, center-bold default, long-press-to-forget | **ADOPT** | Gboard | Proven, free UX conventions. |
| Key-preview popups; long-press alternate/variant trays | **ADOPT** | Apple | Restores confirmation lost on glass; progressive disclosure. |
| Independent optional sound + light haptic tick | **ADOPT** | Apple | Per-key feedback aids speed/accuracy; respect Silent/Reduce. |
| `AccessibilityNodeProvider` so TalkBack explores each key | **ADOPT** | Baseline + Samsung | Current IME is one a11y node — fails accessibility grade. |
| One-handed / split / floating / resizable / number row / landscape | **ADOPT** | Gboard + Samsung | Table-stakes ergonomics; pure geometry, no ML/network. |
| Clipboard manager (history, pin, regex entity extraction) | **ADOPT** | Gboard | Best effort-to-value; on-device, no ML. |
| Emoji picker + keyword search + skin tones + recents (Unicode CLDR) | **ADOPT** | Gboard | Permissive data, fully offline. |
| Material You dynamic color + presets (classic Views) | **ADOPT** | Gboard | Native feel; Apache-2.0 lib; no Compose. |
| Same-script simultaneous multilingual typing | **ADOPT** | Gboard | Decoder-config feature; gated on permissive per-lang data. |
| Glide / gesture typing (build from Apache statistical classifier) | **ADOPT (build)** | Gboard/OSS | No droppable permissive engine exists; accuracy trails Gboard. |
| Composing-safe, clipboard-free, inset-anchored insertion | **ADOPT** | Samsung | The only insertion that survives Honeyboard. |
| Voice typing (host recognizer or bundled Vosk, opt-in) | **APPROXIMATE** | Gboard | Don't build ASR; keep core process network-less. |
| Unit / currency / temperature math conversions | **APPROXIMATE/DEFER** | Apple | Adds locale-data complexity; arithmetic-only at launch. |
| Grammar Check (rule-based + small bundled model) | **APPROXIMATE/DEFER** | Gboard | On-device is feasible later; not v1. |
| Emoji-combo pack (curated first-party) | **APPROXIMATE** (optional) | Gboard | Google artwork is proprietary; ship small own pack or omit. |
| GIF search | **REJECT** | Gboard | Inherently network. |
| Cloud translate | **REJECT** | Gboard | Inherently network. |
| Generative rewrite / tone (Gemini Nano) | **REJECT** | Gboard | Proprietary, hardware-gated on-device LLM. |
| Federated learning / DP / secure aggregation | **REJECT** | Gboard | Cloud-training pipeline; irrelevant with no cloud. |
| Forking GPL keyboards (HeliBoard/OpenBoard/Unexpected/CleverKeys) | **REJECT** | OSS | Copyleft would force open-sourcing Tally. |
| Bundling `libjni_latinimegoogle.so` | **REJECT** | OSS | Non-redistributable proprietary blob. |
| CC-BY-SA / CC-BY-NC / GPL dictionary data | **REJECT** | OSS | License-incompatible with closed commercial use. |

### 6.3 The one-screen mental model for the agents

```
        ┌─────────────────────────────────────────────────────────┐
        │                   TALLY IME (primary)                    │
        │                                                          │
        │   SuggestionStrip:  [ word ]  [ *DEFAULT* ]  [ word ]    │  ← 3, center bold (Gboard)
        │                     └─ math chip rides HERE (Apple) ─┘   │
        │                                                          │
        │   ┌──────────────── ONE DECODER (Gboard) ─────────────┐  │
        │   │ spatial(Gaussian) + lexicon(FST) + n-gram LM      │  │
        │   │ → tap / completion / next-word / glide(built)     │  │
        │   └──────────────────────────┬────────────────────────┘  │
        │                              │ host interface (swappable) │
        │   core-math.evaluate(...) ───┤ same engine, both surfaces │
        │   (FROZEN contract) ─────────┘                            │
        │                                                          │
        │   per-key popup • haptic/sound • long-press trays        │  ← Apple feel
        │   one-handed/split/float/resize/number-row • a11y nodes  │  ← ergonomics + grade
        └─────────────────────────────────────────────────────────┘
                              ▲
                              │ same core-math
        ┌─────────────────────┴───────────────────────────────────┐
        │   TALLY OVERLAY (secondary, off-Play)                    │
        │   finishComposing → setSelection → ACTION_SET_TEXT       │  ← Samsung-safe
        │   → read-back verify;  anchored ABOVE ime via insets;    │
        │   NO clipboard.                                          │
        └─────────────────────────────────────────────────────────┘

   NO INTERNET PERMISSION ANYWHERE.  All on-device.  Permissive licenses only.
```

---

## 7. Explicitly NOT copying (and why)

These are deliberate non-goals. Recording them prevents an execution agent from "helpfully" adding
them back and breaking a constraint.

| Not copying | Why not |
| --- | --- |
| **Gboard's neural spatial model / on-device transformer LM** | Proprietary, learned weights; an n-gram + Gaussian spatial model meets the bar. Chasing parity is unbounded R&D for marginal v1 gain. **Approximate, don't clone.** |
| **Federated learning + differential privacy + secure aggregation** | These exist *only* to make **cloud training** safe. With **no cloud and no network**, there is nothing to make safe — copying them would be cargo-culting infrastructure we will never run. |
| **Any network feature** (GIF search, cloud translate, cloud rewrite, model auto-update over the wire) | Violates the load-bearing NO-INTERNET guarantee. Out by construction — not a deferral, a rejection. |
| **`libjni_latinimegoogle.so` (Google's glide blob)** | Non-redistributable proprietary binary; a moving target across Gboard versions. Bundling it is a legal landmine and a maintenance trap. |
| **GPL/LGPL bases** (HeliBoard, OpenBoard, Unexpected Keyboard, CleverKeys) and **KenLM** | Copyleft. Forking/static-linking would force Tally's own source open, breaking the closed-source business constraint. (LGPL dynamic linking is technically possible but operationally brittle on mobile — we **build the n-gram scorer in-house** instead.) |
| **CC-BY-SA / CC-BY-NC / GPL dictionary & frequency data** | A *data* copyleft/non-commercial trap as fatal as code copyleft. Permissive corpora only, per-language provenance tracked. |
| **Apple's exact undocumented internals** (fragment-boundary rules, haptic curve, full unit list) | Not public; reverse-engineering edge cases is guesswork. `core-math`'s own documented rules are the authority. **Copy the *behavior and feel*, not inferred internals.** |
| **Anyone else's suggestion strip** (Gboard/Samsung) | There is **no plugin/extension API** for either ([`03-platform-strategy.md`](docs/03-platform-strategy.md)). We reproduce the *experience* inside **our own** IME and overlay; we never assume access to a third party's surface. |
| **Samsung's clipboard-based / `getExtractedText`-as-getter patterns** | These are the *cause* of the breakage, not a feature to emulate. We do the opposite: composing-safe, clipboard-free, verified insertion. |
| **A Compose UI** | Stack is **classic Views**. Even where a reference uses Compose (e.g. Material 3 samples), we implement equivalents in Views per the build constraint. |
