package ru.railbrake.calculator.ui

import android.content.Context
import android.graphics.Paint
import android.graphics.Typeface
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.clip
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalDensity
import org.json.JSONObject
import ru.railbrake.calculator.core.TechnicalDataRepository
import ru.railbrake.calculator.core.TechnicalEntry
import ru.railbrake.calculator.core.TechnicalFamily
import kotlin.math.min
import kotlin.math.max
import kotlin.math.abs

private data class SchemeNode(val key: String, val equipmentId: String, val label: String, val x: Float, val y: Float, val width: Float, val height: Float) {
    val center get() = Offset(x + width / 2f, y + height / 2f)
}
private data class SchemeEdge(val from: String, val to: String, val kind: String, val label: String)
private data class SchemeStep(val title: String, val explanation: String, val edges: List<SchemeEdge>)
private data class SchemeSource(val title: String, val details: String)
private data class SchemeSequence(
    val id: String, val family: TechnicalFamily, val sourceSchemeRef: String, val title: String,
    val disclaimer: String, val nodes: List<SchemeNode>, val steps: List<SchemeStep>,
    val sources: List<SchemeSource>, val left: Float, val top: Float, val right: Float, val bottom: Float
)
private data class FlowStyle(val label: String, val light: Color, val dark: Color, val pattern: String)

private class StepwiseSchemeData(private val context: Context) {
    private fun asset(name: String) = JSONObject(context.assets.open("technical/$name").bufferedReader().use { it.readText() })
    private val palette = asset("scheme_semantic_palette.json")
    val styles: Map<String, FlowStyle> = buildMap {
        val list = palette.getJSONArray("flowTokens")
        for (i in 0 until list.length()) {
            val item = list.getJSONObject(i)
            put(item.getString("flowKind"), FlowStyle(item.getString("label"),
                item.getJSONObject("light").getString("stroke").asColor(),
                item.getJSONObject("dark").getString("stroke").asColor(),
                item.optString("lineStyle")))
        }
    }
    val sequences: List<SchemeSequence> = run {
        val layouts = asset("scheme_layout_metadata.json").getJSONArray("layouts").apply {
            val extra = asset("tem2_scheme_layout_metadata.json").getJSONArray("layouts")
            for (i in 0 until extra.length()) put(extra.getJSONObject(i))
        }
        val byId = (0 until layouts.length()).associate { i ->
            layouts.getJSONObject(i).getString("sequenceId") to layouts.getJSONObject(i)
        }
        val flow = asset("stepwise_scheme_flows.json").getJSONArray("sequences").apply {
            val extra = asset("tem2_stepwise_scheme_flows.json").getJSONArray("sequences")
            for (i in 0 until extra.length()) put(extra.getJSONObject(i))
        }
        (0 until flow.length()).map { i ->
            val item = flow.getJSONObject(i)
            val layout = byId.getValue(item.getString("id"))
            val bounds = layout.getJSONObject("contentBounds")
            val rawNodes = layout.getJSONArray("nodes")
            val nodes = (0 until rawNodes.length()).map { j ->
                val node = rawNodes.getJSONObject(j)
                val pos = node.getJSONObject("position")
                SchemeNode(node.getString("nodeKey"), node.optString("equipmentId"), node.getString("label"),
                    pos.getDouble("x").toFloat(), pos.getDouble("y").toFloat(),
                    pos.getDouble("width").toFloat(), pos.getDouble("height").toFloat())
            }
            val rawSteps = item.getJSONArray("steps")
            val steps = (0 until rawSteps.length()).map { j ->
                val step = rawSteps.getJSONObject(j)
                val rawEdges = step.getJSONArray("edges")
                SchemeStep(step.getString("title"), step.optString("explanation"),
                    (0 until rawEdges.length()).map { k ->
                        val edge = rawEdges.getJSONObject(k)
                        SchemeEdge(edge.optString("fromAnchor").ifBlank { edge.getString("fromEquipmentId") },
                            edge.optString("toAnchor").ifBlank { edge.getString("toEquipmentId") },
                            edge.getString("flowKind"), edge.optString("label"))
                    })
            }
            val rawSources = item.getJSONArray("sourcePresentations")
            val sources = (0 until rawSources.length()).mapNotNull { j ->
                val source = rawSources.getJSONObject(j)
                source.optString("title").takeIf { it.isNotBlank() && it != "Источник схемы" }
                    ?.let { SchemeSource(it, source.optString("documentDetails")) }
            }.distinct()
            SchemeSequence(item.getString("id"), TechnicalFamily.valueOf(item.getString("family")),
                item.getString("sourceSchemeRef"), item.getString("title"), item.getString("functionalDisclaimer"),
                nodes, steps, sources, bounds.getDouble("left").toFloat(), bounds.getDouble("top").toFloat(),
                bounds.getDouble("right").toFloat(), bounds.getDouble("bottom").toFloat())
        }
    }
    fun forEntry(entry: TechnicalEntry): List<SchemeSequence> {
        val source = when (entry.id) {
            "CHME3-INT-PNEUMATIC" -> "CHME3-SCH-PNEUMATIC"
            "CHME3E-INT-START" -> "CHME3E-SCH-START-ELECTRONIC"
            else -> if (entry.family.isTem2) entry.id else entry.id.replace("-INT-", "-SCH-")
        }
        val matching = sequences.filter { it.family == entry.family && it.sourceSchemeRef == source }
        if (matching.isNotEmpty() || !entry.family.isChme3 || entry.hotspots.isEmpty()) return matching
        // Other technical views have no verified edge route. Give them a compact,
        // zoomable equipment map without suggesting that the grid is a pipe/wire layout.
        val nodes = entry.hotspots.sortedWith(compareBy({ it.y }, { it.x })).mapIndexed { index, spot ->
            SchemeNode(spot.equipmentId, spot.equipmentId, spot.label,
                20f + (index % 3) * 210f, 20f + (index / 3) * 105f, 190f, 82f)
        }
        return listOf(SchemeSequence(entry.id, entry.family, entry.id, entry.title,
            "Функциональное расположение элементов; соединения и фактическое размещение оборудования здесь не показаны.",
            nodes, listOf(SchemeStep("Обзор оборудования", "Выберите элемент для открытия карточки.", emptyList())),
            emptyList(), 0f, 0f, 650f, 40f + ((nodes.size + 2) / 3) * 105f))
    }
}

