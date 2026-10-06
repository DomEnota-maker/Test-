package ru.railbrake.calculator.core

import java.io.File
import java.util.zip.GZIPInputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import ru.railbrake.calculator.ui.frameworkModulesForFamily

class DiagnosticFrameworkV2Test {
    private val root = JSONObject(File("src/main/assets/technical/diagnostic_framework_v2.json").readText())
    private val canonical = JSONObject(GZIPInputStream(
        File("src/main/assets/technical/vl80s_equipment.json.gz").inputStream()
    ).bufferedReader().use { it.readText() })

    @Test fun referenceModuleUsesCanonicalEquipmentAndIndependentQuestionGraph() {
        val scenario = requireNotNull(DiagnosticRepository.scenario("pantograph-no-rise"))
        val module = DiagnosticFrameworkV2.parse(root, { canonical }, mapOf(scenario.id to scenario)).single()
        assertEquals(scenario.id, module.scenarioId)
        assertEquals(8, module.nodes.size)
        assertEquals("pnr-danger", module.startNodeId)
        assertEquals("Токоприёмник", module.components.getValue("VL-EQ-HV-002").title)
        assertEquals("Видно ли повреждение токоприёмника или контактного провода?", module.nodes.getValue("pnr-danger").question)
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
        val local = module.orderedDirections(listOf("pnr-scope" to DiagnosticResponse.YES))
        val common = module.orderedDirections(listOf("pnr-scope" to DiagnosticResponse.NO))
        assertEquals("local", local.first().id)
        assertEquals("common", common.first().id)
        assertEquals(initial.toSet(), local.map { it.id }.toSet())
        assertEquals(initial.toSet(), common.map { it.id }.toSet())
        assertEquals("air", module.orderedDirections(listOf(
            "pnr-scope" to DiagnosticResponse.YES,
            "pnr-leak" to DiagnosticResponse.YES
        )).first().id)
        assertEquals(initial, module.directions.map { it.id })
        assertFalse(module.nodes.getValue("pnr-danger").answers.getValue(DiagnosticResponse.UNKNOWN).isBlank())
    }

    @Test fun frameworkGraphRoutesByAnswerAndKeepsUnknownExplanation() {
        val scenario = requireNotNull(DiagnosticRepository.scenario("pantograph-no-rise"))
        val module = DiagnosticFrameworkV2.parse(root, { canonical }, mapOf(scenario.id to scenario)).single()
        assertEquals(null, module.nextNode("pnr-danger", DiagnosticResponse.YES))
        assertEquals("pnr-arc", module.nextNode("pnr-danger", DiagnosticResponse.NO)?.id)
        assertTrue(module.answerMeaning("pnr-danger", DiagnosticResponse.UNKNOWN).isNotBlank())
        assertEquals(null, module.nextNode("pnr-danger", DiagnosticResponse.UNKNOWN))
        assertEquals("pnr-permission", module.nextNode("pnr-arc", DiagnosticResponse.NO)?.id)
        assertEquals("pnr-leak", module.nextNode("pnr-valve", DiagnosticResponse.YES)?.id)
        assertEquals(null, module.nextNode("pnr-leak", DiagnosticResponse.UNKNOWN))
    }

    @Test fun aNewProfileAndDataDefinedGraphNeedNoNewCoreBranch() {
        val tem2Catalog = JSONObject(File("src/main/assets/technical/tem2_tem2_catalog.json").readText())
        val raw = JSONObject(root.toString())
        val item = raw.getJSONArray("modules").getJSONObject(0)
        item.put("scenarioId", "fixture-tem2-observation")
        item.put("profileId", "tem2")
        item.put("profileTitle", "ТЭМ2")
        item.put("variantIds", org.json.JSONArray().put("tem2-base"))
        item.put("variantTitles", JSONObject().put("tem2-base", "Исполнение уточняется"))
        item.put("canonicalEquipmentAsset", "technical/tem2_tem2_catalog.json")
        item.put("systemIds", org.json.JSONArray().put("TEM2-SYS-DIESEL"))
        item.put("systemTitles", JSONObject().put("TEM2-SYS-DIESEL", "Дизель и механическая часть"))
        item.put("componentIds", org.json.JSONArray().put("TEM2-EQ-DIESEL"))
        item.remove("referencePackAsset")
        item.put("knowledge", org.json.JSONArray())
        item.put("startNodeId", "first")
        item.put("directions", org.json.JSONArray().put(JSONObject()
            .put("id", "first-direction").put("title", "Проверка")
            .put("questionKey", "first").put("componentIds", org.json.JSONArray().put("TEM2-EQ-DIESEL"))
            .put("explanation", "Проверить признак")))
        item.put("nodes", org.json.JSONArray().put(JSONObject()
            .put("id", "first").put("condition", "В начале")
            .put("question", "Есть признак?").put("explanation", "Один признак")
            .put("componentIds", org.json.JSONArray().put("TEM2-EQ-DIESEL"))
            .put("answers", JSONObject().put("YES", "Есть").put("NO", "Нет").put("UNKNOWN", "Неизвестно"))
            .put("next", JSONObject().put("YES", "__end__").put("NO", "__end__")
                .put("UNKNOWN", "__end__"))))
        val module = DiagnosticFrameworkV2.parse(raw, { tem2Catalog }).single()
        assertEquals("tem2", module.profileId)
        assertEquals("ТЭМ2", module.profileTitle)
        assertEquals("first", module.startNodeId)
        assertEquals("Дизель ПД1 / фактически установленное исполнение",
            module.components.getValue("TEM2-EQ-DIESEL").title)
        assertEquals(listOf(module), frameworkModulesForFamily(listOf(module), TechnicalFamily.TEM2))
        assertTrue(frameworkModulesForFamily(listOf(module), TechnicalFamily.VL80S).isEmpty())
    }

    @Test fun goldenPantographGraphExhaustivelyCoversEveryTerminalRouteAndResponseEdge() {
        val scenario = requireNotNull(DiagnosticRepository.scenario("pantograph-no-rise"))
        val module = DiagnosticFrameworkV2.parse(root, { canonical }, mapOf(scenario.id to scenario)).single()
        val responses = DiagnosticResponse.entries.toSet()
        val visitedEdges = linkedSetOf<Pair<String, DiagnosticResponse>>()
        val terminalRoutes = mutableListOf<List<Pair<String, DiagnosticResponse>>>()

        fun walk(nodeId: String, trail: List<Pair<String, DiagnosticResponse>>, active: Set<String>) {
            assertFalse("cycle at $nodeId", nodeId in active)
            val node = module.nodes.getValue(nodeId)
            responses.forEach { response ->
                visitedEdges += nodeId to response
                val next = node.nextNodeIds.getValue(response)
                val updated = trail + (nodeId to response)
                if (next == null) terminalRoutes += updated
                else walk(next, updated, active + nodeId)
            }
        }

        walk(module.startNodeId, emptyList(), emptySet())
        val expectedEdges = module.nodes.keys.flatMap { id -> responses.map { id to it } }.toSet()
        assertEquals(expectedEdges, visitedEdges)
        assertEquals(25, terminalRoutes.size)
        assertTrue(terminalRoutes.all { it.isNotEmpty() })
        assertTrue(module.nodes.values.all {
            it.answers.getValue(DiagnosticResponse.UNKNOWN) != it.answers.getValue(DiagnosticResponse.NO)
        })
    }
}
