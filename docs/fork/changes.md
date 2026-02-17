# Fork Changes Log

This log tracks meaningful differences between this fork and upstream.

## Entry Template

Use this structure for each new entry:

- **Date**: YYYY-MM-DD
- **Area**: CI | Release | Build | App Behavior | Docs | Other
- **Change**: Short summary of what changed
- **Why**: Rationale for the fork-specific change
- **Upstream Impact**: none | temporary divergence | long-lived divergence
- **Links**: PRs, commits, workflows, or docs paths

---

## Changes

### 2026-02-17

- **Area**: Docs
- **Change**: Added a dedicated fork documentation section with a top-level docs navigation entry and root README pointer.
- **Why**: Make fork-specific differences easy to find without mixing them into upstream-focused documentation.
- **Upstream Impact**: none
- **Links**: `docs/fork/README.md`, `docs/fork/changes.md`, `docs/SUMMARY.md`, `README.md`

---

- **Area**: CI
- **Change**: Added `sync-upstream.yml` workflow to automatically sync the fork's `main` branch with upstream `thunderbird/thunderbird-android`.
- **Why**: Keep the fork up to date without manual fetch/merge. On clean merges it pushes directly to `main`; on conflicts it opens a PR for manual resolution.
- **Upstream Impact**: none
- **Links**: `.github/workflows/sync-upstream.yml`

---

- **Area**: Build
- **Change**: Changed `applicationId` from `net.thunderbird.android` to `me.aboutblank.relaymail`.
- **Why**: Allows installing this fork alongside the original Thunderbird app on the same device.
- **Upstream Impact**: long-lived divergence
- **Links**: `app-thunderbird/build.gradle.kts`

---

- **Area**: CI
- **Change**: Added `fork-release.yml` workflow that builds, signs, and publishes a FOSS release APK to GitHub Releases on tag push (`v*`).
- **Why**: Provides a simple release mechanism for the fork without the complexity of the upstream shippable builds workflow.
- **Upstream Impact**: none
- **Links**: `.github/workflows/fork-release.yml`

---

### 2026-02-18

- **Area**: App Behavior
- **Change**: Rebranded app from "Thunderbird" to "RelayMail" — updated app name, launcher icons (all variants), logo drawables, About screen (logo, authors, URLs), notification icon, User-Agent header, settings import allowlist, and migration provider URI.
- **Why**: Give the fork its own identity distinct from upstream Thunderbird.
- **Upstream Impact**: long-lived divergence
- **Links**: `app-thunderbird/src/*/res/`, `core/ui/compose/theme2/thunderbird/`, `core/ui/legacy/theme2/thunderbird/`, `legacy/ui/legacy/src/main/`, `feature/migration/provider/`, `feature/settings/import/`, `images/thunderbird/`

