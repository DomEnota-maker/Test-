package ru.railbrake.calculator.core

import java.io.File
import java.util.zip.GZIPInputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.railbrake.calculator.data.KnowledgeDepth
import ru.railbrake.calculator.data.KnowledgeMode

class ReferenceKnowledgePackTest {
    private val canonical = JSONObject(GZIPInputStream(
        File("src/main/assets/technical/vl80s_equipment.json.gz").inputStream()
    ).bufferedReader().use { it.readText() })
    private val framework = JSONObject(File("src/main/assets/technical/diagnostic_framework_v2.json").readText())
    private val raw = JSONObject(File("src/main/assets/technical/vl80s_pantograph_reference_pack.json").readText())
    private val module get() = DiagnosticFrameworkV2.parse(framework, { canonical },
        mapOf("pantograph-no-rise" to requireNotNull(DiagnosticRepository.scenario("pantograph-no-rise")))).single()
    private val pack get() = ReferenceKnowledgePackRepository.parse(raw, module)

    private fun visible(mode: KnowledgeMode, depth: KnowledgeDepth, gate: Boolean, study: Boolean) =
        pack.orderedVisible(mode, depth, gate, "vl80s-general", study, null)

    @Test fun allEntriesKeepProvenanceApplicabilityAndRelations() {
        val parsed = pack
        assertEquals("technical/vl80s_pantograph_reference_pack.json", module.referencePackAsset)
        assertEquals(19, parsed.entries.size)
        assertEquals(12, parsed.sources.size)
        assertEquals(3, parsed.conflicts.size)
        assertTrue(parsed.entries.all { it.sourceRefs.isNotEmpty() && it.applicability.variants.isNotEmpty() &&
            it.classification.isNotBlank() && it.statementType.isNotBlank() && it.runtimeRole.isNotBlank() &&
            it.qualityControl.reviewStatus.isNotBlank() && it.knowledgeLayerDepth > 0 })
        assertEquals(setOf("DIAGNOSTIC_CONTEXT", "LEARNING_REFERENCE", "RESTRICTED_REFERENCE", "SOURCE_NOTE"),
            parsed.entries.mapTo(hashSetOf()) { it.runtimeRole })
        assertEquals("OPEN_CONFLICT", parsed.entries.first { it.title.startsWith("Давление") }.qualityControl.reviewStatus)
        assertEquals("REPORTED", parsed.entries.first { it.classification == "UNVERIFIED" }.confidence)
        assertEquals("RESTRICTED", parsed.entries.first { it.classification == "FIELD_PRACTICE" }.applicationStatus)
        assertEquals("RESTRICTED_REFERENCE",
            parsed.entries.first { it.classification == "FIELD_PRACTICE" }.runtimeRole)
        assertEquals("SOURCE_NOTE", parsed.entries.first { it.classification == "UNVERIFIED" }.runtimeRole)
        assertFalse(parsed.entries.first { it.classification == "UNSAFE_METHOD" }.restrictedProcedureIncluded!!)
    }

    @Test fun anotherScenarioCanAttachAReferencePackThroughData() {
        val alternateFramework = JSONObject(framework.toString())
        alternateFramework.getJSONArray("modules").getJSONObject(0)
            .put("scenarioId", "additional-reference-scenario")
        val alternatePack = JSONObject(raw.toString()).put("scenarioId", "additional-reference-scenario")
        val alternateModule = DiagnosticFrameworkV2.parse(alternateFramework, { canonical }).single()
        assertEquals("technical/vl80s_pantograph_reference_pack.json", alternateModule.referencePackAsset)
        assertEquals(19, ReferenceKnowledgePackRepository.parse(alternatePack, alternateModule).entries.size)
    }

    @Test fun sixModeDepthCombinationsAndIndependentEmergencyGate() {
        for (mode in KnowledgeMode.entries) for (depth in KnowledgeDepth.entries) {
            val off = visible(mode, depth, gate = false, study = true)
            val on = visible(mode, depth, gate = true, study = true)
            assertTrue(off.isNotEmpty())
            assertTrue(off.none { it.classification in setOf("FIELD_PRACTICE", "UNSAFE_METHOD") })
            if (mode == KnowledgeMode.BASIC || depth != KnowledgeDepth.DETAILED) assertEquals(off, on)
            if (mode == KnowledgeMode.ADVANCED && depth == KnowledgeDepth.DETAILED)
                assertEquals(setOf("FIELD_PRACTICE", "UNSAFE_METHOD"),
                    (on - off.toSet()).mapTo(hashSetOf()) { it.classification })
        }
        assertTrue(visible(KnowledgeMode.BASIC, KnowledgeDepth.MINIMAL, false, false)
            .all { it.classification == "NORMATIVE" })
        assertTrue(visible(KnowledgeMode.ADVANCED, KnowledgeDepth.STANDARD, false, false)
            .any { it.classification == "CASE_STUDY" })
        assertTrue(visible(KnowledgeMode.ADVANCED, KnowledgeDepth.DETAILED, false, true)
            .any { it.classification == "HISTORICAL" })
        assertTrue(visible(KnowledgeMode.ADVANCED, KnowledgeDepth.DETAILED, false, true)
            .any { it.classification == "MAINTENANCE_PRACTICE" })
    }

    @Test fun answerChangesPriorityWithoutRemovingOrProvingDirections() {
        val parsed = pack
        val before = parsed.orderedVisible(KnowledgeMode.ADVANCED, KnowledgeDepth.STANDARD,
            false, "vl80s-general", false, null)
        val after = parsed.orderedVisible(KnowledgeMode.ADVANCED, KnowledgeDepth.STANDARD,
            false, "vl80s-general", false, "air")
        assertEquals(before.toSet(), after.toSet())
        assertTrue("air" in after.first().relations.directionIds)
        assertEquals(null, module.nextNode("pnr-danger", DiagnosticResponse.UNKNOWN))
    }

    @Test fun runtimeRejectsUnknownEnumsRolesAndUnsafeSemanticDrift() {
        fun rejected(mutator: (JSONObject) -> Unit): Boolean {
            val broken = JSONObject(raw.toString())
            mutator(broken.getJSONArray("entries").getJSONObject(0))
            return runCatching { ReferenceKnowledgePackRepository.parse(broken, module) }.isFailure
        }
        assertTrue(rejected { it.put("classification", "NOT_A_CLASS") })
        assertTrue(rejected { it.put("statementType", "NOT_A_STATEMENT") })
        assertTrue(rejected { it.put("confidence", "CERTAINISH") })
        assertTrue(rejected { it.put("applicationStatus", "MAYBE_ACTIONABLE") })
        assertTrue(rejected { it.put("runtimeRole", "AUTO_DIAGNOSE") })

        val unsafe = JSONObject(raw.toString())
        val unsafeEntry = (0 until unsafe.getJSONArray("entries").length())
            .map { unsafe.getJSONArray("entries").getJSONObject(it) }
            .first { it.getString("classification") == "UNSAFE_METHOD" }
        unsafeEntry.put("runtimeRole", "DIAGNOSTIC_CONTEXT")
        assertTrue(runCatching { ReferenceKnowledgePackRepository.parse(unsafe, module) }.isFailure)

        val unverified = JSONObject(raw.toString())
        val unverifiedEntry = (0 until unverified.getJSONArray("entries").length())
            .map { unverified.getJSONArray("entries").getJSONObject(it) }
            .first { it.getString("classification") == "UNVERIFIED" }
        unverifiedEntry.put("confidence", "VERIFIED")
        assertTrue(runCatching { ReferenceKnowledgePackRepository.parse(unverified, module) }.isFailure)
    }
}
