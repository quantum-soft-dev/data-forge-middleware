#!/usr/bin/env python3
"""Print a stable identity for the current Git worktree candidate state."""

from __future__ import annotations

import hashlib
import os
import stat
import subprocess
import sys


def git(*args: str) -> bytes:
    return subprocess.check_output(("git", *args), stderr=subprocess.DEVNULL)


def add_field(digest: hashlib._Hash, label: bytes, value: bytes) -> None:
    digest.update(len(label).to_bytes(4, "big"))
    digest.update(label)
    digest.update(len(value).to_bytes(8, "big"))
    digest.update(value)


def main() -> int:
    try:
        root = git("rev-parse", "--show-toplevel").rstrip(b"\n")
        head = git("rev-parse", "--verify", "HEAD").strip()
        index = git("ls-files", "--stage", "-z")
        listed = git("ls-files", "--cached", "--others", "--exclude-standard", "-z")
        submodules = git("submodule", "status", "--recursive")
    except subprocess.CalledProcessError:
        print("snapshot.py must run inside a Git worktree", file=sys.stderr)
        return 2

    digest = hashlib.sha256()
    add_field(digest, b"HEAD", head)
    add_field(digest, b"INDEX", index)
    add_field(digest, b"SUBMODULES", submodules)

    for relative in sorted(path for path in listed.split(b"\0") if path):
        absolute = root + b"/" + relative
        add_field(digest, b"PATH", relative)
        try:
            before = os.lstat(absolute)
        except FileNotFoundError:
            add_field(digest, b"MISSING", b"")
            continue

        mode = stat.S_IFMT(before.st_mode) | stat.S_IMODE(before.st_mode)
        add_field(digest, b"MODE", str(mode).encode("ascii"))

        if stat.S_ISLNK(before.st_mode):
            add_field(digest, b"SYMLINK", os.readlink(absolute))
            continue
        if not stat.S_ISREG(before.st_mode):
            add_field(digest, b"SPECIAL", b"")
            continue

        file_digest = hashlib.sha256()
        with open(absolute, "rb") as stream:
            for chunk in iter(lambda: stream.read(1024 * 1024), b""):
                file_digest.update(chunk)
        after = os.lstat(absolute)
        if (before.st_size, before.st_mtime_ns, before.st_ino) != (
            after.st_size,
            after.st_mtime_ns,
            after.st_ino,
        ):
            print(f"worktree changed while hashing {os.fsdecode(relative)}", file=sys.stderr)
            return 3
        add_field(digest, b"CONTENT", file_digest.digest())

    print(f"sha256:{digest.hexdigest()}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
