#!/usr/bin/env python3
"""Backfill runtimeRole for legacy VL80S reference-pack entries.

This is intentionally conservative and deterministic:
- restricted/unsafe material -> RESTRICTED_REFERENCE;
- UNVERIFIED material -> SOURCE_NOTE unless explicitly diagnostic;
- entries marked diagnosis=true -> DIAGNOSTIC_CONTEXT;
- everything else -> LEARNING_REFERENCE.

The script never changes classification, confidence, application status,
visibility, sources, applicability, text, or restrictions.
"""

from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PACK_ROOT = ROOT / "docs" / "reference_packs" / "vl80s"
MANIFEST = PACK_ROOT / "EXPANSION_MANIFEST.json"


def choose_role(entry: dict) -> str:
    classification = entry.get("classification")
    application = entry.get("applicationStatus")
    visibility = entry.get("visibility") or {}
    if classification == "UNSAFE_METHOD" or application == "RESTRICTED" or visibility.get("extendedEmergencyRequired") is True:
        return "RESTRICTED_REFERENCE"
    if classification == "UNVERIFIED" and not visibility.get("diagnosis", False):
        return "SOURCE_NOTE"
    if visibility.get("diagnosis", False):
        return "DIAGNOSTIC_CONTEXT"
    return "LEARNING_REFERENCE"


def main() -> int:
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    changed_files = 0
    changed_entries = 0
    for row in manifest.get("scenarios", []):
        path = PACK_ROOT / row["path"]
        if not path.exists():
            continue
        data = json.loads(path.read_text(encoding="utf-8"))
        changed = False
        for entry in data.get("entries", []):
            if "runtimeRole" not in entry:
                entry["runtimeRole"] = choose_role(entry)
                changed = True
                changed_entries += 1
        if changed:
            path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            changed_files += 1
            print(f"backfilled runtimeRole: {path.relative_to(ROOT)}")
    print(f"runtimeRole migration: {changed_entries} entries across {changed_files} file(s)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
