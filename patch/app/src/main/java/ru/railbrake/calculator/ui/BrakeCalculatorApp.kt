package ru.railbrake.calculator.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.NavigationDrawerItem
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import ru.railbrake.calculator.R
import ru.railbrake.calculator.core.Appendix12Input
import ru.railbrake.calculator.core.Appendix12Result
import ru.railbrake.calculator.core.AppendixFormula
import ru.railbrake.calculator.core.BrakeCalculator
import ru.railbrake.calculator.core.ConsistItem
import ru.railbrake.calculator.core.LocomotiveCatalogRegistry
import ru.railbrake.calculator.core.LocomotiveDatabase
import ru.railbrake.calculator.core.LocomotiveSpec
import ru.railbrake.calculator.core.MassCalculationInput
import ru.railbrake.calculator.core.MassCalculationResult
import ru.railbrake.calculator.core.MassSupplementResult
import ru.railbrake.calculator.core.ProfileMode
import ru.railbrake.calculator.core.TenTonsChoice
import ru.railbrake.calculator.core.TechnicalFamily
import ru.railbrake.calculator.core.TechnicalDataRepository
import ru.railbrake.calculator.core.TechnicalSection
import ru.railbrake.calculator.core.WorkingLocomotive
import ru.railbrake.calculator.core.assistant.AssistantRuntime
import ru.railbrake.calculator.data.HistoryRecord
import ru.railbrake.calculator.data.HistoryRepository
import ru.railbrake.calculator.data.SecretAccessRepository
import ru.railbrake.calculator.data.WorkingLocomotiveRepository
import ru.railbrake.calculator.ui.theme.AccentPalette
import ru.railbrake.calculator.ui.theme.AppThemeMode
import ru.railbrake.calculator.ui.theme.RailTheme
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs

private enum class AppScreen(val title: String) {
    HOME("Главная"),
    DIAGNOSTICS("Диагностика"),
    LOCOMOTIVES("Локомотивы / атлас"),
    LOCOMOTIVE_MATERIAL("Локомотивы / атлас"),
    LOCOMOTIVE_LEGACY("Локомотивы / атлас"),
    ACCEPTANCE("Приёмка"),
    KNOWLEDGE("База знаний"),
    SAFETY("Охрана труда"),
    FIRST_AID("Первая помощь"),
    CALCULATIONS("Расчёты"),
    MASS("По массе"),
    APPENDIX("ИДП №12"),
    HISTORY("История"),
    EXAM_QUESTIONS("Вопросы и ответы"),
    SETTINGS("Настройки")
}

private enum class OutputMode(val title: String) {
    QUICK("Быстрый"),
    STUDY("Учебный")
}

private enum class MassSource(val title: String, val subtitle: String) {
    DIRECT("Масса + оси", "Готовые масса и количество осей"),
    WAGONS("По вагонам", "Оси считаются из 4-, 6- и 8-осных вагонов"),
    CONSIST("Сплотка", "Масса и оси из базы локомотивов"),
    MANUAL_LOAD("Нагрузка вручную", "Масса + известная фактическая нагрузка на ось")
}

private data class AppendixPrefill(
    val axleCount: Int?,
    val oilyRails: Boolean,
    val windSpeedMs: String,
    val windMatches: Boolean,
    val token: Long = System.nanoTime()
)

internal fun isDeveloperEasterEgg(massTons: Double, axleCount: Int?): Boolean =
    abs(massTons - 2381.0) < 1e-9 && axleCount == 999

