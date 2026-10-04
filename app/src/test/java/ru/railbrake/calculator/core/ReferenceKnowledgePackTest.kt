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
        assertEquals(19, parsed.entries.size)
        assertEquals(12, parsed.sources.size)
        assertEquals(3, parsed.conflicts.size)
        assertTrue(parsed.entries.all { it.sourceRefs.isNotEmpty() && it.applicability.variants.isNotEmpty() &&
            it.classification.isNotBlank() && it.statementType.isNotBlank() &&
            it.qualityControl.reviewStatus.isNotBlank() && it.knowledgeLayerDepth > 0 })
        assertEquals("OPEN_CONFLICT", parsed.entries.first { it.title.startsWith("Давление") }.qualityControl.reviewStatus)
        assertEquals("REPORTED", parsed.entries.first { it.classification == "UNVERIFIED" }.confidence)
        assertEquals("RESTRICTED", parsed.entries.first { it.classification == "FIELD_PRACTICE" }.applicationStatus)
        assertFalse(parsed.entries.first { it.classification == "UNSAFE_METHOD" }.restrictedProcedureIncluded!!)
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
}
