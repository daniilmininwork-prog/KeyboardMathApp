# 08 — State & strategy: the fork won; make it official (2026-07-05)

> **What this is.** A full-repo audit of everything carrying the Tally name — this repository
> and the `TallyBoard` HeliBoard fork next to it — one month after work stopped. It reviews both
> attempts, resolves the strategic contradiction between them, and sets the ship plan. Where this
> document and `00-master-plan.md` disagree, **this document wins** (it supersedes 00's
> do-not-fork decision with the benefit of the fork actually existing and working).

## 1. The problem Tally solves (unchanged, still right)

iOS QuickType evaluates `12×9=` inline and offers `108` right in the typing flow. Android has
nothing native. The product is that one moment: type arithmetic anywhere, tap the result,
never leave the field. Constraints that make it defensible: fully on-device, no INTERNET
permission, silent on non-math text (false positives are the one unforgivable sin).

## 2. What exists today (verified this audit)

| Asset | Where | State |
| --- | --- | --- |
| **core-math engine** | this repo, `core-math/` | **Excellent.** BigDecimal `MathContext(34, HALF_EVEN)`, parse-first-then-veto detection, right-anchoring, locale-aware formatting. **224 JVM tests green** (golden/property/fuzz/stress/false-positive corpora). The crown jewel. |
| Clean-room keyboard v2 | this repo, `keyboard-engine/`+`ime/`+`prediction/` (~24k LOC) | M0–M5 done ("Samsung-parity Phase 2"), but still a prototype-grade daily driver vs Gboard/Samsung. 42/45 tasks; TX.2/TX.3 remain. |
| Overlay (rebuilt) | this repo, `overlay/` (+`overlay-app/`) | TO.1–TO.5 done, 77 tests green, correct-or-silent design; **stuck at TO.6 ship/cut human gate** (Samsung device matrix + Play policy). |
| **Tally: Calculate** | this repo, `process-text/` (258 LOC) | **Done and shipped as APK** (Jun 4). Zero permissions, PROCESS_TEXT selection-toolbar calculator. Works with every keyboard. |
| **TallyBoard fork** | `../TallyBoard` | HeliBoard v3.9-101 fork, engine vendored verbatim (diff-verified identical minus the KMP collapse), 50-line bridge + 3-point InputLogic integration, settings toggle, native-lib hardening. **Built and device-verified Jun 4** (`Tally-Keyboard-3.9.apk`). Sat **uncommitted for a month**; now preserved on branch `tally-integration` with the 95-entry false-positive corpus ported (all green). |

Timeline: clean-room build May 31–Jun 3 → honest root-cause docs (`rebuild/00–07`) → fork built
in ONE DAY on Jun 4 and immediately reached the end-to-end experience the clean-room path had
not reached in five. That speed differential is the whole strategic argument.

## 3. Critique

1. **The license contradiction was never resolved.** `01-competitive-analysis.md` hard-gates
   the product as closed-source (GPL excluded), and `00-master-plan.md` §2 explicitly rejects
   forking a keyboard for that reason. The next day, Tally was built INTO a GPL-3.0 keyboard.
   Both artifacts now exist; the docs pretend the second doesn't. Shipping TallyBoard
   closed-source would be a GPL violation; shipping it open kills the closed-source strategy
   *for that artifact*. This needed a decision, not silence — §4 makes it.
2. **A month of work sat uncommitted on a moving upstream.** The fork's entire value-add
   (19 modified files + 3 new dirs) was untracked while upstream advanced. One `git clean` or
   upstream sync away from loss. (Fixed this audit: branch `tally-integration`.)
3. **The fork shipped without its immune system.** Only a 10-case port-integrity test came
   along; the golden/property/fuzz/false-positive corpora — the very thing that makes the
   engine trustworthy — stayed behind in core-math. (Partially fixed this audit: the 95-entry
   false-positive corpus is ported and green. Golden/property/fuzz still to port.)
4. **The clean-room keyboard is a strategic sinkhole.** Matching Gboard/Samsung/HeliBoard on
   gesture typing, multilingual dictionaries, layouts, OEM quirks, and accessibility is a
   multi-year product in itself — all to deliver a feature that needs one suggestion chip.
   M0–M5 got impressively far and is still nowhere near "a keyboard a stranger keeps".
5. **The overlay is a Play-policy dead end** (accessibility-API surface; per
   `overlay-ship-cut-decision.md` the device matrix can't even be verified in this
   environment). It's good engineering pointed at a distribution channel that doesn't want it.
