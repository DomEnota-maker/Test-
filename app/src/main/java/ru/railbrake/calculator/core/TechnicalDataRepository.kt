package ru.railbrake.calculator.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

enum class TechnicalFamily(val title: String, val subtitle: String) {
    VL80S("ВЛ80С", "Техническая база локомотива"),
    ERMAK("Ермак", "2ЭС5К / 3ЭС5К"),
    CHME3("ЧМЭ3", "Маневровый тепловоз"),
    CHME3T("ЧМЭ3Т", "Реостатное торможение"),
    CHME3E("ЧМЭ3Э", "Электронное регулирование"),
    TEM2("ТЭМ2", "Маневровый тепловоз"),
    TEM2U("ТЭМ2У", "Улучшенное исполнение");

    val isChme3: Boolean get() = this == CHME3 || this == CHME3T || this == CHME3E
    val isTem2: Boolean get() = this == TEM2 || this == TEM2U
}

enum class TechnicalSection(val title: String) {
    PROFILES("Исполнения"),
    EQUIPMENT("Оборудование"),
    KNOWLEDGE("Статьи"),
    DIAGNOSTICS("Диагностика"),
    ELECTRICAL("Электросхемы"),
    PNEUMATIC("Пневмосхемы"),
    ACCEPTANCE("Приёмка"),
    SYSTEMS("Системы"),
    SAFETY("Охрана труда")
}

data class TechnicalBlock(
    val title: String,
    val lines: List<String>
)

data class TechnicalHotspot(
    val equipmentId: String,
    val label: String,
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
)

data class TechnicalEntry(
    val id: String,
    val family: TechnicalFamily,
    val section: TechnicalSection,
    val title: String,
    val subtitle: String,
    val status: String,
    val blocks: List<TechnicalBlock>,
    val relatedIds: List<String> = emptyList(),
    val sequence: List<String> = emptyList(),
    val sequenceLabels: Map<String, String> = emptyMap(),
    val hotspots: List<TechnicalHotspot> = emptyList(),
    val searchText: String,
    val searchAliases: List<String> = emptyList()
)

data class ErmakSchemeDiagnosticLink(
    val diagnosticId: String,
    val profileGateRequired: Boolean,
    val reason: String
)

data class ErmakSchemeLinkContext(
    val models: List<String>,
    val profiles: List<String>,
    val selectionRequired: Boolean,
    val notes: String,
    val diagnosticLinks: List<ErmakSchemeDiagnosticLink>
)

internal fun fullAcceptanceSequence(requiredIds: List<String>, expandedIds: List<String>): List<String> =
    (requiredIds + expandedIds).distinct()

internal const val VL80S_REQUIRED_ACCEPTANCE_COUNT = 15
internal const val ERMAK_REQUIRED_ACCEPTANCE_COUNT = 18

internal fun verifiedRequiredAcceptanceIds(entries: List<TechnicalEntry>, expectedCount: Int): List<String> {
    require(entries.size == expectedCount) { "Unexpected mandatory acceptance count: ${entries.size}, expected $expectedCount" }
    val ids = entries.map(TechnicalEntry::id)
    require(ids.size == ids.distinct().size) { "Mandatory acceptance IDs must be unique" }
    require(entries.all { it.status == "MANDATORY_CHECK" }) { "Mandatory route contains a non-mandatory entry" }
    return ids
}

class TechnicalDataRepository internal constructor(private val loadAsset: (String) -> JSONObject) {
    constructor(context: Context) : this({ asset -> TechnicalAssetReader.json(context, asset) })
    companion object {
        private val sharedSectionCache = mutableMapOf<Pair<TechnicalFamily, TechnicalSection>, List<TechnicalEntry>>()
        private val sharedEntryCache = mutableMapOf<String, TechnicalEntry>()
    }

    private val legacyEquipmentIds by lazy {
        json("technical/vl80s_equipment.json").array("records").objects()
            .mapNotNull { item ->
                item.optString("legacyId").takeIf(String::isNotBlank)?.let { legacy ->
                    "vl80-eq-$legacy" to item.optString("id")
                }
            }.toMap()
    }

    // Stage 7 is the canonical Ermak cross-link graph. Equipment/system/article
    // cards consume these indexes directly instead of duplicating link lists.
    private val ermakLinkIndexes by lazy {
        json("technical/ermak_links.json").obj("indexes")
    }

    private fun ermakEquipmentLinks(equipmentId: String): JSONObject =
        ermakLinkIndexes.obj("byEquipment").obj(equipmentId)

    private fun ermakSystemLinks(systemId: String): JSONObject =
        ermakLinkIndexes.obj("bySystem").obj(systemId)

    private fun ermakKnowledgeLinks(article: JSONObject): JSONObject {
        val equipmentId = article.optString("equipmentId")
        if (equipmentId.isNotBlank()) return ermakEquipmentLinks(equipmentId)
        val articleId = article.optString("id")
        if (articleId.startsWith("ER-KB-SYS-")) {
            return ermakSystemLinks(articleId.removePrefix("ER-KB-"))
        }
        return JSONObject()
    }

    // VL80S assets already contain canonical equipment IDs in scheme nodes. Build the
    // reverse index at runtime so equipment cards can navigate back to the current
    // electrical/pneumatic schemes instead of exposing legacy schemeNodeIds tokens.
    private val vl80sSchemeIdsByEquipment by lazy {
        val result = linkedMapOf<String, MutableList<String>>()
        val schemes = buildList {
            addAll(json("technical/vl80s_electrical.json").array("baseSchemes").objects())
            addAll(json("technical/vl80s_pneumatic.json").array("views").objects())
        }
        schemes.forEach { scheme ->
            val schemeId = scheme.optString("id")
            if (schemeId.isBlank()) return@forEach
            val equipmentIds = buildList {
                addAll(scheme.array("equipmentIds").strings())
                addAll(scheme.array("nodes").objects().mapNotNull { node ->
                    node.optString("equipmentId").takeIf(String::isNotBlank)
                })
            }.distinct()
            equipmentIds.forEach { equipmentId ->
                result.getOrPut(equipmentId) { mutableListOf() }.add(schemeId)
            }
        }
        result.mapValues { (_, ids) -> ids.distinct() }
    }

    fun ermakSchemeLinkContext(schemeId: String): ErmakSchemeLinkContext? {
        val byScheme = ermakLinkIndexes.optJSONObject("byScheme") ?: return null
        val raw = byScheme.optJSONObject(schemeId) ?: return null
        val rules = raw.obj("variantRules")
        val diagnostics = raw.array("diagnosticLinks").objects().mapNotNull { item ->
            item.optString("diagnosticId").takeIf(String::isNotBlank)?.let { diagnosticId ->
                ErmakSchemeDiagnosticLink(
                    diagnosticId = diagnosticId,
                    profileGateRequired = item.optBoolean("profileGateRequired", false),
                    reason = item.optString("reason")
                )
            }
        }.distinctBy(ErmakSchemeDiagnosticLink::diagnosticId)
        return ErmakSchemeLinkContext(
            models = rules.array("models").strings(),
            profiles = rules.array("includeProfiles").strings(),
            selectionRequired = rules.optBoolean("selectionRequired", false),
            notes = rules.optString("notes"),
            diagnosticLinks = diagnostics
        )
    }

    val entries: List<TechnicalEntry>
        get() = TechnicalFamily.entries.flatMap { family ->
            sections(family).flatMap { section -> sectionEntries(family, section) }
        }

    fun sections(family: TechnicalFamily): List<TechnicalSection> = when (family) {
        TechnicalFamily.CHME3, TechnicalFamily.CHME3T, TechnicalFamily.CHME3E,
        TechnicalFamily.TEM2, TechnicalFamily.TEM2U -> listOf(
            TechnicalSection.SYSTEMS, TechnicalSection.EQUIPMENT, TechnicalSection.KNOWLEDGE,
            TechnicalSection.ELECTRICAL, TechnicalSection.PNEUMATIC, TechnicalSection.DIAGNOSTICS,
            TechnicalSection.ACCEPTANCE
        )
        TechnicalFamily.VL80S -> listOf(
            TechnicalSection.EQUIPMENT,
            TechnicalSection.SYSTEMS,
            TechnicalSection.KNOWLEDGE,
            TechnicalSection.ELECTRICAL,
            TechnicalSection.PNEUMATIC
        )
        TechnicalFamily.ERMAK -> listOf(
            TechnicalSection.SYSTEMS,
            TechnicalSection.EQUIPMENT,
            TechnicalSection.KNOWLEDGE,
            TechnicalSection.ELECTRICAL,
            TechnicalSection.PNEUMATIC
        )
    }

    fun entries(family: TechnicalFamily, section: TechnicalSection, query: String = ""): List<TechnicalEntry> {
        val needle = query.trim().lowercase()
        return sectionEntries(family, section).filter { needle.isBlank() || needle in it.searchText }
    }

    fun entry(id: String, familyHint: TechnicalFamily? = null): TechnicalEntry? {
        val canonicalId = legacyEquipmentIds[id.lowercase()] ?: id
        if ((canonicalId.startsWith("CHME3") && familyHint?.isChme3 == true) ||
            ((canonicalId.startsWith("TEM2") || canonicalId.startsWith("TEM2U")) && familyHint?.isTem2 == true)) {
            return TechnicalSection.entries.firstNotNullOfOrNull { section ->
                sectionEntries(familyHint, section).firstOrNull { it.id == canonicalId }
            }
        }
        synchronized(sharedSectionCache) {
            sharedEntryCache[canonicalId]?.let { return it }
        }
        candidateSections(canonicalId).forEach { (family, section) ->
            sectionEntries(family, section)
            synchronized(sharedSectionCache) {
                sharedEntryCache[canonicalId]?.let { return it }
            }
        }
        return null
    }

