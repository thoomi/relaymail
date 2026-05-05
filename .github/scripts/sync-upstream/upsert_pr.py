#!/usr/bin/env python3

import json
import logging
import tempfile
from pathlib import Path

from sync_upstream import (
    close_issue,
    configure_logging,
    env,
    git_lines,
    json_output,
    run,
    truncate_markdown_file_list,
)

LOGGER = logging.getLogger(__name__)


def main() -> None:
    configure_logging()

    repository = env("GITHUB_REPOSITORY")
    owner = env("GITHUB_REPOSITORY_OWNER")
    run_id = env("GITHUB_RUN_ID")
    target_branch = env("TARGET_BRANCH")
    upstream_repo = env("UPSTREAM_REPO")
    upstream_branch = env("UPSTREAM_BRANCH")
    branch = env("BRANCH")
    refresh = env("REFRESH")
    merge_conflict = env("MERGE_CONFLICT")
    sync_label = env("SYNC_LABEL", "")

    label_args = ["--label", sync_label] if label_exists(repository, sync_label) else []
    if sync_label and not label_args:
        LOGGER.info("Label '%s' does not exist; creating PR without it.", sync_label)

    changed_files = git_lines("diff", "--name-only", f"origin/{target_branch}...HEAD")
    changed_files_preview = truncate_markdown_file_list(changed_files, 30)

    if merge_conflict == "true":
        conflict_files = read_conflict_files()
        conflict_files_preview = truncate_markdown_file_list(conflict_files, 30)
        claude_summary = claude_change_summary()
        title = "Upstream sync - Claude resolved, please review"
        body = f"""Automated upstream sync encountered merge conflicts. **Claude attempted to resolve them automatically.**

**What happened:**
- Attempted to merge `{upstream_repo}:{upstream_branch}` into `{target_branch}`
- Merge conflicts were detected
- Claude Code resolved the conflicts and committed the result
- Total files changed in this sync branch: {len(changed_files)}
- Files with merge conflicts: {len(conflict_files)}

**What to do:**
1. Review the conflicted files first; Claude's resolution is best-effort
2. If correct, approve and merge
3. If something is wrong, push fixes to this branch; subsequent runs will not force-overwrite human commits

**Conflicted files to review first:**
{conflict_files_preview}

**Claude change summary:**
{claude_summary}

cc @{owner}

_Created automatically by the sync-upstream workflow._
"""
    else:
        title = "Upstream sync"
        body = f"""Automated upstream sync merged cleanly.

**What happened:**
- Merged `{upstream_repo}:{upstream_branch}` into `{target_branch}`
- Total files changed in this sync branch: {len(changed_files)}

**What to do:**
1. Review the upstream changes
2. If correct, approve and merge
3. If something is wrong, push fixes to this branch; subsequent runs will not force-overwrite human commits

**Changed files preview:**
{changed_files_preview}

cc @{owner}

_Created automatically by the sync-upstream workflow._
"""

    body_file = write_temp_file(body)

    LOGGER.info("PR title: %s", title)
    LOGGER.info("PR body preview:\n%s", "\n".join(body.splitlines()[:200]))

    if refresh == "true":
        run(["gh", "pr", "edit", branch, "--repo", repository, "--title", title, "--body-file", body_file])
        run(
            [
                "gh",
                "pr",
                "comment",
                branch,
                "--repo",
                repository,
                "--body",
                f"Branch refreshed by sync workflow run #{run_id}. cc @{owner}",
            ],
        )
        LOGGER.info("Updated existing PR for branch %s", branch)
    else:
        run(
            [
                "gh",
                "pr",
                "create",
                "--title",
                title,
                "--body-file",
                body_file,
                "--base",
                target_branch,
                "--head",
                branch,
                *label_args,
                "--repo",
                repository,
            ],
        )
        LOGGER.info("Created new PR for branch %s", branch)

    close_stale_manual_issue(repository, branch)


def label_exists(repository: str, label: str) -> bool:
    if not label:
        return False

    labels = json_output(
        [
            "gh",
            "label",
            "list",
            "--repo",
            repository,
            "--search",
            label,
            "--json",
            "name",
        ],
    )
    return any(item.get("name") == label for item in labels)


def read_conflict_files() -> list[str]:
    path = Path(".git/sync-upstream-conflict-files.txt")
    if not path.exists():
        return []
    return [line for line in path.read_text(encoding="utf-8").splitlines() if line]


def claude_change_summary() -> str:
    raw = env("CLAUDE_STRUCTURED_OUTPUT", "").strip()
    outcome = env("CLAUDE_OUTCOME", "").strip()

    if not raw:
        if outcome == "success":
            return "Claude completed successfully, but did not expose structured summary output."
        return "Claude did not complete successfully, so no structured summary is available."

    try:
        data = json.loads(raw)
    except json.JSONDecodeError as error:
        return f"Unable to parse Claude structured output: {error}"

    summary = data.get("summary")
    if isinstance(summary, str) and summary.strip():
        return summary.strip()
    return "Claude returned structured output, but the `summary` field was empty."


def close_stale_manual_issue(repository: str, branch: str) -> None:
    title = "Upstream sync - manual merge needed"
    issues = json_output(
        [
            "gh",
            "issue",
            "list",
            "--repo",
            repository,
            "--state",
            "open",
            "--limit",
            "100",
            "--json",
            "number,title",
        ],
    )
    issue_number = next((issue["number"] for issue in issues if issue.get("title") == title), None)
    if issue_number is None:
        return

    close_issue(
        repository,
        issue_number,
        f"A sync PR is now available from `{branch}`; closing this stale manual-resolution issue.",
    )
    LOGGER.info("Closed stale manual-resolution issue #%s", issue_number)


def write_temp_file(content: str) -> str:
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", delete=False) as file:
        file.write(content)
        return file.name


if __name__ == "__main__":
    main()
