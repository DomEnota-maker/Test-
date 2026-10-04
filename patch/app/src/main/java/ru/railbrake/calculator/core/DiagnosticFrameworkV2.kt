package ru.railbrake.calculator.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class KnowledgeClassification(val title: String) {
    TECHNICAL("Устройство"), DIAGNOSTIC("Диагностика"), TRAINING("Обучение"),
    OPERATIONAL_EXPERIENCE("Опыт эксплуатации")
}

enum class KnowledgeQuality { CONFIRMED, REQUIRES_VARIANT_CHECK, REFERENCE_ONLY }

data class FrameworkSource(
    val id: String,
    val title: String,
    val type: String,
    val version: String,
    val locator: String
)

data class FrameworkComponent(
    val id: String,
    val title: String,
    val purpose: String,
    val systemIds: Set<String>,
    val relatedIds: Set<String>
)

data class FrameworkKnowledgeEntry(
    val id: String,
    val classification: KnowledgeClassification,
    val title: String,
    val body: String,
    val componentIds: Set<String>,
    val sourceIds: Set<String>,
    val quality: KnowledgeQuality
)

data class FrameworkDiagnosticNode(
    val id: String,
    val transitionCondition: String,
    val question: String,
    val explanation: String,
    val answers: Map<DiagnosticResponse, String>,
    val nextNodeIds: Map<DiagnosticResponse, String?>,
    val componentIds: Set<String>
)

data class FrameworkDirection(
    val id: String,
    val title: String,
    val questionKey: String,
    val componentIds: Set<String>,
    val explanation: String
)

data class FrameworkDiagnosticModule(
    val scenarioId: String,
    val profileId: String,
    val title: String,
    val profileTitle: String,
    val variantTitles: Map<String, String>,
    val reactionStatus: String,
    val variantIds: Set<String>,
    val systemIds: Set<String>,
    val systemTitles: Map<String, String>,
    val symptom: String,
    val safetyActions: List<String>,
    val stopConditions: List<String>,
    val components: Map<String, FrameworkComponent>,
    val sources: Map<String, FrameworkSource>,
    val knowledge: List<FrameworkKnowledgeEntry>,
    val directions: List<FrameworkDirection>,
    val nodes: Map<String, FrameworkDiagnosticNode>,
    val startNodeId: String
) {
    fun nextNode(currentId: String, response: DiagnosticResponse): FrameworkDiagnosticNode? =
        nodes.getValue(currentId).nextNodeIds.getValue(response)?.let(nodes::getValue)

    fun answerMeaning(currentId: String, response: DiagnosticResponse): String =
        nodes.getValue(currentId).answers.getValue(response)

    fun orderedDirections(answerTrail: List<Pair<String, DiagnosticResponse>>): List<FrameworkDirection> {
        val visited = answerTrail.mapTo(hashSetOf()) { it.first }
        val next = answerTrail.lastOrNull()?.let { (id, response) -> nodes[id]?.nextNodeIds?.get(response) }
        return directions.filter { it.questionKey == next } +
            directions.filter { it.questionKey != next && it.questionKey !in visited } +
            directions.filter { it.questionKey != next && it.questionKey in visited }
    }
}

/**
 * The v2 asset stores a diagnostic graph and knowledge linked to canonical Atlas
 * equipment. Equipment is resolved from its existing technical asset. Other
 * profiles and their graphs can be added as data without a profile-specific branch.
 */
object DiagnosticFrameworkV2 {
    private const val ASSET = "technical/diagnostic_framework_v2.json"

    fun load(context: Context): List<FrameworkDiagnosticModule> = parse(
        TechnicalAssetReader.json(context.applicationContext, ASSET),
        { name -> TechnicalAssetReader.json(context.applicationContext, name) },
        DiagnosticRepository.scenarios.associateBy(DiagnosticScenario::id)
    )

