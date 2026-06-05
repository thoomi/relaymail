# Relay Mail

A friendly fork of [Thunderbird for Android](https://github.com/thunderbird/thunderbird-android)
(formerly K-9 Mail) that adds a **biometric app-lock** — require fingerprint or
face unlock before the app can be opened.

> Personal project. Core email functionality comes from upstream Thunderbird for
> Android; Relay Mail layers an app-level biometric lock on top.

## Why

Thunderbird for Android has no app-level lock. Relay Mail adds one, so opening
the app (and seeing your mail) requires device biometric authentication —
useful on shared or frequently handed-over phones.

## What's different from upstream

- **Biometric app-lock** via AndroidX `BiometricPrompt`, on launch and on return from background.
- Otherwise tracks upstream Thunderbird for Android.

## Automation

The fork is kept current and shippable with two CI pipelines:

- **Weekly agent-assisted upstream sync.** A scheduled GitHub Actions workflow
  runs every week: it pulls the latest [Thunderbird for Android](https://github.com/thunderbird/thunderbird-android),
  attempts to merge it into the fork, and when merge conflicts come up, hands
  them to an AI agent that resolves them. The result is opened as a pull request
  for human review before it lands — so the fork stays up to date with upstream
  without manual rebasing.
- **Automated release pipeline.** A CI pipeline builds, versions, and publishes
  releases automatically.

## Stack

Kotlin · Android · AndroidX BiometricPrompt · based on Thunderbird for Android / K-9 Mail.

## Status

Personal/experimental fork — build from source with Android Studio. Not published on app stores.

## Upstream & license

Based on [Thunderbird for Android](https://github.com/thunderbird/thunderbird-android),
Apache License 2.0. Retains upstream license and attribution.
