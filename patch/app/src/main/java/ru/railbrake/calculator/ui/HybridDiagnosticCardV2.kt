package ru.railbrake.calculator.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.railbrake.calculator.core.DiagnosticResponse
import ru.railbrake.calculator.core.DiagnosticScenario
import ru.railbrake.calculator.core.FrameworkDiagnosticModule
import ru.railbrake.calculator.core.FrameworkDirection
import ru.railbrake.calculator.core.FrameworkKnowledgeEntry
import ru.railbrake.calculator.core.ReferenceKnowledgeEntry
import ru.railbrake.calculator.core.ReferenceKnowledgePack
import ru.railbrake.calculator.core.ReferenceKnowledgePackRepository
import ru.railbrake.calculator.core.KnowledgeClassification
import ru.railbrake.calculator.data.KnowledgeDepth
import ru.railbrake.calculator.data.KnowledgeMode
import ru.railbrake.calculator.data.ExtendedEmergencyModeRepository

/** Presentation over one immutable knowledge module and the existing answer trail. */
@Composable
internal fun HybridDiagnosticCardV2(
    module: FrameworkDiagnosticModule,
    scenario: DiagnosticScenario?,
    locomotiveTitle: String,
    variantTitle: String,
    variantId: String,
    systemTitles: List<String>,
    mode: KnowledgeMode,
    depth: KnowledgeDepth,
    answerTrail: List<Pair<String, DiagnosticResponse>>,
    onOpenEquipment: (String) -> Unit
) {
    var study by rememberSaveable(module.scenarioId) { mutableStateOf(false) }
    val answerByNode = answerTrail.toMap()
    val directions = module.orderedDirections(answerTrail)
    val context = LocalContext.current
    val referencePack = remember(context, module.scenarioId, module.referencePackAsset) {
        ReferenceKnowledgePackRepository.load(context, module)
    }
    val emergencyEnabled = ExtendedEmergencyModeRepository(context).isEnabled()
    val applicableVariant = variantId.takeIf { it in module.variantIds } ?: module.variantIds.first()
    val nextDirection = directions.firstOrNull { it.questionKey !in answerByNode }?.id

    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(scenario?.title ?: module.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Локомотив: $locomotiveTitle")
            Text("Исполнение: $variantTitle")
            Text("Система: ${systemTitles.joinToString().ifBlank { scenario?.category ?: "Система не уточнена" }}")
            Text("Реакция: ${scenario?.severity?.title ?: module.reactionStatus}")
            Text("Что произошло: ${module.symptom}")
            Text("Начните с вопроса ниже. Если признак неизвестен, выберите «Не знаю».",
                style = MaterialTheme.typography.bodySmall)
        }
    }

    if (mode == KnowledgeMode.BASIC) {
        if (referencePack != null) ReferenceKnowledgeSection(referencePack, module, mode, depth, emergencyEnabled,
            applicableVariant, study = false, nextDirection, onOpenEquipment)
        if (depth == KnowledgeDepth.MINIMAL && answerTrail.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Направления проверки", fontWeight = FontWeight.Bold)
                    directions.forEach { direction ->
                        Text("• ${direction.title}${answerByNode[direction.questionKey]?.let { " — ответ: ${it.title.lowercase()}" }.orEmpty()}")
                    }
                    Text("Ответы лишь уточняют поиск; причина не установлена.", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (depth != KnowledgeDepth.MINIMAL && answerTrail.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("Направление проверки", fontWeight = FontWeight.Bold)
                    directions.firstOrNull { it.questionKey !in answerByNode }?.let { direction ->
                        Text(direction.title)
                        if (depth == KnowledgeDepth.DETAILED) Text(direction.explanation)
                    } ?: Text("Различающие вопросы пройдены. Сверьте результаты с разрешёнными проверками ниже.")
                    Text("Другие направления остаются доступными; ответ сам по себе не подтверждает причину.",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        return
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(selected = !study, onClick = { study = false }, label = { Text("Диагностика") })
        FilterChip(selected = study, onClick = { study = true }, label = { Text("Изучение") })
    }

    if (referencePack == null && study && depth == KnowledgeDepth.DETAILED) KnowledgeLayers(module, showExperience = true, onOpenEquipment = onOpenEquipment)
    DirectionMap(directions, module, answerByNode, depth, onOpenEquipment)
    if (referencePack != null) ReferenceKnowledgeSection(referencePack, module, mode, depth, emergencyEnabled,
        applicableVariant, study, nextDirection, onOpenEquipment)
    else {
        if (study && depth != KnowledgeDepth.DETAILED) KnowledgeLayers(module, showExperience = depth == KnowledgeDepth.STANDARD, onOpenEquipment = onOpenEquipment)
        if (!study && depth == KnowledgeDepth.DETAILED) KnowledgeLayers(module, showExperience = false, onOpenEquipment = onOpenEquipment)
    }
}

@Composable
private fun ReferenceKnowledgeSection(pack: ReferenceKnowledgePack, module: FrameworkDiagnosticModule,
    mode: KnowledgeMode, depth: KnowledgeDepth, emergencyEnabled: Boolean, variantId: String,
    study: Boolean, nextDirectionId: String?, onOpenEquipment: (String) -> Unit) {
    val entries = pack.orderedVisible(mode, depth, emergencyEnabled, variantId, study, nextDirectionId)
    if (entries.isEmpty()) return
    Text(if (study) "Изучение системы" else "Материалы по проверке",
        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    entries.forEach { entry -> ReferenceKnowledgeCard(entry, pack, module, depth, onOpenEquipment) }
}

private fun ReferenceKnowledgeEntry.classTitle(): String = when (classification) {
    "NORMATIVE" -> "Правила безопасности"
    "TECHNICAL_REFERENCE" -> "Устройство"
    "OPERATIONAL_EXPERIENCE" -> "Опыт эксплуатации"
    "MAINTENANCE_PRACTICE" -> "Техническое обслуживание"
    "HISTORICAL" -> "История"
    "ARCHIVE" -> "Архив"
    "CASE_STUDY" -> "Разбор случая"
    "FIELD_PRACTICE" -> "Полевая практика · отдельное ограничение"
    "UNSAFE_METHOD" -> "Нештатный метод · не применять как рекомендацию"
    "UNVERIFIED" -> "Неподтверждённые сведения"
    else -> "Справочный материал"
}

private fun ReferenceKnowledgeEntry.confidenceTitle(): String = when (confidence) {
    "VERIFIED" -> "Подтверждено источниками"
    "SUPPORTED" -> "Подкреплено источниками"
    "REPORTED" -> "Сообщается; нужна проверка"
    else -> "Не установлено"
}

@Composable
private fun ReferenceKnowledgeCard(entry: ReferenceKnowledgeEntry, pack: ReferenceKnowledgePack,
    module: FrameworkDiagnosticModule, depth: KnowledgeDepth, onOpenEquipment: (String) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(entry.classTitle(), color = if (entry.applicationStatus == "RESTRICTED")
                MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
            Text(entry.title, fontWeight = FontWeight.Bold)
            Text(entry.summary)
            if (depth != KnowledgeDepth.MINIMAL) {
                Text("Достоверность: ${entry.confidenceTitle()}", style = MaterialTheme.typography.bodySmall)
                if (entry.applicability.variantCheckRequired) Text("Применимость к исполнению уточняется по схеме конкретной машины.",
                    style = MaterialTheme.typography.bodySmall)
                if (entry.applicationStatus in setOf("EXPERIENCE", "PRACTICE", "RESTRICTED"))
                    Text("Справочный опыт; не является обязательным действием или допуском к работам.",
                        fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
            }
            if (depth == KnowledgeDepth.DETAILED) {
                entry.details.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
                entry.limitations.forEach { Text("Ограничение: $it", style = MaterialTheme.typography.bodySmall) }
                entry.qualityControl.conflictGroupIds.mapNotNull(pack.conflicts::get).forEach { conflict ->
                    Text("Требует уточнения: ${conflict.title}. ${conflict.note}",
                        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (entry.qualityControl.requiresHumanReview)
                    Text("Требует дополнительной проверки специалистом.", style = MaterialTheme.typography.bodySmall)
                entry.sourceRefs.mapNotNull(pack.sources::get).forEach { source ->
                    Text("Источник: ${source.title} · категория ${source.authority}",
                        style = MaterialTheme.typography.bodySmall)
                }
                entry.relations.componentIds.mapNotNull(module.components::get).forEach { component ->
                    TextButton(onClick = { onOpenEquipment(component.id) }) { Text("Атлас: ${component.title}") }
                }
            }
        }
    }
}

@Composable
private fun DirectionMap(
    directions: List<FrameworkDirection>,
    module: FrameworkDiagnosticModule,
    answerByNode: Map<String, DiagnosticResponse>,
    depth: KnowledgeDepth,
    onOpenEquipment: (String) -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Карта направлений поиска", fontWeight = FontWeight.Bold)
            directions.forEach { direction ->
                val response = answerByNode[direction.questionKey]
                Text("${direction.title}: ${response?.let { "наблюдение — ${it.title.lowercase()}" } ?: "ещё не проверено"}")
                if (depth != KnowledgeDepth.MINIMAL) {
                    Text(direction.explanation, style = MaterialTheme.typography.bodySmall)
                }
                if (depth == KnowledgeDepth.DETAILED) {
                    direction.componentIds.mapNotNull(module.components::get).forEach { component ->
                        TextButton(onClick = { onOpenEquipment(component.id) }) { Text("Элемент Атласа: ${component.title}") }
                    }
                }
            }
            Text("Порядок меняется по ответам. Непроверенные направления не исчезают; причина не считается установленной.",
                style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun KnowledgeLayers(module: FrameworkDiagnosticModule, showExperience: Boolean, onOpenEquipment: (String) -> Unit) {
    module.knowledge.filter { showExperience || it.classification != KnowledgeClassification.OPERATIONAL_EXPERIENCE }
        .forEach { entry -> KnowledgeLayer(entry, module, onOpenEquipment) }
}

@Composable
private fun KnowledgeLayer(entry: FrameworkKnowledgeEntry, module: FrameworkDiagnosticModule, onOpenEquipment: (String) -> Unit) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(entry.classification.title, style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
            Text(entry.title, fontWeight = FontWeight.Bold)
            Text(entry.body)
            entry.componentIds.mapNotNull(module.components::get).forEach { component ->
                TextButton(onClick = { onOpenEquipment(component.id) }) { Text("${component.title}: ${component.purpose}") }
            }
            Text("Проверка: ${when (entry.quality.name) {
                "CONFIRMED" -> "подтверждено"
                "REQUIRES_VARIANT_CHECK" -> "сверить исполнение"
                else -> "справочно"
            }}", style = MaterialTheme.typography.bodySmall)
            entry.sourceIds.mapNotNull(module.sources::get).forEach { source ->
                Text("Источник: ${source.title}${source.version.takeIf(String::isNotBlank)?.let { ", $it" }.orEmpty()}",
                    style = MaterialTheme.typography.bodySmall)
            }
            if (entry.classification == KnowledgeClassification.OPERATIONAL_EXPERIENCE) {
                Text("Опыт эксплуатации не является обязательным действием или разрешением на работу.",
                    fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
