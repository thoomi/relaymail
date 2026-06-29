#!/usr/bin/env python3

"""Resolve the open sync PR targeted by a CI failure (or a manual dispatch).

Emits `found`, `pr_number`, `head_branch`, and `head_sha` GitHub outputs. When no
matching open sync PR exists the script exits cleanly with `found=false` so the
workflow can stop without failing.
"""

import logging

from sync_upstream import configure_logging, env, github_output, json_output

LOGGER = logging.getLogger(__name__)


def main() -> None:
    configure_logging()

    repository = env("GITHUB_REPOSITORY")
    prefix = env("SYNC_BRANCH_PREFIX", "sync/upstream-")
    event_name = env("EVENT_NAME")

    if event_name == "workflow_dispatch":
        resolved = resolve_from_dispatch(repository)
    else:
        resolved = resolve_from_workflow_run(repository)

    if resolved is None:
        LOGGER.info("No matching open sync PR; nothing to auto-fix.")
        github_output("found", False)
        return

    pr_number, head_branch, head_sha = resolved

    if not head_branch.startswith(prefix):
        LOGGER.info("Head branch '%s' is not a sync branch (prefix '%s'); skipping.", head_branch, prefix)
        github_output("found", False)
        return

    LOGGER.info("Targeting PR #%s on branch '%s' (sha %s)", pr_number, head_branch, head_sha)
    github_output("found", True)
    github_output("pr_number", str(pr_number))
    github_output("head_branch", head_branch)
    github_output("head_sha", head_sha)


def resolve_from_dispatch(repository: str) -> tuple[int, str, str] | None:
    pr_number = env("DISPATCH_PR")
    pr = json_output(
        [
            "gh",
            "pr",
            "view",
            pr_number,
            "--repo",
            repository,
            "--json",
            "number,headRefName,headRefOid,state",
        ],
    )
    if not isinstance(pr, dict):
        LOGGER.info("PR #%s not found.", pr_number)
        return None
    if pr.get("state") != "OPEN":
        LOGGER.info("PR #%s is not open (state=%s).", pr_number, pr.get("state"))
        return None
    return int(pr["number"]), str(pr["headRefName"]), str(pr["headRefOid"])


def resolve_from_workflow_run(repository: str) -> tuple[int, str, str] | None:
    head_branch = env("RUN_HEAD_BRANCH")
    head_sha = env("RUN_HEAD_SHA")

    prs = json_output(
        [
            "gh",
            "pr",
            "list",
            "--repo",
            repository,
            "--head",
            head_branch,
            "--state",
            "open",
            "--json",
            "number,headRefName,headRefOid",
        ],
    )
    if not isinstance(prs, list) or not prs:
        LOGGER.info("No open PR found for head branch '%s'.", head_branch)
        return None

    pr = prs[0]
    # Trust the SHA the failing CI run actually used so the freshness check can
    # detect a newer commit pushed after CI started.
    return int(pr["number"]), str(pr["headRefName"]), head_sha


if __name__ == "__main__":
    main()