    private val readableSources by lazy {
        val sources = json("technical/source_presentations.json").getJSONObject("sources")
        buildMap {
            val keys = sources.keys()
            while (keys.hasNext()) {
                val id = keys.next()
                val source = sources.getJSONObject(id)
                put(id, source.optString("title"))
            }
        }
    }

    private val embeddedReference = Regex("(?i)(?:TEM2U|TEM2|CHME3E|CHME3T|CHME3|VL80|VL|ER|SYS|SAFETY)(?:-[A-Z0-9_]+)+")

    private fun readableReferences(value: String): String = embeddedReference.replace(value) { match ->
        readableSources[match.value] ?: entry(match.value)?.title ?: ""
    }

    fun displayLines(lines: List<String>): List<String> = lines.mapNotNull { line ->
        line.split(" • ")
            .mapNotNull { part ->
                part.takeUnless { looksLikeEntryId(it) && entry(it) != null }
                    ?.let(::readableReferences)?.let(::technicalPresentationLine)?.takeIf(String::isNotBlank)
            }.distinct().joinToString(" • ").takeIf(String::isNotBlank)
    }

    fun referencedEntries(lines: List<String>): List<TechnicalEntry> =
        lines.flatMap { it.split(" • ") }.filter(::looksLikeEntryId).mapNotNull(::entry).distinctBy(TechnicalEntry::id)

    fun count(family: TechnicalFamily, section: TechnicalSection): Int = sectionEntries(family, section).size

    private fun sectionEntries(family: TechnicalFamily, section: TechnicalSection): List<TechnicalEntry> {
        val key = family to section
        synchronized(sharedSectionCache) {
            sharedSectionCache[key]?.let { return it }
        }
        val loaded = loadSection(family, section)
        synchronized(sharedSectionCache) {
            val cached = sharedSectionCache.getOrPut(key) { loaded }
            cached.forEach { entry -> sharedEntryCache.putIfAbsent(entry.id, entry) }
            return cached
        }
    }

    private fun loadSection(family: TechnicalFamily, section: TechnicalSection): List<TechnicalEntry> = when (family) {
        TechnicalFamily.CHME3, TechnicalFamily.CHME3T, TechnicalFamily.CHME3E ->
            loadChme3Section(family, section)
        TechnicalFamily.TEM2, TechnicalFamily.TEM2U -> loadTem2Section(family, section)
        TechnicalFamily.VL80S -> when (section) {
            TechnicalSection.PROFILES -> loadVl80sProfiles()
            TechnicalSection.EQUIPMENT -> loadVl80sEquipment()
            TechnicalSection.DIAGNOSTICS -> loadVl80sDiagnostics()
            TechnicalSection.ELECTRICAL -> loadVl80sElectrical()
            TechnicalSection.PNEUMATIC -> loadVl80sPneumatic()
            TechnicalSection.ACCEPTANCE -> loadVl80sAcceptance()
            TechnicalSection.KNOWLEDGE -> loadVl80sKnowledge() + loadWheelFlatReference(TechnicalFamily.VL80S) + loadBrakeJamReference(TechnicalFamily.VL80S)
            TechnicalSection.SYSTEMS -> loadVl80sSystems()
            TechnicalSection.SAFETY -> loadSafety(TechnicalFamily.VL80S)
        }
        TechnicalFamily.ERMAK -> when (section) {
            TechnicalSection.PROFILES -> loadErmakProfiles()
            TechnicalSection.SYSTEMS -> loadErmakSystems()
            TechnicalSection.EQUIPMENT -> loadErmakEquipment()
            TechnicalSection.KNOWLEDGE -> loadErmakKnowledge() + loadWheelFlatReference(TechnicalFamily.ERMAK) + loadBrakeJamReference(TechnicalFamily.ERMAK)
            TechnicalSection.DIAGNOSTICS -> loadErmakDiagnostics()
            TechnicalSection.ELECTRICAL -> loadErmakSchemes().filter { it.section == TechnicalSection.ELECTRICAL }
            TechnicalSection.PNEUMATIC -> loadErmakSchemes().filter { it.section == TechnicalSection.PNEUMATIC }
            TechnicalSection.ACCEPTANCE -> loadErmakAcceptance()
            TechnicalSection.SAFETY -> loadSafety(TechnicalFamily.ERMAK)
        }
    }

    private fun candidateSections(id: String): List<Pair<TechnicalFamily, TechnicalSection>> = when {
        id.startsWith("TEM2") || id.startsWith("TEM2U") -> listOf(TechnicalFamily.TEM2, TechnicalFamily.TEM2U)
            .flatMap { family -> TechnicalSection.entries.map { family to it } }
        id.startsWith("CHME3") -> listOf(TechnicalFamily.CHME3, TechnicalFamily.CHME3T, TechnicalFamily.CHME3E)
            .flatMap { family -> TechnicalSection.entries.map { family to it } }
        id.startsWith("VL80-ACC-") || id.startsWith("VL80-ASST-") || id.startsWith("VL80-REQ-") || id.startsWith("VL80-ROUTE-") || id.startsWith("route_") -> listOf(TechnicalFamily.VL80S to TechnicalSection.ACCEPTANCE)
        id.startsWith("VL-EQ-") -> listOf(TechnicalFamily.VL80S to TechnicalSection.EQUIPMENT)
        id.startsWith("VL-SYS-") -> listOf(TechnicalFamily.VL80S to TechnicalSection.SYSTEMS)
        id.startsWith("vl80-") -> listOf(TechnicalFamily.VL80S to TechnicalSection.KNOWLEDGE)
        id.startsWith("VL-SCH-") -> listOf(TechnicalFamily.VL80S to TechnicalSection.ELECTRICAL, TechnicalFamily.VL80S to TechnicalSection.PNEUMATIC)
        id.startsWith("ER-VARIANT-") -> listOf(TechnicalFamily.ERMAK to TechnicalSection.PROFILES)
        id.startsWith("ER-ACC-") || id.startsWith("ER-REQ-") || id.startsWith("ER-ROUTE-") -> listOf(TechnicalFamily.ERMAK to TechnicalSection.ACCEPTANCE)
        id.startsWith("SYS-") -> listOf(TechnicalFamily.ERMAK to TechnicalSection.SYSTEMS)
        id.startsWith("ER-EQ-") -> listOf(TechnicalFamily.ERMAK to TechnicalSection.EQUIPMENT)
        id.startsWith("ER-KB-") -> listOf(TechnicalFamily.ERMAK to TechnicalSection.KNOWLEDGE)
        id.startsWith("ER-DIAG-") -> listOf(TechnicalFamily.ERMAK to TechnicalSection.DIAGNOSTICS)
        id.startsWith("ER-SCH-") -> listOf(TechnicalFamily.ERMAK to TechnicalSection.ELECTRICAL, TechnicalFamily.ERMAK to TechnicalSection.PNEUMATIC)
        id.startsWith("SAFETY-VL80S-") -> listOf(TechnicalFamily.VL80S to TechnicalSection.SAFETY)
        id.startsWith("SAFETY-ERMAK-") -> listOf(TechnicalFamily.ERMAK to TechnicalSection.SAFETY)
        id.matches(Regex("^[a-z][a-z0-9]+(?:-[a-z0-9]+)+$")) -> listOf(TechnicalFamily.VL80S to TechnicalSection.DIAGNOSTICS)
        else -> emptyList()
    }

    private fun loadChme3Section(family: TechnicalFamily, section: TechnicalSection): List<TechnicalEntry> {
        val name = "technical/chme3_${family.name.lowercase()}_catalog.json"
        return json(name).array("entries").objects().filter { it.optString("section") == section.name }.map { raw ->
            val title = raw.optString("title")
            val subtitle = raw.optString("subtitle")
            val blocks = raw.array("blocks").objects().map { block ->
                TechnicalBlock(block.optString("title"), block.array("lines").strings())
            }
            val aliases = raw.array("searchAliases").strings()
            TechnicalEntry(
                id = raw.optString("id"), family = family, section = section,
                title = title, subtitle = subtitle, status = raw.optString("status"),
                blocks = blocks, relatedIds = raw.array("relatedIds").strings(),
                sequence = raw.array("sequence").strings(),
                hotspots = raw.array("hotspots").objects().map { hotspot ->
                    TechnicalHotspot(
                        equipmentId = hotspot.optString("equipmentId"), label = hotspot.optString("label"),
                        x = hotspot.optInt("x"), y = hotspot.optInt("y"),
                        width = hotspot.optInt("width"), height = hotspot.optInt("height")
                    )
                },
                searchAliases = aliases,
                searchText = raw.optString("searchText").lowercase()
            )
        }
    }

    private fun loadTem2Section(family: TechnicalFamily, section: TechnicalSection): List<TechnicalEntry> {
        // Profile-specific asset and applicability are supplied by the foundation manifest projection.
        val name = if (family == TechnicalFamily.TEM2) "technical/tem2_tem2_catalog.json"
                   else "technical/tem2_tem2u_catalog.json"
        val profileId = LocomotiveProfileRegistry.fromTechnicalFamily(family).profileId
        val root = json(name)
        if (root.optString("profileId") != profileId) return emptyList()
        return root.array("entries").objects().filter { it.optString("section") == section.name }.map { raw ->
            TechnicalEntry(
                id = raw.optString("id"), family = family, section = section,
                title = raw.optString("title"), subtitle = raw.optString("subtitle"), status = raw.optString("status"),
                blocks = raw.array("blocks").objects().map { block ->
                    TechnicalBlock(block.optString("title"), block.array("lines").strings())
                },
                relatedIds = raw.array("relatedIds").strings(), sequence = raw.array("sequence").strings(),
                hotspots = raw.array("hotspots").objects().map { spot ->
                    TechnicalHotspot(spot.optString("equipmentId"), spot.optString("label"),
                        spot.optInt("x"), spot.optInt("y"), spot.optInt("width"), spot.optInt("height"))
                },
                searchAliases = raw.array("searchAliases").strings(), searchText = raw.optString("searchText").lowercase()
            )
        }
    }

