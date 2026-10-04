#!/usr/bin/env python3
"""Static QA for VL80S knowledge reference packs.

The validator intentionally focuses on semantic separation, not prose quality.
It is safe to run before runtime integration because packs live under docs/.
"""

from __future__ import annotations

import json
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
PACK_ROOT = ROOT / "docs" / "reference_packs" / "vl80s"
MANIFEST = PACK_ROOT / "EXPANSION_MANIFEST.json"

CLASSIFICATIONS = {
    "NORMATIVE",
    "MANUFACTURER",
    "TECHNICAL_REFERENCE",
    "OPERATIONAL_EXPERIENCE",
    "FIELD_PRACTICE",
    "MAINTENANCE_PRACTICE",
    "HISTORICAL",
    "ARCHIVE",
    "CASE_STUDY",
    "UNVERIFIED",
    "UNSAFE_METHOD",
}
STATEMENT_TYPES = {"FACT", "OBSERVATION", "HYPOTHESIS", "RECOMMENDATION", "CASE_REPORT"}
CONFIDENCE = {"VERIFIED", "SUPPORTED", "REPORTED", "UNKNOWN"}
APPLICATION = {"REFERENCE", "EXPERIENCE", "PRACTICE", "ACTIONABLE", "RESTRICTED"}
RUNTIME_ROLES = {"DIAGNOSTIC_CONTEXT", "LEARNING_REFERENCE", "RESTRICTED_REFERENCE", "SOURCE_NOTE"}

REQUIRED_ENTRY_FIELDS = {
    "id",
    "title",
    "classification",
    "statementType",
    "confidence",
    "applicationStatus",
    "applicability",
    "sourceRefs",
    "limitations",
    "relations",
    "qualityControl",
}

STATUS_ORDER = {
    "RESEARCHING": 0,
    "DRAFT_STRUCTURED": 1,
    "SOURCE_REVIEWED": 2,
    "QA_READY": 3,
    "READY_FOR_WORK": 4,
}


def load_json(path: Path):
    with path.open("r", encoding="utf-8") as fh:
        return json.load(fh)


def fail(errors: list[str], msg: str) -> None:
    errors.append(msg)


def warn(warnings: list[str], msg: str) -> None:
    warnings.append(msg)


