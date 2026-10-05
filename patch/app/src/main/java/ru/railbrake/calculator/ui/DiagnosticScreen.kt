package ru.railbrake.calculator.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.railbrake.calculator.core.DiagnosticActionLevel
import ru.railbrake.calculator.core.DiagnosticCheck
import ru.railbrake.calculator.core.DiagnosticRepository
import ru.railbrake.calculator.core.DiagnosticFrameworkV2
import ru.railbrake.calculator.core.FrameworkDiagnosticNode
import ru.railbrake.calculator.core.FrameworkDiagnosticModule
import ru.railbrake.calculator.core.DiagnosticResponse
import ru.railbrake.calculator.core.DiagnosticScenario
import ru.railbrake.calculator.core.DiagnosticSeverity
import ru.railbrake.calculator.core.EquipmentReference
import ru.railbrake.calculator.core.ExamQuestion
import ru.railbrake.calculator.core.ExamQuestionRepository
import ru.railbrake.calculator.core.LocomotiveProfiles
import ru.railbrake.calculator.core.TechnicalDataRepository
import ru.railbrake.calculator.core.Vl80sObservationCatalog
import ru.railbrake.calculator.core.Vl80sNormalValues
import ru.railbrake.calculator.data.DiagnosticSessionRecord
import ru.railbrake.calculator.data.DiagnosticSessionRepository
import ru.railbrake.calculator.data.LocomotiveProfileRepository
import ru.railbrake.calculator.data.KnowledgeDisplayRepository
import ru.railbrake.calculator.data.KnowledgeDepth
import ru.railbrake.calculator.data.SecretAccessRepository
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private data class QuickRouteItem(
    val title: String,
    val subtitle: String,
    val scenarioId: String
)

private val quickRouteItems = listOf(
    QuickRouteItem("Токоприёмник / ГВ", "Не поднимается токоприёмник, не включается или отключается ГВ.", "gv-no-close"),
    QuickRouteItem("Тяга и ЭКГ", "Нет тяги, не набираются позиции, различается ток секций или групп.", "traction-no-assemble"),
    QuickRouteItem("Вспомогательные машины", "Не запускаются фазорасщепитель, вентиляторы или компрессор.", "aux-machines"),
    QuickRouteItem("Масляный насос трансформатора", "Не запускается, отключается или не подтверждается работа маслонасоса трансформатора.", "oil-pump-failure"),
    QuickRouteItem("Тормоза и давление", "Падает ТМ, не отпускает тормоз, не набирается давление ГР.", "brake-pipe-leak"),
    QuickRouteItem("Безопасность движения", "АЛСН/ЭПК, внезапное торможение, срабатывание контроля бдительности.", "alsn-epk"),
    QuickRouteItem("Нагрев, дым, запах", "Признаки пожара, пробоя или опасного нагрева оборудования.", "smoke-fire-flashover")
)

