# ADR-0004 — Gesture Typing: Patent Clearance Tracking

**Status:** Pending legal review before public ship  
**Date:** 2026-06-02  
**Deciders:** Engineering lead; Legal (to be engaged)

---

## Context

T4.1 implements a statistical gesture (swipe) typing decoder. Gesture typing is a
patent-dense area. The most prominent patent families include:

- **Swype/Nuance** — the original continuous-trace-to-word patents, some of which have
  expired; others are still in force in specific jurisdictions.
- **Google AGDSL** (Android Gesture Swipe Log-likelihood) — proprietary; covers
  neural-spatial methods in `libjni_latinimegoogle.so`.
- **TouchType / SwiftKey** — geometric-trace approximation; some expired.

## Decision

The implementation in `GestureDecoderImpl` uses the **statistical trace-scoring approach
derived from FlorisBoard (Apache-2.0)**:

1. Uniform spatial resampling of the raw trace (`PathResampler`).
2. Ideal key-path construction per candidate word (`IdealPathBuilder`).
3. Mean squared displacement scoring between resampled trace and ideal path
   (`GesturePathScorer`).
4. Language-model rescoring with the same n-gram model used by the tap decoder.

This approach does **not** implement:
- Any proprietary Swype dynamic-time-warping method.
- The Google AGDSL neural spatial encoder.
- Any hidden Markov model or connected-text recognition method from TouchType.

## Legal action required

- [ ] Engage legal counsel to review the specific implementation against known live
  patents before any public release of the glide feature.
- [ ] Record sign-off here with counsel's name, date, and any required modifications.
- [ ] If clearance cannot be obtained, the `GestureDecoder` feature must be gated off
  or removed before public ship. The tap decoder (T2.2) is unaffected.

## Consequences

Shipping this feature without legal sign-off is blocked by T4.1 acceptance criteria.
The implementation is complete and tested; the blocking gate is legal, not technical.
Engineering can continue tuning and integrating the decoder; the feature is gated in
the release checklist pending this ADR's completion.
