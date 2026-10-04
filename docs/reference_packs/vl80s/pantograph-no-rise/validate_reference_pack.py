#!/usr/bin/env python3
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent
PACK = ROOT / "reference_pack.json"

REQUIRED = {
    "classification",
    "statementType",
    "confidence",
    "applicationStatus",
    "knowledgeLayerDepth",
    "visibility",
    "applicability",
    "sourceRefs",
    "limitations",
    "relations",
    "qualityControl",
}

CLASSIFICATIONS = {
    "NORMATIVE", "MANUFACTURER", "TECHNICAL_REFERENCE", "OPERATIONAL_EXPERIENCE",
    "FIELD_PRACTICE", "MAINTENANCE_PRACTICE", "HISTORICAL", "ARCHIVE",
    "CASE_STUDY", "UNVERIFIED", "UNSAFE_METHOD",
}
STATEMENT_TYPES = {"FACT", "OBSERVATION", "HYPOTHESIS", "RECOMMENDATION", "CASE_REPORT"}
CONFIDENCE = {"VERIFIED", "SUPPORTED", "REPORTED", "UNKNOWN"}
APPLICATION = {"REFERENCE", "EXPERIENCE", "PRACTICE", "ACTIONABLE", "RESTRICTED"}
DEPTHS = {"MINIMAL", "STANDARD", "DETAILED"}


def fail(message: str) -> None:
    raise AssertionError(message)


def main() -> None:
    data = json.loads(PACK.read_text(encoding="utf-8"))
    assert data["schemaVersion"] == "knowledge-reference-pack/1"
    entries = data["entries"]
    source_ids = {s["id"] for s in data["sourceRegistry"]}
    conflict_ids = {c["id"] for c in data["conflictGroups"]}
    entry_ids = [e["id"] for e in entries]

    assert len(entry_ids) == len(set(entry_ids)), "duplicate entry id"
    assert len(entries) == data["acceptanceExpectations"]["actualEntryCount"]
    assert len(entries) >= data["acceptanceExpectations"]["minimumEntryCount"]

    seen_statement = set()
    seen_confidence = set()
    seen_application = set()

    for e in entries:
        missing = REQUIRED - set(e)
        if missing:
            fail(f"{e.get('id')}: missing {sorted(missing)}")
        if e["classification"] not in CLASSIFICATIONS:
            fail(f"{e['id']}: bad classification")
        if e["statementType"] not in STATEMENT_TYPES:
            fail(f"{e['id']}: bad statementType")
        if e["confidence"] not in CONFIDENCE:
            fail(f"{e['id']}: bad confidence")
        if e["applicationStatus"] not in APPLICATION:
            fail(f"{e['id']}: bad applicationStatus")
        if not 1 <= e["knowledgeLayerDepth"] <= 6:
            fail(f"{e['id']}: bad knowledgeLayerDepth")
        if e["visibility"]["minDepth"] not in DEPTHS:
            fail(f"{e['id']}: bad minDepth")
        if not e["sourceRefs"]:
            fail(f"{e['id']}: no sources")
        unknown_sources = set(e["sourceRefs"]) - source_ids
        if unknown_sources:
            fail(f"{e['id']}: unresolved source refs {sorted(unknown_sources)}")
        unknown_conflicts = set(e["qualityControl"].get("conflictGroupIds", [])) - conflict_ids
        if unknown_conflicts:
            fail(f"{e['id']}: unresolved conflicts {sorted(unknown_conflicts)}")
        related = set(e["relations"].get("relatedEntryIds", []))
        unknown_related = related - set(entry_ids)
        if unknown_related:
            fail(f"{e['id']}: unresolved related entries {sorted(unknown_related)}")

        if e["classification"] in {"FIELD_PRACTICE", "UNSAFE_METHOD"}:
            assert e["applicationStatus"] == "RESTRICTED", f"{e['id']}: restricted class must remain RESTRICTED"
            assert e["visibility"]["extendedEmergencyRequired"] is True, f"{e['id']}: missing restricted gate"
            assert e.get("restrictedProcedure", {}).get("procedureIncluded") is False, f"{e['id']}: dangerous procedure unexpectedly included"

        seen_statement.add(e["statementType"])
        seen_confidence.add(e["confidence"])
        seen_application.add(e["applicationStatus"])

    assert STATEMENT_TYPES <= seen_statement, f"missing Statement Type coverage: {STATEMENT_TYPES - seen_statement}"
    assert CONFIDENCE <= seen_confidence, f"missing Confidence coverage: {CONFIDENCE - seen_confidence}"
    assert APPLICATION <= seen_application, f"missing Application Status coverage: {APPLICATION - seen_application}"

    coverage = data["coverage"]
    assert coverage["MANUFACTURER"] == "PENDING_PRIMARY_SOURCE"
    assert any(e["qualityControl"].get("conflictGroupIds") for e in entries), "no conflict-QC test case"
    assert any(e["confidence"] == "UNKNOWN" for e in entries), "no unknown-confidence test case"
    assert any(e["classification"] == "UNVERIFIED" for e in entries), "no unverified test case"

    print(f"OK: {len(entries)} entries, {len(source_ids)} sources, {len(conflict_ids)} conflict groups")


if __name__ == "__main__":
    main()
