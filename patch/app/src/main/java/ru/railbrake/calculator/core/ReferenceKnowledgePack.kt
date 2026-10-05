package ru.railbrake.calculator.core

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import ru.railbrake.calculator.data.KnowledgeDepth
import ru.railbrake.calculator.data.KnowledgeMode

data class ReferenceSource(val id: String, val authority: String, val type: String, val title: String, val url: String)
data class ReferenceConflict(val id: String, val title: String, val status: String, val note: String)
data class ReferenceVisibility(
    val base: Boolean, val extended: Boolean, val emergencyRequired: Boolean,
    val diagnosis: Boolean, val study: Boolean, val minDepth: KnowledgeDepth
)
data class ReferenceApplicability(
    val profiles: Set<String>, val variants: Set<String>, val variantCheckRequired: Boolean,
    val conditions: List<String>
)
data class ReferenceRelations(
    val componentIds: Set<String>, val directionIds: Set<String>, val relatedEntryIds: Set<String>
)
data class ReferenceQualityControl(
    val reviewStatus: String, val requiresHumanReview: Boolean, val interpretationNotes: String,
    val conflictGroupIds: Set<String>, val humanFactorRisk: String
)
data class ReferenceKnowledgeEntry(
    val id: String, val title: String, val classification: String, val statementType: String,
    val confidence: String, val applicationStatus: String, val knowledgeLayerDepth: Int,
    val visibility: ReferenceVisibility, val applicability: ReferenceApplicability,
    val summary: String, val details: List<String>, val limitations: List<String>,
    val sourceRefs: Set<String>, val relations: ReferenceRelations,
    val qualityControl: ReferenceQualityControl, val restrictedProcedureIncluded: Boolean?
) {
    fun visible(mode: KnowledgeMode, depth: KnowledgeDepth, emergencyEnabled: Boolean,
        profileId: String, variantId: String, study: Boolean): Boolean {
        if (profileId !in applicability.profiles || variantId !in applicability.variants) return false
        if (depth.ordinal < visibility.minDepth.ordinal) return false
        if (visibility.emergencyRequired) {
            // A separate safety gate never turns restricted content into an instruction.
            return mode == KnowledgeMode.ADVANCED && emergencyEnabled && study &&
                applicationStatus == "RESTRICTED" && restrictedProcedureIncluded == false
        }
        if (mode == KnowledgeMode.BASIC && !visibility.base ||
            mode == KnowledgeMode.ADVANCED && !visibility.extended) return false
        return if (study) visibility.study else visibility.diagnosis
    }
}

data class ReferenceKnowledgePack(
    val scenarioId: String, val profileId: String, val variantIds: Set<String>,
    val productionStatus: String, val sources: Map<String, ReferenceSource>,
    val conflicts: Map<String, ReferenceConflict>, val entries: List<ReferenceKnowledgeEntry>
) {
    fun orderedVisible(mode: KnowledgeMode, depth: KnowledgeDepth, emergencyEnabled: Boolean,
        variantId: String, study: Boolean, nextDirectionId: String?): List<ReferenceKnowledgeEntry> =
        entries.filter { it.visible(mode, depth, emergencyEnabled, profileId, variantId, study) }
            .sortedWith(compareBy<ReferenceKnowledgeEntry> {
                if (nextDirectionId != null && nextDirectionId in it.relations.directionIds) 0 else 1
            }.thenByDescending { it.knowledgeLayerDepth })
}

/** The pack is an overlay of linked knowledge; its IDs never become user-facing labels. */
object ReferenceKnowledgePackRepository {
    fun load(context: Context, module: FrameworkDiagnosticModule): ReferenceKnowledgePack? {
        val asset = module.referencePackAsset ?: return null
        return parse(TechnicalAssetReader.json(context.applicationContext, asset), module)
    }