private fun String.asColor(): Color = Color(android.graphics.Color.parseColor(this))

@Composable
internal fun StepwiseSchemeForEntry(
    entry: TechnicalEntry,
    repository: TechnicalDataRepository,
    onOpen: (TechnicalEntry) -> Unit
): Boolean {
    val context = LocalContext.current
    val data = remember(context) { StepwiseSchemeData(context.applicationContext) }
    val available = remember(entry.id, entry.family) { data.forEntry(entry) }
    if (available.isEmpty()) return false
    StepwiseSchemeViewer(available, data.styles, repository, entry, onOpen)
    return true
}

@Composable
private fun StepwiseSchemeViewer(
    sequences: List<SchemeSequence>, styles: Map<String, FlowStyle>,
    repository: TechnicalDataRepository, entry: TechnicalEntry, onOpen: (TechnicalEntry) -> Unit
) {
    var selectedId by rememberSaveable(entry.id) { mutableStateOf(sequences.first().id) }
    val sourceSequence = sequences.firstOrNull { it.id == selectedId } ?: sequences.first()
    val sequence = remember(sourceSequence, repository) { sourceSequence.copy(nodes = sourceSequence.nodes.map { node ->
        node.copy(label = repository.entry(node.equipmentId, entry.family)?.title ?: node.label)
    }) }
    var stepIndex by rememberSaveable(sequence.id) { mutableIntStateOf(0) }
    val step = sequence.steps[stepIndex.coerceIn(sequence.steps.indices)]
    var selectedNode by rememberSaveable(sequence.id) { mutableStateOf<String?>(null) }
    val node = sequence.nodes.firstOrNull { it.key == selectedNode }
    val target = node?.equipmentId?.let { repository.entry(it, entry.family) }
    val isDark = MaterialTheme.colorScheme.background.red < 0.3f
    val background = MaterialTheme.colorScheme.surface
    val foreground = MaterialTheme.colorScheme.onSurface

    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Интерактивная схема ${entry.family.title}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            if (sequences.size > 1) LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(sequences, key = { it.id }) { option ->
                    FilterChip(selected = option.id == sequence.id, onClick = { selectedId = option.id }, label = { Text(option.title) })
                }
            }
            Text(sequence.title, style = MaterialTheme.typography.titleMedium)
            Text(sequence.disclaimer, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            SchemeCanvas(sequence, stepIndex, styles, isDark, background, foreground,
                selectedNode, onSelect = { key ->
                    selectedNode = key
                    sequence.nodes.firstOrNull { it.key == key }?.equipmentId
                        ?.let { repository.entry(it, entry.family) }?.let(onOpen)
                })
            Text("Элементы схемы", fontWeight = FontWeight.Bold)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(sequence.nodes, key = { it.key }) { item ->
                    OutlinedButton(
                        onClick = {
                            selectedNode = item.key
                            item.equipmentId.let { repository.entry(it, entry.family) }?.let(onOpen)
                        },
                        modifier = Modifier.heightIn(min = 48.dp)
                    ) { Text(item.label) }
                }
            }
            Text(if (sequence.steps.size == 1 && step.edges.isEmpty()) step.title
                else "Шаг ${stepIndex + 1} из ${sequence.steps.size}: ${step.title}", fontWeight = FontWeight.Bold)
            if (step.explanation.isNotBlank()) Text(step.explanation)
            step.edges.distinctBy { it.kind to it.label }.forEach { edge ->
                val style = styles[edge.kind]
                if (style != null) Text("→ ${edge.label.ifBlank { style.label }} · ${style.label}",
                    color = if (isDark) style.dark else style.light)
            }
            if (sequence.steps.size > 1) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { stepIndex-- }, enabled = stepIndex > 0, modifier = Modifier.weight(1f)) { Text("← Назад") }
                Button(onClick = { stepIndex++ }, enabled = stepIndex < sequence.steps.lastIndex, modifier = Modifier.weight(1f)) { Text("Дальше →") }
            }
            node?.let {
                Text("Выбрано: ${it.label}", fontWeight = FontWeight.Bold)
                if (target != null) OutlinedButton(onClick = { onOpen(target) }) { Text("Открыть карточку →") }
            }
            if (sequence.sources.isNotEmpty()) {
                Text("Источники", fontWeight = FontWeight.Bold)
                sequence.sources.forEach { source ->
                    Text(listOf(source.title, source.details).filter(String::isNotBlank).joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** A reading layout for the verified functional graph, independent of physical placement. */
private fun readingOrder(sequence: SchemeSequence): List<SchemeNode> {
    val byKey = sequence.nodes.associateBy { it.key }
    val keys = linkedSetOf<String>()
    sequence.steps.forEach { step -> step.edges.forEach { edge ->
        keys += edge.from
        keys += edge.to
    } }
    sequence.nodes.forEach { keys += it.key }
    return keys.mapNotNull(byKey::get)
}

private fun readingLayout(sequence: SchemeSequence, width: Float): List<SchemeNode> {
    val ordered = readingOrder(sequence)
    val columns = if (width >= 560f) 2 else 1
    val margin = 16f
    val gapX = 20f
    val gapY = 36f
    val nodeWidth = (width - margin * 2 - gapX * (columns - 1)) / columns
    val nodeHeight = 96f
    return ordered.mapIndexed { index, node ->
        val row = index / columns
        val col = index % columns
        val visualCol = if (columns == 2 && row % 2 == 1) columns - 1 - col else col
        node.copy(x = margin + visualCol * (nodeWidth + gapX),
            y = margin + row * (nodeHeight + gapY), width = nodeWidth, height = nodeHeight)
    }
}

@Composable
private fun SchemeCanvas(
    sequence: SchemeSequence, stepIndex: Int, styles: Map<String, FlowStyle>, isDark: Boolean,
    background: Color, foreground: Color, selectedNode: String?, onSelect: (String) -> Unit
) {
    var zoom by remember(sequence.id) { mutableFloatStateOf(1f) }
    var shiftX by remember(sequence.id) { mutableFloatStateOf(0f) }
    var shiftY by remember(sequence.id) { mutableFloatStateOf(0f) }
    val currentEdges = sequence.steps[stepIndex].edges.toSet()
    val completedEdges = sequence.steps.take(stepIndex).flatMap { it.edges }.toSet()
    val allEdges = remember(sequence.id) { sequence.steps.flatMap { it.edges }.distinct() }
    val activeNodes = (currentEdges + completedEdges).flatMap { listOf(it.from, it.to) }.toSet()
    val currentNodes = currentEdges.flatMap { listOf(it.from, it.to) }.toSet()
    val highlight = if (isDark) Color(0xFF7DD3FC) else Color(0xFF0369A1)

    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val density = LocalDensity.current
            val width = maxWidth.value
            val nodes = remember(sequence.id, width) { readingLayout(sequence, width) }
            val nodeByKey = remember(nodes) { nodes.associateBy { it.key } }
            val contentHeight = (nodes.maxOfOrNull { it.y + it.height } ?: 0f) + 16f
            val height = max(290f, contentHeight)
            val widthPx = with(density) { maxWidth.toPx() }
            val heightPx = with(density) { height.dp.toPx() }
            val insetPx = with(density) { 8.dp.toPx() }
            val fit = min((widthPx - insetPx * 2) / width, (heightPx - insetPx * 2) / height)
            val center = Offset(widthPx / 2f, heightPx / 2f)
            val contentCenter = Offset(width / 2f, height / 2f)
            fun clamp(value: Float, extent: Float, viewport: Float): Float {
                val overscroll = with(density) { 16.dp.toPx() }
                val range = max(overscroll, (extent * fit * zoom - viewport) / 2f + overscroll)
                return value.coerceIn(-range, range)
            }
            val gestures = Modifier.fillMaxSize()
                .pointerInput(sequence.id, widthPx, heightPx) {
                    awaitEachGesture {
                        awaitFirstDown(requireUnconsumed = false)
                        var previousCentroid: Offset? = null
                        var previousSpan: Float? = null
                        do {
                            val event = awaitPointerEvent()
                            val pressed = event.changes.filter { it.pressed }
                            if (pressed.size >= 2) {
                                val centroid = Offset(pressed.map { it.position.x }.average().toFloat(),
                                    pressed.map { it.position.y }.average().toFloat())
                                val span = pressed.map { (it.position - centroid).getDistance() }.average().toFloat()
                                val factor = previousSpan?.takeIf { it > 0f }?.let { span / it } ?: 1f
                                val next = (zoom * factor).coerceIn(1f, 5f)
                                val ratio = next / zoom
                                val pan = previousCentroid?.let { centroid - it } ?: Offset.Zero
                                zoom = next
                                shiftX = clamp(shiftX * ratio + pan.x + (centroid.x - center.x) * (1f - ratio), width, widthPx)
                                shiftY = clamp(shiftY * ratio + pan.y + (centroid.y - center.y) * (1f - ratio), height, heightPx)
                                previousCentroid = centroid
                                previousSpan = span
                                event.changes.forEach { it.consume() }
                            } else {
                                previousCentroid = null
                                previousSpan = null
                            }
                        } while (event.changes.any { it.pressed })
                    }
                }
                .pointerInput(sequence.id, widthPx, heightPx) {
                    detectTapGestures(onDoubleTap = { zoom = 1f; shiftX = 0f; shiftY = 0f }, onTap = { point ->
                        val logical = Offset(
                            (point.x - center.x - shiftX) / (fit * zoom) + contentCenter.x,
                            (point.y - center.y - shiftY) / (fit * zoom) + contentCenter.y
                        )
                        val minHit = with(density) { 48.dp.toPx() } / (fit * zoom)
                        nodes.filter { node ->
                            val padX = max(0f, (minHit - node.width) / 2f)
                            val padY = max(0f, (minHit - node.height) / 2f)
                            logical.x in (node.x - padX)..(node.x + node.width + padX) &&
                                logical.y in (node.y - padY)..(node.y + node.height + padY)
                        }.minByOrNull { (it.center - logical).getDistance() }?.let { onSelect(it.key) }
                    })
                }
            Canvas(Modifier.fillMaxWidth().height(height.dp)
                .clip(RoundedCornerShape(12.dp))
                .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(12.dp))
                .then(gestures).background(background)) {
                withTransform({
                    translate(center.x + shiftX, center.y + shiftY)
                    scale(fit * zoom, fit * zoom)
                    translate(-contentCenter.x, -contentCenter.y)
                }) {
                    allEdges.forEach { edge ->
                        val from = nodeByKey[edge.from] ?: return@forEach
                        val to = nodeByKey[edge.to] ?: return@forEach
                        val sameRow = abs(from.center.y - to.center.y) < 10f
                        val start: Offset
                        val end: Offset
                        val horizontalEnd: Boolean
                        val arrowSign: Float
                        val path = Path()
                        if (sameRow) {
                            horizontalEnd = true
                            val right = to.center.x > from.center.x
                            arrowSign = if (right) 1f else -1f
                            start = Offset(if (right) from.x + from.width else from.x, from.center.y)
                            end = Offset(if (right) to.x else to.x + to.width, to.center.y)
                            path.moveTo(start.x, start.y)
                            path.lineTo(end.x, end.y)
                        } else if (to.y > from.y + from.height + 2f && to.y - from.y < from.height * 2.5f) {
                            horizontalEnd = false
                            arrowSign = 0f
                            start = Offset(from.center.x, from.y + from.height)
                            end = Offset(to.center.x, to.y)
                            val mid = (start.y + end.y) / 2f
                            path.moveTo(start.x, start.y)
                            path.lineTo(start.x, mid)
                            path.lineTo(end.x, mid)
                            path.lineTo(end.x, end.y)
                        } else {
                            horizontalEnd = true
                            arrowSign = -1f
                            val lane = width - 5f
                            start = Offset(from.x + from.width, from.center.y)
                            end = Offset(to.x + to.width, to.center.y)
                            path.moveTo(start.x, start.y)
                            path.lineTo(lane, start.y)
                            path.lineTo(lane, end.y)
                            path.lineTo(end.x, end.y)
                        }
                        val token = styles[edge.kind]
                        val color = when {
                            edge in currentEdges -> (if (isDark) token?.dark else token?.light) ?: highlight
                            edge in completedEdges -> ((if (isDark) token?.dark else token?.light) ?: highlight).copy(alpha = 0.65f)
                            else -> foreground.copy(alpha = 0.28f)
                        }
                        val pattern = token?.pattern.orEmpty()
                        val effect = when {
                            "dotted" in pattern -> PathEffect.dashPathEffect(floatArrayOf(2f, 7f))
                            "dash-dot" in pattern -> PathEffect.dashPathEffect(floatArrayOf(12f, 5f, 2f, 5f))
                            "dashed" in pattern -> PathEffect.dashPathEffect(floatArrayOf(11f, 7f))
                            else -> null
                        }
                        val stroke = if (edge in currentEdges) 4f else 2.5f
                        drawPath(path, color, style = Stroke(width = stroke, pathEffect = effect))
                        if ("double" in pattern) withTransform({ translate(3f, 3f) }) {
                            drawPath(path, color, style = Stroke(width = 1.5f, pathEffect = effect))
                        }
                        val arrow = Path().apply {
                            moveTo(end.x, end.y)
                            if (horizontalEnd) {
                                lineTo(end.x - arrowSign * 12f, end.y - 6f)
                                lineTo(end.x - arrowSign * 12f, end.y + 6f)
                            } else {
                                lineTo(end.x - 6f, end.y - 12f)
                                lineTo(end.x + 6f, end.y - 12f)
                            }
                            close()
                        }
                        drawPath(arrow, color)
                    }
                    val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                        textSize = 16f
                        typeface = Typeface.DEFAULT_BOLD
                        color = android.graphics.Color.parseColor(if (isDark) "#F1F5F9" else "#17212B")
                    }
                    nodes.forEach { node ->
                        val selected = node.key == selectedNode
                        val stroke = when {
                            selected || node.key in currentNodes -> highlight
                            node.key in activeNodes -> highlight.copy(alpha = 0.65f)
                            else -> foreground.copy(alpha = 0.42f)
                        }
                        drawRoundRect(color = if (isDark) Color(0xFF243237) else Color(0xFFF0F5F7),
                            topLeft = Offset(node.x, node.y), size = Size(node.width, node.height),
                            cornerRadius = CornerRadius(10f))
                        drawRoundRect(color = stroke, topLeft = Offset(node.x, node.y),
                            size = Size(node.width, node.height), cornerRadius = CornerRadius(10f),
                            style = Stroke(if (selected || node.key in currentNodes) 3f else 2f))
                        val lines = mutableListOf<String>()
                        var line = ""
                        node.label.split(' ').forEach { word ->
                            val candidate = if (line.isBlank()) word else "$line $word"
                            if (paint.measureText(candidate) > node.width - 18f && line.isNotBlank()) {
                                lines += line
                                line = word
                            } else line = candidate
                        }
                        if (line.isNotBlank()) lines += line
                        lines.take(4).forEachIndexed { index, value ->
                            var visible = value
                            while (visible.isNotEmpty() && paint.measureText(visible) > node.width - 18f) {
                                visible = visible.dropLast(1)
                            }
                            if (visible != value) visible = visible.dropLast(1) + "…"
                            drawContext.canvas.nativeCanvas.drawText(visible, node.x + 9f,
                                node.y + (node.height - min(lines.size, 4) * 19f) / 2f + 16f + index * 19f, paint)
                        }
                    }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { zoom = (zoom / 1.4f).coerceAtLeast(1f) }) { Text("−") }
            OutlinedButton(onClick = { zoom = (zoom * 1.4f).coerceAtMost(5f) }) { Text("+") }
            OutlinedButton(onClick = { zoom = 1f; shiftX = 0f; shiftY = 0f }) { Text("Показать целиком") }
        }
        Text("Прокручивайте страницу одним пальцем. Двумя пальцами увеличивайте и перемещайте схему. Двойное касание возвращает общий вид.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
