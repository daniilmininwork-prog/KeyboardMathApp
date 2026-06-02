# Rebuild plan — from math-chip prototype to a production keyboard

This directory is the system-architecture and execution plan for taking Tally from its current
state (a strong math engine, a prototype keyboard, a broken overlay) to a production-grade
general-purpose Android keyboard with working inline math and a repositioned, best-effort overlay.

It is **additive** to the existing `docs/01–10` (which remain valid as product/spec/security
background) and **supersedes** the phase numbering in `docs/10-implementation-plan.md`, which
predates the general-keyboard pivot.

These are **plans for engineering agents to execute** — not source code.

## Read in this order

| Doc | What it decides |
|---|---|
| [`00-master-plan.md`](00-master-plan.md) | The situation, the strategy, the two gates (license + no-network), the full **decision ledger**, the milestone program, and the v1.0 cut line. **Start here.** |
| [`01-competitive-analysis.md`](01-competitive-analysis.md) | Apple / Gboard / Samsung / OSS design choices, *why* each exists, and how to combine them into Tally's design language. Where the bar is. |
| [`02-overlay-root-cause-and-redesign.md`](02-overlay-root-cause-and-redesign.md) | Why the overlay fails (especially on Samsung), the correct-or-silent redesign, the honest Play-policy reality, and the reposition decision. |
| [`03-ime-architecture-v2.md`](03-ime-architecture-v2.md) | The keyboard rebuild: clean-room decision, module set, rendering/input pipeline, prediction integration, accessibility, security seam. |
| [`04-feature-matrix-and-roadmap.md`](04-feature-matrix-and-roadmap.md) | The full feature set, tiered P0/P1/P2 with build-vs-buy + license + privacy, and the phased roadmap. |
| [`05-device-framework-compatibility.md`](05-device-framework-compatibility.md) | "Optimize for as many devices/frameworks as possible," concretely: versions, OEMs, form factors, RTL, cross-platform, and the test matrix. |
| [`06-security-privacy-hardening.md`](06-security-privacy-hardening.md) | Threat model v2, least-privilege permissions, secure-field handling, supply chain, and the executable hardening checklist. |
| [`07-agent-execution-plan.md`](07-agent-execution-plan.md) | The task graph: epics M0–M5 + overlay track, self-contained task cards with dependencies and acceptance criteria, verification gates, and parallelization waves. **The thing you execute.** |

## The one-paragraph version

Keep the math engine; **rebuild the keyboard layer clean-room** (it is a dead-end prototype, and the
closed-source license gate rules out forking any existing keyboard); **reposition the overlay** to a
best-effort, off-Play assist that is correct-or-silent and dormant whenever Tally's own IME is
active; and hold a **structurally verifiable** privacy line throughout (no `INTERNET`, ever; an empty
IME permission list; an independent audit). The IME is the product. The first three tasks to
dispatch are in `07 §8`.

## Provenance

This plan was produced by auditing the codebase against the existing specs and researching the
current (2026) Android IME / accessibility / Play-policy landscape, with the highest-risk technical
claims adversarially verified before they were allowed to drive a decision (e.g., the corrections
that background clipboard *writes* are not blocked — only reads are; that a passive math overlay is
*declarable* on Play rather than outright banned; and that no permissive, droppable glide engine
exists). Where a verification downgraded a claim, the affected document says so explicitly so that
executing agents do not over-assert.
