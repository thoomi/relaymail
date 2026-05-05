#!/usr/bin/env python3

import json
import logging
import os
import shlex
import subprocess
import sys
import uuid
from collections.abc import Sequence
from pathlib import Path

type JsonValue = dict[str, JsonValue] | list[JsonValue] | str | int | float | bool | None

LOGGER = logging.getLogger(__name__)


def configure_logging() -> None:
    logging.basicConfig(
        level=logging.INFO,
        format="%(message)s",
        stream=sys.stdout,
    )


def env(name: str, default: str | None = None) -> str:
    value = os.environ.get(name, default)
    if value is None:
        raise RuntimeError(f"Missing required environment variable: {name}")
    return value


def run(
    command: Sequence[str],
    *,
    check: bool = True,
    capture_output: bool = False,
) -> subprocess.CompletedProcess[str]:
    LOGGER.info("+ %s", shlex.join(command))
    stderr = subprocess.STDOUT if capture_output else None
    return subprocess.run(
        list(command),
        check=check,
        text=True,
        stdout=subprocess.PIPE if capture_output else None,
        stderr=stderr,
    )


def output(command: Sequence[str], *, check: bool = True) -> str:
    result = run(command, check=check, capture_output=True)
    return result.stdout.strip()


def json_output(command: Sequence[str]) -> JsonValue:
    raw = output(command)
    if not raw:
        return None
    return json.loads(raw)


def github_output(name: str, value: str | bool) -> None:
    output_file = Path(env("GITHUB_OUTPUT"))
    text = str(value).lower() if isinstance(value, bool) else str(value)

    with output_file.open("a", encoding="utf-8") as file:
        if "\n" in text:
            delimiter = f"EOF_{uuid.uuid4().hex}"
            file.write(f"{name}<<{delimiter}\n{text}\n{delimiter}\n")
        else:
            file.write(f"{name}={text}\n")


def git_lines(*args: str, check: bool = True) -> list[str]:
    text = output(["git", *args], check=check)
    if not text:
        return []
    return text.splitlines()


def markdown_file_list(paths: Sequence[str]) -> str:
    return "\n".join(f"- `{path}`" for path in paths)


def truncate_markdown_file_list(paths: Sequence[str], limit: int) -> str:
    preview = paths[:limit]
    lines = [f"- `{path}`" for path in preview]
    remaining = len(paths) - len(preview)
    if remaining > 0:
        lines.append(f"- _... and {remaining} more files_")
    return "\n".join(lines)


def write_text(path: str | Path, content: str) -> None:
    Path(path).write_text(content, encoding="utf-8")


def read_text(path: str | Path) -> str:
    return Path(path).read_text(encoding="utf-8", errors="replace")


def close_issue(repository: str, issue_number: int, comment: str) -> None:
    run(
        [
            "gh",
            "issue",
            "close",
            str(issue_number),
            "--repo",
            repository,
            "--comment",
            comment,
        ],
    )
