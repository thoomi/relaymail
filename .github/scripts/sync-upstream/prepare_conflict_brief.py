#!/usr/bin/env python3

import logging
from pathlib import Path

from sync_upstream import configure_logging, env, git_lines, markdown_file_list, output, read_text, write_text

LOGGER = logging.getLogger(__name__)


def main() -> None:
    configure_logging()

    upstream_repo = env("UPSTREAM_REPO")
    upstream_branch = env("UPSTREAM_BRANCH")
    target_branch = env("TARGET_BRANCH")

    brief_path = Path(".git/sync-upstream-conflict-brief.md")
    conflict_files_path = Path(".git/sync-upstream-conflict-files.txt")
    conflict_files = git_lines("diff", "--name-only", "--diff-filter=U")

    write_text(conflict_files_path, "\n".join(conflict_files) + ("\n" if conflict_files else ""))
    LOGGER.info("Preparing conflict brief for %d conflicted files", len(conflict_files))

    sections = [
        "# Upstream Sync Conflict Brief",
        "",
        f"- Upstream repo: `{upstream_repo}`",
        f"- Upstream branch: `{upstream_branch}`",
        f"- Target branch: `{target_branch}`",
        "",
        "Start from this brief. Only inspect full files, additional history, or other repo areas when this summary is insufficient.",
        "",
        "## Unmerged files",
        markdown_file_list(conflict_files),
    ]

    for file in conflict_files:
        fork_log = output(
            [
                "git",
                "log",
                "--oneline",
                f"upstream/{upstream_branch}..origin/{target_branch}",
                "--",
                file,
            ],
            check=False,
        )
        fork_commits = "\n".join(f"- {line}" for line in fork_log.splitlines()) or "- none"

        sections.extend(
            [
                "",
                f"## File: `{file}`",
                "",
                "### Fork-only commits touching this file",
                fork_commits,
                "",
                "### Conflict hunks from working tree",
                "```text",
                conflict_hunks(file),
                "```",
            ],
        )

    write_text(brief_path, "\n".join(sections) + "\n")

    LOGGER.info("Conflict brief written to %s", brief_path)
    LOGGER.info("Conflict files list written to %s", conflict_files_path)
    LOGGER.info("Conflict files:\n%s", markdown_file_list(conflict_files))
    LOGGER.info("Conflict brief preview:\n%s", "\n".join(read_text(brief_path).splitlines()[:80]))


def conflict_hunks(file: str) -> str:
    path = Path(file)
    if not path.exists() or not path.is_file():
        return "(file is not present in the working tree; inspect git index stages)"

    in_conflict = False
    hunks: list[str] = []
    for line in read_text(path).splitlines():
        if line.startswith("<<<<<<< "):
            in_conflict = True
        if in_conflict:
            hunks.append(line)
        if line.startswith(">>>>>>> "):
            in_conflict = False
            hunks.append("")

    return "\n".join(hunks) if hunks else "(no text conflict markers found)"


if __name__ == "__main__":
    main()