    private fun looksLikeEntryId(value: String): Boolean {
        val id=value.trim()
        return id.startsWith("TEM2") || id.startsWith("CHME3") || id.startsWith("VL-") || id.startsWith("VL80-") || id.startsWith("ER-") || id.startsWith("SYS-") || id.startsWith("SAFETY-") || id.startsWith("route_") || id.matches(Regex("^[a-z][a-z0-9]+(?:-[a-z0-9]+)+$"))
    }

    private fun json(asset: String): JSONObject = loadAsset(asset)

    private fun loadVl80sProfiles(): List<TechnicalEntry> {
        val root = json("technical/vl80s_variants.json")
        return root.array("physicalBuckets").objects().map { item ->
            entry(
                item, TechnicalFamily.VL80S, TechnicalSection.PROFILES,
                title = item.optString("range"),
                subtitle = "Профиль секции ${item.optString("id")}",
                status = item.optString("confidence"),
                blocks = listOfNotEmpty(
                    block("Диапазон", item.optString("range")),
                    block("Особенности", item.array("features").strings()),
                    block("Опорные источники", item.array("sourceTags").strings())
                )
            )
        }
    }

    private fun loadErmakProfiles(): List<TechnicalEntry> {
        val selector = json("technical/ermak_schemes.json").obj("variantSelector")
        return selector.array("dimensions").objects().map { item ->
            val id = "ER-VARIANT-${item.optString("id")}"
            val source = JSONObject(item.toString()).put("id", id)
            entry(
                source, TechnicalFamily.ERMAK, TechnicalSection.PROFILES,
                title = item.optString("title"),
                subtitle = item.array("values").strings().joinToString(" • "),
                status = if (item.optBoolean("required")) "REQUIRED" else "PROFILE_REQUIRED",
                blocks = listOfNotEmpty(
                    block("Доступные значения", item.array("values").strings())
                )
            )
        }
    }

    private fun loadVl80sEquipment(): List<TechnicalEntry> {
        val records = json("technical/vl80s_equipment.json").array("records").objects()
        val equipmentById = records.associateBy { it.optString("id") }

        fun relatedEquipmentLine(relation: JSONObject): String? {
  val targetId = relation.optString("targetId")
  if (targetId.isBlank()) return null
  val targetName = equipmentById[targetId]?.optString("name")?.takeIf(String::isNotBlank) ?: return null
  val note = relation.optString("note").trim()
  return if (note.isBlank()) targetName else "$targetName — $note"
        }

        return records.map { item ->
  val equipmentId = item.optString("id")
  val canonicalSchemeIds = vl80sSchemeIdsByEquipment[equipmentId].orEmpty()
  val relationObjects = item.array("relations").objects()
  val related = buildList {
      addAll(item.array("diagnosticScenarioIds").strings())
      addAll(relationObjects.mapNotNull { it.optString("targetId").takeIf(String::isNotBlank) })
      addAll(canonicalSchemeIds)
  }.distinct()
  entry(
      item, TechnicalFamily.VL80S, TechnicalSection.EQUIPMENT,
      title = item.optString("name"),
      subtitle = item.optString("purpose"),
      status = item.optString("evidenceStatus"),
      blocks = listOfNotEmpty(
          block("Назначение", item.optString("purpose")),
          block("Модель / исполнение", item.array("modelNames").strings()),
          block("Количество", item.optString("quantity")),
          block("Расположение", item.obj("location").summary()),
          block("Схемные обозначения", item.array("schemeDesignations").strings()),
          block("Обозначения и алиасы", item.array("aliases").strings()),
          block("Как работает", item.optString("principle")),
          block("Параметры", item.array("parameters").stringsOrSummaries()),
          block("Нормальное состояние", item.array("normalState").strings()),
          block("Признаки отклонения", item.array("deviationSigns").strings()),
          block("Связанное оборудование", relationObjects.mapNotNull(::relatedEquipmentLine).distinct()),
          block("Функциональные связи", item.array("legacyConnections").strings()),
          block("Системы", item.array("systemIds").strings()),
          block("Схемы", canonicalSchemeIds),
          block("Диагностика", item.array("diagnosticScenarioIds").strings()),
          block("Применимость", item.obj("applicability").summary()),
          block("Особенности", item.array("featureRules").stringsOrSummaries()),
          block("Примечания", item.array("notes").stringsOrSummaries()),
          block("Безопасность", item.array("safetyNotes").stringsOrSummaries()),
          block("Источники", item.array("sourceRefs").stringsOrSummaries())
      ),
      relatedIds = related
  )
        }
    }

    private fun loadVl80sSystems(): List<TechnicalEntry> {
        val equipment = json("technical/vl80s_equipment.json").array("records").objects()
        val diagnostics = json("technical/vl80s_diagnostics.json").array("scenarios").objects()
        val systemIds = (equipment.flatMap { it.array("systemIds").strings() } +
            diagnostics.flatMap { it.array("systemIds").strings() }).distinct().sorted()
        return systemIds.map { systemId ->
            val systemEquipment = equipment.filter { systemId in it.array("systemIds").strings() }
            val systemDiagnostics = diagnostics.filter { systemId in it.array("systemIds").strings() }
            val title = mapOf(
                "VL-SYS-AU" to "Автоматическое управление",
                "VL-SYS-BR" to "Тормозное оборудование",
                "VL-SYS-CB" to "Цепи управления и блокировки",
                "VL-SYS-CT" to "Контроль и сигнализация",
                "VL-SYS-FR" to "Фазорасщепитель и вспомогательные машины",
                "VL-SYS-HV" to "Высоковольтные цепи",
                "VL-SYS-MC" to "Тяговые двигатели и силовые цепи",
                "VL-SYS-PN" to "Пневматическая система",
                "VL-SYS-PR" to "Защита и релейная аппаратура",
                "VL-SYS-SF" to "Безопасность движения",
                "VL-SYS-TR" to "Тяговое регулирование"
            )[systemId] ?: "Система ВЛ80С"
            TechnicalEntry(
                id = systemId,
                family = TechnicalFamily.VL80S,
                section = TechnicalSection.SYSTEMS,
                title = title,
                subtitle = "Оборудование: ${systemEquipment.size} • Диагностические сценарии: ${systemDiagnostics.size}",
                status = "INFORMATION",
                blocks = listOfNotEmpty(
                    block("Оборудование системы", systemEquipment.map { it.optString("name") }),
                    block("Связанные неисправности", systemDiagnostics.map { it.optString("title") })
                ),
                relatedIds = systemEquipment.map { it.optString("id") } + systemDiagnostics.map { it.optString("id") },
                searchText = (title + " " + systemEquipment.joinToString(" ") { it.optString("name") } + " " +
                    systemDiagnostics.joinToString(" ") { it.optString("title") }).lowercase()
            )
        }
    }

    private fun loadVl80sKnowledge(): List<TechnicalEntry> =
        KnowledgeRepository.allArticles.filter { article ->
            article.category.contains("ВЛ80", ignoreCase = true) ||
                article.tags.any { it.contains("ВЛ80", ignoreCase = true) }
        }.map { article ->
            TechnicalEntry(
                id = article.id,
                family = TechnicalFamily.VL80S,
                section = TechnicalSection.KNOWLEDGE,
                title = article.title,
                subtitle = article.summary,
                status = article.status,
                blocks = listOfNotEmpty(
                    block("Материал", article.body),
                    block("Темы", article.tags),
                    block("Источник", listOfNotNull(article.source.title, article.source.note))
                ),
                relatedIds = article.relatedArticleIds,
                searchText = listOf(article.title, article.summary, article.body.joinToString(" "), article.tags.joinToString(" "))
                    .joinToString(" ").lowercase()
            )
        }

    private fun requiredAcceptanceEntries(
        family: TechnicalFamily,
        prefix: String,
        source: String,
        requirements: List<Pair<String, String>>
    ): List<TechnicalEntry> = requirements.mapIndexed { index, (title, check) ->
        TechnicalEntry(
            id = "$prefix-${(index + 1).toString().padStart(2, '0')}",
            family = family,
            section = TechnicalSection.ACCEPTANCE,
            title = title,
            subtitle = check,
            status = "MANDATORY_CHECK",
            blocks = listOf(
                TechnicalBlock("Обязательная проверка", listOf(check)),
                TechnicalBlock("Источник", listOf(source))
            ),
            searchText = "$title $check $source обязательная приёмка".lowercase()
        )
    }

