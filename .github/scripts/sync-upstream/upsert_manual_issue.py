#!/usr/bin/env python3

import logging
import tempfile
from pathlib import Path

from sync_upstream import configure_logging, env, json_output, read_text, run, truncate_markdown_file_list

LOGGER = logging.getLogger(__name__)


def main() -> None:
    configure_logging()

    repository = env("GITHUB_REPOSITORY")
    owner = env("GITHUB_REPOSITORY_OWNER")
    run_id = env("GITHUB_RUN_ID")
    target_branch = env("TARGET_BRANCH")
    upstream_repo = env("UPSTREAM_REPO")
    upstream_branch = env("UPSTREAM_BRANCH")
    claude_available = env("CLAUDE_AVAILABLE", "unknown")
    claude_outcome = env("CLAUDE_OUTCOME", "skipped")
    manual_reason = env("MANUAL_REASON", "unknown")

    title = "Upstream sync - manual merge needed"
    conflict_files = read_conflict_files()
    conflict_files_preview = truncate_markdown_file_list(conflict_files, 50)
    conflict_brief_preview = "\n".join(read_text(".git/sync-upstream-conflict-brief.md").splitlines()[:220])

    body = f"""Automated upstream sync encountered merge conflicts that were not safe to commit.

**What happened:**
- Attempted to merge `{upstream_repo}:{upstream_branch}` into `{target_branch}`
- Merge conflicts were detected
- Claude available: {claude_available}
- Claude outcome: {claude_outcome or "skipped"}
- Manual reason: {manual_reason or "unknown"}
- Files with merge conflicts: {len(conflict_files)}

**What to do:**
1. Create a branch from `{target_branch}`
2. Merge `{upstream_repo}:{upstream_branch}` locally
3. Resolve the conflicts and open a normal sync PR

**Conflicted files:**
{conflict_files_preview}

**Conflict brief preview:**
```text
{conflict_brief_preview}
```

cc @{owner}

_Created automatically by the sync-upstream workflow run #{run_id}._
"""
    body_file = write_temp_file(body)
    issue_number = find_existing_issue(repository, title)

    LOGGER.info("Issue title: %s", title)
    LOGGER.info("Issue body preview:\n%s", "\n".join(body.splitlines()[:200]))

    if issue_number is not None:
        run(["gh", "issue", "edit", str(issue_number), "--repo", repository, "--body-file", body_file])
        run(
            [
                "gh",
                "issue",
                "comment",
                str(issue_number),
                "--repo",
                repository,
                "--body",
                f"Conflict report refreshed by sync workflow run #{run_id}. cc @{owner}",
            ],
        )
        LOGGER.info("Updated existing manual-resolution issue #%s", issue_number)
    else:
        run(["gh", "issue", "create", "--repo", repository, "--title", title, "--body-file", body_file])
        LOGGER.info("Created manual-resolution issue")


def read_conflict_files() -> list[str]:
    path = Path(".git/sync-upstream-conflict-files.txt")
    if not path.exists():
        return []
    return [line for line in path.read_text(encoding="utf-8").splitlines() if line]


def find_existing_issue(repository: str, title: str) -> int | None:
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
    return next((issue["number"] for issue in issues if issue.get("title") == title), None)


def write_temp_file(content: str) -> str:
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", delete=False) as file:
        file.write(content)
        return file.name


if __name__ == "__main__":
    main()
