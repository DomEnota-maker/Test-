package ru.railbrake.calculator.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ru.railbrake.calculator.core.LocomotiveCatalogRegistry
import ru.railbrake.calculator.core.DiagnosticFrameworkV2
import ru.railbrake.calculator.core.FrameworkDiagnosticModule
import ru.railbrake.calculator.core.TechnicalFamily

@Composable
fun LocomotiveDiagnosticsScreen(
    initialScenarioId: String? = null,
    initialEquipmentId: String? = null,
    initialFamily: TechnicalFamily? = null,
    workingFamily: TechnicalFamily? = null,
    workingVariantId: String? = null,
    onFamilyChange: (TechnicalFamily) -> Unit = {},
    onOpenAtlasEquipment: (String) -> Unit
) {
    val context = LocalContext.current
    val frameworkModules = remember(context) { DiagnosticFrameworkV2.load(context) }
    val linkedFamily = if (initialScenarioId != null || initialEquipmentId != null)
        frameworkModules.firstOrNull { it.scenarioId == initialScenarioId }?.let { module ->
            LocomotiveCatalogRegistry.profiles.firstOrNull { it.id == module.profileId }?.family
        } ?: diagnosticInitialFamily(initialScenarioId, initialEquipmentId) else null
    var familyName by rememberSaveable(workingFamily, initialFamily, initialScenarioId, initialEquipmentId) {
        mutableStateOf((initialFamily ?: linkedFamily ?: workingFamily)?.name.orEmpty())
    }
    val family = TechnicalFamily.entries.firstOrNull { it.name == familyName }
    val familyModules = family?.let { frameworkModulesForFamily(frameworkModules, it) }.orEmpty()
    var selectedModuleId by rememberSaveable(familyName, initialScenarioId) {
        mutableStateOf(initialScenarioId?.takeIf { id -> familyModules.any { it.scenarioId == id } })
    }
    val selectedModule = familyModules.firstOrNull { it.scenarioId == selectedModuleId }
    BackHandler(enabled = selectedModule != null) { selectedModuleId = null }
    Column(Modifier.fillMaxSize()) {
        if (family == null) {
            Text("Выберите серию для диагностики. Это разовый просмотр; рабочий локомотив не изменится.",
                modifier = Modifier.padding(16.dp), style = MaterialTheme.typography.bodyMedium)
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            LocomotiveCatalogRegistry.families.forEach { option ->
                FilterChip(
                    selected = family == option,
                    onClick = { familyName = option.name; onFamilyChange(option) },
                    label = { Text(option.title) }
                )
            }
        }
        if (workingFamily != null && family != null && family != workingFamily) {
            Text("Материал другой серии. Рабочий локомотив не изменён.",
                modifier = Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
        }
        if (family != null) Box(Modifier.fillMaxWidth().weight(1f)) {
            if (family == TechnicalFamily.VL80S)
                DiagnosticScreen(diagnosticScenarioForFamily(initialScenarioId, family),
                    diagnosticEquipmentForFamily(initialEquipmentId, family), onOpenAtlasEquipment)
            else if (selectedModule != null) FrameworkStandaloneDetails(
                module = selectedModule, onBack = { selectedModuleId = null },
                initialVariantId = workingVariantId.takeIf { family == workingFamily },
                onOpenAtlasEquipment = onOpenAtlasEquipment)
            else Column {
                if (familyModules.isNotEmpty()) {
                    Text("Модули базы знаний", modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.titleMedium)
                    LazyRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(familyModules, key = { it.scenarioId }) { module ->
                            Card(onClick = { selectedModuleId = module.scenarioId }) {
                                Text(module.title, Modifier.padding(12.dp))
                            }
                        }
                    }
                }
                Box(Modifier.fillMaxWidth().weight(1f)) {
                    ErmakDiagnosticsScreen(
                        initialScenarioId = diagnosticScenarioForFamily(initialScenarioId, family),
                        initialEquipmentId = diagnosticEquipmentForFamily(initialEquipmentId, family),
                        workingVariantId = workingVariantId.takeIf { family == workingFamily },
                        family = family
                    )
                }
            }
        }
    }
}

internal fun frameworkModulesForFamily(modules: List<FrameworkDiagnosticModule>, family: TechnicalFamily):
    List<FrameworkDiagnosticModule> = modules.filter {
        it.profileId == LocomotiveCatalogRegistry.profile(family).id
    }

internal fun diagnosticInitialFamily(scenarioId: String?, equipmentId: String?): TechnicalFamily =
    when {
        scenarioId?.startsWith("TEM2U-") == true || equipmentId?.startsWith("TEM2U-") == true -> TechnicalFamily.TEM2U
        scenarioId?.startsWith("TEM2-") == true || equipmentId?.startsWith("TEM2-") == true -> TechnicalFamily.TEM2
        scenarioId?.startsWith("CHME3E-") == true || equipmentId?.startsWith("CHME3E-") == true -> TechnicalFamily.CHME3E
        scenarioId?.startsWith("CHME3T-") == true || equipmentId?.startsWith("CHME3T-") == true -> TechnicalFamily.CHME3T
        scenarioId?.startsWith("CHME3-") == true || equipmentId?.startsWith("CHME3-") == true -> TechnicalFamily.CHME3
        scenarioId?.startsWith("ER-DIAG-") == true || equipmentId?.startsWith("ER-EQ-") == true -> TechnicalFamily.ERMAK
        else -> TechnicalFamily.VL80S
    }

internal fun diagnosticScenarioForFamily(id: String?, family: TechnicalFamily): String? = when (family) {
    TechnicalFamily.VL80S -> id?.takeUnless { it.startsWith("ER-") }
    TechnicalFamily.ERMAK -> id?.takeIf { it.startsWith("ER-DIAG-") }
    TechnicalFamily.CHME3 -> id?.takeIf { it.startsWith("CHME3-") }
    TechnicalFamily.CHME3T -> id?.takeIf { it.startsWith("CHME3-") || it.startsWith("CHME3T-") }
    TechnicalFamily.CHME3E -> id?.takeIf { it.startsWith("CHME3-") || it.startsWith("CHME3E-") }
    TechnicalFamily.TEM2 -> id?.takeIf { it.startsWith("TEM2-") }
    TechnicalFamily.TEM2U -> id?.takeIf { it.startsWith("TEM2-") || it.startsWith("TEM2U-") }
}

internal fun diagnosticEquipmentForFamily(id: String?, family: TechnicalFamily): String? = when (family) {
    TechnicalFamily.VL80S -> id?.takeUnless { it.startsWith("ER-") }
    TechnicalFamily.ERMAK -> id?.takeIf { it.startsWith("ER-EQ-") }
    TechnicalFamily.CHME3 -> id?.takeIf { it.startsWith("CHME3-") }
    TechnicalFamily.CHME3T -> id?.takeIf { it.startsWith("CHME3-") || it.startsWith("CHME3T-") }
    TechnicalFamily.CHME3E -> id?.takeIf { it.startsWith("CHME3-") || it.startsWith("CHME3E-") }
    TechnicalFamily.TEM2 -> id?.takeIf { it.startsWith("TEM2-") }
    TechnicalFamily.TEM2U -> id?.takeIf { it.startsWith("TEM2-") || it.startsWith("TEM2U-") }
}