@Composable
fun DiagnosticScreen(initialScenarioId: String? = null, initialEquipmentId: String? = null,
    onOpenAtlasEquipment: (String) -> Unit) {
    var selectedId by rememberSaveable(initialScenarioId) { mutableStateOf(initialScenarioId) }
    var selectedEquipmentId by rememberSaveable(initialEquipmentId) { mutableStateOf(initialEquipmentId) }
    val selected = DiagnosticRepository.scenarios.firstOrNull { it.id == selectedId }
    val context = LocalContext.current
    val frameworkModules = remember(context) { DiagnosticFrameworkV2.load(context) }
    val standalone = frameworkModules.firstOrNull { it.scenarioId == selectedId && selected == null }
    val selectedEquipment = Vl80sObservationCatalog.equipment(selectedEquipmentId.orEmpty())

    BackHandler(enabled = selected != null || standalone != null || selectedEquipment != null) {
        if (selectedEquipment != null) selectedEquipmentId = null else selectedId = null
    }

    when {
        selectedEquipment != null -> EquipmentDetails(
            equipment = selectedEquipment,
            onBack = { selectedEquipmentId = null },
            onOpenScenario = { scenarioId ->
                selectedEquipmentId = null
                selectedId = scenarioId
            }
        )
        standalone != null -> FrameworkStandaloneDetails(standalone, onBack = { selectedId = null },
            onOpenAtlasEquipment = onOpenAtlasEquipment)
        selected == null -> DiagnosticCatalog(
            onOpen = { selectedId = it.id },
            onOpenEquipment = { selectedEquipmentId = it.id },
            standaloneModules = frameworkModules.filter {
                it.profileId == "vl80s" && DiagnosticRepository.scenario(it.scenarioId) == null
            },
            onOpenFramework = { selectedId = it }
        )
        else -> DiagnosticDetails(
            scenario = selected,
            onBack = { selectedId = null },
            onOpenRelated = { selectedId = it },
            onOpenEquipment = { selectedEquipmentId = it },
            onOpenAtlasEquipment = onOpenAtlasEquipment
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DiagnosticCatalog(
    onOpen: (DiagnosticScenario) -> Unit,
    onOpenEquipment: (EquipmentReference) -> Unit,
    standaloneModules: List<FrameworkDiagnosticModule>,
    onOpenFramework: (String) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var category by rememberSaveable { mutableStateOf("Все") }
    var catalogMode by rememberSaveable { mutableStateOf("scenarios") }
    val context = LocalContext.current
    val profileRepository = remember { LocomotiveProfileRepository(context) }
    val sessionRepository = remember { DiagnosticSessionRepository(context) }
    var selectedVariantId by rememberSaveable { mutableStateOf(profileRepository.selectedVariantId()) }
    var historyVersion by remember { mutableStateOf(0) }
    val sessions = remember(historyVersion) { sessionRepository.loadForProfile(DiagnosticSessionRepository.PROFILE_VL80S) }
    val results = remember(query, category, selectedVariantId) {
        DiagnosticRepository.search(query, category).filter { scenario ->
            LocomotiveProfiles.appliesToVariant(selectedVariantId, scenario.applicableVariantIds)
        }
    }
    val observationResults = remember(query, selectedVariantId) {
        val (observations, equipment) = Vl80sObservationCatalog.search(query)
        observations.filter { observation ->
            observation.scenarioIds.any { scenarioId ->
                DiagnosticRepository.scenario(scenarioId)?.let { scenario ->
                    LocomotiveProfiles.appliesToVariant(selectedVariantId, scenario.applicableVariantIds)
                } == true
            }
        } to equipment
    }
    val quickResults = quickRouteItems.filter { item ->
        val scenario = DiagnosticRepository.scenario(item.scenarioId)
        val matchesVariant = scenario?.let { candidate ->
            LocomotiveProfiles.appliesToVariant(selectedVariantId, candidate.applicableVariantIds)
        } == true
        val matchesQuery = query.isBlank() || listOf(
            item.title,
            item.subtitle,
            scenario?.title.orEmpty(),
            scenario?.summary.orEmpty()
        ).any { value -> value.contains(query, ignoreCase = true) }
        matchesVariant && matchesQuery
    }
    val standaloneResults = standaloneModules.filter { module ->
        (category == "Все" || query.isNotBlank()) &&
            (query.isBlank() || listOf(module.title, module.profileTitle, module.symptom)
                .any { it.contains(query, ignoreCase = true) })
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            RailSectionHeader("Диагностика ВЛ80С")
        }
        item { DiagnosticSafetyNotice() }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(
                    selected = catalogMode == "scenarios",
                    onClick = { catalogMode = "scenarios" },
                    label = { Text("Неисправность") }
                )
                FilterChip(
                    selected = catalogMode == "observations",
                    onClick = { catalogMode = "observations" },
                    label = { Text("Что я вижу?") }
                )
                FilterChip(
                    selected = catalogMode == "quick",
                    onClick = { catalogMode = "quick" },
                    label = { Text("В пути") }
                )
                FilterChip(catalogMode == "history", { catalogMode = "history" }, label = { Text("Журнал") })
            }
        }
        if (catalogMode == "scenarios" || catalogMode == "observations" || catalogMode == "quick") item {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                label = {
                    Text(
                        when (catalogMode) {
                            "scenarios" -> "Симптом или аппарат"
                            "observations" -> "Лампа, прибор, звук или аппарат"
                            else -> "Поиск во вкладке «В пути»"
                        }
                    )
                },
                placeholder = {
                    Text(
                        when (catalogMode) {
                            "scenarios" -> "Например: ЭКГ, №395, БУРТ, АЛСН"
                            "observations" -> "Например: выбило ГВ, ТМ падает, стук, боксование"
                            else -> "Например: маслонасос, тормоза, дым"
                        }
                    )
                },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        if (catalogMode == "scenarios") item {
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                LazyRow(
                    modifier = Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    items(DiagnosticRepository.categories) { item ->
                        FilterChip(
                            selected = category == item,
                            onClick = { category = item },
                            label = { Text(item) }
                        )
                    }
                }
                Text("→", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            }
        }
        if (catalogMode == "scenarios" && standaloneResults.isNotEmpty()) {
            item { Text("Модули базы знаний", style = MaterialTheme.typography.titleLarge) }
            items(standaloneResults, key = { "framework-${it.scenarioId}" }) { module ->
                Card(onClick = { onOpenFramework(module.scenarioId) }, modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(module.profileTitle, color = MaterialTheme.colorScheme.primary)
                        Text(module.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(module.symptom, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        if (catalogMode == "history") {
            item {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text("Локальный журнал", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                    if (sessions.isNotEmpty()) TextButton(onClick = { sessionRepository.clearProfile(DiagnosticSessionRepository.PROFILE_VL80S); historyVersion++ }) { Text("Очистить") }
                }
            }
            if (sessions.isEmpty()) item { InfoCard("Пока пусто", listOf("Сохранённые результаты диагностики появятся здесь и останутся на устройстве."), MaterialTheme.colorScheme.surfaceVariant) }
            items(sessions, key = { it.timestampMillis }) { session ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(session.scenarioTitle, fontWeight = FontWeight.Black)
                        Text(SimpleDateFormat("dd.MM.yyyy HH:mm", Locale.getDefault()).format(Date(session.timestampMillis)), color = MaterialTheme.colorScheme.primary)
                        Text(session.severity)
                        Text(session.report, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        } else if (catalogMode == "quick") {
            item {
                BorderedCautionCard(
                    "Быстрая оценка",
                    listOf(
                        "Выберите наблюдаемое явление. Здесь только безопасное первичное направление; оно не заменяет действующие инструкции.",
                        "При дыме, огне, дуге, повреждении контактной сети, опасном нагреве или неясной высоковольтной защите прекратите диагностические действия и доложите."
                    )
                )
            }
            if (quickResults.isEmpty()) {
                item {
                    InfoCard(
                        "Ничего не найдено",
                        listOf("Измените запрос или очистите строку поиска."),
                        MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }
            items(quickResults, key = { it.scenarioId }) { item ->
                val scenario = DiagnosticRepository.scenario(item.scenarioId)
                Card(
                    onClick = { scenario?.let(onOpen) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(item.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
                        Text(item.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Открыть безопасный маршрут →", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else if (catalogMode == "observations") {
            if (observationResults.first.isEmpty() && observationResults.second.isEmpty()) {
                item {
                    InfoCard(
                        title = "Ничего не найдено",
                        lines = listOf("Попробуйте разговорную формулировку: «главник», «ТМ падает», «шипит», «бокс» или «дым"),
                        tone = MaterialTheme.colorScheme.surfaceVariant
                    )
                }
            }
            items(observationResults.first, key = { "observation-${it.id}" }) { observation ->
                val scenario = observation.scenarioIds
                    .mapNotNull(DiagnosticRepository::scenario)
                    .firstOrNull { candidate ->
                        LocomotiveProfiles.appliesToVariant(selectedVariantId, candidate.applicableVariantIds)
                    }
                Card(
                    onClick = { scenario?.let(onOpen) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(observation.kind.title, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Text(observation.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
                        Text(observation.description)
                        val relatedEquipmentTitles = observation.equipmentIds
                            .mapNotNull { id -> Vl80sObservationCatalog.equipment(id)?.title }
                            .distinct()
                        if (relatedEquipmentTitles.isNotEmpty()) {
                            Text("Связано: ${relatedEquipmentTitles.joinToString()}", style = MaterialTheme.typography.bodySmall)
                        }
                        Text("Открыть безопасный алгоритм →", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                }
            }
            items(observationResults.second, key = { "equipment-${it.id}" }) { equipment ->
                Card(
                    onClick = { onOpenEquipment(equipment) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("Аппарат", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Text(equipment.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
                        Text(equipment.purpose)
                        Text("Где находится: ${equipment.location}", style = MaterialTheme.typography.bodySmall)
                        Text("Открыть аппарат →", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else if (results.isEmpty() && standaloneResults.isEmpty()) {
            item {
                InfoCard(
                    title = "Ничего не найдено",
                    lines = listOf("Попробуйте название аппарата, общий симптом или выберите категорию «Все»."),
                    tone = MaterialTheme.colorScheme.surfaceVariant
                )
            }
        } else {
            items(results, key = { it.id }) { scenario ->
                Card(
                    onClick = { onOpen(scenario) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text(scenario.category, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                        Text(scenario.severity.title, style = MaterialTheme.typography.labelMedium, fontWeight = FontWeight.Bold)
                        Text(scenario.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
                        Text(scenario.summary, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text("Связано: ${scenario.relatedEquipment.joinToString()}", style = MaterialTheme.typography.bodySmall)
                        Text("Открыть алгоритм →", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
internal fun FrameworkStandaloneDetails(
    module: FrameworkDiagnosticModule,
    onBack: () -> Unit,
    onOpenAtlasEquipment: (String) -> Unit,
    initialVariantId: String? = null
) {
    val context = LocalContext.current
    val settings = remember(context) { KnowledgeDisplayRepository(context) }
    val storedVariantId = initialVariantId ?: remember(context) { LocomotiveProfileRepository(context).selectedVariantId() }
    var selectedVariantId by rememberSaveable(module.scenarioId) {
        mutableStateOf(storedVariantId.takeIf(module.variantIds::contains)
            ?: module.variantIds.singleOrNull())
    }
    val variantTitle = selectedVariantId?.let(module.variantTitles::get) ?: "Исполнение требуется уточнить"
    var currentKey by rememberSaveable(module.scenarioId) { mutableStateOf<String?>(module.startNodeId) }
    var trail by rememberSaveable(module.scenarioId) { mutableStateOf(emptyList<String>()) }
    val answers = trail.mapNotNull { record ->
        val key = record.substringBefore('\t')
        val response = runCatching { DiagnosticResponse.valueOf(record.substringAfter('\t')) }.getOrNull()
        response?.let { key to it }
    }
    val answerTexts = answers.map { (key, response) ->
        "${module.nodes.getValue(key).question} — ${response.title}. ${module.answerMeaning(key, response)}"
    }
    LazyColumn(modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item { TextButton(onClick = onBack) { Text("← Все неисправности") } }
        if (module.variantIds.size > 1) item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Выберите исполнение по данным своей машины")
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(module.variantIds.toList()) { id ->
                        FilterChip(selected = id == selectedVariantId,
                            onClick = { selectedVariantId = id; trail = emptyList(); currentKey = module.startNodeId },
                            label = { Text(module.variantTitles.getValue(id)) })
                    }
                }
            }
        }
        item {
            HybridDiagnosticCardV2(module = module, scenario = null,
                locomotiveTitle = module.profileTitle, variantTitle = variantTitle,
                variantId = selectedVariantId.orEmpty(),
                systemTitles = module.systemIds.mapNotNull(module.systemTitles::get),
                mode = settings.mode(), depth = settings.depth(), answerTrail = answers,
                onOpenEquipment = onOpenAtlasEquipment)
        }
        item { DiagnosticSafetyNotice() }
        item { InfoCard("Сначала", module.safetyActions, MaterialTheme.colorScheme.primaryContainer) }
        if (selectedVariantId == null) item {
            InfoCard("Исполнение не выбрано", listOf("Уточните исполнение, прежде чем переходить к диагностическим вопросам."),
                MaterialTheme.colorScheme.errorContainer)
        } else item {
            TriageCard(scenario = null, frameworkNode = currentKey?.let(module.nodes::get),
                currentQuestionKey = currentKey, answers = answerTexts,
                onAnswer = { response ->
                    val key = requireNotNull(currentKey)
                    trail = trail + "$key\t${response.name}"
                    currentKey = module.nextNode(key, response)?.id
                },
                onReset = { trail = emptyList(); currentKey = module.startNodeId },
                onBackOne = {
                    currentKey = trail.lastOrNull()?.substringBefore('\t') ?: module.startNodeId
                    trail = trail.dropLast(1)
                })
        }
        answers.lastOrNull()?.let { (key, response) ->
            item { InfoCard("Оценка по ответу", listOf(module.answerMeaning(key, response)),
                MaterialTheme.colorScheme.primaryContainer) }
        }
        item { InfoCard("Прекратить диагностику", module.stopConditions, MaterialTheme.colorScheme.errorContainer) }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
private fun DiagnosticDetails(
    scenario: DiagnosticScenario,
    onBack: () -> Unit,
    onOpenRelated: (String) -> Unit,
    onOpenEquipment: (String) -> Unit,
    onOpenAtlasEquipment: (String) -> Unit
) {
    var currentQuestionKey by rememberSaveable(scenario.id) {
        mutableStateOf(scenario.questions.firstOrNull()?.key)
    }
    var answers by rememberSaveable(scenario.id) { mutableStateOf(emptyList<String>()) }
    var sectionNote by rememberSaveable(scenario.id) { mutableStateOf("") }
    var modeNote by rememberSaveable(scenario.id) { mutableStateOf("") }
    var instrumentNote by rememberSaveable(scenario.id) { mutableStateOf("") }
    var feedbackNote by rememberSaveable(scenario.id) { mutableStateOf("") }
    var candidateScores by remember(scenario.id) { mutableStateOf(emptyMap<String, Int>()) }
    var currentAssessment by rememberSaveable(scenario.id) { mutableStateOf("") }
    var ordinaryRouteStopped by rememberSaveable(scenario.id) { mutableStateOf(false) }
    var answerTrail by rememberSaveable(scenario.id) { mutableStateOf(emptyList<String>()) }
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val sessionRepository = remember { DiagnosticSessionRepository(context) }
    val examQuestionRepository = remember { ExamQuestionRepository(context) }
    val examQuestionsUnlocked = remember { SecretAccessRepository(context).isUnlocked() }
    val relatedQuestions = remember(scenario.id) {
        if (examQuestionsUnlocked) examQuestionRepository.questions.filter { scenario.id in it.diagnosticScenarioIds } else emptyList()
    }
    val profileRepository = remember { LocomotiveProfileRepository(context) }
    val profileId = profileRepository.selectedProfileId()
    val variantId = profileRepository.selectedVariantId()
    val selectedVariant = LocomotiveProfiles.variant(variantId)
    val frameworkModule = remember(context, scenario.id) {
        DiagnosticFrameworkV2.load(context).firstOrNull { it.scenarioId == scenario.id }
    }?.takeIf { it.profileId == profileId &&
        LocomotiveProfiles.appliesToVariant(variantId, it.variantIds) }
    LaunchedEffect(frameworkModule?.startNodeId) {
        if (answerTrail.isEmpty() && frameworkModule != null) currentQuestionKey = frameworkModule.startNodeId
    }
    val knowledgeSettings = remember(context) { KnowledgeDisplayRepository(context) }
    val knowledgeMode = knowledgeSettings.mode()
    val knowledgeDepth = knowledgeSettings.depth()
    var showAdditionalDetails by rememberSaveable(scenario.id) { mutableStateOf(false) }
    val compactCard = frameworkModule != null && knowledgeDepth == KnowledgeDepth.MINIMAL && !showAdditionalDetails
    val scenarioMatchesVariant = LocomotiveProfiles.appliesToVariant(variantId, scenario.applicableVariantIds)
    val relatedScenarioIds = scenario.relatedScenarioIds.filter { relatedId ->
        DiagnosticRepository.scenario(relatedId)?.let { relatedScenario ->
            LocomotiveProfiles.appliesToVariant(variantId, relatedScenario.applicableVariantIds)
        } == true
    }
    var savedLocally by rememberSaveable(scenario.id) { mutableStateOf(false) }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            TextButton(onClick = onBack) { Text("← Все неисправности") }
            if (frameworkModule == null) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    RailStatusPill(scenario.category)
                    SeverityLabel(scenario.severity)
                }
                Text(scenario.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
                Text(scenario.summary, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    "Профиль: ${selectedVariant?.title ?: "ВЛ80С — проверка исполнения обязательна"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (!scenarioMatchesVariant) {
                Text(
                    "Этот сценарий не помечен применимым к выбранному исполнению. Используйте его только как указатель и сверяйте схему конкретной секции.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    fontWeight = FontWeight.Bold
                )
            }
        }
        if (frameworkModule != null) item {
            HybridDiagnosticCardV2(
                module = frameworkModule,
                scenario = scenario,
                locomotiveTitle = LocomotiveProfiles.profile(frameworkModule.profileId)?.title ?: "Локомотив не указан",
                variantTitle = selectedVariant?.title ?: "Исполнение не уточнено",
                variantId = variantId,
                systemTitles = frameworkModule.systemIds.mapNotNull { systemId ->
                    TechnicalDataRepository(context).entry(systemId)?.title
                },
                mode = knowledgeMode,
                depth = knowledgeDepth,
                answerTrail = answerTrail.mapNotNull { record ->
                    val response = runCatching { DiagnosticResponse.valueOf(record.substringAfter('\t')) }.getOrNull()
                    response?.let { record.substringBefore('\t') to it }
                },
                onOpenEquipment = onOpenAtlasEquipment
            )
        }
        item { DiagnosticSafetyNotice() }
        item { InfoCard("Сначала", scenario.immediateActions, MaterialTheme.colorScheme.primaryContainer) }
        item { InfoCard("Опасные признаки", scenario.dangerSigns, MaterialTheme.colorScheme.errorContainer) }
        item {
            TriageCard(
                scenario = scenario,
                frameworkNode = currentQuestionKey?.let { frameworkModule?.nodes?.get(it) },
                currentQuestionKey = currentQuestionKey,
                answers = answers,
                onAnswer = { response ->
                    val nodeKey = requireNotNull(currentQuestionKey)
                    val question = scenario.questions.firstOrNull { it.key == nodeKey }
                    val meaning = frameworkModule?.answerMeaning(nodeKey, response)
                        ?: DiagnosticRepository.meaning(requireNotNull(question), response)
                    currentAssessment = meaning
                    val legacyNext = question?.let { when (response) {
                        DiagnosticResponse.YES -> it.yesNextKey
                        DiagnosticResponse.NO -> it.noNextKey
                        DiagnosticResponse.UNKNOWN -> it.unknownNextKey
                    } }
                    val nextKey = if (frameworkModule != null) frameworkModule.nextNode(nodeKey, response)?.id
                        else DiagnosticRepository.nextQuestion(scenario, nodeKey, response)?.key
                    ordinaryRouteStopped = (legacyNext == DiagnosticRepository.END_OF_FLOW ||
                        frameworkModule != null && nextKey == null) &&
                        (response == DiagnosticResponse.UNKNOWN || scenario.questions.lastOrNull()?.key != nodeKey)
                    val questionText = frameworkModule?.nodes?.get(nodeKey)?.question ?: requireNotNull(question).text
                    answers = answers + "$questionText — ${response.title}. $meaning"
                    answerTrail = answerTrail + "$nodeKey\t${response.name}"
                    candidateScores = candidateScores.toMutableMap().also { scores ->
                        question?.let { DiagnosticRepository.candidateCauseIds(it, response) }.orEmpty().forEach { causeId ->
                            scores[causeId] = (scores[causeId] ?: 0) + 1
                        }
                    }
                    currentQuestionKey = nextKey
                },
                onReset = {
                    answers = emptyList()
                    answerTrail = emptyList()
                    candidateScores = emptyMap()
                    currentAssessment = ""
                    ordinaryRouteStopped = false
                    currentQuestionKey = frameworkModule?.startNodeId ?: scenario.questions.firstOrNull()?.key
                },
                onBackOne = {
                    val removed = answerTrail.lastOrNull()
                    if (removed != null) {
                        val removedKey = removed.substringBefore('\t')
                        answerTrail = answerTrail.dropLast(1)
                        answers = answers.dropLast(1)
                        currentQuestionKey = removedKey
                        ordinaryRouteStopped = false
                        candidateScores = buildMap {
                            answerTrail.forEach { record ->
                                val key = record.substringBefore('\t')
                                val response = runCatching {
                                    DiagnosticResponse.valueOf(record.substringAfter('\t'))
                                }.getOrNull() ?: return@forEach
                                val priorQuestion = scenario.questions.firstOrNull { it.key == key } ?: return@forEach
                                DiagnosticRepository.candidateCauseIds(priorQuestion, response).forEach { causeId ->
                                    put(causeId, (get(causeId) ?: 0) + 1)
                                }
                            }
                        }
                        currentAssessment = answerTrail.lastOrNull()?.let { record ->
                            val key = record.substringBefore('\t')
                            val response = runCatching {
                                DiagnosticResponse.valueOf(record.substringAfter('\t'))
                            }.getOrNull()
                            val priorQuestion = scenario.questions.firstOrNull { it.key == key }
                            if (response != null) {
                                frameworkModule?.answerMeaning(key, response)
                                    ?: priorQuestion?.let { DiagnosticRepository.meaning(it, response) }.orEmpty()
                            } else ""
                        }.orEmpty()
                    }
                }
            )
        }
        if (scenario.observableSigns.isNotEmpty() && !compactCard) {
            item { InfoCard("Что наблюдать", scenario.observableSigns, MaterialTheme.colorScheme.surfaceVariant) }
        }
        if (currentAssessment.isNotBlank()) {
            val nextQuestionText = currentQuestionKey?.let { frameworkModule?.nodes?.get(it)?.question }
                ?: scenario.questions.firstOrNull { it.key == currentQuestionKey }?.text
            item {
                InfoCard(
                    "Текущая оценка по ответам",
                    buildList {
                        add(currentAssessment)
                        if (nextQuestionText != null) add("Следующее уточнение: $nextQuestionText")
                        else if (ordinaryRouteStopped) add("Доступных различающих вопросов больше нет. Помощь продолжается ниже: сохранены возможные причины, разрешённые проверки, ограничения и данные для доклада; неподтверждённый вариант не считать установленным.")
                        else add("Вопросы этого маршрута пройдены. Сопоставьте вывод с признаками, проверками и условиями прекращения диагностики ниже.")
                    },
                    MaterialTheme.colorScheme.primaryContainer
                )
            }
        }
        if (frameworkModule != null && knowledgeDepth == KnowledgeDepth.MINIMAL) item {
            TextButton(onClick = { showAdditionalDetails = !showAdditionalDetails }) {
                Text(if (showAdditionalDetails) "Свернуть подробные проверки" else "Показать подробные проверки и материалы")
            }
        }
        if (scenario.systemExplanation.isNotEmpty() && frameworkModule == null) {
            item { InfoCard("Как связана система", scenario.systemExplanation, MaterialTheme.colorScheme.secondaryContainer) }
        }
        val leadingCauses = scenario.diagnosticCauses
            .filter { (candidateScores[it.id] ?: 0) > 0 }
            .sortedByDescending { candidateScores[it.id] ?: 0 }
        if (leadingCauses.isNotEmpty() && !compactCard) {
            item {
                InfoCard(
                    "Наиболее подходящие ветви по ответам",
                    leadingCauses.take(3).map { "${it.title}: ${it.explanation}" },
                    MaterialTheme.colorScheme.tertiaryContainer
                )
            }
        }
        if (!compactCard && (frameworkModule == null || answerTrail.isNotEmpty())) {
            item { InfoCard("Вероятные причины — гипотезы, не вывод", scenario.probableCauses, MaterialTheme.colorScheme.surfaceVariant) }
        }
        if (scenario.operationalConsequences.isNotEmpty() && !compactCard) {
            item { InfoCard("К чему может привести", scenario.operationalConsequences, MaterialTheme.colorScheme.errorContainer) }
        }
        if (!compactCard) item {
            Text("Проверки по уровню допуска", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            Text(
                "Уровень указан для каждой проверки. Он не расширяет допуск конкретного работника.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!compactCard) items(scenario.checks) { check -> DiagnosticCheckCard(check) }
        if (scenario.trainingNotes.isNotEmpty() && frameworkModule == null) {
            item { InfoCard("Почему алгоритм спрашивает именно это", scenario.trainingNotes, MaterialTheme.colorScheme.tertiaryContainer) }
        }
        item { InfoCard("Запрещено", scenario.prohibited, MaterialTheme.colorScheme.errorContainer) }
        item { InfoCard("Прекратить диагностику", scenario.stopConditions, MaterialTheme.colorScheme.errorContainer) }
        if (!compactCard) item {
            SessionJournal(
                sectionNote = sectionNote,
                onSectionNote = { sectionNote = it },
                modeNote = modeNote,
                onModeNote = { modeNote = it },
                instrumentNote = instrumentNote,
                onInstrumentNote = { instrumentNote = it },
                feedbackNote = feedbackNote,
                onFeedbackNote = { feedbackNote = it },
                prompts = scenario.feedbackPrompts
            )
        }
        if (!compactCard) item {
            val sessionLines = listOfNotNull(
                sectionNote.takeIf { it.isNotBlank() }?.let { "Секция/место: $it" },
                modeNote.takeIf { it.isNotBlank() }?.let { "Режим: $it" },
                instrumentNote.takeIf { it.isNotBlank() }?.let { "Приборы и индикация: $it" },
                feedbackNote.takeIf { it.isNotBlank() }?.let { "Что изменилось: $it" }
            )
            val report = buildDiagnosticReport(scenario, answers, sessionLines)
            Card(
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
                shape = RoundedCornerShape(20.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Доклад", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
                    Text("Запишите: ${scenario.reportFields.joinToString()}")
                    if (answers.isNotEmpty()) {
                        HorizontalDivider()
                        answers.forEach { Text("• $it") }
                    }
                    sessionLines.forEach { Text("• $it") }
                    Button(onClick = { clipboard.setText(AnnotatedString(report)) }) {
                        Text("Скопировать шаблон доклада")
                    }
                    OutlinedButton(
                        onClick = {
                            sessionRepository.add(
                                DiagnosticSessionRecord(
                                    timestampMillis = System.currentTimeMillis(),
                                    profileId = profileId,
                                    variantId = variantId,
                                    scenarioId = scenario.id,
                                    scenarioTitle = scenario.title,
                                    severity = scenario.severity.title,
                                    report = report
                                )
                            )
                            savedLocally = true
                        }
                    ) { Text("Сохранить в локальную историю") }
                    if (savedLocally) Text("Сессия сохранена на устройстве.", color = MaterialTheme.colorScheme.primary)
                }
            }
        }
        if (relatedScenarioIds.isNotEmpty() && !compactCard) {
            item {
                RelatedScenarios(relatedScenarioIds, onOpenRelated)
            }
        }
        val linkedEquipment = Vl80sObservationCatalog.equipment.filter { equipment ->
            equipment.scenarioIds.contains(scenario.id)
        }
        if (linkedEquipment.isNotEmpty() && !compactCard) {
            item { RelatedEquipment(linkedEquipment, onOpenEquipment) }
        }
        if (relatedQuestions.isNotEmpty() && !compactCard) {
            item { RelatedExamQuestions(relatedQuestions) }
        }
        item {
            InfoCard(
                "Применимость и источник",
                listOf(
                    "Уровень доверия: ${scenario.informationConfidence.title}.",
                    if (scenarioMatchesVariant) "Выбранное исполнение входит в область применимости сценария." else "Выбранное исполнение НЕ входит в подтверждённую область применимости сценария.",
                    scenario.applicability,
                    scenario.sourceNote
                ),
                MaterialTheme.colorScheme.surfaceVariant
            )
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
private fun EquipmentDetails(
    equipment: EquipmentReference,
    onBack: () -> Unit,
    onOpenScenario: (String) -> Unit
) {
    val context = LocalContext.current
    val examQuestionRepository = remember { ExamQuestionRepository(context) }
    val examQuestionsUnlocked = remember { SecretAccessRepository(context).isUnlocked() }
    val selectedVariantId = remember { LocomotiveProfileRepository(context).selectedVariantId() }
    val relatedScenarios = equipment.scenarioIds
        .mapNotNull(DiagnosticRepository::scenario)
        .filter { scenario -> LocomotiveProfiles.appliesToVariant(selectedVariantId, scenario.applicableVariantIds) }
    val relatedQuestions = remember(equipment.id) {
        if (examQuestionsUnlocked) examQuestionRepository.questions.filter { equipment.id in it.equipmentIds } else emptyList()
    }
    val observations = Vl80sObservationCatalog.observations.filter { observation ->
        equipment.id in observation.equipmentIds && observation.scenarioIds.any { scenarioId ->
            DiagnosticRepository.scenario(scenarioId)?.let { scenario ->
                LocomotiveProfiles.appliesToVariant(selectedVariantId, scenario.applicableVariantIds)
            } == true
        }
    }
    val normalValues = Vl80sNormalValues.forEquipment(equipment.id)

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            TextButton(onClick = onBack) { Text("← Диагностика") }
            Text("Аппарат", color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
            Text(equipment.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black)
            Text(equipment.purpose, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item { DiagnosticSafetyNotice() }
        item {
            InfoCard(
                "Назначение и связи",
                listOf("Связано с: ${equipment.connections.joinToString()}"),
                MaterialTheme.colorScheme.secondaryContainer
            )
        }
        item {
            InfoCard(
                "Где искать",
                listOf(equipment.location, equipment.variantNote),
                MaterialTheme.colorScheme.surfaceVariant
            )
        }
        if (observations.isNotEmpty()) {
            item {
                InfoCard(
                    "Что может быть видно бригаде",
                    observations.map { "${it.kind.title}: ${it.title}" },
                    MaterialTheme.colorScheme.tertiaryContainer
                )
            }
        }
        if (normalValues.isNotEmpty()) {
            item {
                InfoCard(
                    "Опорные параметры",
                    normalValues.flatMap { value ->
                        listOf(
                            "${value.title}: ${value.normalValue}",
                            "Применимость: ${value.applicability}",
                            "Источник: ${value.source}"
                        )
                    },
                    MaterialTheme.colorScheme.secondaryContainer
                )
            }
        }
        item {
            Text("Связанная диагностика", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            Text("Переход открывает безопасный маршрут, а не инструкцию по ремонту.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (relatedScenarios.isEmpty()) {
            item {
                InfoCard(
                    "Нет связанной диагностики для исполнения",
                    listOf("Для выбранного исполнения у этого аппарата нет подтверждённых связанных сценариев. Сменяйте исполнение только если оно соответствует фактической секции."),
                    MaterialTheme.colorScheme.surfaceVariant
                )
            }
        }
        items(relatedScenarios, key = { it.id }) { scenario ->
            Card(
                onClick = { onOpenScenario(scenario.id) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(scenario.severity.title, color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)
                    Text(scenario.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
                    Text(scenario.summary, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        if (relatedQuestions.isNotEmpty()) {
            item { RelatedExamQuestions(relatedQuestions) }
        }
        item {
            InfoCard(
                "Применимость",
                listOf("Уровень доверия: ${equipment.confidence.title}.", equipment.variantNote),
                MaterialTheme.colorScheme.surfaceVariant
            )
        }
        item { Spacer(Modifier.height(20.dp)) }
    }
}

@Composable
private fun TriageCard(
    scenario: DiagnosticScenario?,
    frameworkNode: FrameworkDiagnosticNode?,
    currentQuestionKey: String?,
    answers: List<String>,
    onAnswer: (DiagnosticResponse) -> Unit,
    onReset: () -> Unit,
    onBackOne: () -> Unit
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.secondary.copy(alpha = 0.42f)),
        shape = RoundedCornerShape(17.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Уточнение симптома", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            val question = scenario?.questions?.firstOrNull { it.key == currentQuestionKey }
            if (question != null || frameworkNode != null) {
                Text("Шаг ${answers.size + 1}; дальнейший вопрос зависит от ответа")
                Text(frameworkNode?.question ?: requireNotNull(question).text, fontWeight = FontWeight.Bold)
                if (frameworkNode != null) {
                    Text(frameworkNode.explanation, style = MaterialTheme.typography.bodySmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = { onAnswer(DiagnosticResponse.YES) }) { Text("Да") }
                    OutlinedButton(onClick = { onAnswer(DiagnosticResponse.NO) }) { Text("Нет") }
                }
                TextButton(onClick = { onAnswer(DiagnosticResponse.UNKNOWN) }) { Text("Не знаю") }
            } else {
                Text("Вопросы пройдены. Выводы включены в шаблон доклада.", fontWeight = FontWeight.Bold)
            }
            answers.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
            if (answers.isNotEmpty()) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = onBackOne) { Text("Назад на шаг") }
                    TextButton(onClick = onReset) { Text("Начать заново") }
                }
            }
        }
    }
}

@Composable
private fun SeverityLabel(severity: DiagnosticSeverity) {
    val color = when (severity) {
        DiagnosticSeverity.INFORMATION -> MaterialTheme.colorScheme.primary
        DiagnosticSeverity.ATTENTION -> Color(0xFF9A6700)
        DiagnosticSeverity.RESTRICT_OPERATION -> Color(0xFFC55200)
        DiagnosticSeverity.STOP_AND_REPORT -> MaterialTheme.colorScheme.error
    }
    Text(severity.title.uppercase(), color = color, fontWeight = FontWeight.Black, style = MaterialTheme.typography.labelMedium)
}

@Composable
private fun SessionJournal(
    sectionNote: String,
    onSectionNote: (String) -> Unit,
    modeNote: String,
    onModeNote: (String) -> Unit,
    instrumentNote: String,
    onInstrumentNote: (String) -> Unit,
    feedbackNote: String,
    onFeedbackNote: (String) -> Unit,
    prompts: List<String>
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(17.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Журнал диагностической сессии", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            Text("Записи сохраняются при переходах внутри приложения и попадут в шаблон доклада.")
            OutlinedTextField(sectionNote, onSectionNote, label = { Text("Секция, тележка или место") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(modeNote, onModeNote, label = { Text("Скорость, позиция и режим") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(instrumentNote, onInstrumentNote, label = { Text("Приборы, лампы и защита") }, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(feedbackNote, onFeedbackNote, label = { Text("Что изменилось после действия") }, modifier = Modifier.fillMaxWidth())
            prompts.forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun RelatedScenarios(ids: List<String>, onOpen: (String) -> Unit) {
    val related = ids.mapNotNull(DiagnosticRepository::scenario)
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(17.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Связанные неисправности", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            Text("Один симптом может относиться сразу к нескольким системам.")
            related.forEach { item ->
                TextButton(onClick = { onOpen(item.id) }) { Text("${item.title} →") }
            }
        }
    }
}

@Composable
private fun RelatedEquipment(items: List<EquipmentReference>, onOpen: (String) -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(17.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Связанные аппараты", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            Text("Назначение и расположение отмечены с учётом варианта исполнения.")
            items.forEach { equipment ->
                TextButton(onClick = { onOpen(equipment.id) }) { Text("${equipment.title} →") }
            }
        }
    }
}

@Composable
private fun RelatedExamQuestions(items: List<ExamQuestion>) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        shape = RoundedCornerShape(17.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("Связанные проверочные сведения", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black)
            Text(
                "Формулировки ниже взяты из экзаменационной базы и не заменяют эксплуатационный документ.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            items.take(8).forEach { item ->
                Text("• ${item.question}", fontWeight = FontWeight.SemiBold)
                Text(item.correctAnswer, style = MaterialTheme.typography.bodySmall)
            }
            if (items.size > 8) {
                Text("Ещё связано: ${items.size - 8}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun DiagnosticCheckCard(check: DiagnosticCheck) {
    val (label, color) = when (check.level) {
        DiagnosticActionLevel.CAB -> "ИЗ КАБИНЫ" to Color(0xFF2E7D32)
        DiagnosticActionLevel.SAFE_STOP -> "ПОСЛЕ БЕЗОПАСНОЙ ОСТАНОВКИ" to Color(0xFF9A6700)
        DiagnosticActionLevel.AUTHORIZED_ONLY -> "ТОЛЬКО ДОПУЩЕННЫЙ ПЕРСОНАЛ" to Color(0xFFB3261E)
        DiagnosticActionLevel.STOP -> "ПРЕКРАТИТЬ ДЕЙСТВИЯ" to Color(0xFFB3261E)
    }
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        border = BorderStroke(1.dp, color),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(label, color = color, fontWeight = FontWeight.Black, style = MaterialTheme.typography.labelMedium)
            Text(check.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
            Text("Действие: ${check.action}")
            Text("Норма: ${check.expected}", color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Если не так: ${check.ifAbnormal}", color = color, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
internal fun DiagnosticSafetyNotice() {
    BorderedCautionCard(
        title = "Важно: это не допуск к работам",
        lines = listOf(DiagnosticRepository.safetyNotice),
        bulletLines = false
    )
}

@Composable
private fun BorderedCautionCard(title: String, lines: List<String>, bulletLines: Boolean = true) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
        shape = RoundedCornerShape(17.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.error)
            lines.forEach { Text(if (bulletLines) "• $it" else it) }
        }
    }
}

@Composable
internal fun InfoCard(title: String, lines: List<String>, tone: Color) {
    val danger = tone == MaterialTheme.colorScheme.errorContainer
    val accent = when {
        danger -> MaterialTheme.colorScheme.error
        tone == MaterialTheme.colorScheme.primaryContainer -> MaterialTheme.colorScheme.primary
        tone == MaterialTheme.colorScheme.tertiaryContainer -> MaterialTheme.colorScheme.tertiary
        tone == MaterialTheme.colorScheme.secondaryContainer -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.colorScheme.outline
    }
    Card(
        colors = CardDefaults.cardColors(
            containerColor = if (danger) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surface
        ),
        border = BorderStroke(1.dp, accent.copy(alpha = if (danger) 0.72f else 0.38f)),
        shape = RoundedCornerShape(17.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, color = accent)
            lines.forEach { Text("• $it") }
        }
    }
}

internal fun buildDiagnosticReport(
    scenario: DiagnosticScenario,
    answers: List<String>,
    sessionLines: List<String> = emptyList()
): String = buildString {
    appendLine("ВЛ80С — ${scenario.title}")
    appendLine("Уровень: ${scenario.severity.title}.")
    appendLine("Зафиксировать: ${scenario.reportFields.joinToString()}.")
    if (answers.isNotEmpty()) {
        appendLine("Ответы:")
        answers.forEach { appendLine("- $it") }
    }
    if (sessionLines.isNotEmpty()) {
        appendLine("Наблюдения:")
        sessionLines.forEach { appendLine("- $it") }
    }
    appendLine("Применимость: ${scenario.applicability}")
}
