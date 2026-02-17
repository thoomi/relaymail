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

### 2026-02-17

- **Area**: CI
- **Change**: Added `sync-upstream.yml` workflow to automatically sync the fork's `main` branch with upstream `thunderbird/thunderbird-android`.
- **Why**: Keep the fork up to date without manual fetch/merge. On clean merges it pushes directly to `main`; on conflicts it opens a PR for manual resolution.
- **Upstream Impact**: none
- **Links**: `.github/workflows/sync-upstream.yml`