6. **Docs drift.** `README.md` still says "Status: design phase"; the true status is "two
   working products and a fork". The best thinking (rebuild docs) is genuinely strong — the
   failure mode is that decisions made in code (the fork) never flowed back into them.

## 4. Decisions (superseding 00 where they conflict)

- **D-2026-07-05-1 — The fork is the keyboard strategy.** TallyBoard ships as the Tally
  keyboard, **openly, under GPL-3.0**, source published (a public repo is mandatory for GPL
  compliance and is also the F-Droid/IzzyOnDroid ticket). Rationale: it reached the product
  experience in a day on a maintained, beloved base; the clean-room path burns years
  re-earning table stakes. The closed-source gate from `01` is **revoked for the keyboard**.
- **D-2026-07-05-2 — core-math stays canonical here and gets a permissive license (MIT or
  Apache-2.0).** We own it, so we can license it permissively AND vendor it into the GPL fork
  (permissive→GPL is one-way compatible). That keeps every future surface open: the GPL
  keyboard, a closed process-text app if ever desired, iOS/KMP later. The vendored copy in
  TallyBoard carries GPL headers, correctly.
- **D-2026-07-05-3 — Overlay: CUT for distribution; archive the code.** TO.6 gets its answer:
  no store channel wants it, the Samsung matrix is unverifiable here, and the fork makes it
  redundant (anyone willing to sideload an overlay will sideload a keyboard). The TO.1–TO.5
  work stays in-tree, mothballed, with this decision recorded. Revisit only if real users ask.
- **D-2026-07-05-4 — Ship Tally: Calculate now.** It's finished, zero-permission, and
  Play-friendly. It is the brand wedge for Gboard loyalists and costs nothing to keep.
- **D-2026-07-05-5 — The clean-room keyboard modules are mothballed, not deleted.**
  `keyboard-engine`/`ime`/`prediction` stop receiving work. They remain as reference and as a
  hedge if the fork relationship ever sours.
- **D-2026-07-05-6 — Open an upstreaming conversation with HeliBoard.** The integration is
  ~150 lines against upstream + a self-contained engine; as an off-by-default "math results"
  setting it is a plausible upstream feature. If accepted, fork-maintenance debt drops to
  zero permanently. Open a Discussion/issue first, PR only on signal.

## 5. Plan

**Phase A — make the fork releasable (~days)**
1. ~~Commit the integration~~ (done: `tally-integration`, incl. false-positive corpus).
2. Port the golden + property corpora from core-math (JUnit 4). Add one Robolectric
   InputLogic test: `2+2=` → KIND_TALLY chip shown → pick → text is `2+2=4`, history clean.
3. Create the user's own GitHub repo (e.g. `daniilmininwork-prog/tallyboard`), point origin
   there (upstream stays as `upstream` remote), push the branch. **Never push to
   Helium314/HeliBoard.**
4. Rebase onto the latest upstream **tag** (releases only, never main-chasing); build signed
   release; smoke on `Tally_Test_API35` (see CLAUDE.md for the ADB/IME drill).
5. Release v0.1 via GitHub Releases + IzzyOnDroid. Play later (GPL is Play-compatible with
   published source; Data Safety: no data collected).

**Phase B — stop the bleeding here (~hours)**
6. Add `LICENSE-MIT` (or Apache-2.0) to `core-math` per D-2; note the vendoring relationship
   in both repos. Update `README.md` status + `ROADMAP.md` to this strategy; mark TO.6
   resolved-as-cut in `overlay-ship-cut-decision.md`; close TX.2/TX.3 against the new plan.
7. Ship Tally: Calculate to Play (needs: signing key, listing copy, Data Safety = none).

**Phase C — kill the fork tax (opportunistic)**
8. HeliBoard Discussion: "would you take an off-by-default inline-math suggestion setting?"
   With the false-positive corpus as the credibility exhibit. If yes → PR; if no → the
   per-release rebase cadence from Phase A is the steady state (~101 commits/month upstream,
   touching 3 integration points — expect occasional trivial conflicts only).

## 6. Tech-debt verdict

**No new app and no overhaul.** The overhaul already happened — it was the Jun-4 fork pivot;
it just was never committed, licensed, or written down. Total debt to releasable: the fork
carries ~150 integration lines on a maintained base with the engine's tests now arriving;
that is *low* debt. The high-debt asset is the clean-room keyboard, and the correct treatment
is mothballing, not repayment. The engine — the only truly hard thing built here — is done,
proven (224 + 105 tests green across both repos), and now the strategy protects it instead of
the keyboard rewrite consuming it.
