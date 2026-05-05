#!/usr/bin/env python3

import logging

from sync_upstream import configure_logging, env, github_output, output, run

LOGGER = logging.getLogger(__name__)


def main() -> None:
    configure_logging()

    upstream_branch = env("UPSTREAM_BRANCH")
    claude_outcome = env("CLAUDE_OUTCOME", "skipped")
    sync_commit_msg_claude = env(
        "SYNC_COMMIT_MSG_CLAUDE",
        f"Merge upstream/{upstream_branch} (conflicts auto-resolved by Claude)",
    )

    LOGGER.info("Claude step outcome: %s", claude_outcome)

    if claude_outcome != "success":
        LOGGER.info("Claude did not complete successfully; no merge commit will be created.")
        write_outputs(False, False, f"Claude outcome was {claude_outcome or 'skipped'}")
        run(["git", "merge", "--abort"], check=False)
        return

    unmerged_entries = output(["git", "ls-files", "-u"])
    if unmerged_entries:
        LOGGER.info("Unmerged index entries remain; no merge commit will be created.")
        LOGGER.info("%s", unmerged_entries)
        write_outputs(False, False, "Unmerged index entries remain after Claude")
        run(["git", "merge", "--abort"], check=False)
        return

    run(["git", "add", "-A"])

    diff_check = output(["git", "diff", "--cached", "--check"], check=False)
    if "leftover conflict marker" in diff_check:
        LOGGER.info("Conflict markers remain in staged content; no merge commit will be created.")
        LOGGER.info("%s", diff_check)
        write_outputs(False, False, "Conflict markers remain after Claude")
        run(["git", "merge", "--abort"], check=False)
        return

    LOGGER.info("Claude resolved conflicts automatically.")
    LOGGER.info("Staged diff stat:")
    run(["git", "diff", "--cached", "--stat"])
    run(["git", "commit", "-m", sync_commit_msg_claude])
    LOGGER.info("Created commit:")
    run(["git", "--no-pager", "log", "-1", "--stat", "--oneline"])
    write_outputs(True, True, "")


def write_outputs(ready_for_pr: bool, claude_resolved: bool, manual_reason: str) -> None:
    github_output("ready_for_pr", ready_for_pr)
    github_output("claude_resolved", claude_resolved)
    github_output("manual_reason", manual_reason)


if __name__ == "__main__":
    main()
