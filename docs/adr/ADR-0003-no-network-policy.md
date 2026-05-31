# ADR-0003: No network — the app ships without the INTERNET permission

- **Status:** accepted
- **Context date:** project kickoff

## Context

Tally is a keyboard (and an optional overlay). Both can see everything the user types. For a
product in that position, the difference between "we promise not to send your data" and "we
*cannot* send your data" is the difference between asking for trust and not needing it.

Android makes this provable: without the `INTERNET` permission, an app cannot open a network
socket. The guarantee is enforced by the OS and visible in the manifest and store listing.

## Decision

**No module declares the `INTERNET` permission, and no networking dependency exists anywhere in
the build.** Exfiltration of typed text is therefore not merely against policy — it is not
something the app is capable of doing.

- Enforced by a CI **manifest guard** that fails the build if `INTERNET` (or any non-allowlisted
  permission) appears, in any module or merged manifest, from Phase 0 onward.
- The Play **Data Safety** form is declared as "no data collected, no data shared," backed by the
  absent permission.
- All features must work fully offline. Anything that would require the network (e.g. live
  currency conversion) is **out of scope** and cannot be added without superseding this ADR.

## Consequences

- The strongest possible, verifiable privacy claim for a keyboard.
- Some otherwise-attractive features (currency conversion, cloud sync, crash telemetry that
  transmits) are off the table by default. We consider this a feature, not a limitation.
- Crash reporting, if ever wanted, must be on-device/opt-in and would require a new ADR explicitly
  weighing it against this one.

## Alternatives considered

- **Request INTERNET "just in case" / for optional online features:** destroys the core trust
  proposition for marginal benefit; rejected.
- **Network behind a user toggle:** still requires the permission in the manifest, so the
  "cannot phone home" guarantee is lost even when toggled off; rejected.