    internal fun parse(
        root: JSONObject,
        canonicalAsset: (String) -> JSONObject,
        legacyScenarios: Map<String, DiagnosticScenario> = emptyMap()
    ): List<FrameworkDiagnosticModule> {
        require(root.optString("schemaVersion") == "diagnostic-framework-v2/1")
        val modules = root.getJSONArray("modules").objects().map { raw ->
            val scenarioId = raw.required("scenarioId")
            val profileId = raw.required("profileId")
            val variantIds = raw.strings("variantIds").toSet()
            val variantTitles = raw.getJSONObject("variantTitles").let { names ->
                names.keys().asSequence().associateWith { names.getString(it).trim() }
            }
            require(variantIds.isNotEmpty() && variantTitles.keys == variantIds && variantTitles.values.all(String::isNotBlank)) {
                "$scenarioId: missing variant presentation"
            }
            val safetyActions = raw.strings("safetyActions")
            val stopConditions = raw.strings("stopConditions")
            require(safetyActions.isNotEmpty() && stopConditions.isNotEmpty() &&
                (safetyActions + stopConditions).all(String::isNotBlank)) { "$scenarioId: missing safety boundary" }
            val systemIds = raw.strings("systemIds").toSet()
            val systemTitles = raw.getJSONObject("systemTitles").let { names ->
                names.keys().asSequence().associateWith { names.getString(it).trim() }
            }
            require(systemTitles.keys == systemIds && systemTitles.values.all(String::isNotBlank)) {
                "$scenarioId: missing system presentation"
            }
            val componentIds = raw.strings("componentIds").toSet()
            val asset = canonicalAsset(raw.required("canonicalEquipmentAsset"))
            val allComponents = asset.getJSONArray("records").objects().associate { item ->
                val id = item.required("id")
                id to FrameworkComponent(
                    id, item.required("name"), item.required("purpose"),
                    item.strings("systemIds").toSet(),
                    item.optJSONArray("relations")?.objects()
                        ?.mapNotNull { it.optString("targetId").takeIf(String::isNotBlank) }?.toSet().orEmpty()
                )
            }
            require(componentIds.isNotEmpty() && allComponents.keys.containsAll(componentIds)) {
                "$scenarioId: unresolved canonical component"
            }
            val components = allComponents.filterKeys { it in componentIds }
            require(systemIds.isNotEmpty() && components.values.flatMap { it.systemIds }.containsAll(systemIds)) {
                "$scenarioId: unresolved system"
            }
            val sourceCatalog = asset.getJSONObject("sources")
            val sources = sourceCatalog.keys().asSequence().associateWith { id ->
                val source = sourceCatalog.getJSONObject(id)
                FrameworkSource(id, source.required("title"), source.optString("type"),
                    source.optString("version"), source.optString("locator"))
            }
            val knowledge = raw.getJSONArray("knowledge").objects().map { item ->
                FrameworkKnowledgeEntry(
                    item.required("id"), KnowledgeClassification.valueOf(item.required("classification")),
                    item.required("title"), item.required("body"), item.strings("componentIds").toSet(),
                    item.strings("sourceIds").toSet(), KnowledgeQuality.valueOf(item.required("quality"))
                )
            }
            require(knowledge.map { it.id }.distinct().size == knowledge.size)
            knowledge.forEach {
                require(componentIds.containsAll(it.componentIds) && sources.keys.containsAll(it.sourceIds)) {
                    "$scenarioId: unresolved knowledge reference ${it.id}"
                }
                require(it.sourceIds.isNotEmpty()) { "$scenarioId: knowledge without source ${it.id}" }
            }

            val legacy = legacyScenarios[scenarioId]
            legacy?.let { scenario ->
                require(scenario.profileId == profileId && scenario.questions.map { it.key }.distinct().size == scenario.questions.size) {
                    "$scenarioId: legacy graph/profile mismatch"
                }
            }
            val directions = raw.getJSONArray("directions").objects().map { item ->
                FrameworkDirection(item.required("id"), item.required("title"), item.required("questionKey"),
                    item.strings("componentIds").toSet(), item.required("explanation"))
            }
            val nodes = if (raw.has("nodes")) parseNodes(raw.getJSONArray("nodes").objects())
                else {
                    val scenario = requireNotNull(legacy) { "$scenarioId: missing graph" }
                    scenario.questions.mapIndexed { index, q ->
                    fun next(key: String?): String? = when (key) {
                        DiagnosticRepository.END_OF_FLOW -> null
                        null -> scenario.questions.getOrNull(index + 1)?.key
                        else -> key
                    }
                    q.key to FrameworkDiagnosticNode(
                        q.key, "После ответа на предыдущий вопрос либо в начале маршрута",
                        q.text, "Ответ уточняет направление проверки и не устанавливает причину сам по себе.",
                        mapOf(DiagnosticResponse.YES to q.yesMeaning, DiagnosticResponse.NO to q.noMeaning,
                            DiagnosticResponse.UNKNOWN to q.unknownMeaning),
                        mapOf(DiagnosticResponse.YES to next(q.yesNextKey), DiagnosticResponse.NO to next(q.noNextKey),
                            DiagnosticResponse.UNKNOWN to next(q.unknownNextKey)),
                        directions.filter { it.questionKey == q.key }.flatMap { it.componentIds }.toSet()
                    )
                    }.toMap()
                }
            val startNodeId = raw.optString("startNodeId").ifBlank { legacy?.questions?.firstOrNull()?.key.orEmpty() }
            require(startNodeId in nodes && nodes.values.all { node ->
                node.transitionCondition.isNotBlank() && node.question.isNotBlank() && node.explanation.isNotBlank() &&
                    node.answers.keys == DiagnosticResponse.entries.toSet() &&
                    node.answers.values.all(String::isNotBlank) &&
                    node.nextNodeIds.keys == DiagnosticResponse.entries.toSet() &&
                    node.nextNodeIds.values.filterNotNull().all { it in nodes } &&
                    componentIds.containsAll(node.componentIds)
            }) { "$scenarioId: invalid diagnostic graph" }
            val reachable = mutableSetOf<String>()
            fun visit(id: String) {
                if (!reachable.add(id)) return
                nodes.getValue(id).nextNodeIds.values.filterNotNull().forEach(::visit)
            }
            visit(startNodeId)
            require(reachable == nodes.keys) { "$scenarioId: unreachable diagnostic node" }
            require(directions.map { it.id }.distinct().size == directions.size && directions.all {
                it.questionKey in nodes && componentIds.containsAll(it.componentIds)
            }) { "$scenarioId: invalid direction links" }
            FrameworkDiagnosticModule(scenarioId, profileId, raw.required("title"), raw.required("profileTitle"),
                variantTitles, raw.required("reactionStatus"), variantIds, systemIds, systemTitles, raw.required("symptom"),
                safetyActions, stopConditions,
                components, sources, knowledge, directions, nodes, startNodeId)
        }
        require(modules.map { it.scenarioId }.distinct().size == modules.size)
        return modules
    }

    private fun parseNodes(items: List<JSONObject>): Map<String, FrameworkDiagnosticNode> = items.map { item ->
        val id = item.required("id")
        val answers = item.getJSONObject("answers")
        val next = item.getJSONObject("next")
        id to FrameworkDiagnosticNode(id, item.required("condition"), item.required("question"),
            item.required("explanation"), DiagnosticResponse.entries.associateWith { answers.required(it.name) },
            DiagnosticResponse.entries.associateWith { response ->
                next.optString(response.name).takeUnless { it.isBlank() || it == DiagnosticRepository.END_OF_FLOW }
            }, item.strings("componentIds").toSet())
    }.toMap().also { require(it.size == items.size) { "Duplicate diagnostic node" } }

    private fun JSONObject.required(key: String): String = getString(key).trim().also {
        require(it.isNotBlank()) { "Missing $key" }
    }
    private fun JSONObject.strings(key: String): List<String> = getJSONArray(key).strings()
    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)
    private fun JSONArray.strings(): List<String> = (0 until length()).map(::getString)
}
