#!/usr/bin/env python3
"""Normalize derived metadata in VL80S reference packs.

Only fields that can be computed without editorial judgment are changed.
This deliberately never changes classification, confidence, application status,
source attribution, applicability, limitations, or knowledge text.
"""

from __future__ import annotations

import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PACK_ROOT = ROOT / "docs" / "reference_packs" / "vl80s"
MANIFEST = PACK_ROOT / "EXPANSION_MANIFEST.json"


def main() -> int:
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    changed = 0
    for row in manifest.get("scenarios", []):
        path = PACK_ROOT / row["path"]
        if not path.exists():
            continue
        data = json.loads(path.read_text(encoding="utf-8"))
        acceptance = data.get("acceptanceExpectations")
        if not isinstance(acceptance, dict):
            continue
        actual = len(data.get("entries", []))
        if acceptance.get("actualEntryCount") != actual:
            acceptance["actualEntryCount"] = actual
            path.write_text(json.dumps(data, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
            print(f"normalized {path.relative_to(ROOT)}: actualEntryCount={actual}")
            changed += 1
    print(f"normalized files: {changed}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