    internal fun parse(root: JSONObject, module: FrameworkDiagnosticModule): ReferenceKnowledgePack {
        require(root.getString("schemaVersion") == "knowledge-reference-pack/1")
        val scenarioId = root.getString("scenarioId")
        val profileId = root.getString("profileId")
        val variants = root.getJSONArray("variantIds").strings().toSet()
        require(scenarioId == module.scenarioId && profileId == module.profileId && module.variantIds.containsAll(variants))
        val sources = root.getJSONArray("sourceRegistry").objects().associate { source ->
            val id = source.getString("id")
            id to ReferenceSource(id, source.getString("authority"), source.getString("type"),
                source.getString("title"), source.getString("url"))
        }
        val conflicts = root.getJSONArray("conflictGroups").objects().associate { conflict ->
            val id = conflict.getString("id")
            id to ReferenceConflict(id, conflict.getString("title"), conflict.getString("status"), conflict.getString("note"))
        }
        val entries = root.getJSONArray("entries").objects().map { raw ->
            val visibility = raw.getJSONObject("visibility")
            val applicability = raw.getJSONObject("applicability")
            val relations = raw.getJSONObject("relations")
            val qc = raw.getJSONObject("qualityControl")
            ReferenceKnowledgeEntry(raw.getString("id"), raw.getString("title"), raw.getString("classification"),
                raw.getString("statementType"), raw.getString("confidence"), raw.getString("applicationStatus"),
                raw.getInt("knowledgeLayerDepth"),
                ReferenceVisibility(visibility.getBoolean("base"), visibility.getBoolean("extended"),
                    visibility.getBoolean("extendedEmergencyRequired"), visibility.getBoolean("diagnosis"),
                    visibility.getBoolean("study"), KnowledgeDepth.valueOf(visibility.getString("minDepth"))),
                ReferenceApplicability(applicability.getJSONArray("profiles").strings().toSet(),
                    applicability.getJSONArray("variants").strings().toSet(),
                    applicability.getBoolean("variantCheckRequired"), applicability.getJSONArray("conditions").strings()),
                raw.getString("summary"), raw.getJSONArray("details").strings(),
                raw.getJSONArray("limitations").strings(), raw.getJSONArray("sourceRefs").strings().toSet(),
                ReferenceRelations(relations.getJSONArray("componentIds").strings().toSet(),
                    relations.getJSONArray("directionIds").strings().toSet(),
                    relations.getJSONArray("relatedEntryIds").strings().toSet()),
                ReferenceQualityControl(qc.getString("reviewStatus"), qc.getBoolean("requiresHumanReview"),
                    qc.getString("interpretationNotes"), qc.getJSONArray("conflictGroupIds").strings().toSet(),
                    qc.getString("humanFactorRisk")),
                raw.optJSONObject("restrictedProcedure")?.getBoolean("procedureIncluded"))
        }
        val expectations = root.getJSONObject("acceptanceExpectations")
        require(entries.size == expectations.getInt("actualEntryCount") &&
            entries.size >= expectations.getInt("minimumEntryCount") &&
            entries.map { it.id }.toSet().size == entries.size)
        require(sources.size == root.getJSONArray("sourceRegistry").length() &&
            conflicts.size == root.getJSONArray("conflictGroups").length())
        val entryIds = entries.mapTo(hashSetOf()) { it.id }
        val directionIds = module.directions.mapTo(hashSetOf()) { it.id }
        entries.forEach { entry ->
            require(entry.sourceRefs.isNotEmpty() && sources.keys.containsAll(entry.sourceRefs))
            require(module.components.keys.containsAll(entry.relations.componentIds) &&
                directionIds.containsAll(entry.relations.directionIds) &&
                entryIds.containsAll(entry.relations.relatedEntryIds) &&
                conflicts.keys.containsAll(entry.qualityControl.conflictGroupIds))
            require(profileId in entry.applicability.profiles && variants.containsAll(entry.applicability.variants))
            if (entry.classification in setOf("FIELD_PRACTICE", "UNSAFE_METHOD")) require(
                entry.applicationStatus == "RESTRICTED" && entry.visibility.emergencyRequired &&
                    entry.restrictedProcedureIncluded == false)
        }
        return ReferenceKnowledgePack(scenarioId, profileId, variants, root.getString("productionStatus"),
            sources, conflicts, entries)
    }

    private fun JSONArray.objects(): List<JSONObject> = (0 until length()).map(::getJSONObject)
    private fun JSONArray.strings(): List<String> = (0 until length()).map(::getString)
}
