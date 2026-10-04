package ru.railbrake.calculator.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import ru.railbrake.calculator.data.KnowledgeDepth
import ru.railbrake.calculator.data.KnowledgeDisplayRepository
import ru.railbrake.calculator.data.KnowledgeMode

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun KnowledgeDisplaySettingsSection() {
    val context = LocalContext.current
    val repository = remember(context) { KnowledgeDisplayRepository(context) }
    var mode by remember { mutableStateOf(repository.mode()) }
    var depth by remember { mutableStateOf(repository.depth()) }
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Карточка диагностики", style = MaterialTheme.typography.titleMedium)
            Text("Объём знаний в диагностической карточке", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KnowledgeMode.entries.forEach { option ->
                    FilterChip(selected = mode == option, onClick = {
                        repository.setMode(option); mode = option
                    }, label = { Text(option.title) })
                }
            }
            Text("Глубина отображения", style = MaterialTheme.typography.bodyMedium)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                KnowledgeDepth.entries.forEach { option ->
                    FilterChip(selected = depth == option, onClick = {
                        repository.setDepth(option); depth = option
                    }, label = { Text(option.title) })
                }
            }
            Text(
                "Здесь открываются объяснения и учебные материалы. Аварийные приёмы регулируются отдельным переключателем ниже.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