def validate_pack(path: Path, manifest_row: dict, errors: list[str], warnings: list[str]) -> None:
    data = load_json(path)
    scenario_id = manifest_row["scenarioId"]
    label = f"{scenario_id} ({path.relative_to(ROOT)})"

    if data.get("scenarioId") != scenario_id:
        fail(errors, f"{label}: pack scenarioId does not match manifest")
    canonical = data.get("canonicalScenarioId")
    if canonical is not None and canonical != scenario_id:
        fail(errors, f"{label}: canonicalScenarioId must equal manifest scenarioId")
    lifecycle = data.get("scenarioLifecycle")
    if lifecycle is not None and lifecycle != "ACTIVE":
        fail(errors, f"{label}: reference expansion is for active canonical scenarios; got {lifecycle!r}")

    sources = data.get("sourceRegistry", [])
    source_ids = [s.get("id") for s in sources]
    if None in source_ids or len(source_ids) != len(set(source_ids)):
        fail(errors, f"{label}: duplicate/missing source id")
    source_set = set(source_ids)

    conflicts = data.get("conflictGroups", [])
    conflict_ids = [c.get("id") for c in conflicts]
    if None in conflict_ids or len(conflict_ids) != len(set(conflict_ids)):
        fail(errors, f"{label}: duplicate/missing conflict group id")
    conflict_set = set(conflict_ids)

    entries = data.get("entries", [])
    ids = [e.get("id") for e in entries]
    if not entries:
        fail(errors, f"{label}: no knowledge entries")
        return
    if None in ids or len(ids) != len(set(ids)):
        fail(errors, f"{label}: duplicate/missing knowledge entry id")

    for entry in entries:
        eid = entry.get("id", "<missing-id>")
        prefix = f"{label}/{eid}"
        missing = REQUIRED_ENTRY_FIELDS - entry.keys()
        if missing:
            fail(errors, f"{prefix}: missing fields {sorted(missing)}")
            continue

        classification = entry["classification"]
        statement_type = entry["statementType"]
        confidence = entry["confidence"]
        application = entry["applicationStatus"]
        runtime_role = entry.get("runtimeRole")

        if classification not in CLASSIFICATIONS:
            fail(errors, f"{prefix}: unknown classification {classification!r}")
        if statement_type not in STATEMENT_TYPES:
            fail(errors, f"{prefix}: unknown statementType {statement_type!r}")
        if confidence not in CONFIDENCE:
            fail(errors, f"{prefix}: unknown confidence {confidence!r}")
        if application not in APPLICATION:
            fail(errors, f"{prefix}: unknown applicationStatus {application!r}")
        if runtime_role is not None and runtime_role not in RUNTIME_ROLES:
            fail(errors, f"{prefix}: unknown runtimeRole {runtime_role!r}")
        if runtime_role is None:
            warn(warnings, f"{prefix}: runtimeRole absent (legacy pack is allowed, new packs should set it)")

        refs = entry.get("sourceRefs", [])
        if not refs:
            fail(errors, f"{prefix}: sourceRefs must not be empty")
        unknown_sources = sorted(set(refs) - source_set)
        if unknown_sources:
            fail(errors, f"{prefix}: unresolved sourceRefs {unknown_sources}")

        qc = entry.get("qualityControl") or {}
        unknown_conflicts = sorted(set(qc.get("conflictGroupIds", [])) - conflict_set)
        if unknown_conflicts:
            fail(errors, f"{prefix}: unresolved conflictGroupIds {unknown_conflicts}")

        # Semantic separation rules.
        if classification in {"ARCHIVE", "HISTORICAL"} and application == "ACTIONABLE":
            fail(errors, f"{prefix}: {classification} cannot be ACTIONABLE without a separate modern entry")
        if classification == "FIELD_PRACTICE" and application == "ACTIONABLE":
            fail(errors, f"{prefix}: FIELD_PRACTICE cannot silently become ACTIONABLE")
        if classification == "UNVERIFIED" and confidence == "VERIFIED":
            fail(errors, f"{prefix}: UNVERIFIED cannot have VERIFIED confidence")
        if classification == "UNSAFE_METHOD":
            if application != "RESTRICTED":
                fail(errors, f"{prefix}: UNSAFE_METHOD must be RESTRICTED")
            visibility = entry.get("visibility") or {}
            if visibility.get("extendedEmergencyRequired") is not True:
                fail(errors, f"{prefix}: UNSAFE_METHOD must require the separate emergency/restricted gate")
            procedure = entry.get("restrictedProcedure") or {}
            if procedure.get("procedureIncluded") is True:
                fail(errors, f"{prefix}: unrestricted reference pack must not embed hazardous step-by-step procedure")
            if runtime_role not in {None, "RESTRICTED_REFERENCE"}:
                fail(errors, f"{prefix}: UNSAFE_METHOD runtimeRole must be RESTRICTED_REFERENCE")

        if classification in {"ARCHIVE", "HISTORICAL"} and runtime_role == "DIAGNOSTIC_CONTEXT":
            fail(errors, f"{prefix}: archive/history must not be injected as active diagnostic context")

        applicability = entry.get("applicability") or {}
        if applicability.get("variantCheckRequired") and not applicability.get("profiles"):
            fail(errors, f"{prefix}: variant-gated entry has no profile")

        limitations = entry.get("limitations")
        if not isinstance(limitations, list) or not limitations:
            fail(errors, f"{prefix}: limitations must be a non-empty list")


def main() -> int:
    errors: list[str] = []
    warnings: list[str] = []
    manifest = load_json(MANIFEST)
    rows = manifest.get("scenarios", [])
    ids = [row.get("scenarioId") for row in rows]
    if None in ids or len(ids) != len(set(ids)):
        fail(errors, "manifest: scenarioId values must be present and unique")

    paths = [row.get("path") for row in rows]
    if None in paths or len(paths) != len(set(paths)):
        fail(errors, "manifest: pack paths must be present and unique")

    for row in rows:
        scenario_id = row.get("scenarioId", "<missing>")
        status = row.get("packStatus")
        if status not in STATUS_ORDER:
            fail(errors, f"manifest/{scenario_id}: unknown packStatus {status!r}")
            continue
        if row.get("scenarioLifecycle") != "ACTIVE":
            fail(errors, f"manifest/{scenario_id}: scenarioLifecycle must be ACTIVE")

        path = PACK_ROOT / row["path"]
        if not path.exists():
            if STATUS_ORDER[status] >= STATUS_ORDER["DRAFT_STRUCTURED"]:
                fail(errors, f"manifest/{scenario_id}: status {status} requires existing pack {path}")
            else:
                warn(warnings, f"manifest/{scenario_id}: research entry has no pack yet")
            continue
        validate_pack(path, row, errors, warnings)

    print(f"VL80S reference-pack QA: {len(errors)} error(s), {len(warnings)} warning(s)")
    for msg in warnings:
        print(f"WARN: {msg}")
    for msg in errors:
        print(f"ERROR: {msg}")
    return 1 if errors else 0


if __name__ == "__main__":
    sys.exit(main())
