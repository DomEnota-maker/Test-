package ru.railbrake.calculator.core

import java.io.File
import java.util.zip.GZIPInputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DiagnosticFrameworkV2Test {
    private val root = JSONObject(File("src/main/assets/technical/diagnostic_framework_v2.json").readText())
    private val canonical = JSONObject(GZIPInputStream(
        File("src/main/assets/technical/vl80s_equipment.json.gz").inputStream()
    ).bufferedReader().use { it.readText() })

    @Test fun referenceModuleUsesCanonicalEquipmentAndExistingQuestionGraph() {
        val scenario = requireNotNull(DiagnosticRepository.scenario("pantograph-no-rise"))
        val module = DiagnosticFrameworkV2.parse(root, { canonical }, mapOf(scenario.id to scenario)).single()
        assertEquals(scenario.id, module.scenarioId)
        assertEquals(scenario.questions.map { it.key }.toSet(), module.nodes.keys)
        assertEquals("pnr-danger", module.startNodeId)
        assertEquals("Токоприёмник", module.components.getValue("VL-EQ-HV-002").title)
        assertEquals(scenario.questions.first().text, module.nodes.getValue("pnr-danger").question)
        assertTrue(module.nodes.values.all { it.answers.keys == DiagnosticResponse.entries.toSet() })
        assertTrue(module.knowledge.any { it.classification == KnowledgeClassification.OPERATIONAL_EXPERIENCE &&
            it.quality == KnowledgeQuality.REFERENCE_ONLY })
        assertTrue(module.knowledge.all { it.sourceIds.isNotEmpty() })
    }

    @Test fun answersReorderDirectionsWithoutDiscardingAlternativesOrMutatingSource() {
        val scenario = requireNotNull(DiagnosticRepository.scenario("pantograph-no-rise"))
        val module = DiagnosticFrameworkV2.parse(root, { canonical }, mapOf(scenario.id to scenario)).single()
        val initial = module.directions.map { it.id }
        val reordered = module.orderedDirections(listOf("pnr-danger" to DiagnosticResponse.UNKNOWN))
        assertEquals(initial.toSet(), reordered.map { it.id }.toSet())
        assertEquals("safety", reordered.last().id)
        assertEquals("permission", module.orderedDirections(listOf("pnr-danger" to DiagnosticResponse.NO)).first().id)
        assertEquals(initial, module.directions.map { it.id })
        assertFalse(module.nodes.getValue("pnr-danger").answers.getValue(DiagnosticResponse.UNKNOWN).isBlank())
    }

    @Test fun frameworkGraphRoutesByAnswerAndKeepsUnknownExplanation() {
        val scenario = requireNotNull(DiagnosticRepository.scenario("pantograph-no-rise"))
        val module = DiagnosticFrameworkV2.parse(root, { canonical }, mapOf(scenario.id to scenario)).single()
        assertEquals(null, module.nextNode("pnr-danger", DiagnosticResponse.YES))
        assertEquals("pnr-permission", module.nextNode("pnr-danger", DiagnosticResponse.NO)?.id)
        assertTrue(module.answerMeaning("pnr-danger", DiagnosticResponse.UNKNOWN).isNotBlank())
        assertEquals(module.nodes.getValue("pnr-danger").question, scenario.questions.first().text)
    }

    @Test fun aNewProfileAndDataDefinedGraphNeedNoNewCoreBranch() {
        val raw = JSONObject(root.toString())
        val item = raw.getJSONArray("modules").getJSONObject(0)
        item.put("scenarioId", "future-family-scenario")
        item.put("profileId", "future-family")
        item.put("variantIds", org.json.JSONArray().put("future-variant"))
        item.put("startNodeId", "first")
        item.put("directions", org.json.JSONArray().put(JSONObject()
            .put("id", "first-direction").put("title", "Проверка")
            .put("questionKey", "first").put("componentIds", org.json.JSONArray().put("VL-EQ-HV-002"))
            .put("explanation", "Проверить признак")))
        item.put("nodes", org.json.JSONArray().put(JSONObject()
            .put("id", "first").put("condition", "В начале")
            .put("question", "Есть признак?").put("explanation", "Один признак")
            .put("componentIds", org.json.JSONArray().put("VL-EQ-HV-002"))
            .put("answers", JSONObject().put("YES", "Есть").put("NO", "Нет").put("UNKNOWN", "Неизвестно"))
            .put("next", JSONObject().put("YES", "__end__").put("NO", "__end__")
                .put("UNKNOWN", "__end__"))))
        val module = DiagnosticFrameworkV2.parse(raw, { canonical }).single()
        assertEquals("future-family", module.profileId)
        assertEquals("first", module.startNodeId)
    }
}