    private fun loadVl80sAcceptance(): List<TechnicalEntry> {
        val root = json("technical/vl80s_acceptance.json")
        val items = root.array("items").objects().map { item ->
            entry(
                item, TechnicalFamily.VL80S, TechnicalSection.ACCEPTANCE,
                title = item.optString("title"), subtitle = item.optString("check"), status = item.optString("phase"),
                blocks = listOfNotEmpty(
                    block("Проверка", item.optString("check")),
                    block("Нормальные признаки", item.array("normalSigns").strings()),
                    block("Возможные неисправности", item.array("possibleFaults").strings()),
                    block("Опасные признаки", item.array("dangerFlags").strings()),
                    block("Диагностические переходы", item.array("diagnosticHints").stringsOrSummaries()),
                    block("Граница действий", item.optString("actionBoundary")),
                    block("Условия", item.array("preconditions").strings()),
                    block("Применимость", item.obj("variantRule").summary()),
                    block("Источники", item.array("sourceRefs").stringsOrSummaries())
                ),
                relatedIds = buildList {
                    item.optString("equipmentId").takeIf(String::isNotBlank)?.let(::add)
                    addAll(item.array("relatedEquipmentIds").strings())
                    addAll(item.array("diagnosticHints").objects().mapNotNull { hint -> hint.optString("scenarioId").takeIf(String::isNotBlank) ?: hint.optString("id").takeIf(String::isNotBlank) })
                }.distinct()
            )
        }
        val requiredItems = requiredAcceptanceEntries(
            family = TechnicalFamily.VL80S,
            prefix = "VL80-REQ",
            source = "ВЛ80С. Руководство по эксплуатации: раздел IV «Техническое обслуживание ТО-1 электровоза локомотивными бригадами» и раздел «Приёмка электровоза в депо»",
            requirements = listOf(
                "Документы и передача замечаний" to "Проверить записи в журнале технического состояния, получить сведения о замечаниях и выполненных работах.",
                "Механическая часть" to "Осмотреть доступные узлы механической части в объёме ТО-1; выявленные неисправности зафиксировать установленным порядком.",
                "Крышевое оборудование и токоприёмник" to "Осмотреть с земли крышевое оборудование и проверить токоприёмник в установленном безопасном порядке.",
                "Тяговые двигатели и вспомогательные машины" to "Осмотреть доступное внешнее состояние тяговых двигателей и вспомогательных машин.",
                "Вентиляция и форкамеры" to "Проверить доступное состояние вентиляции, воздухозаборов и форкамер.",
                "Электрическое и пневматическое управление" to "Проверить аппаратуру управления и работу вспомогательных машин в предусмотренном руководством порядке.",
                "Освещение и сигнализация" to "Проверить освещение, звуковые и световые сигналы.",
                "Песок и пескоподача" to "Проверить наличие песка и работу устройств пескоподачи.",
                "Масло тягового трансформатора" to "Проверить уровень масла тягового трансформатора.",
                "Конденсат и утечки воздуха" to "Удалить конденсат из предусмотренных сборников и проверить отсутствие недопустимых утечек воздуха.",
                "Приборы и показания" to "Проверить предусмотренные контрольно-измерительные приборы и сигнализацию.",
                "Запас воды" to "Проверить предусмотренный запас воды.",
                "Инструмент, СИЗ и схемы" to "Проверить комплектность инструмента, защитных средств, противопожарного имущества и необходимых схем.",
                "Стеклоочистители" to "Проверить работу стеклоочистителей.",
                "Тормозное оборудование" to "Проверить тормозное оборудование по действующей инструкции по техническому обслуживанию тормозов."
            )
        )
        val requiredIds = verifiedRequiredAcceptanceIds(requiredItems, VL80S_REQUIRED_ACCEPTANCE_COUNT)
        val requiredRoute = TechnicalEntry(
            id = "VL80-ROUTE-required",
            family = TechnicalFamily.VL80S,
            section = TechnicalSection.ACCEPTANCE,
            title = "Обязательная приёмка",
            subtitle = "Базовый объём по руководству • ${requiredIds.size} пунктов",
            status = "ROUTE",
            blocks = listOf(
                TechnicalBlock(
                    "Основание",
                    listOf(
                        "Составлено по разделам приёмки в депо и ТО-1 руководства по эксплуатации ВЛ80С.",
                        "Фактический обязательный объём уточняется утверждённым перечнем депо и действующими местными инструкциями."
                    )
                )
            ),
            sequence = requiredIds,
            searchText = "обязательная приёмка ВЛ80С ТО-1".lowercase()
        )
        val routes = root.array("routes").objects().map { route ->
            val ids=route.array("itemIds").strings()
            val mode=when(route.optString("mode")){"step_by_step"->"пошагово";"checklist"->"контрольный список";"route"->"маршрут";"area"->"по зоне";else->"маршрут"}
            TechnicalEntry(
                id="VL80-ROUTE-${route.optString("id")}", family=TechnicalFamily.VL80S, section=TechnicalSection.ACCEPTANCE,
                title=when (route.optString("id")) {
                    "route_canonical" -> "Полный осмотр"
                    "route_from_outside" -> "Полный осмотр — начать снаружи"
                    "route_from_cab" -> "Полный осмотр — начать из кабины"
                    else -> route.optString("title")
                },
                subtitle="${requiredIds.size + ids.size} пунктов • $mode",
                status=if (route.optString("id") == "route_canonical") "ROUTE" else "ROUTE_VARIANT",
                blocks=listOf(
                    TechnicalBlock(
                        "Режим",
                        listOf(
                            "Последовательное прохождение пунктов приёмки с отметками «проверено» и «замечание».",
                            "Расширенный маршрут систематизирует карточки оборудования и не объявляет все пункты обязательными при каждой приёмке."
                        )
                    )
                ),
                sequence=fullAcceptanceSequence(requiredIds, ids), searchText=(route.optString("title")+" "+mode).lowercase()
            )
        }
        val effectiveRoutes=if(routes.isNotEmpty()) routes else listOf(TechnicalEntry(
            id="VL80-ROUTE-fallback", family=TechnicalFamily.VL80S, section=TechnicalSection.ACCEPTANCE,
            title="Полная приёмка", subtitle="${items.size} пунктов • пошагово", status="ROUTE", blocks=emptyList(),
            sequence=fullAcceptanceSequence(requiredIds, items.map(TechnicalEntry::id)), searchText="полная приёмка пошагово"
        ))
        return Vl80sAssistantAcceptance.entries() + listOf(requiredRoute) + effectiveRoutes + requiredItems + items
    }

    private fun loadVl80sElectrical(): List<TechnicalEntry> =
        json("technical/vl80s_electrical.json").array("baseSchemes").objects().map { item ->
            schemeEntry(item, TechnicalFamily.VL80S, TechnicalSection.ELECTRICAL, "semanticEdges")
        }

    private fun loadVl80sPneumatic(): List<TechnicalEntry> =
        json("technical/vl80s_pneumatic.json").array("views").objects().map { item ->
            schemeEntry(item, TechnicalFamily.VL80S, TechnicalSection.PNEUMATIC, "edges")
        }

    private fun loadVl80sDiagnostics(): List<TechnicalEntry> =
        json("technical/vl80s_diagnostics.json").array("scenarios").objects().map { item ->
            entry(
                item, TechnicalFamily.VL80S, TechnicalSection.DIAGNOSTICS,
                title = item.optString("title"),
                subtitle = item.optString("summary"),
                status = item.optString("severity"),
                blocks = listOfNotEmpty(
                    block("Симптом", item.optString("summary")),
                    block("Категория", item.optString("category")),
                    block("Связанное оборудование", item.array("equipmentIds").strings()),
                    block("Системы", item.array("systemIds").strings()),
                    block("Приёмка", item.obj("acceptance").summary()),
                    block("Электросхемы", item.obj("electricalSchemes").summary()),
                    block("Пневмосхемы", item.obj("pneumaticViews").summary()),
                    block("Применимость", item.array("applicableVariantIds").strings())
                ),
                relatedIds = buildList {
                    addAll(item.array("relatedScenarioIds").strings())
                    addAll(item.array("equipmentIds").strings())
                    addAll(item.obj("acceptance").allStrings())
                    addAll(item.obj("electricalSchemes").allStrings())
                    addAll(item.obj("pneumaticViews").allStrings())
                }.distinct()
            )
        }

