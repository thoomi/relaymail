#!/usr/bin/env python3

"""Commit and push whatever Claude changed while fixing CI.

Only commits when Claude succeeded and actually modified tracked files. The commit
message carries the `[auto-fix]` marker so `check_autofix_attempts.py` can count it
on the next CI cycle. The push goes to the PR branch through the PAT-authenticated
remote, which re-triggers CI. Emits `pushed` and `no_changes` outputs.
"""

import logging

from sync_upstream import configure_logging, env, github_output, output, run

LOGGER = logging.getLogger(__name__)


def main() -> None:
    configure_logging()

    claude_outcome = env("CLAUDE_OUTCOME", "skipped")
    marker = env("AUTOFIX_MARKER", "[auto-fix]")
    attempt = int(env("ATTEMPT", "0"))
    head_branch = env("HEAD_BRANCH")

    if claude_outcome != "success":
        LOGGER.info("Claude outcome was '%s'; nothing to commit.", claude_outcome)
        write_outputs(pushed=False, no_changes=False)
        return

    run(["git", "add", "-A"])

    staged = output(["git", "diff", "--cached", "--name-only"])
    if not staged:
        LOGGER.info("Claude made no file changes; nothing to commit.")
        write_outputs(pushed=False, no_changes=True)
        return

    LOGGER.info("Staged changes:\n%s", staged)
    run(["git", "diff", "--cached", "--stat"])

    message = f"{marker} fix CI on sync branch (attempt {attempt + 1})"
    run(["git", "commit", "-m", message])
    LOGGER.info("Created auto-fix commit:")
    run(["git", "--no-pager", "log", "-1", "--oneline"])

    run(["git", "push", "origin", f"HEAD:{head_branch}"])
    LOGGER.info("Pushed auto-fix commit to '%s'; CI will re-run.", head_branch)
    write_outputs(pushed=True, no_changes=False)


def write_outputs(*, pushed: bool, no_changes: bool) -> None:
    github_output("pushed", pushed)
    github_output("no_changes", no_changes)


if __name__ == "__main__":
    main()
