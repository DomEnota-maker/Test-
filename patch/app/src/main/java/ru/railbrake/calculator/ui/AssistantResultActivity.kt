package ru.railbrake.calculator.ui

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import ru.railbrake.calculator.core.TechnicalFamily
import ru.railbrake.calculator.core.TechnicalSection
import ru.railbrake.calculator.core.WorkingLocomotive
import ru.railbrake.calculator.core.assistant.AssistantTarget
import ru.railbrake.calculator.core.KnowledgeRepository
import ru.railbrake.calculator.core.assistant.KnowledgeArticleAssistantAdapter
import ru.railbrake.calculator.data.WorkingLocomotiveRepository
import ru.railbrake.calculator.ui.theme.AccentPalette
import ru.railbrake.calculator.ui.theme.AppThemeMode
import ru.railbrake.calculator.ui.theme.RailBrakeTheme

class AssistantResultActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        setContent {
            val preferences = remember {
                getSharedPreferences("calculation_inputs", Context.MODE_PRIVATE)
            }
            val palette = remember {
                runCatching {
                    AccentPalette.valueOf(
                        preferences.getString("accent_palette", AccentPalette.BLUE.name)
                            ?: AccentPalette.BLUE.name
                    )
                }.getOrDefault(AccentPalette.BLUE)
            }
            val themeMode = remember {
                runCatching {
                    AppThemeMode.valueOf(
                        preferences.getString("theme_mode", AppThemeMode.LIGHT.name)
                            ?: AppThemeMode.LIGHT.name
                    )
                }.getOrDefault(AppThemeMode.LIGHT)
            }