    private fun loadErmakAcceptance(): List<TechnicalEntry> {
    val equipment = loadErmakEquipment()
    if (equipment.isEmpty()) return emptyList()

    val requiredItems = requiredAcceptanceEntries(
        family = TechnicalFamily.ERMAK,
        prefix = "ER-REQ",
        source = "ИДМБ.661142.009РЭ7 (3ТС.001.012РЭ7), раздел 3.10 «Техническое обслуживание ТО-1»",
        requirements = listOf(
            "Инструмент, СИЗ, огнетушители и схемы" to "Проверить наличие и исправность инструмента, принадлежностей, защитных средств, огнетушителей и схем электрических и пневматических цепей.",
            "Механическая часть" to "Осмотреть механическую часть: крепления, предохранительные устройства, подвешивание, буксы, колёсные пары и рычажную тормозную систему.",
            "Гребнесмазыватели" to "Выполнить обслуживание гребнесмазывателей по инструкции изготовителя.",
            "Течи масла и смазки" to "Убедиться в отсутствии течи масла демпферов и смазки из кожухов зубчатых передач.",
            "Тяговые двигатели и вспомогательные машины" to "Осмотреть внешнее состояние тяговых двигателей и вспомогательных машин, проверить отсутствие течи смазки.",
            "Система вентиляции" to "Осмотреть воздухозаборные жалюзи и парусиновые патрубки, убедиться в отсутствии повреждений.",
            "Масло тягового трансформатора" to "Проверить уровень масла в тяговом трансформаторе.",
            "Запас воды" to "Проверить уровень воды в баках умывальника и санузла.",
            "Песок и пескоподача" to "Проверить наличие песка в бункерах и работу устройств пескоподачи.",
            "Стеклоочистители" to "Проверить работу стеклоочистителей.",
            "Пневматические соединения" to "Проверить герметичность соединений трубопроводов пневматической системы.",
            "Удаление конденсата" to "Удалить конденсат из резервуаров, влагосборников и маслоотделителей.",
            "Крыша и токоприёмники" to "Осмотреть с земли крышевое оборудование и проверить токоприёмники в установленном безопасном порядке.",
            "Освещение и сигнализация" to "Проверить освещение, звуковые и световые сигналы.",
            "МСУД и силовые режимы" to "Проверить работу МСУД и сбор тяговой и тормозной схем в установленном руководством порядке.",
            "Приборы и индикаторы" to "Проверить контрольно-измерительные приборы, индикаторы и предусмотренную сигнализацию.",
            "Кассеты регистрации" to "Проверить наличие и установленное состояние кассет регистрации.",
            "Тормозное оборудование" to "Проверить тормозное оборудование по действующей инструкции по техническому обслуживанию тормозов."
        )
    )
    val requiredIds = verifiedRequiredAcceptanceIds(requiredItems, ERMAK_REQUIRED_ACCEPTANCE_COUNT)

    fun lines(entry: TechnicalEntry, title: String): List<String> =
        entry.blocks.firstOrNull { it.title.equals(title, ignoreCase = true) }?.lines.orEmpty()

    fun locationText(entry: TechnicalEntry): String =
        lines(entry, "Расположение").joinToString(" ").lowercase()

    val outsideKeywords = listOf(
        "наруж", "снаруж", "кры", "тележ", "подкуз", "под куз", "автосцеп",
        "букс", "колес", "рам", "торц", "лобов", "межсек"
    )
    val cabKeywords = listOf(
        "кабин", "пульт", "машинист", "помощник", "контроллер", "панел", "стол"
    )

    val outside = equipment.filter { item -> outsideKeywords.any { it in locationText(item) } }
    val cabin = equipment.filter { item -> cabKeywords.any { it in locationText(item) } }
    val middle = equipment.filterNot { it in outside || it in cabin }

    fun acceptanceId(source: TechnicalEntry) = "ER-ACC-${source.id.removePrefix("ER-EQ-")}"
    val acceptanceIdByEquipment = equipment.associate { it.id to acceptanceId(it) }

    val items = equipment.map { source ->
        val location = lines(source, "Расположение")
        val normal = lines(source, "Нормальное состояние")
        val deviations = lines(source, "Признаки отклонения")
        val applicability = lines(source, "Применимость")
        TechnicalEntry(
  id = acceptanceId(source),
  family = TechnicalFamily.ERMAK,
  section = TechnicalSection.ACCEPTANCE,
  title = source.title,
  subtitle = location.firstOrNull()?.let { "Расположение: $it" } ?: source.subtitle,
  status = "CHECK",
  blocks = buildList {
      add(
          TechnicalBlock(
              "Контрольный пункт",
              listOf(
                  "Зафиксируйте фактическое состояние узла. Нормы, допуски и порядок действий сверяйте с действующей эксплуатационной документацией."
              )
          )
      )
      if (location.isNotEmpty()) add(TechnicalBlock("Расположение", location))
      if (normal.isNotEmpty()) add(TechnicalBlock("Нормальное состояние", normal))
      if (deviations.isNotEmpty()) add(TechnicalBlock("Признаки отклонения", deviations))
      if (applicability.isNotEmpty()) add(TechnicalBlock("Применимость", applicability))
  },
  relatedIds = listOf(source.id),
  searchAliases = buildList {
      addAll(source.searchAliases)
      source.subtitle.takeIf(String::isNotBlank)?.let(::add)
      normal.firstOrNull()?.takeIf(String::isNotBlank)?.let(::add)
      deviations.firstOrNull()?.takeIf(String::isNotBlank)?.let(::add)
  }.distinct(),
  searchText = listOf(
      source.title,
      source.subtitle,
      location.joinToString(" "),
      normal.joinToString(" "),
      deviations.joinToString(" "),
      applicability.joinToString(" ")
  ).joinToString(" ").lowercase()
        )
    }

    fun routeSequence(order: List<TechnicalEntry>): List<String> =
        order.mapNotNull { acceptanceIdByEquipment[it.id] }.distinct()

    val fromOutside = routeSequence(outside + middle + cabin)
    val fromCab = routeSequence(cabin + middle.asReversed() + outside.asReversed())

    fun route(id: String, title: String, subtitle: String, sequence: List<String>, status: String = "ROUTE_VARIANT") = TechnicalEntry(
        id = id,
        family = TechnicalFamily.ERMAK,
        section = TechnicalSection.ACCEPTANCE,
        title = title,
        subtitle = "$subtitle • ${sequence.size} пунктов",
        status = status,
        blocks = listOf(
  TechnicalBlock(
      "Режим",
      listOf(
          "Расширенная часть чек-листа построена по расположению и карточкам оборудования Ермака, уже включённым в приложение.",
          "Она не является подтверждённой нормативной последовательностью ежедневной приёмки.",
          "Если узел отсутствует на конкретном исполнении, используйте статус «Не применяется».",
          "Чек-лист фиксирует результат осмотра и не заменяет действующую эксплуатационную документацию."
      )
  )
        ),
        sequence = sequence,
        searchText = "$title $subtitle приёмка ермак 2эс5к 3эс5к".lowercase()
    )

    val canonical = route(
        "ER-ROUTE-route_canonical",
        "Полный осмотр",
        "Расширенная проверка с выбором точки начала",
        fullAcceptanceSequence(requiredIds, fromOutside),
        status = "ROUTE"
    )
    val outsideRoute = route(
        "ER-ROUTE-route_from_outside",
        "Полный осмотр Ермак — начать снаружи",
        "От наружных зон к внутреннему оборудованию и кабине",
        fullAcceptanceSequence(requiredIds, fromOutside)
    )
    val cabRoute = route(
        "ER-ROUTE-route_from_cab",
        "Полный осмотр Ермак — начать из кабины",
        "От кабины к внутреннему оборудованию и наружным зонам",
        fullAcceptanceSequence(requiredIds, fromCab)
    )

    val requiredRoute = route(
        "ER-ROUTE-required",
        "Обязательная приёмка",
        "Базовый объём по руководству",
        requiredIds,
        status = "ROUTE"
    ).copy(
        blocks = listOf(
            TechnicalBlock(
                "Основание",
                listOf(
                    "Составлено по ИДМБ.661142.009РЭ7, раздел 3.10 «Техническое обслуживание ТО-1».",
                    "Механическую часть и тяговые двигатели осматривать при заторможенном электровозе и опущенном токоприёмнике.",
                    "Фактический обязательный объём уточняется действующими местными инструкциями и исполнением локомотива."
                )
            )
        )
    )

    return listOf(requiredRoute, canonical, outsideRoute, cabRoute) + requiredItems + items
}

    private fun loadErmakSystems(): List<TechnicalEntry> {
        val equipmentRecords = json("technical/ermak_equipment.json").array("records").objects()
        val equipmentBySystem = equipmentRecords
            .flatMap { equipment -> equipment.array("systemIds").strings().map { it to equipment } }
            .groupBy({ it.first }, { it.second })
        val knowledgeArticles = json("technical/ermak_knowledge.json").array("articles").objects()
        val articlesBySystem = knowledgeArticles
            .flatMap { article -> article.array("relatedSystems").strings().map { it to article } }
            .groupBy({ it.first }, { it.second })
        val systemArticleById = knowledgeArticles.mapNotNull { article ->
            article.optString("id")
                .takeIf { it.startsWith("ER-KB-SYS-") }
                ?.removePrefix("ER-KB-")
                ?.let { systemId -> systemId to article }
        }.toMap()

        return json("technical/ermak_system_map.json").array("systems").objects().map { item ->
            val systemId = item.optString("id")
            val equipment = equipmentBySystem[systemId].orEmpty()
            val systemArticle = systemArticleById[systemId]
            val supportingArticles = articlesBySystem[systemId].orEmpty()
                .filter { article ->
                    article.optString("articleType") != "equipment" &&
                        article.optString("id") != systemArticle?.optString("id")
                }
                .distinctBy { it.optString("id") }
            val summary = systemArticle?.optString("summary").orEmpty()
                .ifBlank { item.optString("purpose").ifBlank { item.optString("description") } }
            val relatedSystems = systemArticle?.array("relatedSystems")?.strings().orEmpty()
            val canonicalLinks = ermakSystemLinks(systemId)

            entry(
                item, TechnicalFamily.ERMAK, TechnicalSection.SYSTEMS,
                title = item.optString("name").ifBlank { item.optString("title") },
                subtitle = summary,
                status = item.optString("evidenceStatus").ifBlank { item.optString("status") },
                blocks = listOfNotEmpty(
                    block("Назначение", summary),
                    block("Как работает", systemArticle?.optString("principle").orEmpty()),
                    block("Нормальное состояние", systemArticle?.array("normalState")?.strings().orEmpty()),
                    block("Признаки отклонения", systemArticle?.array("deviationSigns")?.strings().orEmpty()),
                    block("Ключевые параметры", systemArticle?.array("keyParameters")?.stringsOrSummaries().orEmpty()),
                    block("Оборудование системы", equipment.mapNotNull { record ->
                        record.optString("name").takeIf(String::isNotBlank)
                    }),
                    block("Связанные системы", relatedSystems),
                    block("Эксплуатационные особенности", systemArticle?.array("variantRules")?.strings().orEmpty()),
                    block("Справочные материалы", supportingArticles.mapNotNull { article ->
                        article.optString("title").takeIf(String::isNotBlank)
                    }),
                    block("Диагностика", canonicalLinks.array("diagnosticIds").strings()),
                    block("Схемы", canonicalLinks.array("schemeIds").strings()),
                    block("Требует уточнения по исполнению", systemArticle?.array("openQuestions")?.strings().orEmpty())
                ),
                relatedIds = buildList {
                    addAll(equipment.mapNotNull { it.optString("id").takeIf(String::isNotBlank) })
                    systemArticle?.optString("id")?.takeIf(String::isNotBlank)?.let(::add)
                    addAll(supportingArticles.mapNotNull { it.optString("id").takeIf(String::isNotBlank) })
                    addAll(relatedSystems)
                    addAll(canonicalLinks.array("diagnosticIds").strings())
                    addAll(canonicalLinks.array("schemeIds").strings())
                    addAll(item.array("relations").objects().mapNotNull { it.optString("targetId").takeIf(String::isNotBlank) })
                }.distinct()
            )
        }
    }