@Composable
fun BrakeCalculatorApp(
    palette: AccentPalette,
    onPaletteChange: (AccentPalette) -> Unit,
    themeMode: AppThemeMode,
    onThemeModeChange: (AppThemeMode) -> Unit
) {
    val context = LocalContext.current
    val historyRepository = remember { HistoryRepository(context) }
    val secretAccessRepository = remember { SecretAccessRepository(context) }
    val workingRepository = remember(context) { WorkingLocomotiveRepository(context) }
    var workingLocomotive by remember { mutableStateOf(workingRepository.selected()) }
    val workingFamily = workingLocomotive?.family
    var viewingFamilyName by rememberSaveable {
        mutableStateOf(LocomotiveCatalogRegistry.initialViewingFamily(null, workingLocomotive).name)
    }
    val viewingFamily = LocomotiveCatalogRegistry.browsingFamilyOrDefault(
        viewingFamilyName,
        workingFamily ?: TechnicalFamily.VL80S
    )
    var auxiliaryToolsVisible by remember { mutableStateOf(secretAccessRepository.isUnlocked()) }
    var historyVersion by remember { mutableIntStateOf(0) }
    var screenName by rememberSaveable { mutableStateOf(AppScreen.HOME.name) }
    var appendixPrefill by remember { mutableStateOf<AppendixPrefill?>(null) }
    var knowledgeStartArticleId by rememberSaveable { mutableStateOf<String?>(null) }
    var knowledgeStartQuery by rememberSaveable { mutableStateOf<String?>(null) }
    var diagnosticStartScenarioId by rememberSaveable { mutableStateOf<String?>(null) }
    var diagnosticStartEquipmentId by rememberSaveable { mutableStateOf<String?>(null) }
    var diagnosticReturnScreenName by rememberSaveable { mutableStateOf(AppScreen.HOME.name) }
    var locomotiveMaterialArticleId by rememberSaveable { mutableStateOf<String?>(null) }
    var locomotiveMaterialQuery by rememberSaveable { mutableStateOf<String?>(null) }
    var technicalFamilyName by rememberSaveable { mutableStateOf(TechnicalFamily.VL80S.name) }
    var technicalSectionName by rememberSaveable { mutableStateOf(TechnicalSection.EQUIPMENT.name) }
    var technicalInitialEntryId by rememberSaveable { mutableStateOf<String?>(null) }
    var atlasReturnToDiagnostics by rememberSaveable { mutableStateOf(false) }
    var diagnosticRootVersion by rememberSaveable { mutableIntStateOf(0) }
    var locomotiveRootVersion by rememberSaveable { mutableIntStateOf(0) }
    var acceptanceRootVersion by rememberSaveable { mutableIntStateOf(0) }
    var knowledgeRootVersion by rememberSaveable { mutableIntStateOf(0) }
    var safetyRootVersion by rememberSaveable { mutableIntStateOf(0) }
    var firstAidRootVersion by rememberSaveable { mutableIntStateOf(0) }
    var historyRootVersion by rememberSaveable { mutableIntStateOf(0) }
    var examRootVersion by rememberSaveable { mutableIntStateOf(0) }
    val screen = AppScreen.valueOf(screenName)
    val drawerState = androidx.compose.material3.rememberDrawerState(DrawerValue.Closed)
    val drawerScope = rememberCoroutineScope()

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                ) {
                Row(
                    modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_launcher_art),
                        contentDescription = null,
                        modifier = Modifier.size(50.dp)
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        Text("Железнодорожный помощник", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
                        Text("Сегодня: ${workingLocomotive?.title ?: "локомотив не выбран"}",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                    }
                }

                val mainItems = listOf(AppScreen.HOME, AppScreen.DIAGNOSTICS, AppScreen.LOCOMOTIVES, AppScreen.ACCEPTANCE, AppScreen.KNOWLEDGE, AppScreen.SAFETY, AppScreen.FIRST_AID)
                val toolItems = buildList {
                    add(AppScreen.CALCULATIONS)
                    add(AppScreen.HISTORY)
                    if (auxiliaryToolsVisible) add(AppScreen.EXAM_QUESTIONS)
                }
                mainItems.forEach { item ->
                    NavigationDrawerItem(
                        label = { Text(item.title) },
                        selected = screen == item || (item == AppScreen.LOCOMOTIVES && screen in listOf(AppScreen.LOCOMOTIVE_MATERIAL, AppScreen.LOCOMOTIVE_LEGACY)),
                        onClick = {
                            when (item) {
                                AppScreen.DIAGNOSTICS -> {
                                    diagnosticStartScenarioId = null
                                    diagnosticStartEquipmentId = null
                                    diagnosticReturnScreenName = AppScreen.HOME.name
                                    diagnosticRootVersion++
                                }
                                AppScreen.LOCOMOTIVES -> {
                                    locomotiveMaterialArticleId = null
                                    locomotiveMaterialQuery = null
                                    locomotiveRootVersion++
                                }
                                AppScreen.ACCEPTANCE -> {
                                    technicalSectionName = TechnicalSection.ACCEPTANCE.name
                                    acceptanceRootVersion++
                                }
                                AppScreen.KNOWLEDGE -> {
                                    knowledgeStartArticleId = null
                                    knowledgeStartQuery = null
                                    knowledgeRootVersion++
                                }
                                AppScreen.SAFETY -> {
                                    safetyRootVersion++
                                }
                                AppScreen.FIRST_AID -> firstAidRootVersion++
                                else -> Unit
                            }
                            screenName = item.name
                            drawerScope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                toolItems.forEach { item ->
                    NavigationDrawerItem(
                        label = { Text(item.title) },
                        selected = screen == item || (item == AppScreen.CALCULATIONS && screen in listOf(AppScreen.MASS, AppScreen.APPENDIX)),
                        onClick = {
                            when (item) {
                                AppScreen.HISTORY -> historyRootVersion++
                                AppScreen.EXAM_QUESTIONS -> examRootVersion++
                                else -> Unit
                            }
                            screenName = item.name
                            drawerScope.launch { drawerState.close() }
                        },
                        modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                    )
                }
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                NavigationDrawerItem(
                    label = { Text("Настройки") },
                    selected = screen == AppScreen.SETTINGS,
                    onClick = { screenName = AppScreen.SETTINGS.name; drawerScope.launch { drawerState.close() } },
                    modifier = Modifier.padding(NavigationDrawerItemDefaults.ItemPadding)
                )
                }
            }
        }
    ) {
        BackHandler(enabled = screen != AppScreen.HOME) {
            screenName = when (screen) {
                AppScreen.MASS, AppScreen.APPENDIX -> AppScreen.CALCULATIONS.name
                AppScreen.LOCOMOTIVE_MATERIAL -> if (atlasReturnToDiagnostics) {
                    atlasReturnToDiagnostics = false
                    AppScreen.DIAGNOSTICS.name
                } else AppScreen.LOCOMOTIVES.name
                AppScreen.LOCOMOTIVE_LEGACY -> AppScreen.LOCOMOTIVES.name
                AppScreen.DIAGNOSTICS -> diagnosticReturnScreenName
                else -> AppScreen.HOME.name
            }
        }
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
            ) {
                if (screen == AppScreen.HOME) {
                    AppHeader(
                        onOpenMenu = { drawerScope.launch { drawerState.open() } },
                        onOpenFirstAid = { screenName = AppScreen.FIRST_AID.name }
                    )
                } else {
                    RailCompactHeader(
                        title = screen.title,
                        onOpenMenu = { drawerScope.launch { drawerState.open() } },
                        onOpenFirstAid = { screenName = AppScreen.FIRST_AID.name },
                        onHome = { screenName = AppScreen.HOME.name }
                    )
                }
                when (screen) {
                AppScreen.HOME -> HomeScreen(
                    latestHistory = remember(historyVersion) { historyRepository.load().firstOrNull() },
                    workingLocomotive = workingLocomotive,
                    onWorkingLocomotiveChange = { selected ->
                        workingRepository.select(selected)
                        AssistantRuntime.activate(selected?.family, selected?.variantId)
                        workingLocomotive = selected
                        selected?.family?.let { viewingFamilyName = it.name }
                        diagnosticStartScenarioId = null
                        technicalInitialEntryId = null
                        screenName = AppScreen.HOME.name
                    },
                    onDiagnostics = {
                        diagnosticStartScenarioId = null
                        diagnosticStartEquipmentId = null
                        diagnosticReturnScreenName = AppScreen.HOME.name
                        screenName = AppScreen.DIAGNOSTICS.name
                    },
                    onKnowledge = {
                        knowledgeStartArticleId = null
                        knowledgeStartQuery = null
                        screenName = AppScreen.KNOWLEDGE.name
                    },
                    onLocomotives = { screenName = AppScreen.LOCOMOTIVES.name },
                    onCalculations = { screenName = AppScreen.CALCULATIONS.name }
                )
                AppScreen.CALCULATIONS -> ScrollPage {
                    CalculationsHub(
                        onMass = { screenName = AppScreen.MASS.name },
                        onAppendix = { screenName = AppScreen.APPENDIX.name }
                    )
                }
                AppScreen.MASS -> ScrollPage {
                    ChildBackButton("Расчёты") { screenName = AppScreen.CALCULATIONS.name }
                    MassScreen(
                        onHistory = {
                            historyRepository.add(it)
                            historyVersion++
                        },
                        onOpenAppendix = { prefill ->
                            appendixPrefill = prefill
                            screenName = AppScreen.APPENDIX.name
                        },
                        onDirectCalculation = { massTons, axleCount ->
                            if (secretAccessRepository.recordDirectCalculation(massTons, axleCount)) {
                                auxiliaryToolsVisible = true
                            }
                        }
                    )
                }
                AppScreen.APPENDIX -> ScrollPage {
                    ChildBackButton("Расчёты") { screenName = AppScreen.CALCULATIONS.name }
                    AppendixScreen(
                        prefill = appendixPrefill,
                        onHistory = {
                            historyRepository.add(it)
                            historyVersion++
                        }
                    )
                }
                AppScreen.HISTORY -> key(historyRootVersion) {
                    HistoryScreen(
                        repository = historyRepository,
                        version = historyVersion,
                        onCleared = { historyVersion++ }
                    )
                }
                AppScreen.LOCOMOTIVES -> key(locomotiveRootVersion) {
                    LocomotiveReferenceScreen(
                        workingFamily = workingFamily,
                        viewingFamily = viewingFamily,
                        onViewingFamilyChange = { viewingFamilyName = it.name },
                        onOpenTechnical = { family, section ->
                            viewingFamilyName = family.name
                            technicalFamilyName = family.name
                            technicalSectionName = section.name
                            technicalInitialEntryId = null
                            screenName = AppScreen.LOCOMOTIVE_MATERIAL.name
                        },
                        onOpenInteractiveVl80s = {
                            viewingFamilyName = TechnicalFamily.VL80S.name
                            locomotiveMaterialQuery = null
                            locomotiveMaterialArticleId = "vl80-layout"
                            screenName = AppScreen.LOCOMOTIVE_LEGACY.name
                        },
                        onOpenInteractiveErmak = {
                            viewingFamilyName = TechnicalFamily.ERMAK.name
                            technicalFamilyName = TechnicalFamily.ERMAK.name
                            technicalSectionName = TechnicalSection.ELECTRICAL.name
                            technicalInitialEntryId = "ER-SCH-LAYOUT-2ES5K-BASE"
                            screenName = AppScreen.LOCOMOTIVE_MATERIAL.name
                        },
                        onOpenInteractiveChme3 = { family ->
                            viewingFamilyName = family.name
                            technicalFamilyName = family.name
                            technicalSectionName = TechnicalSection.ELECTRICAL.name
                            technicalInitialEntryId = when (family) {
                                TechnicalFamily.TEM2, TechnicalFamily.TEM2U -> "${family.name}-INT-TEM2-SCH-TRACTION"
                                TechnicalFamily.CHME3E -> "CHME3E-INT-TRACTION-CONTROL"
                                else -> "CHME3-INT-TRACTION"
                            }
                            screenName = AppScreen.LOCOMOTIVE_MATERIAL.name
                        }
                    )
                }
                AppScreen.LOCOMOTIVE_MATERIAL -> TechnicalCatalogScreen(
                    initialFamily = runCatching { TechnicalFamily.valueOf(technicalFamilyName) }.getOrDefault(TechnicalFamily.VL80S),
                    initialSection = runCatching { TechnicalSection.valueOf(technicalSectionName) }.getOrDefault(TechnicalSection.EQUIPMENT),
                    initialEntryId = technicalInitialEntryId,
                    lockFamily = false,
                    onFamilyChange = { family ->
                        viewingFamilyName = family.name
                        technicalFamilyName = family.name
                    },
                    onSectionBack = {
                        screenName = if (atlasReturnToDiagnostics) AppScreen.DIAGNOSTICS.name else AppScreen.LOCOMOTIVES.name
                        atlasReturnToDiagnostics = false
                    },
                    onOpenLegacyArticle = { articleId ->
                        locomotiveMaterialQuery = null
                        locomotiveMaterialArticleId = articleId
                        screenName = AppScreen.LOCOMOTIVE_LEGACY.name
                    },
                    onOpenDiagnosticScenario = { scenarioId, sourceEntryId, sourceSection ->
                        val targetFamily = diagnosticInitialFamily(scenarioId, null)
                        technicalFamilyName = targetFamily.name
                        viewingFamilyName = targetFamily.name
                        technicalSectionName = sourceSection.name
                        technicalInitialEntryId = sourceEntryId
                        diagnosticStartScenarioId = scenarioId
                        diagnosticStartEquipmentId = null
                        diagnosticReturnScreenName = AppScreen.LOCOMOTIVE_MATERIAL.name
                        diagnosticRootVersion++
                        screenName = AppScreen.DIAGNOSTICS.name
                    }
                )
                AppScreen.LOCOMOTIVE_LEGACY -> KnowledgeBaseScreen(
                    initialArticleId = locomotiveMaterialArticleId,
                    initialQuery = locomotiveMaterialQuery,
                    sectionBackLabel = "Локомотивы / атлас",
                    onSectionBack = { screenName = AppScreen.LOCOMOTIVES.name }
                )
                AppScreen.ACCEPTANCE -> key(acceptanceRootVersion, viewingFamily) {
                    TechnicalCatalogScreen(
                        initialFamily = viewingFamily,
                        initialSection = TechnicalSection.ACCEPTANCE,
                        sectionBackLabel = "Главная",
                        onSectionBack = { screenName = AppScreen.HOME.name },
                        lockFamily = false,
                        lockSection = true,
                        onFamilyChange = { family ->
                            viewingFamilyName = family.name
                            technicalFamilyName = family.name
                        }
                    )
                }
                AppScreen.DIAGNOSTICS -> key(diagnosticRootVersion) {
                    LocomotiveDiagnosticsScreen(
                        initialScenarioId = diagnosticStartScenarioId,
                        initialEquipmentId = diagnosticStartEquipmentId,
                        initialFamily = viewingFamily,
                        workingFamily = workingFamily,
                        workingVariantId = workingLocomotive?.variantId,
                        onFamilyChange = { family -> viewingFamilyName = family.name },
                        onOpenAtlasEquipment = { equipmentId ->
                            atlasReturnToDiagnostics = true
                            technicalFamilyName = TechnicalDataRepository(context).entry(equipmentId)?.family?.name
                                ?: viewingFamily.name
                            technicalSectionName = TechnicalSection.EQUIPMENT.name
                            technicalInitialEntryId = equipmentId
                            screenName = AppScreen.LOCOMOTIVE_MATERIAL.name
                        }
                    )
                }
                AppScreen.KNOWLEDGE -> key(knowledgeRootVersion) {
                    KnowledgeBaseScreen(
                        initialArticleId = knowledgeStartArticleId,
                        initialQuery = knowledgeStartQuery
                    )
                }
                AppScreen.SAFETY -> key(safetyRootVersion) {
                    SafetyScreen(onBack = { screenName = AppScreen.HOME.name })
                }
                AppScreen.FIRST_AID -> key(firstAidRootVersion) {
                    FirstAidScreen(onBack = { screenName = AppScreen.HOME.name })
                }
                AppScreen.EXAM_QUESTIONS -> key(examRootVersion) { ExamQuestionScreen(
                    onHide = {
                        secretAccessRepository.hide()
                        auxiliaryToolsVisible = false
                        screenName = AppScreen.HOME.name
                    },
                    onOpenScenario = { scenarioId ->
                        viewingFamilyName = diagnosticInitialFamily(scenarioId, null).name
                        diagnosticStartScenarioId = scenarioId
                        diagnosticStartEquipmentId = null
                        diagnosticReturnScreenName = AppScreen.HOME.name
                        screenName = AppScreen.DIAGNOSTICS.name
                    },
                    onOpenEquipment = { equipmentId ->
                        viewingFamilyName = diagnosticInitialFamily(null, equipmentId).name
                        diagnosticStartScenarioId = null
                        diagnosticStartEquipmentId = equipmentId
                        diagnosticReturnScreenName = AppScreen.HOME.name
                        screenName = AppScreen.DIAGNOSTICS.name
                    },
                    onOpenKnowledgeTopic = { topic ->
                        knowledgeStartArticleId = null
                        knowledgeStartQuery = topic
                        screenName = AppScreen.KNOWLEDGE.name
                    }
                ) }
                AppScreen.SETTINGS -> ScrollPage {
                    SettingsScreen(palette, onPaletteChange, themeMode, onThemeModeChange)
                }
                }
            }
        }
    }
}

