#!/usr/bin/env python3

from datetime import UTC, datetime
import logging

from sync_upstream import configure_logging, env, github_output, json_output, output, run

LOGGER = logging.getLogger(__name__)


def main() -> None:
    configure_logging()

    repository = env("GITHUB_REPOSITORY")
    sync_branch_prefix = env("SYNC_BRANCH_PREFIX")
    target_branch = env("TARGET_BRANCH")
    upstream_branch = env("UPSTREAM_BRANCH")
    run_id = env("GITHUB_RUN_ID")

    sync_commit_msg_clean = env("SYNC_COMMIT_MSG_CLEAN", f"Merge upstream/{upstream_branch}")
    sync_commit_msg_manual = env(
        "SYNC_COMMIT_MSG_MANUAL",
        f"Merge upstream/{upstream_branch} (conflicts need manual resolution)",
    )
    sync_commit_msg_claude = env(
        "SYNC_COMMIT_MSG_CLAUDE",
        f"Merge upstream/{upstream_branch} (conflicts auto-resolved by Claude)",
    )

    prs = json_output(
        [
            "gh",
            "pr",
            "list",
            "--repo",
            repository,
            "--state",
            "open",
            "--base",
            target_branch,
            "--limit",
            "100",
            "--json",
            "headRefName",
        ],
    )

    existing_branch = next(
        (
            pr["headRefName"]
            for pr in prs
            if pr.get("headRefName", "").startswith(sync_branch_prefix)
        ),
        "",
    )

    refresh = False
    if existing_branch:
        run(["git", "fetch", "--no-tags", "origin", existing_branch])

        commits = output(
            [
                "git",
                "rev-list",
                "--first-parent",
                "--count",
                f"origin/{target_branch}..origin/{existing_branch}",
            ],
        )
        last_author = output(["git", "log", "-1", "--format=%an", f"origin/{existing_branch}"])
        last_msg = output(["git", "log", "-1", "--format=%s", f"origin/{existing_branch}"])
        message_is_known = last_msg in {
            sync_commit_msg_clean,
            sync_commit_msg_manual,
            sync_commit_msg_claude,
        }

        if commits == "1" and last_author == "github-actions[bot]" and message_is_known:
            refresh = True
            branch = existing_branch
            LOGGER.info("Existing branch %s is untouched; refreshing it.", branch)
        else:
            LOGGER.info("Existing branch %s has human commits; opening a new PR.", existing_branch)
            branch = new_branch(sync_branch_prefix, run_id)
    else:
        branch = new_branch(sync_branch_prefix, run_id)

    LOGGER.info("Selected sync branch: %s", branch)
    LOGGER.info("Refresh existing branch: %s", refresh)
    github_output("branch", branch)
    github_output("refresh", refresh)


def new_branch(prefix: str, run_id: str) -> str:
    today = datetime.now(UTC).strftime("%Y-%m-%d")
    return f"{prefix}{today}-{run_id}"


if __name__ == "__main__":
    main()