    private fun loadErmakEquipment(): List<TechnicalEntry> {
        val records = json("technical/ermak_equipment.json").array("records").objects()
        val equipmentById = records.associateBy { it.optString("id") }
        val knowledgeByEquipment = json("technical/ermak_knowledge.json").array("articles").objects()
            .mapNotNull { article ->
                article.optString("equipmentId").takeIf(String::isNotBlank)?.let { it to article }
            }.toMap()

        return records.map { item ->
            val equipmentId = item.optString("id")
            val knowledge = knowledgeByEquipment[equipmentId]
            val canonicalLinks = ermakEquipmentLinks(equipmentId)
            val relationIds = item.array("relations").objects()
                .mapNotNull { relation -> relation.optString("targetId").takeIf(String::isNotBlank) }
                .distinct()
            val relatedEquipmentNames = relationIds.mapNotNull { targetId ->
                equipmentById[targetId]?.optString("name")?.takeIf(String::isNotBlank)
            }.distinct()

            entry(
                item, TechnicalFamily.ERMAK, TechnicalSection.EQUIPMENT,
                title = item.optString("name"),
                subtitle = item.optString("purpose"),
                status = item.optString("evidenceStatus"),
                blocks = listOfNotEmpty(
                    block("Назначение", item.optString("purpose")),
                    block("Как работает", knowledge?.optString("principle").orEmpty()),
                    block("Расположение", item.obj("location").locationSummary()),
                    block("Количество", item.optString("quantity")),
                    block("Обозначения", item.array("schemeDesignations").strings() + item.array("modelNames").strings()),
                    block("Параметры", item.array("parameters").stringsOrSummaries()),
                    block("Нормальное состояние", knowledge?.array("normalState")?.strings().orEmpty()),
                    block("Признаки отклонения", knowledge?.array("deviationSigns")?.strings().orEmpty()),
                    block("Связанное оборудование", relatedEquipmentNames),
                    block("Применимость", item.obj("applicability").summary()),
                    block("Системы", item.array("systemIds").strings()),
                    block("Статья", listOfNotNull(canonicalLinks.optString("equipmentArticleId").takeIf(String::isNotBlank))),
                    block("Диагностика", canonicalLinks.array("diagnosticIds").strings()),
                    block("Схемы", canonicalLinks.array("schemeIds").strings()),
                    block("Примечания", item.array("notes").stringsOrSummaries()),
                    block("Источники", item.array("sourceRefs").stringsOrSummaries())
                ),
                relatedIds = buildList {
                    addAll(relationIds)
                    addAll(item.array("systemIds").strings())
                    canonicalLinks.optString("equipmentArticleId").takeIf(String::isNotBlank)?.let(::add)
                    addAll(canonicalLinks.array("diagnosticIds").strings())
                    addAll(canonicalLinks.array("schemeIds").strings())
                    knowledge?.optString("id")?.takeIf(String::isNotBlank)?.let(::add)
                }.distinct()
            )
        }
    }

    private fun loadErmakKnowledge(): List<TechnicalEntry> =
        json("technical/ermak_knowledge.json").array("articles").objects().map { item ->
            val canonicalLinks = ermakKnowledgeLinks(item)
            entry(
                item, TechnicalFamily.ERMAK, TechnicalSection.KNOWLEDGE,
                title = item.optString("title"),
                subtitle = item.optString("summary"),
                status = item.optString("status"),
                blocks = listOfNotEmpty(
                    block("Кратко", item.optString("summary")),
                    block("Расположение", item.optString("locationScope")),
                    block("Принцип работы", item.optString("principle")),
                    block("Нормальное состояние", item.array("normalState").strings()),
                    block("Признаки отклонения", item.array("deviationSigns").strings()),
                    block("Параметры", item.array("keyParameters").stringsOrSummaries()),
                    block("Применимость", item.obj("scope").summary()),
                    block("Особенности исполнения", item.array("variantRules").strings()),
                    block("Диагностика", canonicalLinks.array("diagnosticIds").strings()),
                    block("Схемы", canonicalLinks.array("schemeIds").strings()),
                    block("Источники", item.array("sourceRefs").stringsOrSummaries())
                ),
                relatedIds = buildList {
                    addAll(item.array("equipmentRefs").strings())
                    addAll(item.array("relatedSystems").strings())
                    addAll(canonicalLinks.array("diagnosticIds").strings())
                    addAll(canonicalLinks.array("schemeIds").strings())
                    addAll(item.obj("futureLinks").allStrings())
                }.distinct()
            )
        }

    private fun loadErmakDiagnostics(): List<TechnicalEntry> =
        json("technical/ermak_diagnostics.json").array("scenarios").objects().map { item ->
            val projection = item.obj("vl80sUiProjection")
            entry(
                item, TechnicalFamily.ERMAK, TechnicalSection.DIAGNOSTICS,
                title = item.optString("title"),
                subtitle = item.optString("symptom"),
                status = item.optString("severity").ifBlank { item.optString("status") },
                blocks = listOfNotEmpty(
                    block("Симптом", item.optString("symptom")),
                    block("Немедленные действия", projection.array("immediateActions").strings()),
                    block("Опасные признаки", projection.array("dangerSigns").strings()),
                    block("Уточнения", projection.array("questions").stringsOrSummaries()),
                    block("Возможные причины", projection.array("probableCauses").strings()),
                    block("Безопасные проверки", projection.array("checks").stringsOrSummaries()),
                    block("Запрещено", projection.array("prohibited").strings()),
                    block("Условия прекращения", projection.array("stopConditions").strings()),
                    block("Что доложить", projection.array("reportFields").strings()),
                    block("Применимость", projection.optString("applicability")),
                    block("Источник", projection.optString("sourceNote"))
                ),
                relatedIds = buildList {
                    addAll(item.array("systemIds").strings())
                    addAll(item.array("equipmentRefs").strings())
                    addAll(item.array("knowledgeRefs").strings())
                    addAll(item.array("relatedScenarioIds").strings())
                }.distinct()
            )
        }

    private fun loadErmakSchemes(): List<TechnicalEntry> =
        json("technical/ermak_schemes.json").array("schemes").objects().map { item ->
            val type = item.optString("schemeType")
            val section = if (type.contains("pneumatic") || type.contains("brake")) {
                TechnicalSection.PNEUMATIC
            } else {
                TechnicalSection.ELECTRICAL
            }
            val hotspots = item.obj("layers").array("hotspotLayer").objects()
            val flow = item.obj("layers").array("flowLayer").objects()
            val sequence = if (flow.isNotEmpty()) {
                flow.mapNotNull { step ->
                    step.optString("to").takeIf(String::isNotBlank)
                        ?: step.optString("equipmentId").takeIf(String::isNotBlank)
                }
            } else hotspots.mapNotNull { it.optString("equipmentId").takeIf(String::isNotBlank) }
            val labels = hotspots.mapNotNull { hotspot ->
                hotspot.optString("equipmentId").takeIf(String::isNotBlank)?.let { id ->
                    id to hotspot.optString("label").ifBlank { hotspot.optString("title") }
                }
            }.toMap()
            val technicalHotspots = hotspots.mapNotNull { hotspot ->
                val equipmentId = hotspot.optString("equipmentId")
                val layout = hotspot.obj("layoutHint")
                if (equipmentId.isBlank() || !layout.has("x") || !layout.has("y")) null else TechnicalHotspot(
                    equipmentId = equipmentId,
                    label = hotspot.optString("label").ifBlank { equipmentId },
                    x = layout.optInt("x"),
                    y = layout.optInt("y"),
                    width = layout.optInt("width", 185),
                    height = layout.optInt("height", 78)
                )
            }
            entry(
                item, TechnicalFamily.ERMAK, section,
                title = item.optString("title"),
                subtitle = item.optString("representationMode").replace('_', ' '),
                status = item.optString("status"),
                blocks = listOfNotEmpty(
                    block("Тип", type),
                    block("Системы", item.array("systemIds").strings()),
                    block("Применимость", item.obj("variantRules").summary()),
                    block("Оборудование", item.array("equipmentRefs").strings()),
                    block("Источники", item.array("sourceRefs").stringsOrSummaries()),
                    block("Покрытие", item.obj("coverage").summary())
                ),
                relatedIds = item.array("equipmentRefs").strings(),
                sequence = sequence.distinct(),
                sequenceLabels = labels,
                hotspots = technicalHotspots
            )
        }


    private fun loadBrakeJamReference(family: TechnicalFamily): List<TechnicalEntry> {
        val id = if (family == TechnicalFamily.VL80S) "vl80-brake-jam" else "ER-KB-BRAKE-JAM"
        return listOf(
            TechnicalEntry(
                id = id,
                family = family,
                section = TechnicalSection.KNOWLEDGE,
                title = "Заклинивание тормоза вагона: порядок действий",
                subtitle = "Ручной отпуск, выключение неисправного тормоза и контроль фактического отпуска",
                status = "ATTENTION",
                blocks = listOf(
                    TechnicalBlock("Если отдельный вагон не отпустил", listOf(
                        "По указанию машиниста проверить фактический отпуск: выход штока тормозного цилиндра и отход тормозных колодок (накладок) от колес (дисков).",
                        "Если тормоз отдельного вагона не отпустил, выполнить ручной отпуск — выпустить воздух из запасного резервуара через выпускной клапан.",
                        "После восстановления зарядного давления выполнить установленную проверку торможения и отпуска и повторно убедиться в отпуске тормоза."
                    )),
                    TechnicalBlock("Если неисправность сохраняется и тормоз выключается", listOf(
                        "Перекрыть разобщительный кран на отводе тормозной магистрали к воздухораспределителю — неисправный тормоз вагона отключается от управления по тормозной магистрали.",
                        "Полностью выпустить воздух из запасного резервуара и камер воздухораспределителя через выпускной клапан.",
                        "Убедиться, что шток тормозного цилиндра вернулся и тормозные колодки (накладки) отошли от колес (дисков).",
                        "Если после полного выпуска воздуха колодки (накладки) не отошли, не считать тормоз отпущенным: требуется осмотр механической части тормоза и дальнейшее решение по установленному порядку."
                    )),
                    TechnicalBlock("После выключения тормоза", listOf(
                        "Доложить машинисту номер или место вагона, выполненные действия и результат проверки отпуска.",
                        "Учесть выключенный тормоз при определении фактического тормозного нажатия поезда и внести необходимые изменения в справку ВУ-45 в установленном порядке.",
                        "После возобновления движения проверить действие тормозов поезда в установленном порядке."
                    )),
                    TechnicalBlock("Безопасность при вмешательстве в тормозное оборудование", listOf(
                        "Если требуется техническое обслуживание или ремонт тормозного оборудования грузового вагона в составе поезда, работы выполняются только после перекрытия разобщительного крана и выпуска сжатого воздуха из запасного (рабочего) резервуара и тормозного цилиндра.",
                        "Не разбирать соединения и не ослаблять элементы пневматической системы, находящиеся под давлением."
                    )),
                    TechnicalBlock("Нормативная основа", listOf(
                        "Распоряжение ОАО «РЖД» от 12.12.2017 № 2580р (ред. от 18.02.2025): при отсутствии отпуска у отдельных вагонов предусмотрен выпуск воздуха из запасных резервуаров через выпускной клапан с последующей проверкой отпуска.",
                        "Правила технического обслуживания тормозного оборудования и управления тормозами железнодорожного подвижного состава, протокол Совета по железнодорожному транспорту от 6–7 мая 2014 г. № 60.",
                        "ПОТ РЖД-4100612-ЦДИ-128-2018, п. 2.2.4: требования безопасности при обслуживании и ремонте тормозного оборудования грузового вагона в составе поезда."
                    ))
                ),
                searchText = "заклинивание тормоза не отпустил вагон ручной отпуск воздухораспределитель выпускной клапан запасный резервуар разобщительный кран колодки тормозной цилиндр ву-45 тормозное нажатие".lowercase()
            )
        )
    }