@Composable
private fun AppHeader(onOpenMenu: () -> Unit, onOpenFirstAid: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(start = 8.dp, end = 18.dp, top = 12.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        IconButton(onClick = onOpenMenu) {
            Text("☰", style = MaterialTheme.typography.headlineMedium)
        }
        Image(
            painter = painterResource(R.drawable.ic_launcher_art),
            contentDescription = null,
            modifier = Modifier.size(42.dp)
        )
        Spacer(Modifier.size(8.dp))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(3.dp)
        ) {
            Text(
                "Железнодорожный помощник",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Black
            )
            Text(
                "Рабочий помощник локомотивной бригады",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Column(
            modifier = Modifier
                .clickable(onClick = onOpenFirstAid)
                .padding(horizontal = 8.dp, vertical = 2.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(0.dp)
        ) {
            Text("✚", style = MaterialTheme.typography.headlineMedium, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Black)
            Text("ОПП", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
private fun ScrollPage(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        content = content
    )
}

@Composable
internal fun ChildBackButton(parentTitle: String, onBack: () -> Unit) {
    TextButton(onClick = onBack) {
        Text("← $parentTitle", fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun CalculationsHub(onMass: () -> Unit, onAppendix: () -> Unit) {
    RailSectionHeader("Расчёты", "Инструменты тормозных расчётов вынесены из главной страницы")
    RailHeroCard(
        title = "По массе",
        subtitle = "Расчёт требуемых тормозных башмаков, осей и дополнительных условий по исходным данным состава.",
        action = "ОТКРЫТЬ РАСЧЁТ  →",
        onClick = onMass
    )
    RailNavCard(
        title = "ИДП №12",
        subtitle = "Отдельный расчёт по приложению №12 с учётом уклона, ветра и условий закрепления.",
        marker = "№12",
        onClick = onAppendix,
        modifier = Modifier.fillMaxWidth()
    )
    RailInfoBand("Результаты сохраняются локально в разделе «История». Учебный режим с формулами остаётся доступен внутри расчёта.")
}

@Composable
private fun MassScreen(
    onHistory: (HistoryRecord) -> Unit,
    onOpenAppendix: (AppendixPrefill) -> Unit,
    onDirectCalculation: (Double, Int?) -> Unit
) {
    val context = LocalContext.current
    val inputPrefs = remember { context.getSharedPreferences("calculation_inputs", android.content.Context.MODE_PRIVATE) }
    var outputModeName by rememberSaveable { mutableStateOf(OutputMode.QUICK.name) }
    var sourceName by rememberSaveable { mutableStateOf(MassSource.DIRECT.name) }
    val outputMode = OutputMode.valueOf(outputModeName)
    val source = MassSource.valueOf(sourceName)

    var mass by rememberSaveable { mutableStateOf("") }
    var directAxles by rememberSaveable { mutableStateOf(inputPrefs.getString("axles", "").orEmpty()) }
    var wag4 by rememberSaveable { mutableStateOf("") }
    var wag6 by rememberSaveable { mutableStateOf("") }
    var wag8 by rememberSaveable { mutableStateOf("") }
    var extraAxles by rememberSaveable { mutableStateOf("") }
    var manualLoad by rememberSaveable { mutableStateOf("") }
    var slope by rememberSaveable { mutableStateOf(inputPrefs.getString("slope", "").orEmpty().toRuDoubleOrNull() ?: 12.0) }
    var availableShoes by rememberSaveable { mutableStateOf(inputPrefs.getString("available_shoes", "").orEmpty()) }
    var axlesPerHandBrake by rememberSaveable { mutableStateOf("") }
    var tenChoiceName by rememberSaveable { mutableStateOf("") }
    var oily by rememberSaveable { mutableStateOf(false) }
    var wind by rememberSaveable { mutableStateOf("") }
    var windMatches by rememberSaveable { mutableStateOf(false) }

    var consist by remember { mutableStateOf<List<ConsistItem>>(emptyList()) }
    var customName by rememberSaveable { mutableStateOf("") }
    var customMass by rememberSaveable { mutableStateOf("") }
    var customAxles by rememberSaveable { mutableStateOf("") }

    var result by remember { mutableStateOf<MassCalculationResult?>(null) }
    var supplement by remember { mutableStateOf<MassSupplementResult?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var showDeveloperEasterEgg by rememberSaveable { mutableStateOf(false) }

    fun invalidate() {
        result = null
        supplement = null
        error = null
        showDeveloperEasterEgg = false
    }

    SectionCard("Режим вывода", "В учебном режиме показываются формулы и логика выбора коэффициентов") {
        ChoiceRow(
            options = OutputMode.entries.map { it.title },
            selectedIndex = OutputMode.entries.indexOf(outputMode),
                onSelect = {
                    outputModeName = OutputMode.entries[it].name
                    if (OutputMode.entries[it] == OutputMode.QUICK) tenChoiceName = ""
                    invalidate()
            }
        )
    }

    WarningBox(
        "Таблица III.4 применяется для удержания грузового, грузопассажирского, почтово-багажного, рефрижераторного или хозяйственного поезда после остановки на перегоне, если автотормоза неисправны либо их невозможно привести в действие. Для станционного закрепления используйте расчёт по приложению №12 ИДП."
    )

    SectionCard("Исходные данные", "Выберите удобный способ задать состав") {
        MassSource.entries.forEach { option ->
            ChoiceOption(
                title = option.title,
                subtitle = option.subtitle,
                selected = source == option,
                onClick = {
                    sourceName = option.name
                    tenChoiceName = ""
                    invalidate()
                }
            )
        }

        Spacer(Modifier.height(2.dp))

        when (source) {
            MassSource.DIRECT -> {
                NumericField("Масса учитываемого состава, т", mass, true) { mass = it; invalidate() }
                NumericField("Количество учитываемых осей", directAxles, false) { directAxles = it; inputPrefs.edit().putString("axles", it).apply(); invalidate() }
            }
            MassSource.WAGONS -> {
                NumericField("Масса учитываемого состава, т", mass, true) { mass = it; invalidate() }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { NumericField("4-осных", wag4, false) { wag4 = it; invalidate() } }
                    Box(Modifier.weight(1f)) { NumericField("6-осных", wag6, false) { wag6 = it; invalidate() } }
                    Box(Modifier.weight(1f)) { NumericField("8-осных", wag8, false) { wag8 = it; invalidate() } }
                }
                NumericField("Дополнительных осей от прочих вагонов", extraAxles, false) { extraAxles = it; invalidate() }
                val counted = runCatching {
                    BrakeCalculator.wagonAxles(
                        wag4.toIntOrNull() ?: 0,
                        wag6.toIntOrNull() ?: 0,
                        wag8.toIntOrNull() ?: 0,
                        extraAxles.toIntOrNull() ?: 0
                    )
                }.getOrDefault(0)
                MiniMetric("Рассчитано осей", counted.toString())
            }
            MassSource.CONSIST -> {
                WarningBox("Расчёт локомотивной сплотки по таблице III.4 является только справочной оценкой: отдельное нормативное основание для применения этой таблицы к сплотке не подтверждено. Для закрепления сплотки используйте приложение №12 и фактическое число осей.")
                Text(
                    "Добавляйте только локомотивы, которые входят в расчёт. Ведущий локомотив не добавляйте, если по условию он не учитывается.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                WarningBox("Масса и число осей в справочнике могут отличаться по модификации. При известных точных данных используйте ручной ввод массы и осей.")
                LocomotiveConsistPicker(
                    consist = consist,
                    onChange = { consist = it; invalidate() }
                )
                val totalMass = consist.sumOf { it.totalMassTons }
                val totalAxles = consist.sumOf { it.totalAxles }
                if (consist.isNotEmpty()) {
                    MiniMetric("Сплотка", "${fmt(totalMass)} т • $totalAxles осей")
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Text("Добавить нестандартный локомотив", fontWeight = FontWeight.SemiBold)
                NumericField("Название (можно оставить пустым)", customName, true, allowText = true) { customName = it }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Box(Modifier.weight(1f)) { NumericField("Масса, т", customMass, true) { customMass = it } }
                    Box(Modifier.weight(1f)) { NumericField("Осей", customAxles, false) { customAxles = it } }
                }
                OutlinedButton(
                    onClick = {
                        val m = customMass.toRuDoubleOrNull()
                        val a = customAxles.toIntOrNull()
                        if (m != null && m > 0 && a != null && a > 0) {
                            val spec = LocomotiveSpec(
                                name = customName.ifBlank { "Другой локомотив" },
                                category = "Введено вручную",
                                massTons = m,
                                axles = a,
                                note = "Пользовательские данные"
                            )
                            consist = consist + ConsistItem(spec, 1)
                            customName = ""
                            customMass = ""
                            customAxles = ""
                            invalidate()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp)
                ) { Text("Добавить в сплотку") }
            }
            MassSource.MANUAL_LOAD -> {
                NumericField("Масса учитываемого состава, т", mass, true) { mass = it; invalidate() }
                NumericField("Фактическая нагрузка на ось, т/ось", manualLoad, true) { manualLoad = it; invalidate() }
            }
        }
    }

    val previewLoad = when (source) {
        MassSource.DIRECT -> ratioOrNull(mass.toRuDoubleOrNull(), directAxles.toIntOrNull())
        MassSource.WAGONS -> {
            val ax = BrakeCalculator.wagonAxles(
                wag4.toIntOrNull() ?: 0,
                wag6.toIntOrNull() ?: 0,
                wag8.toIntOrNull() ?: 0,
                extraAxles.toIntOrNull() ?: 0
            )
            ratioOrNull(mass.toRuDoubleOrNull(), ax)
        }
        MassSource.CONSIST -> {
            val m = consist.sumOf { it.totalMassTons }
            val a = consist.sumOf { it.totalAxles }
            ratioOrNull(m, a)
        }
        MassSource.MANUAL_LOAD -> manualLoad.toRuDoubleOrNull()
    }

    if (previewLoad != null) {
        SectionCard("Нагрузка на ось", null) {
            MiniMetric(
                if (source == MassSource.MANUAL_LOAD) "Введённая нагрузка на ось" else "Средняя расчётная нагрузка",
                            "${fmt(previewLoad)} т/ось"
            )
            if (source != MassSource.MANUAL_LOAD) {
                Text(
                    "M/n — среднее расчётное значение. Для неоднородного состава при известных точных данных используйте ручной ввод нагрузки на ось.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            when {
                previewLoad == 10.0 -> {
                    StatusText("Нормативно применяется строка: 10 тс/ось и более", true)
                    if (outputMode == OutputMode.STUDY) {
                        Text(
                            "Нижняя строка доступна только для сравнения, это не нормативный вариант при 10,00 т/ось.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        ChoiceRow(
                            options = listOf("Нормативно: ≥ 10", "< 10 — только сравнение"),
                            selectedIndex = if (tenChoiceName == TenTonsChoice.LESS_THAN_10.name) 1 else 0,
                            onSelect = {
                                tenChoiceName = if (it == 1) TenTonsChoice.LESS_THAN_10.name else TenTonsChoice.TEN_OR_MORE.name
                                invalidate()
                            }
                        )
                    }
                }
                previewLoad < 10.0 -> StatusText("Будет применена строка: менее 10 тс/ось", true)
                else -> StatusText("Будет применена строка: 10 тс/ось и более", true)
            }
        }
    }

    SectionCard("Уклон", "Дискретные значения таблицы расчёта по массе") {
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(BrakeCalculator.massSlopesPermille) { value ->
                FilterChip(
                    selected = value == slope,
                    onClick = { slope = value; inputPrefs.edit().putString("slope", value.toString()).apply(); invalidate() },
                    label = { Text("${fmt(value)}‰") }
                )
            }
        }
    }

    SectionCard("Фактические средства", "Башмаки используются первыми, ручной тормоз дополняет только недостающую часть") {
        NumericField("Доступно тормозных башмаков (например, 16)", availableShoes, false) { availableShoes = it; inputPrefs.edit().putString("available_shoes", it).apply(); invalidate() }
        NumericField("Оси на одну единицу ручного тормоза (например, 4)", axlesPerHandBrake, false) { axlesPerHandBrake = it; invalidate() }
    }

    error?.let { ErrorBox(it) }

    CalculateButton {
        runCatching {
            val massValue: Double
            val axleCount: Int?
            val manualAxleLoad: Double?

            when (source) {
                MassSource.DIRECT -> {
                    massValue = mass.requireRuDouble("Введите массу")
                    axleCount = directAxles.requirePositiveInt("Введите количество осей")
                    manualAxleLoad = null
                }
                MassSource.WAGONS -> {
                    massValue = mass.requireRuDouble("Введите массу")
                    val counted = BrakeCalculator.wagonAxles(
                        wag4.toIntOrNull() ?: 0,
                        wag6.toIntOrNull() ?: 0,
                        wag8.toIntOrNull() ?: 0,
                        extraAxles.toIntOrNull() ?: 0
                    )
                    require(counted > 0) { "Количество осей по вагонам получилось 0" }
                    axleCount = counted
                    manualAxleLoad = null
                }
                MassSource.CONSIST -> {
                    require(consist.isNotEmpty()) { "Добавьте хотя бы один локомотив в сплотку" }
                    massValue = consist.sumOf { it.totalMassTons }
                    axleCount = consist.sumOf { it.totalAxles }
                    manualAxleLoad = null
                }
                MassSource.MANUAL_LOAD -> {
                    massValue = mass.requireRuDouble("Введите массу")
                    axleCount = null
                    manualAxleLoad = manualLoad.requireRuDouble("Введите нагрузку на ось")
                }
            }

            val tenChoice = tenChoiceName
                .takeIf { outputMode == OutputMode.STUDY && it.isNotBlank() }
                ?.let(TenTonsChoice::valueOf)
            val massResult = BrakeCalculator.calculateMass(
                MassCalculationInput(
                    massTons = massValue,
                    axleCount = axleCount,
                    manualAxleLoadTons = manualAxleLoad,
                    slopePermille = slope,
                    tenTonsChoice = tenChoice
                )
            )
            val supplementResult = BrakeCalculator.calculateMassSupplement(
                massResult,
                availableShoes.requireNonNegativeInt("Введите количество доступных башмаков"),
                axlesPerHandBrake.requirePositiveInt("Введите число осей на одну единицу ручного тормоза")
            )
            result = massResult
            supplement = supplementResult
            error = null
            showDeveloperEasterEgg = source == MassSource.DIRECT &&
                isDeveloperEasterEgg(massValue, axleCount)
            if (source == MassSource.DIRECT) {
                onDirectCalculation(massValue, axleCount)
            }

            onHistory(
                HistoryRecord(
                    timestampMillis = System.currentTimeMillis(),
                    mode = "По массе",
                    title = "${fmt(massResult.massTons)} т • ${fmt(massResult.slopePermille)}‰",
                    summary = "${massResult.requiredShoes} ТБ • ${massResult.fullManualBrakeAxles} ручн. осей",
                    details = if (supplementResult.shoeShortage > 0) {
                        "Доступно ${supplementResult.availableShoes} ТБ, не хватает ${supplementResult.shoeShortage}; дополнение ${supplementResult.additionalManualAxles} ручн. осей"
                    } else {
                        "Башмаков достаточно, запас ${supplementResult.availableShoes - massResult.requiredShoes}"
                    }
                )
            )
        }.onFailure { error = it.message ?: "Не удалось выполнить расчёт" }
    }

    if (result != null && supplement != null) {
        MassResultCard(result!!, supplement!!, outputMode == OutputMode.STUDY)

        SectionCard(
            "Условия станционного закрепления",
            "Дополнительная проверка по Приложению №12 ИДП"
        ) {
            SwitchRow(
                "Замасленные рельсы",
                "В расчёте ИДП норма увеличивается в 1,5 раза",
                oily
            ) { oily = it }
            NumericField("Скорость ветра, м/с", wind, true) {
                wind = it
                if ((it.toRuDoubleOrNull() ?: 0.0) <= 15.0) windMatches = false
            }
            if ((wind.toRuDoubleOrNull() ?: 0.0) > 15.0) {
                SwitchRow(
                    "Ветер в опасном направлении",
                    "Совпадает с направлением возможного самопроизвольного движения",
                    windMatches
                ) { windMatches = it }
            }
            WarningBox(
                "Замасленность и ветер не изменяют расчёт по массе. " +
                    "Они учитываются отдельным расчётом по Приложению №12 ИДП."
            )
            Button(
                onClick = {
                    val windValue = wind.toRuDoubleOrNull()
                    if (windValue == null || !windValue.isFinite() || windValue < 0.0) {
                        error = "Введите конечную неотрицательную скорость ветра"
                    } else if (result!!.axleCount == null) {
                        error = "Для перехода к ИДП укажите количество осей: нагрузка введена вручную"
                    } else {
                        onOpenAppendix(
                            AppendixPrefill(
                                axleCount = result!!.axleCount,
                                oilyRails = oily,
                                windSpeedMs = fmt(windValue),
                                windMatches = windMatches
                            )
                        )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp)
            ) { Text("Рассчитать закрепление по ИДП №12") }
        }
    }

    if (showDeveloperEasterEgg) {
        AlertDialog(
            onDismissRequest = { showDeveloperEasterEgg = false },
            title = { Text("Разработчик") },
            text = { Text("Кузнецов Д.С. (DomEnota)") },
            confirmButton = {
                TextButton(onClick = { showDeveloperEasterEgg = false }) {
                    Text("Хорошо")
                }
            }
        )
    }

    SafetyNotice()
}

@Composable
private fun MassResultCard(
    result: MassCalculationResult,
    supplement: MassSupplementResult,
    study: Boolean
) {
    val enough = supplement.shoeShortage == 0
    HeroResult(
        title = "${result.requiredShoes} башмак(ов)",
        subtitle = if (enough) "Доступного количества достаточно" else "Не хватает ${supplement.shoeShortage}, потребуется дополнение ручным тормозом",
        success = enough
    )

    SectionCard("Расчёт", null) {
        Metric("Масса", "${fmt(result.massTons)} т")
        result.axleCount?.let { Metric("Оси", it.toString()) }
        Metric(
            if (result.axleCount != null) "Средняя расчётная нагрузка" else "Введённая нагрузка на ось",
            "${fmt(result.axleLoadTons)} т/ось"
        )
        Metric("Категория III.4", if (result.heavyCategory) "10 тс/ось и более" else "менее 10 тс/ось")
        Metric("Уклон", "${fmt(result.slopePermille)}‰")
        Metric("Требуется башмаков", result.requiredShoes.toString(), true)
        Metric("Полная норма ручных тормозных осей", result.fullManualBrakeAxles.toString(), true)
        Metric("Доступно башмаков", supplement.availableShoes.toString())
        if (result.isExactlyTenTons && !result.heavyCategory) {
            WarningBox("Показано учебное сравнение по строке <10 т/ось. При ровно 10,00 т/ось нормативно применяется строка «10 т/ось и более».")
        }
    }

    if (!enough) {
        SectionCard("Дополнение ручным тормозом", "Имеющиеся башмаки продолжают использоваться") {
            Metric("Недостаёт башмаков", supplement.shoeShortage.toString(), true)
            Metric("Дополнительно ручных тормозных осей", supplement.additionalManualAxles.toString(), true)
            Metric("Единиц с ручным тормозом", supplement.requiredHandBrakeUnits.toString(), true)
            Metric("Фактически заторможено осей", supplement.actuallyBrakedAxles.toString())
            Metric("Запас по осям", supplement.reserveManualAxles.toString())
            WarningBox("Пропорциональное дополнение башмаков ручными тормозами является расчётной функцией приложения. Для эксплуатационного применения сверяйте установленный порядок.")
        }
    }

    if (!study) {
        Text(
            "Дробные результаты округляются вверх как реализация приложения; это не выдаётся за отдельную дословную строку таблицы III.4.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (study) {
        SectionCard("Как рассчитано", "Учебный разбор") {
            result.axleCount?.let {
                Formula("Средняя расчётная нагрузка: q = M / n = ${fmt(result.massTons)} / $it = ${fmt(result.axleLoadTons)} т/ось")
            }
            Formula("K = M × k / 100 = ${fmt(result.massTons)} × ${fmt(result.shoeCoefficientPer100Tons)} / 100 = ${fmt(result.shoesExact)} → ${result.requiredShoes} ТБ")
            Formula("Nруч = M × kруч / 100 = ${fmt(result.massTons)} × ${fmt(result.manualAxleCoefficientPer100Tons)} / 100 = ${fmt(result.manualAxlesExact)} → ${result.fullManualBrakeAxles} осей")
            Text(
                "Дробные результаты округляются вверх как реализация приложения; это пояснение не выдаётся за отдельную дословную норму таблицы III.4.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (supplement.shoeShortage > 0) {
                Formula("осей/ТБ = ${fmt(result.manualAxleCoefficientPer100Tons)} / ${fmt(result.shoeCoefficientPer100Tons)} = ${fmt(supplement.manualAxlesPerShoeEquivalent)}")
                Formula("Остаток = max(0; ${fmt(result.shoesExact)} − ${supplement.availableShoes}) = ${fmt(supplement.remainingShoeEquivalent)} ТБ")
                Formula("Nдоп = ${fmt(supplement.remainingShoeEquivalent)} × ${fmt(supplement.manualAxlesPerShoeEquivalent)} = ${fmt(supplement.additionalManualAxlesExact)} → ${supplement.additionalManualAxles} осей")
            }
        }
    }
}

@Composable
private fun LocomotiveConsistPicker(
    consist: List<ConsistItem>,
    onChange: (List<ConsistItem>) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    OutlinedTextField(
        value = query,
        onValueChange = { query = it },
        modifier = Modifier.fillMaxWidth(),
        label = { Text("Поиск локомотива") },
        singleLine = true,
        shape = RoundedCornerShape(16.dp)
    )

    val found = remember(query) { LocomotiveDatabase.search(query).take(8) }
    found.forEach { loco ->
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
            onClick = {
                val existing = consist.indexOfFirst { it.locomotive.name == loco.name }
                val updated = if (existing >= 0) {
                    consist.toMutableList().also { list ->
                        val old = list[existing]
                        list[existing] = old.copy(quantity = old.quantity + 1)
                    }
                } else consist + ConsistItem(loco, 1)
                onChange(updated)
            }
        ) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(loco.name, fontWeight = FontWeight.Bold)
                Text(
                    "${fmt(loco.massTons)} т • ${loco.axles} осей • ${loco.category}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    if (consist.isNotEmpty()) {
        Text("В сплотке", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        consist.forEachIndexed { index, item ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Column(Modifier.weight(1f)) {
                    Text("${item.locomotive.name} × ${item.quantity}", fontWeight = FontWeight.SemiBold)
                    Text(
                        "${fmt(item.totalMassTons)} т • ${item.totalAxles} осей",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                OutlinedButton(
                    onClick = {
                        val list = consist.toMutableList()
                        if (item.quantity <= 1) list.removeAt(index)
                        else list[index] = item.copy(quantity = item.quantity - 1)
                        onChange(list)
                    },
                    shape = RoundedCornerShape(12.dp)
                ) { Text("−") }
                OutlinedButton(
                    onClick = {
                        val list = consist.toMutableList()
                        list[index] = item.copy(quantity = item.quantity + 1)
                        onChange(list)
                    },
                    shape = RoundedCornerShape(12.dp)
                ) { Text("+") }
            }
        }
    }
}

@Composable
private fun AppendixScreen(
    prefill: AppendixPrefill?,
    onHistory: (HistoryRecord) -> Unit
) {
    val context = LocalContext.current
    val inputPrefs = remember { context.getSharedPreferences("calculation_inputs", android.content.Context.MODE_PRIVATE) }
    var axles by rememberSaveable(prefill?.token) { mutableStateOf(prefill?.axleCount?.toString() ?: inputPrefs.getString("axles", "").orEmpty()) }
    var profileName by rememberSaveable { mutableStateOf(ProfileMode.NORMAL.name) }
    var slope by rememberSaveable { mutableStateOf(inputPrefs.getString("slope", "8").orEmpty()) }
    var formulaName by rememberSaveable { mutableStateOf(AppendixFormula.FORMULA_1.name) }
    var oily by rememberSaveable(prefill?.token) { mutableStateOf(prefill?.oilyRails ?: false) }
    var wind by rememberSaveable(prefill?.token) { mutableStateOf(prefill?.windSpeedMs ?: "0") }
    var windMatches by rememberSaveable(prefill?.token) { mutableStateOf(prefill?.windMatches ?: false) }
    var availableShoes by rememberSaveable { mutableStateOf(inputPrefs.getString("available_shoes", "").orEmpty()) }
    var axlesPerHandBrake by rememberSaveable { mutableStateOf("") }
    var leavingWithoutLocomotive by rememberSaveable { mutableStateOf(true) }
    var point20ConditionName by rememberSaveable { mutableStateOf("") }
    var result by remember { mutableStateOf<Appendix12Result?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    val profile = ProfileMode.valueOf(profileName)
    val slopeValue = slope.toRuDoubleOrNull() ?: 0.0

    SectionCard("Закрепляемая группа", "Расчёт по конкретному пути или его отрезку") {
        NumericField("Количество осей", axles, false) { axles = it; inputPrefs.edit().putString("axles", it).apply(); result = null }
        Text("Подсказка по выбору уклона", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        ChoiceOption("Обычный", "Уклон задан для пути", profile == ProfileMode.NORMAL) { profileName = ProfileMode.NORMAL.name; result = null }
        ChoiceOption("Ломаный, весь путь", "Используется средний уклон всего пути", profile == ProfileMode.BROKEN_FULL_TRACK) { profileName = ProfileMode.BROKEN_FULL_TRACK.name; result = null }
        ChoiceOption("Отдельный отрезок", "Используется фактический уклон отрезка", profile == ProfileMode.SEPARATE_SEGMENT) { profileName = ProfileMode.SEPARATE_SEGMENT.name; result = null }
        NumericField("Уклон, ‰", slope, true) { slope = it; inputPrefs.edit().putString("slope", it).apply(); result = null }
    }

    if (slopeValue > 0.5) {
        SectionCard("Расчётная формула", null) {
            ChoiceOption(
                "Формула №1",
                "K = n × (1,5i + 1) / 200. Однородный состав; также смешанный, если башмаки укладываются под вагоны ≥15 т/ось, а при их отсутствии — под наиболее тяжёлые вагоны группы.",
                formulaName == AppendixFormula.FORMULA_1.name
            ) { formulaName = AppendixFormula.FORMULA_1.name; result = null }
            ChoiceOption(
                "Формула №2",
                "K = n × (4i + 1) / 200. Башмаки укладываются под порожние вагоны; под вагоны <15 т/ось, если они не самые тяжёлые в группе; либо нагрузка на ось неизвестна.",
                formulaName == AppendixFormula.FORMULA_2.name
            ) { formulaName = AppendixFormula.FORMULA_2.name; result = null }
        }
    }

    SectionCard("Условия", "Эти факторы относятся именно к станционному закреплению") {
        SwitchRow(
            "Состав оставляется без локомотива",
            "Проверка специальных условий п. 20 приложения №12 ИДП",
            leavingWithoutLocomotive
        ) { leavingWithoutLocomotive = it; result = null }
        if (leavingWithoutLocomotive && slopeValue > 2.5) {
            WarningBox("При уклоне более 2,5‰ оставление без локомотива запрещено, кроме случаев п. 20 приложения №12. Выберите реально выполненное условие; без него расчёт блокируется.")
            ChoiceOption(
                "Маршрут со стороны спуска защищён или изолирован",
                "Условие подтверждено ТРА и фактической обстановкой",
                point20ConditionName == "route"
            ) { point20ConditionName = "route"; result = null }
            ChoiceOption(
                "Применено стационарное устройство закрепления",
                "Параметры устройства достаточны для этой группы",
                point20ConditionName == "device"
            ) { point20ConditionName = "device"; result = null }
        }
        SwitchRow("Замасленные рельсы", "Норма по п. 8 увеличивается в 1,5 раза", oily) { oily = it; result = null }
        NumericField("Скорость ветра, м/с", wind, true) { wind = it; result = null }
        if ((wind.toRuDoubleOrNull() ?: 0.0) > 15.0) {
            SwitchRow(
                "Ветер в опасном направлении",
                "Совпадает с направлением возможного самопроизвольного движения",
                windMatches
            ) { windMatches = it; result = null }
        }
    }

    SectionCard("Фактические средства", null) {
        NumericField("Доступно тормозных башмаков (например, 16)", availableShoes, false) { availableShoes = it; inputPrefs.edit().putString("available_shoes", it).apply(); result = null }
        NumericField("Оси на одну единицу стояночного тормоза (например, 4)", axlesPerHandBrake, false) { axlesPerHandBrake = it; result = null }
    }

    error?.let { ErrorBox(it) }

    CalculateButton {
        runCatching {
            val resultValue = BrakeCalculator.calculateAppendix12(
                Appendix12Input(
                    axleCount = axles.requirePositiveInt("Введите количество осей"),
                    profileMode = profile,
                    slopePermille = slope.requireNonNegativeDouble("Введите уклон"),
                    formula = if (slopeValue <= 0.5) null else AppendixFormula.valueOf(formulaName),
                    oilyRails = oily,
                    windSpeedMs = wind.requireNonNegativeDouble("Введите скорость ветра"),
                    windDirectionMatchesPossibleMovement = windMatches,
                    availableShoes = availableShoes.requireNonNegativeInt("Введите количество доступных башмаков"),
                    axlesPerHandBrakeUnit = axlesPerHandBrake.requirePositiveInt("Введите число осей на одну единицу стояночного тормоза"),
                    leavingWithoutLocomotive = leavingWithoutLocomotive,
                    point20ConditionConfirmed = point20ConditionName.isNotBlank()
                )
            )
            result = resultValue
            error = null
            onHistory(
                HistoryRecord(
                    timestampMillis = System.currentTimeMillis(),
                    mode = "ИДП №12",
                    title = "${resultValue.totalRequiredShoes} ТБ • ${fmt(slopeValue)}‰",
                    summary = "${axles.toIntOrNull() ?: 0} осей • доступно ${resultValue.availableShoes} ТБ",
                    details = if (resultValue.shoeShortage > 0) {
                        if (resultValue.specialUnderHalfPermilleRuleUsed) {
                            if (resultValue.extraShoesAfterSpecialRule > 0) {
                                "Уклон <0,5‰: 1 стояночный тормоз вместо базовой пары; дополнительная нехватка ${resultValue.extraShoesAfterSpecialRule} ТБ → ${resultValue.substituteBrakeAxles} тормозных осей"
                            } else {
                                "Уклон <0,5‰: вместо базовых башмаков с обеих сторон — 1 единица со стояночным тормозом"
                            }
                        } else {
                            "Не хватает ${resultValue.shoeShortage} ТБ; замена ${resultValue.substituteBrakeAxles} тормозных осей"
                        }
                    } else "Норма по количеству башмаков обеспечена"
                )
            )
        }.onFailure { error = it.message ?: "Не удалось выполнить расчёт" }
    }

    result?.let { AppendixResultCard(it, slopeValue, oily) }
    SafetyNotice()
}

@Composable
private fun AppendixResultCard(result: Appendix12Result, slope: Double, oily: Boolean) {
    val enough = result.shoeShortage == 0
    HeroResult(
        title = "${result.totalRequiredShoes} башмак(ов)",
        subtitle = if (enough) "Норма по количеству башмаков обеспечена" else "Не хватает ${result.shoeShortage} башмак(ов)",
        success = enough
    )
    SectionCard("Разбор закрепления", null) {
        Metric(
            if (result.isLowSlopeRule) "Базовая норма всего" else "Со стороны спуска",
            result.baseShoes.toString()
        )
        if (result.oppositeSideShoes > 0) Metric("С противоположной стороны", result.oppositeSideShoes.toString())
        if (result.windShoes > 0) Metric("Дополнительно из-за ветра", result.windShoes.toString())
        Metric("Итого требуется", result.totalRequiredShoes.toString(), true)
        Metric("Доступно", result.availableShoes.toString())
        if (oily) Metric("Замасленные рельсы", "×1,5")
        if (result.isLowSlopeRule) {
            Formula("Уклон ${fmt(slope)}‰ ≤ 0,5‰: базово по одному башмаку с каждой стороны")
            if (result.baseShoes > 2) {
                WarningBox("После увеличения нормы требуется ${result.baseShoes} башмака. Распределение дополнительных башмаков уточняется по ТРА/локальному нормативному акту.")
            }
        } else {
            val formula = when (result.formulaUsed) {
                AppendixFormula.FORMULA_1 -> "K = n × (1,5i + 1) / 200"
                AppendixFormula.FORMULA_2 -> "K = n × (4i + 1) / 200"
                null -> ""
            }
            Formula("$formula = ${fmt(result.baseExactBeforeOil)}${if (oily) " → ×1,5 = ${fmt(result.baseExactAfterOil)}" else ""} → ${result.baseShoes}")
        }
    }
    if (!enough) {
        SectionCard(
            "Недостаток башмаков",
            if (result.specialUnderHalfPermilleRuleUsed)
                "При уклоне <0,5‰ использована специальная норма стояночного тормоза"
            else
                "По Приложению №12: 5 тормозных осей заменяют 1 башмак в предусмотренных случаях"
        ) {
            Metric("Не хватает башмаков", result.shoeShortage.toString(), true)
            if (result.specialUnderHalfPermilleRuleUsed) {
                Metric("Специальная замена", "1 стояночный тормоз вместо базовых башмаков с обеих сторон", true)
                if (result.extraShoesAfterSpecialRule > 0) {
                    Metric("Доп. нехватка после спец. нормы", "${result.extraShoesAfterSpecialRule} ТБ")
                    Metric("Для доп. нехватки требуется осей", result.substituteBrakeAxles.toString(), true)
                }
            } else {
                Metric("Требуется тормозных осей", result.substituteBrakeAxles.toString(), true)
            }
            Metric("Единиц со стояночным тормозом", result.requiredHandBrakeUnits.toString(), true)
            Metric("Фактически задействовано осей", result.actuallyBrakedAxles.toString())
            if (!result.specialUnderHalfPermilleRuleUsed || result.extraShoesAfterSpecialRule > 0) {
                Metric("Запас по осям", result.reserveBrakeAxles.toString())
            }
            WarningBox("Замена башмаков стояночными тормозами применяется только в случаях, предусмотренных нормативным и локальным порядком.")
        }
    }
}

@Composable
private fun HistoryScreen(repository: HistoryRepository, version: Int, onCleared: () -> Unit) {
    val records = remember(version) { repository.load() }
    var filter by rememberSaveable { mutableStateOf("Все") }
    val visible = remember(records, filter) {
        when (filter) {
            "По массе" -> records.filter { it.mode == "По массе" }
            "ИДП №12" -> records.filter { it.mode == "ИДП №12" }
            else -> records
        }
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                RailSectionHeader("История расчётов", "Локальный журнал рабочих расчётов")
            }
            if (records.isNotEmpty()) {
                TextButton(onClick = { repository.clear(); onCleared() }) { Text("Очистить") }
            }
        }

        if (records.isNotEmpty()) {
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(listOf("Все", "По массе", "ИДП №12")) { item ->
                    FilterChip(
                        selected = filter == item,
                        onClick = { filter = item },
                        label = { Text(item) }
                    )
                }
            }
        }

        if (records.isEmpty()) {
            EmptyCard("История пока пуста. После первого расчёта здесь появится запись.")
        } else if (visible.isEmpty()) {
            EmptyCard("В выбранной категории записей пока нет.")
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                items(visible) { record ->
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Column(Modifier.padding(15.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                RailStatusPill(record.mode)
                                Spacer(Modifier.weight(1f))
                                Text(formatDate(record.timestampMillis), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Text(record.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black)
                            Text(record.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
                            Text(record.details, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LocomotiveReferenceScreen(
    workingFamily: TechnicalFamily?,
    viewingFamily: TechnicalFamily,
    onViewingFamilyChange: (TechnicalFamily) -> Unit,
    onOpenTechnical: (TechnicalFamily, TechnicalSection) -> Unit,
    onOpenInteractiveVl80s: () -> Unit,
    onOpenInteractiveErmak: () -> Unit,
    onOpenInteractiveChme3: (TechnicalFamily) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var seriesExpanded by rememberSaveable { mutableStateOf(false) }
    var browsingFamilyName by rememberSaveable(viewingFamily) {
        mutableStateOf(viewingFamily.name)
    }
    val family = TechnicalFamily.entries.firstOrNull { it.name == browsingFamilyName }
    val found = remember(query) { LocomotiveDatabase.search(query) }
    Column(
        modifier = Modifier.fillMaxSize().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(7.dp)
    ) {
        RailSectionHeader(
            "Локомотивы / атлас",
            "Выберите серию и тип материала"
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LocomotiveCatalogRegistry.families.forEach { option ->
                FilterChip(
                    selected = family == option,
                    onClick = {
                        browsingFamilyName = option.name
                        onViewingFamilyChange(option)
                    },
                    label = { Text(option.title) }
                )
            }
        }
        if (workingFamily != null && family != null && family != workingFamily) Text(
            "Разовый просмотр ${family.title}. Рабочая серия ${workingFamily.title} не изменена.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        if (family == null) Text("Выберите серию для разового просмотра атласа.",
            style = MaterialTheme.typography.bodySmall)
        else {
        Text(family.subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        val materials = buildList<Pair<String, () -> Unit>> {
            add("Интерактивный атлас" to when (family) {
                TechnicalFamily.VL80S -> onOpenInteractiveVl80s
                TechnicalFamily.ERMAK -> onOpenInteractiveErmak
                else -> { { onOpenInteractiveChme3(family) } }
            })
            add("Оборудование" to { onOpenTechnical(family, TechnicalSection.EQUIPMENT) })
            add("Системы" to { onOpenTechnical(family, TechnicalSection.SYSTEMS) })
            add("Статьи" to { onOpenTechnical(family, TechnicalSection.KNOWLEDGE) })
            add("Электросхемы" to { onOpenTechnical(family, TechnicalSection.ELECTRICAL) })
            add("Пневмосхемы" to { onOpenTechnical(family, TechnicalSection.PNEUMATIC) })
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            materials.forEach { (label, action) ->
                FilterChip(
                    selected = false,
                    onClick = action,
                    colors = if (label == "Интерактивный атлас") {
                        FilterChipDefaults.filterChipColors(
                            containerColor = InteractiveAtlasChipContainer,
                            labelColor = InteractiveAtlasChipAccent
                        )
                    } else FilterChipDefaults.filterChipColors(),
                    label = { Text(label) }
                )
            }
        }
        }
        if (!seriesExpanded) Spacer(Modifier.weight(1f))
        Surface(
            modifier = Modifier.fillMaxWidth(),
            onClick = { seriesExpanded = !seriesExpanded },
            shape = RoundedCornerShape(14.dp),
            color = MaterialTheme.colorScheme.surface,
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("Справочная база серий", fontWeight = FontWeight.Bold)
                    Text(
                        if (seriesExpanded) "Нажмите, чтобы свернуть" else "Масса и число осей по сериям",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Text(if (seriesExpanded) "⌃" else "⌄", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
        if (seriesExpanded) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Поиск по серии или типу") },
                singleLine = true,
                shape = RoundedCornerShape(14.dp)
            )
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                items(found) { loco -> LocomotiveCard(loco) }
            }
        }
    }
}

private val InteractiveAtlasChipContainer = Color(0xFF17363A)
private val InteractiveAtlasChipAccent = Color(0xFF65E3D2)

@Composable
private fun LocomotiveCard(loco: LocomotiveSpec) {
    Card(
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 9.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(loco.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                Text("${fmt(loco.massTons)} т", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
                Text("  •  ${loco.axles} ос.", fontWeight = FontWeight.SemiBold)
            }
            Text(loco.category, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (loco.note.isNotBlank()) Text(loco.note, style = MaterialTheme.typography.labelSmall, color = RailTheme.colors.warning)
            Text("${loco.massKind} • ${loco.sourceNote}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SettingsScreen(
    palette: AccentPalette,
    onPaletteChange: (AccentPalette) -> Unit,
    themeMode: AppThemeMode,
    onThemeModeChange: (AppThemeMode) -> Unit
) {
    val context = LocalContext.current
    val packageInfo = remember(context) {
        context.packageManager.getPackageInfo(context.packageName, 0)
    }
    val actualVersionCode = if (android.os.Build.VERSION.SDK_INT >= 28) {
        packageInfo.longVersionCode
    } else {
        @Suppress("DEPRECATION")
        packageInfo.versionCode.toLong()
    }
    SectionCard("Тема", "Светлая используется по умолчанию") {
        ChoiceRow(
            options = AppThemeMode.entries.map { it.title },
            selectedIndex = AppThemeMode.entries.indexOf(themeMode),
            onSelect = { index -> onThemeModeChange(AppThemeMode.entries[index]) }
        )
    }
    SectionCard("Цветовой акцент", "Выбранный цвет работает независимо от светлой или тёмной темы") {
        AccentPalette.entries.forEach { option ->
            ChoiceOption(
                title = option.title,
                subtitle = when (option) {
                    AccentPalette.BLUE -> "Классический холодный синий акцент приложения"
                    AccentPalette.AMBER -> "Тёплый янтарный акцент прежнего оформления"
                    AccentPalette.GREEN -> "Спокойный зелёный для альтернативного оформления"
                    AccentPalette.YELLOW -> "Более светлый сигнальный акцент"
                    AccentPalette.PURPLE -> "Холодный дополнительный акцент"
                },
                selected = palette == option,
                onClick = { onPaletteChange(option) }
            )
        }
    }
    KnowledgeDisplaySettingsSection()
    ExtendedEmergencySettingsSection()
    RailInfoBand("Тема и цветовой акцент настраиваются независимо. Красный по-прежнему зарезервирован для опасности и ОПП.")
    SectionCard("О приложении", "Текущая рабочая сборка") {
        Metric("Версия", "${packageInfo.versionName ?: "—"} ($actualVersionCode)")
        Metric("Профиль", "Рабочий")
    }
    SafetyNotice()
}

@Composable
private fun SectionCard(title: String, subtitle: String?, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(11.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.primary)
                subtitle?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            content()
        }
    }
}

@Composable
private fun ChoiceOption(title: String, subtitle: String, selected: Boolean, onClick: () -> Unit) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
        border = BorderStroke(1.dp, if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.5f) else MaterialTheme.colorScheme.outlineVariant)
    ) {
        Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontWeight = if (selected) FontWeight.Bold else FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun ChoiceRow(options: List<String>, selectedIndex: Int, onSelect: (Int) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        options.forEachIndexed { index, title ->
            if (selectedIndex == index) {
                Button(
                    onClick = { onSelect(index) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(15.dp)
                ) { Text(title, maxLines = 2) }
            } else {
                OutlinedButton(
                    onClick = { onSelect(index) },
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(15.dp)
                ) { Text(title, maxLines = 2) }
            }
        }
    }
}

@Composable
private fun NumericField(
    label: String,
    value: String,
    decimal: Boolean,
    allowText: Boolean = false,
    onValueChange: (String) -> Unit
) {
    OutlinedTextField(
        value = value,
        onValueChange = { next ->
            val filtered = when {
                allowText -> next
                decimal -> next.filter { it.isDigit() || it == ',' || it == '.' }
                else -> next.filter { it.isDigit() }
            }
            onValueChange(filtered)
        },
        modifier = Modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        shape = RoundedCornerShape(16.dp),
        keyboardOptions = KeyboardOptions(
            keyboardType = when {
                allowText -> KeyboardType.Text
                decimal -> KeyboardType.Decimal
                else -> KeyboardType.Number
            }
        )
    )
}

@Composable
private fun SwitchRow(title: String, subtitle: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun CalculateButton(onClick: () -> Unit) {
    Button(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().height(54.dp),
        shape = RoundedCornerShape(18.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
    ) {
        Text("Рассчитать", fontWeight = FontWeight.Black, style = MaterialTheme.typography.titleSmall)
    }
}

@Composable
private fun HeroResult(title: String, subtitle: String, success: Boolean) {
    val colors = RailTheme.colors
    val accent = if (success) colors.success else colors.warning
    val container = if (success) colors.successContainer else colors.warningContainer
    val border = if (success) colors.successBorder else colors.warningBorder
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(24.dp),
        color = container,
        border = BorderStroke(1.dp, border)
    ) {
        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Black, color = accent)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Metric(label: String, value: String, strong: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.Top) {
        Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, modifier = Modifier.weight(0.7f), style = MaterialTheme.typography.bodyMedium, fontWeight = if (strong) FontWeight.Black else FontWeight.SemiBold)
    }
}

@Composable
private fun MiniMetric(label: String, value: String) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.48f)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun Formula(text: String) {
    Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.52f)) {
        Text(text, modifier = Modifier.fillMaxWidth().padding(12.dp), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
    }
}

@Composable
private fun StatusText(text: String, success: Boolean) {
    val colors = RailTheme.colors
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        fontWeight = FontWeight.SemiBold,
        color = if (success) colors.success else colors.warning
    )
}

@Composable
private fun WarningBox(text: String) {
    val colors = RailTheme.colors
    Surface(
        shape = RoundedCornerShape(15.dp),
        color = colors.warningContainer,
        border = BorderStroke(1.dp, colors.warningBorder)
    ) {
        Text(
            text,
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            style = MaterialTheme.typography.bodySmall,
            color = colors.warning
        )
    }
}

@Composable
private fun ErrorBox(text: String) {
    Surface(shape = RoundedCornerShape(16.dp), color = MaterialTheme.colorScheme.error.copy(alpha = 0.08f), border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f))) {
        Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text("Проверьте данные", fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.error)
            Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun EmptyCard(text: String) {
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surface, border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)) {
        Text(text, modifier = Modifier.fillMaxWidth().padding(18.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SafetyNotice() {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(18.dp),
        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.42f),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.primary.copy(alpha = 0.22f))
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Учебный и проверочный инструмент", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onPrimaryContainer)
            Text(
                "Не заменяет ТРА станции, локальные нормативные акты, команды ДСП/ДНЦ и обязательный установленный порядок. Перед практическим применением сверяйте исходные данные и действующие нормы.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

private fun String.toRuDoubleOrNull(): Double? = trim().replace(',', '.').toDoubleOrNull()

private fun String.requireRuDouble(message: String): Double {
    val value = toRuDoubleOrNull()
    require(value != null && value > 0.0) { message }
    return value
}

private fun String.requireNonNegativeDouble(message: String): Double {
    val value = toRuDoubleOrNull()
    require(value != null && value >= 0.0) { message }
    return value
}

private fun String.requirePositiveInt(message: String): Int {
    val value = toIntOrNull()
    require(value != null && value > 0) { message }
    return value
}

private fun String.requireNonNegativeInt(message: String): Int {
    val value = toIntOrNull()
    require(value != null && value >= 0) { message }
    return value
}

private fun ratioOrNull(mass: Double?, axles: Int?): Double? {
    if (mass == null || mass <= 0.0 || axles == null || axles <= 0) return null
    return mass / axles
}

private fun fmt(value: Double): String = String.format(Locale.US, "%.2f", value)
    .trimEnd('0')
    .trimEnd('.')
    .replace('.', ',')

private fun formatDate(timestamp: Long): String = SimpleDateFormat("dd.MM HH:mm", Locale("ru", "RU")).format(Date(timestamp))
