#!/usr/bin/env python3

"""Build a condensed CI-failure brief for Claude.

Gathers every failing CI run for the PR head commit (not just the single run that
triggered the workflow), pulls the failed logs, and condenses them down to the
signal lines an engineer would actually look at -- compile errors, failing gradle
tasks, detekt/spotless findings, test failures -- so the brief stays small enough
to fit comfortably in the prompt. Writes `.git/ci-failure-brief.md` and emits a
`has_failures` output.
"""

import logging
import re
from pathlib import Path

from sync_upstream import configure_logging, env, github_output, json_output, output, write_text

LOGGER = logging.getLogger(__name__)

BRIEF_PATH = Path(".git/ci-failure-brief.md")

# Workflows we treat as code-producing CI. Anything else (markdown, this auto-fix
# workflow itself, security scanners) is ignored as a fix target.
DEFAULT_CI_WORKFLOWS = ["Build - Android", "Quality - Checks", "Build - CLI Tools"]

# Lines worth keeping. Each match also keeps a few following lines for context.
# Kept deliberately tight: Kotlin/detekt errors are `e:`-prefixed, so matching `e: `
# captures them without dragging in the thousands of `w:` deprecation warnings.
SIGNAL_PATTERNS = [
    re.compile(pattern)
    for pattern in (
        r"(?:^|\s)e: ",  # kotlin compile errors AND detekt findings (both `e:`-prefixed)
        r"Unresolved reference",
        r"> Task :.* FAILED",
        r"Execution failed for task",
        r"BUILD FAILED",
        r"FAILURE: Build failed",
        r"What went wrong",
        r"\[[A-Z][A-Za-z]+\]\s*$",  # detekt rule id trailer, e.g. [ParameterNaming]
        r"weighted issues",
        r"Analysis failed with",
        r"format violations|Run '.*spotlessApply",
        r"Dependency Guard|dependencyGuard",
        r"Konsist|KoTest|AssertionError|AssertionFailedError",
        r"There were failing tests",
        r"Test .* (FAILED|failed)",
    )
]

CONTEXT_AFTER = 8
MAX_KEPT_LINES_PER_RUN = 250


def main() -> None:
    configure_logging()

    repository = env("GITHUB_REPOSITORY")
    head_sha = env("HEAD_SHA")
    pr_number = env("PR_NUMBER", "")
    ci_workflows = parse_workflow_names(env("CI_WORKFLOWS", "\n".join(DEFAULT_CI_WORKFLOWS)))

    settled, failing_runs = inspect_ci_runs(repository, head_sha, ci_workflows)

    if not settled:
        # Other CI workflows for this commit are still running. Acting now would
        # build the brief from a partial failure picture (e.g. only the formatting
        # check, before the compile error lands). Defer; the workflow triggers on
        # both success and failure completions, so the last CI workflow to finish
        # re-runs this with everything settled regardless of its own result.
        LOGGER.info("CI for %s has not settled yet; deferring auto-fix.", head_sha)
        write_text(BRIEF_PATH, "# CI Failure Brief\n\nCI has not settled yet; deferring.\n")
        github_output("has_failures", False)
        return

    if not failing_runs:
        LOGGER.info("No failing CI runs found for %s; nothing to fix.", head_sha)
        write_text(BRIEF_PATH, "# CI Failure Brief\n\nNo failing CI runs were found for this commit.\n")
        github_output("has_failures", False)
        return

    sections = [
        "# CI Failure Brief",
        "",
        f"- Repository: `{repository}`",
        f"- PR: #{pr_number}" if pr_number else "- PR: (unknown)",
        f"- Head commit: `{head_sha}`",
        f"- Failing CI workflows: {len(failing_runs)}",
        "",
        "This fork carries a local-only `feature/applock` module that breaks against "
        "upstream module/API moves. Start from this brief; the condensed errors below "
        "are the primary context. Only inspect full files or other repo areas when this "
        "summary is insufficient for a specific error.",
        "",
        "Likely culprits: `feature/applock/api`, `feature/applock/impl` "
        "(`:feature:applock:api`, `:feature:applock:impl`).",
    ]

    for run_info in failing_runs:
        name = run_info.get("workflowName") or run_info.get("name") or "CI"
        run_id = run_info["databaseId"]
        LOGGER.info("Collecting failed log for run #%s (%s)", run_id, name)
        excerpt = condense(fetch_failed_log(repository, run_id))
        sections.extend(
            [
                "",
                f"## Workflow: {name} (run #{run_id})",
                "",
                "```text",
                excerpt or "(no log lines could be retrieved for this run)",
                "```",
            ],
        )

    write_text(BRIEF_PATH, "\n".join(sections) + "\n")
    LOGGER.info("Wrote failure brief to %s", BRIEF_PATH)
    LOGGER.info("Brief preview:\n%s", "\n".join("\n".join(sections).splitlines()[:80]))
    github_output("has_failures", True)


def parse_workflow_names(raw: str) -> set[str]:
    return {line.strip() for line in raw.splitlines() if line.strip()}


def inspect_ci_runs(repository: str, head_sha: str, ci_workflows: set[str]) -> tuple[bool, list[dict]]:
    """Return (settled, failing_runs) for the CI workflows on this commit.

    `settled` is False while any matching CI workflow is still running, so the
    caller can wait for the complete failure picture before acting.
    """
    runs = json_output(
        [
            "gh",
            "run",
            "list",
            "--repo",
            repository,
            "--commit",
            head_sha,
            "--limit",
            "50",
            "--json",
            "databaseId,name,workflowName,conclusion,status",
        ],
    )
    if not isinstance(runs, list):
        return True, []

    ci_runs = [run_info for run_info in runs if (run_info.get("workflowName") or run_info.get("name")) in ci_workflows]
    settled = all(run_info.get("status") == "completed" for run_info in ci_runs)
    failing = [run_info for run_info in ci_runs if run_info.get("conclusion") == "failure"]
    return settled, failing


def fetch_failed_log(repository: str, run_id: int) -> str:
    log = output(
        ["gh", "run", "view", str(run_id), "--repo", repository, "--log-failed"],
        check=False,
    )
    if log:
        return log
    # Fallback: some matrix jobs return nothing for --log-failed; pull the full log.
    return output(
        ["gh", "run", "view", str(run_id), "--repo", repository, "--log"],
        check=False,
    )


def condense(log: str) -> str:
    if not log:
        return ""

    lines = log.splitlines()
    keep: set[int] = set()
    for index, line in enumerate(lines):
        if any(pattern.search(line) for pattern in SIGNAL_PATTERNS):
            for offset in range(0, CONTEXT_AFTER + 1):
                if index + offset < len(lines):
                    keep.add(index + offset)

    if not keep:
        # Nothing matched; keep the tail, which usually holds the failure summary.
        return "\n".join(lines[-40:])

    kept_indices = sorted(keep)
    if len(kept_indices) > MAX_KEPT_LINES_PER_RUN:
        kept_indices = kept_indices[-MAX_KEPT_LINES_PER_RUN:]

    rendered: list[str] = []
    previous: int | None = None
    for index in kept_indices:
        if previous is not None and index > previous + 1:
            rendered.append("    ...")
        rendered.append(lines[index])
        previous = index
    return "\n".join(rendered)


if __name__ == "__main__":
    main()