    private fun loadWheelFlatReference(family: TechnicalFamily): List<TechnicalEntry> {
        val id = if (family == TechnicalFamily.VL80S) "vl80-wheel-flats" else "ER-KB-WHEEL-FLATS"
        return listOf(
            TechnicalEntry(
                id = id,
                family = family,
                section = TechnicalSection.KNOWLEDGE,
                title = "Ползуны колесных пар: скорость и действия",
                subtitle = "Порядок следования при обнаружении ползуна (выбоины) в пути",
                status = "INFORMATION",
                blocks = listOf(
                    TechnicalBlock("Важно", listOf(
                        "Таблица ниже относится к порядку следования после обнаружения ползуна в пути. Она не заменяет браковочные нормы для штатной эксплуатации колесных пар.",
                        "Размер ползуна в таблице — его глубина. Приоритет имеют действующие ПТЭ, распоряжения владельца инфраструктуры и указания ДСП/ДНЦ в конкретной ситуации."
                    )),
                    TechnicalBlock("Таблица — локомотив", listOf(
                        "> 1–2 мм¦Не более 15 км/ч¦До ближайшей железнодорожной станции; колесная пара должна быть заменена.",
                        "> 2–4 мм¦Не более 10 км/ч¦До ближайшей железнодорожной станции; колесная пара должна быть заменена.",
                        "> 4 мм¦Не более 10 км/ч¦До ближайшей станции только при вывешивании колесной пары или исключении возможности вращения колеса."
                    )),
                    TechnicalBlock("При ползуне более 4 мм на локомотиве", listOf(
                        "Локомотив должен быть отцеплен от поезда.",
                        "Тормозные цилиндры и тяговый электродвигатель (группа электродвигателей) поврежденной колесной пары должны быть отключены.",
                        "Осевой редуктор поврежденной колесной пары должен быть отключен."
                    )),
                    TechnicalBlock("Таблица — пассажирские и грузовые вагоны", listOf(
                        "> 1–2 мм¦Пассажирский: не более 100 км/ч; грузовой: не более 70 км/ч¦До ближайшего ПТО, имеющего средства для замены колесных пар.",
                        "> 2–6 мм¦Не более 15 км/ч¦До ближайшей железнодорожной станции; колесная пара должна быть заменена.",
                        "> 6–12 мм¦Не более 10 км/ч¦До ближайшей железнодорожной станции; колесная пара должна быть заменена.",
                        "> 12 мм¦Не более 10 км/ч¦До ближайшей станции при вывешивании колесной пары или исключении возможности вращения колеса."
                    )),
                    TechnicalBlock("Нормативная основа", listOf(
                        "Приказ Минтранса России от 23.06.2022 № 250 «Об утверждении Правил технической эксплуатации железных дорог Российской Федерации», пункт 155."
                    ))
                ),
                searchText = "ползун ползуны выбоина колесная пара колесные пары скорость 15 10 100 70 локомотив вагон птэ пункт 155".lowercase()
            )
        )
    }


    private fun loadSafety(family: TechnicalFamily): List<TechnicalEntry> {
        val familyKey = if (family == TechnicalFamily.VL80S) "VL80S" else "ERMAK"
        val prefix = "SAFETY-$familyKey"

        fun item(
            suffix: String,
            title: String,
            subtitle: String,
            status: String = "INFORMATION",
            blocks: List<TechnicalBlock>
        ): TechnicalEntry {
            val search = buildList {
                add(title)
                add(subtitle)
                blocks.forEach { block ->
                    add(block.title)
                    addAll(block.lines)
                }
            }.joinToString(" ").lowercase()
            return TechnicalEntry(
                id = "$prefix-$suffix",
                family = family,
                section = TechnicalSection.SAFETY,
                title = title,
                subtitle = subtitle,
                status = status,
                blocks = blocks,
                searchText = search
            )
        }

        return listOf(
            item(
                suffix = "FACTORS",
                title = "Опасные и вредные производственные факторы",
                subtitle = "Как классифицировать факторы и не путать справочник с оценкой конкретного рабочего места",
                blocks = listOf(
                    TechnicalBlock("Основные группы", listOf(
                        "Физические факторы",
                        "Химические факторы",
                        "Биологические факторы",
                        "Психофизиологические факторы"
                    )),
                    TechnicalBlock("Для железнодорожной среды", listOf(
                        "Движущийся подвижной состав, части машин и механизмов, зоны возможного перемещения оборудования",
                        "Электрическое напряжение и электрическая дуга",
                        "Шум, вибрация, неблагоприятный микроклимат и недостаточная освещённость",
                        "Пыль, аэрозоли, масла, топлива и другие химические вещества — при их фактическом наличии",
                        "Физические нагрузки, напряжённость внимания и нервно-эмоциональная нагрузка"
                    )),
                    TechnicalBlock("Важно", listOf(
                        "Фактический перечень опасностей определяется по конкретному рабочему месту, выполняемой операции и оценке профессиональных рисков работодателя."
                    )),
                    TechnicalBlock("Нормативная основа", listOf(
                        "ГОСТ 12.0.003-2015 — классификация опасных и вредных производственных факторов",
                        "Трудовой кодекс РФ, статья 214 — выявление опасностей и оценка профессиональных рисков"
                    ))
                )
            ),
            item(
                suffix = "RISK",
                title = "Выявление опасностей и оценка риска",
                subtitle = "Что проверить до начала действия",
                blocks = listOf(
                    TechnicalBlock("Перед началом работы", listOf(
                        "Уточнить выполняемую операцию, место и границы безопасного выполнения",
                        "Проверить, какие опасности относятся именно к этой операции и рабочему месту",
                        "Сверить требования технологической документации, местных инструкций и установленного порядка допуска",
                        "Не считать отсутствие видимой опасности подтверждением безопасности"
                    )),
                    TechnicalBlock("Если условия изменились", listOf(
                        "Повторно оценить ситуацию и действовать по установленному у работодателя порядку управления профессиональными рисками."
                    )),
                    TechnicalBlock("Нормативная основа", listOf(
                        "Трудовой кодекс РФ, статья 214 — систематическое выявление опасностей, анализ и оценка профессиональных рисков"
                    ))
                )
            ),
            item(
                suffix = "PROTECTION",
                title = "Меры защиты: технические, организационные и СИЗ",
                subtitle = "Защита должна соответствовать реальной опасности",
                blocks = listOf(
                    TechnicalBlock("Технические и коллективные меры", listOf(
                        "Использовать предусмотренные ограждения, блокировки, сигнализацию и средства коллективной защиты",
                        "Не обходить защитные устройства ради ускорения работы"
                    )),
                    TechnicalBlock("Организационные меры", listOf(
                        "Соблюдать установленную технологию, порядок допуска, ограждения и взаимодействия работников",
                        "Выполнять только те действия, на которые есть обучение, допуск и полномочия"
                    )),
                    TechnicalBlock("Средства индивидуальной защиты", listOf(
                        "Применять выданные СИЗ по назначению и в исправном состоянии",
                        "СИЗ дополняют, но не отменяют технические и организационные меры безопасности"
                    )),
                    TechnicalBlock("Нормативная основа", listOf(
                        "Трудовой кодекс РФ, статьи 214 и 215 — средства коллективной и индивидуальной защиты, обучение и соблюдение требований охраны труда"
                    ))
                )
            ),
            item(
                suffix = "ELECTRICAL",
                title = "Электробезопасность и границы допуска",
                subtitle = "Справочная информация не является разрешением на работу в электроустановке",
                status = "ATTENTION",
                blocks = listOf(
                    TechnicalBlock("Главное", listOf(
                        "Работы и обслуживание электроустановок выполняются только работниками, соответствующими установленным требованиям к обучению, группе и допуску",
                        "Организационные и технические меры безопасности определяются видом работы и действующими правилами",
                        "Наличие схемы или описания в приложении не даёт права открывать оборудование, выполнять переключения или работы под напряжением"
                    )),
                    TechnicalBlock("Для электровоза", listOf(
                        "Приоритет имеют действующая технологическая документация, инструкции по эксплуатации, местный порядок и указания ответственных работников."
                    )),
                    TechnicalBlock("Нормативная основа", listOf(
                        "Приказ Минтруда России №903н — Правила по охране труда при эксплуатации электроустановок"
                    ))
                )
            ),
            item(
                suffix = "ROLLING-STOCK",
                title = "Безопасность рядом с подвижным составом и на путях",
                subtitle = "Риски движения, маневров и технического обслуживания",
                status = "ATTENTION",
                blocks = listOf(
                    TechnicalBlock("Основной принцип", listOf(
                        "Перемещение по производственной территории, осмотр и обслуживание подвижного состава выполняются по установленным безопасным маршрутам и технологическому порядку",
                        "Маневровая работа, ограждение и закрепление подвижного состава выполняются по установленным правилам с учётом местных условий"
                    )),
                    TechnicalBlock("При эксплуатации локомотива", listOf(
                        "В пути следования требования безопасности определяются технологической документацией и руководствами (инструкциями) по эксплуатации."
                    )),
                    TechnicalBlock("Нормативная основа", listOf(
                        "Приказ Минтруда России №860н — Правила по охране труда при эксплуатации подвижного состава железнодорожного транспорта"
                    ))
                )
            ),
            item(
                suffix = "STOP",
                title = "Когда работу нужно прекратить и сообщить",
                subtitle = "Неисправность, нарушение технологии или угроза жизни и здоровью",
                status = "STOP_AND_REPORT",
                blocks = listOf(
                    TechnicalBlock("Неисправность или нарушение технологии", listOf(
                        "Сообщить непосредственному руководителю о выявленной неисправности оборудования или инструмента, нарушении применяемой технологии и приостановить работу до устранения."
                    )),
                    TechnicalBlock("Угроза жизни и здоровью", listOf(
                        "Немедленно известить непосредственного или вышестоящего руководителя о известной ситуации, угрожающей жизни и здоровью людей."
                    )),
                    TechnicalBlock("Также сообщается", listOf(
                        "О нарушениях требований охраны труда, известных несчастных случаях и ухудшении состояния здоровья, включая признаки профессионального заболевания или острого отравления."
                    )),
                    TechnicalBlock("Нормативная основа", listOf(
                        "Трудовой кодекс РФ, статья 215 — обязанности работника в области охраны труда"
                    ))
                )
            ),
            item(
                suffix = "TRAINING",
                title = "Обучение, инструктаж и первая помощь",
                subtitle = "Допуск к работе начинается не с приложения, а с установленного обучения",
                blocks = listOf(
                    TechnicalBlock("Работник", listOf(
                        "Проходит установленное обучение по охране труда и безопасным методам работы",
                        "Проходит обучение по оказанию первой помощи и применению СИЗ в предусмотренных случаях",
                        "Проходит инструктаж, стажировку и проверку знаний, если это требуется для выполняемой работы"
                    )),
                    TechnicalBlock("Работодатель", listOf(
                        "Не допускает к исполнению обязанностей работников, не прошедших обязательное обучение, инструктаж, проверку знаний и предусмотренные медицинские осмотры."
                    )),
                    TechnicalBlock("Нормативная основа", listOf(
                        "Трудовой кодекс РФ, статьи 214 и 215"
                    ))
                )
            )
        )
    }

