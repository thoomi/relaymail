# Fork Documentation

This section documents behavior and decisions that are specific to this fork and intentionally separate from upstream Thunderbird for Android documentation.

## Purpose

Use this section to quickly understand:

- what differs from upstream,
- why those differences exist,
- and how they affect maintenance of this fork.

## What Belongs Here

Fork-specific content such as:

- CI/CD workflows and automation that only exist in this fork,
- release or maintenance differences,
- configuration differences,
- temporary or long-lived divergences from upstream behavior.

## What Does Not Belong Here

Do not duplicate general project documentation already maintained upstream (architecture, contributor basics, generic release process, etc.). Keep those in existing upstream-aligned docs.

## Change Log Source of Truth

Ongoing fork differences are tracked in:

- [Fork Changes Log](./changes.md)

## Compare This Fork With Upstream

Use GitHub compare view to inspect divergence quickly.

Pattern:

`https://github.com/<fork-owner>/thunderbird-android/compare/<fork-branch>...thunderbird:<upstream-branch>`

Example for this fork:

`https://github.com/thoomi/thunderbird-android/compare/main...thunderbird:main`

Adjust owner/branch names when comparing non-`main` branches.
