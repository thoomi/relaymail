#!/usr/bin/env python3

import argparse
import logging

from sync_upstream import configure_logging, run

LOGGER = logging.getLogger(__name__)


def main() -> None:
    configure_logging()

    parser = argparse.ArgumentParser(description="Push or refresh an upstream sync branch.")
    parser.add_argument("branch")
    parser.add_argument("refresh", choices=("true", "false"))
    args = parser.parse_args()

    LOGGER.info("Pushing branch %s", args.branch)
    if args.refresh == "true":
        run(["git", "push", "--force-with-lease", "origin", args.branch])
        LOGGER.info("Refreshed existing PR for branch %s", args.branch)
    else:
        run(["git", "push", "-u", "origin", args.branch])
        LOGGER.info("Created new remote branch %s", args.branch)


if __name__ == "__main__":
    main()