    private fun schemeEntry(
        item: JSONObject,
        family: TechnicalFamily,
        section: TechnicalSection,
        edgeKey: String
    ): TechnicalEntry {
        val nodes = item.array("nodes").objects()
        val sequence = nodes.mapNotNull { node ->
            node.optString("equipmentId").takeIf(String::isNotBlank)
                ?: node.optString("virtualNodeId").takeIf(String::isNotBlank)
        }
        val sequenceLabels = nodes.mapNotNull { node ->
            val id = node.optString("equipmentId").ifBlank { node.optString("virtualNodeId") }
            id.takeIf(String::isNotBlank)?.let { it to node.optString("label").ifBlank { it } }
        }.toMap()
        return entry(
            item, family, section,
            title = item.optString("title"),
            subtitle = item.optString("scopeNote"),
            status = item.optString("status"),
            blocks = listOfNotEmpty(
                block("Область", item.optString("scopeNote")),
                block("Профили", item.array("profiles").strings()),
                block("Системы", item.array("systems").strings()),
                block("Узлы", nodes.map { node ->
                    listOf(node.optString("label"), node.optString("equipmentId").ifBlank { node.optString("virtualNodeId") })
                        .filter(String::isNotBlank).joinToString(" — ")
                }),
                block("Связи", item.array(edgeKey).stringsOrSummaries()),
                block("Источники", item.array("sourceRefs").stringsOrSummaries())
            ),
            relatedIds = sequence,
            sequence = sequence,
            sequenceLabels = sequenceLabels
        )
    }

    private fun entry(
        source: JSONObject,
        family: TechnicalFamily,
        section: TechnicalSection,
        title: String,
        subtitle: String,
        status: String,
        blocks: List<TechnicalBlock>,
        relatedIds: List<String> = emptyList(),
        sequence: List<String> = emptyList(),
        sequenceLabels: Map<String, String> = emptyMap(),
        hotspots: List<TechnicalHotspot> = emptyList()
    ): TechnicalEntry {
        val id = source.optString("id")
        val aliases = source.array("aliases").strings()
        val search = buildList {
            add(title); add(subtitle); addAll(aliases)
            blocks.forEach { block ->
                add(block.title)
                addAll(block.lines.flatMap { it.split(" • ") }
                    .mapNotNull(::technicalPresentationLine))
            }
        }.joinToString(" ").lowercase()
        return TechnicalEntry(
            id = id,
            family = family,
            section = section,
            title = title.ifBlank { id },
            subtitle = subtitle,
            status = status,
            blocks = blocks,
            relatedIds = relatedIds.filter(String::isNotBlank).distinct(),
            sequence = sequence.filter(String::isNotBlank).distinct(),
            sequenceLabels = sequenceLabels.filterKeys(String::isNotBlank),
            hotspots = hotspots.filter { it.equipmentId.isNotBlank() && it.width > 0 && it.height > 0 },
            searchText = search,
            searchAliases = aliases
        )
    }
}

private fun JSONObject.array(key: String): JSONArray = optJSONArray(key) ?: JSONArray()
private fun JSONObject.obj(key: String): JSONObject = optJSONObject(key) ?: JSONObject()

private fun JSONObject.locationSummary(): String = buildList {
    optString("sectionScope").takeIf(String::isNotBlank)?.let(::technicalLocationLabel)?.let(::add)
    array("sectionKinds").strings().mapNotNull(::technicalLocationLabel).forEach(::add)
    optString("zone").takeIf(String::isNotBlank)?.let(::technicalLocationLabel)?.let(::add)
}.distinct().joinToString(" • ")

private fun JSONArray.objects(): List<JSONObject> = buildList {
    for (index in 0 until length()) optJSONObject(index)?.let(::add)
}

private fun JSONArray.strings(): List<String> = buildList {
    for (index in 0 until length()) {
        val value = opt(index)
        if (value is String && value.isNotBlank()) add(value)
    }
}

private fun JSONArray.stringsOrSummaries(): List<String> = buildList {
    for (index in 0 until length()) {
        when (val value = opt(index)) {
            is String -> if (value.isNotBlank()) add(value)
            is JSONObject -> value.summary().takeIf(String::isNotBlank)?.let(::add)
        }
    }
}

private fun JSONObject.summary(): String = buildList {
    val preferred = listOf(
        "title", "name", "summary", "text", "prompt", "action", "expected", "ifAbnormal",
        "value", "unit", "type", "relationType", "kind", "targetId", "sourceId", "locator",
        "zone", "sectionScope", "confidence", "models", "profiles", "includeProfiles", "excludeProfiles"
    )
    preferred.forEach { key ->
        when (val value = opt(key)) {
            is String -> if (value.isNotBlank()) add(value)
            is Number, is Boolean -> add(value.toString())
            is JSONArray -> addAll(value.strings())
        }
    }
    if (isEmpty()) addAll(allStrings().take(8))
}.distinct().joinToString(" • ")

private fun JSONObject.allStrings(): List<String> = buildList {
    val keys = keys()
    while (keys.hasNext()) {
        when (val value = opt(keys.next())) {
            is String -> if (value.isNotBlank()) add(value)
            is JSONArray -> {
                addAll(value.strings())
                value.objects().forEach { addAll(it.allStrings()) }
            }
            is JSONObject -> addAll(value.allStrings())
        }
    }
}

private fun block(title: String, line: String): TechnicalBlock? =
    line.takeIf(String::isNotBlank)?.let { TechnicalBlock(title, listOf(it)) }

private fun block(title: String, lines: List<String>): TechnicalBlock? =
    lines.filter(String::isNotBlank).distinct().takeIf { it.isNotEmpty() }?.let { TechnicalBlock(title, it) }

private fun listOfNotEmpty(vararg blocks: TechnicalBlock?): List<TechnicalBlock> = blocks.filterNotNull()

internal fun isInternalTechnicalReference(value: String): Boolean =
    UserFacingTechnicalPolicy.isInternalReference(value)

internal fun userFacingTechnicalText(value: String): String = value
    .replace(Regex("variant-profile", RegexOption.IGNORE_CASE), "профиль исполнения")
    .replace(Regex("ER-EQ/KB\\s+карточки", RegexOption.IGNORE_CASE), "карточки оборудования и справочные материалы")
    .replace(Regex("ER-EQ/KB", RegexOption.IGNORE_CASE), "карточки оборудования и справочные материалы")
    .replace(Regex("\\b(?:VL80|VL|ER)-[A-Z0-9][A-Z0-9_-]*\\b", RegexOption.IGNORE_CASE), "")
    .replace(Regex("\\s{2,}"), " ")
    .replace(Regex("(?:\\s*•\\s*){2,}"), " • ")
    .trim(' ', '•')
