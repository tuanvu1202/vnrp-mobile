#!/usr/bin/env python3
"""Generate a SHA-256 cache manifest from a directory.

Usage:
  python tools/generate_manifest.py \
    --root sample-cdn/cache \
    --base-url https://cdn.example.com/mobile/cache \
    --version 2026.07.18.1 \
    --output sample-cdn/manifest.json
"""

from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
from urllib.parse import quote


def sha256_file(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as handle:
        for chunk in iter(lambda: handle.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--root", type=Path, required=True)
    parser.add_argument("--base-url", required=True)
    parser.add_argument("--version", required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()

    root = args.root.resolve()
    if not root.is_dir():
        raise SystemExit(f"Cache root không tồn tại: {root}")

    files = []
    for path in sorted(p for p in root.rglob("*") if p.is_file()):
        relative = path.relative_to(root).as_posix()
        encoded_path = "/".join(quote(part) for part in relative.split("/"))
        files.append(
            {
                "path": relative,
                "url": f"{args.base_url.rstrip('/')}/{encoded_path}",
                "size": path.stat().st_size,
                "sha256": sha256_file(path),
            }
        )

    manifest = {
        "version": args.version,
        "files": files,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2),
        encoding="utf-8",
    )
    print(f"Đã ghi {len(files)} file vào {args.output}")


if __name__ == "__main__":
    main()
