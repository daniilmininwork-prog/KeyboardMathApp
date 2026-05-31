# Security policy

Tally is a keyboard. It sits where it can see everything a person types, so we take security and
privacy as the product's foundation, not a feature. The full posture is in
[docs/07-security-privacy.md](docs/07-security-privacy.md); this file is the short version and how
to report problems.

## Our guarantees

- **On-device only.** All math is computed locally.
- **Cannot phone home.** The app ships without the `INTERNET` permission and has no networking
  dependency, so it is incapable of sending your data anywhere
  ([ADR-0003](docs/adr/ADR-0003-no-network-policy.md)).
- **No keystroke logging or storage.** We don't record or persist what you type; only your
  settings are saved.
- **No analytics, ads, accounts, or data-collecting third-party SDKs.**
- **No network permission** (anyone can confirm this by inspecting the app), a public **Data
  Safety** declaration of "no data collected," and an **independent security audit** — the claims
  above are checkable without taking our word for it, even though the source is private.

## Supported versions

Security fixes target the latest released version. Older versions are not patched; please update.

## Reporting a vulnerability

Please report privately — **do not** disclose publicly before a fix ships.

- Email the security contact published on the app's Play listing / support page. (The source
  repository is private, so external private-reporting via GitHub isn't available.)
- Include: affected version, a description, reproduction steps, and impact.
- We'll acknowledge promptly, keep you updated, and credit you in the release notes if you'd like.

Please give us reasonable time to ship a fix before any public disclosure.

## Scope

In scope: the keyboard, the overlay/accessibility mode, the math engine, build/release integrity,
and anything that could weaken the guarantees above (e.g. a path that could log typed text, or a
dependency introducing network access).

Out of scope: issues that require a device already compromised at the OS level, or social-
engineering of the user outside the app.
