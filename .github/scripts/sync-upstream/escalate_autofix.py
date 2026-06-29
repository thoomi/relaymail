#!/usr/bin/env python3

"""Escalate a sync PR the auto-fixer could not get green.

Adds a `needs-human` label (creating it if missing) and posts a single PR comment
summarising how many attempts were made, which CI workflows still fail, and Claude's
own summary if available. Modelled on `upsert_manual_issue.py`.
"""

import json
import logging
import tempfile
from pathlib import Path

from sync_upstream import configure_logging, env, json_output, read_text, run

LOGGER = logging.getLogger(__name__)

BRIEF_PATH = Path(".git/ci-failure-brief.md")
COMMENT_MARKER = "<!-- ci-autofix-escalation -->"


def main() -> None:
    configure_logging()

    repository = env("GITHUB_REPOSITORY")
    owner = env("GITHUB_REPOSITORY_OWNER")
    run_id = env("GITHUB_RUN_ID")
    pr_number = env("PR_NUMBER")
    label = env("NEEDS_HUMAN_LABEL", "needs-human")
    attempts = env("ATTEMPTS", "0")
    max_attempts = env("MAX_AUTOFIX_ATTEMPTS", "2")
    claude_outcome = env("CLAUDE_OUTCOME", "skipped")

    ensure_label(repository, label)
    run(["gh", "pr", "edit", pr_number, "--repo", repository, "--add-label", label])
    LOGGER.info("Labelled PR #%s with '%s'.", pr_number, label)

    body = build_comment(
        owner=owner,
        run_id=run_id,
        attempts=attempts,
        max_attempts=max_attempts,
        claude_outcome=claude_outcome,
        failing_workflows=failing_workflow_names(),
        claude_summary=claude_change_summary(),
    )
    body_file = write_temp_file(body)

    LOGGER.info("Escalation comment preview:\n%s", "\n".join(body.splitlines()[:60]))
    run(["gh", "pr", "comment", pr_number, "--repo", repository, "--body-file", body_file])
    LOGGER.info("Posted escalation comment on PR #%s.", pr_number)


def build_comment(
    *,
    owner: str,
    run_id: str,
    attempts: str,
    max_attempts: str,
    claude_outcome: str,
    failing_workflows: list[str],
    claude_summary: str,
) -> str:
    failing = "\n".join(f"- {name}" for name in failing_workflows) or "- (could not determine)"
    return f"""{COMMENT_MARKER}
## CI auto-fix needs a human

The automated CI auto-fixer could not get this sync PR green and is handing it back.

**Status:**
- Auto-fix attempts used: {attempts} / {max_attempts}
- Last Claude outcome: {claude_outcome or "skipped"}

**Still failing:**
{failing}

**What Claude reported last:**
{claude_summary}

**What to do:**
1. Pull this branch and reproduce the failing checks above.
2. Fix the remaining issues (usually `feature/applock/**` adapting to upstream changes).
3. Push to this branch; CI will re-run. Remove the `needs-human` label once green.

cc @{owner}

_Posted automatically by the ci-autofix workflow run #{run_id}._
"""


def failing_workflow_names() -> list[str]:
    if not BRIEF_PATH.exists():
        return []
    names = []
    for line in read_text(BRIEF_PATH).splitlines():
        if line.startswith("## Workflow: "):
            name = line[len("## Workflow: ") :]
            name = name.split(" (run #", 1)[0].strip()
            names.append(name)
    return names


def claude_change_summary() -> str:
    raw = env("CLAUDE_STRUCTURED_OUTPUT", "").strip()
    if not raw:
        return "No structured summary was produced."
    try:
        data = json.loads(raw)
    except json.JSONDecodeError as error:
        return f"Unable to parse Claude structured output: {error}"
    summary = data.get("summary") if isinstance(data, dict) else None
    if isinstance(summary, str) and summary.strip():
        return summary.strip()
    return "Claude returned structured output, but the `summary` field was empty."


def ensure_label(repository: str, label: str) -> None:
    labels = json_output(
        ["gh", "label", "list", "--repo", repository, "--search", label, "--json", "name"],
    )
    if isinstance(labels, list) and any(item.get("name") == label for item in labels):
        return
    LOGGER.info("Creating missing label '%s'.", label)
    run(
        [
            "gh",
            "label",
            "create",
            label,
            "--repo",
            repository,
            "--color",
            "D93F0B",
            "--description",
            "Automated CI auto-fix gave up; needs manual attention",
            "--force",
        ],
        check=False,
    )


def write_temp_file(content: str) -> str:
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", delete=False) as file:
        file.write(content)
        return file.name


if __name__ == "__main__":
    main()