            RailBrakeTheme(palette = palette, themeMode = themeMode) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    ResultContent()
                }
            }
        }
    }

    @Composable
    private fun ResultContent() {
        val kind = intent.getStringExtra(EXTRA_KIND)
        val id = intent.getStringExtra(EXTRA_ID)

        if (kind.isNullOrBlank() || id.isNullOrBlank()) {
            InvalidTarget()
            return
        }

        val working = remember { WorkingLocomotiveRepository(this).selected() }
        val viewed = WorkingLocomotive.fromStored(intent.getStringExtra(EXTRA_VIEW_LOC))
        val targetFamily = when (kind) {
            KIND_VL80_DIAGNOSTIC -> TechnicalFamily.VL80S
            KIND_ERMAK_DIAGNOSTIC -> TechnicalFamily.ERMAK
            KIND_CHME3_DIAGNOSTIC -> TechnicalFamily.entries.firstOrNull {
                it.name == intent.getStringExtra(EXTRA_FAMILY)
            }
            KIND_TECHNICAL -> TechnicalFamily.entries.firstOrNull {
                it.name == intent.getStringExtra(EXTRA_FAMILY)
            }
            KIND_KNOWLEDGE -> KnowledgeRepository.articleById(id)
                ?.let(KnowledgeArticleAssistantAdapter::adapt)?.family
            else -> null
        }
        Column(Modifier.fillMaxSize()) {
            if (working != null && targetFamily != null &&
                (targetFamily != working.family || (viewed != null && viewed != working))) {
                Text("Материал ${viewed?.title ?: targetFamily.title} · рабочий локомотив ${working.title} не изменён",
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelLarge)
            }
            Box(Modifier.weight(1f)) { TargetContent(kind, id, working?.family, working?.variantId) }
        }
    }

    @Composable
    private fun TargetContent(kind: String, id: String, workingFamily: TechnicalFamily?,
                              workingVariantId: String?) {
        when (kind) {
            KIND_VL80_DIAGNOSTIC,
            KIND_ERMAK_DIAGNOSTIC,
            KIND_CHME3_DIAGNOSTIC -> {
                var atlasEquipmentId by rememberSaveable(kind, id) { mutableStateOf<String?>(null) }
                if (atlasEquipmentId != null) {
                    TechnicalCatalogScreen(initialFamily = TechnicalFamily.VL80S,
                        initialSection = TechnicalSection.EQUIPMENT,
                        initialEntryId = atlasEquipmentId,
                        onSectionBack = { atlasEquipmentId = null })
                } else {
                    LocomotiveDiagnosticsScreen(initialScenarioId = id, workingFamily = workingFamily,
                        workingVariantId = workingVariantId,
                        initialFamily = if (kind == KIND_CHME3_DIAGNOSTIC) TechnicalFamily.entries.firstOrNull {
                            it.name == intent.getStringExtra(EXTRA_FAMILY)
                        } else null,
                        onOpenAtlasEquipment = { atlasEquipmentId = it })
                }
            }

            KIND_FIRST_AID -> {
                FirstAidScreen(
                    onBack = { finish() },
                    initialTopicId = id
                )
            }

            KIND_KNOWLEDGE -> {
                KnowledgeBaseScreen(
                    initialArticleId = id,
                    sectionBackLabel = "Помощник",
                    onSectionBack = { finish() }
                )
            }

            KIND_TECHNICAL -> {
                val family = runCatching {
                    TechnicalFamily.valueOf(intent.getStringExtra(EXTRA_FAMILY).orEmpty())
                }.getOrNull()
                val section = runCatching {
                    TechnicalSection.valueOf(intent.getStringExtra(EXTRA_SECTION).orEmpty())
                }.getOrNull()

                if (family == null || section == null) {
                    InvalidTarget()
                    return
                }

                TechnicalCatalogScreen(
                    initialFamily = family,
                    initialSection = section,
                    lockFamily = workingFamily != null,
                    sectionBackLabel = "Помощник",
                    onSectionBack = { finish() },
                    initialEntryId = id,
                    onOpenLegacyArticle = { articleId ->
                        startActivity(createIntent(this, AssistantTarget.Knowledge(articleId)))
                    },
                    onOpenDiagnosticScenario = { scenarioId, _, _ ->
                        val target = if (family.isChme3) {
                            AssistantTarget.Chme3Diagnostic(family, scenarioId)
                        } else if (
                            family == TechnicalFamily.ERMAK || scenarioId.startsWith("ER-DIAG-")
                        ) {
                            AssistantTarget.ErmakDiagnostic(scenarioId)
                        } else {
                            AssistantTarget.Vl80Diagnostic(scenarioId)
                        }
                        startActivity(createIntent(this, target))
                    }
                )
            }

            else -> InvalidTarget()
        }
    }

    @Composable
    private fun InvalidTarget() {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Не удалось открыть найденный материал",
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                "Поисковый результат не содержит корректного адреса карточки.",
                style = MaterialTheme.typography.bodyMedium
            )
            Button(onClick = { finish() }) {
                Text("Назад")
            }
        }
    }

    companion object {
        private const val EXTRA_KIND = "assistant_kind"
        private const val EXTRA_ID = "assistant_id"
        private const val EXTRA_FAMILY = "assistant_family"
        private const val EXTRA_SECTION = "assistant_section"
        private const val EXTRA_VIEW_LOC = "assistant_view_locomotive"

        private const val KIND_TECHNICAL = "technical"
        private const val KIND_VL80_DIAGNOSTIC = "vl80_diagnostic"
        private const val KIND_ERMAK_DIAGNOSTIC = "ermak_diagnostic"
        private const val KIND_CHME3_DIAGNOSTIC = "chme3_diagnostic"
        private const val KIND_KNOWLEDGE = "knowledge"
        private const val KIND_FIRST_AID = "first_aid"

        fun createIntent(context: Context, target: AssistantTarget,
                         viewed: WorkingLocomotive? = null): Intent =
            Intent(context, AssistantResultActivity::class.java).apply {
                viewed?.let { putExtra(EXTRA_VIEW_LOC, it.name) }
                when (target) {
                    is AssistantTarget.Technical -> {
                        putExtra(EXTRA_KIND, KIND_TECHNICAL)
                        putExtra(EXTRA_ID, target.entryId)
                        putExtra(EXTRA_FAMILY, target.family.name)
                        putExtra(EXTRA_SECTION, target.section.name)
                    }

                    is AssistantTarget.Vl80Diagnostic -> {
                        putExtra(EXTRA_KIND, KIND_VL80_DIAGNOSTIC)
                        putExtra(EXTRA_ID, target.scenarioId)
                    }

                    is AssistantTarget.ErmakDiagnostic -> {
                        putExtra(EXTRA_KIND, KIND_ERMAK_DIAGNOSTIC)
                        putExtra(EXTRA_ID, target.scenarioId)
                    }

                    is AssistantTarget.Chme3Diagnostic -> {
                        putExtra(EXTRA_KIND, KIND_CHME3_DIAGNOSTIC)
                        putExtra(EXTRA_ID, target.scenarioId)
                        putExtra(EXTRA_FAMILY, target.family.name)
                    }

                    is AssistantTarget.Knowledge -> {
                        putExtra(EXTRA_KIND, KIND_KNOWLEDGE)
                        putExtra(EXTRA_ID, target.articleId)
                    }

                    is AssistantTarget.FirstAid -> {
                        putExtra(EXTRA_KIND, KIND_FIRST_AID)
                        putExtra(EXTRA_ID, target.topicId)
                    }
                }
            }

        fun intent(context: Context, target: AssistantTarget,
                   viewed: WorkingLocomotive? = null): Intent =
            createIntent(context, target, viewed)
    }
}
