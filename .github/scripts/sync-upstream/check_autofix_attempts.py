#!/usr/bin/env python3

"""Loop guard for the CI auto-fix workflow.

Counts how many auto-fix commits already exist on the PR branch (relative to the
target branch) by matching the `[auto-fix]` marker in commit messages. This is a
durable counter: it lives in committed git history on the branch itself, so it
survives across the independent `workflow_run` invocations without any external
state. Emits `attempts` and `capped` outputs.
"""

import logging

from sync_upstream import configure_logging, env, git_lines, github_output, run

LOGGER = logging.getLogger(__name__)


def main() -> None:
    configure_logging()

    target_branch = env("TARGET_BRANCH", "main")
    marker = env("AUTOFIX_MARKER", "[auto-fix]")
    max_attempts = int(env("MAX_AUTOFIX_ATTEMPTS", "2"))

    run(["git", "fetch", "--no-tags", "origin", target_branch], check=False)

    commits = git_lines(
        "log",
        "--fixed-strings",
        f"--grep={marker}",
        "--pretty=%H",
        f"origin/{target_branch}..HEAD",
        check=False,
    )
    attempts = len(commits)
    capped = attempts >= max_attempts

    LOGGER.info(
        "Auto-fix attempts so far on this branch: %d (max %d) -> capped=%s",
        attempts,
        max_attempts,
        capped,
    )

    github_output("attempts", str(attempts))
    github_output("capped", capped)


if __name__ == "__main__":
    main()
