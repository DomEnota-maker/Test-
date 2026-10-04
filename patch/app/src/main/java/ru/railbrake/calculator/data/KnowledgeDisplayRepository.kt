package ru.railbrake.calculator.data

import android.content.Context

enum class KnowledgeMode(val title: String) { BASIC("Базовый"), ADVANCED("Расширенные знания") }
enum class KnowledgeDepth(val title: String) {
    MINIMAL("Минимальная"), STANDARD("Стандартная"), DETAILED("Подробная")
}

/** Knowledge presentation is independent from the safety opt-in for emergency routes. */
class KnowledgeDisplayRepository(context: Context) {
    private val prefs = context.applicationContext.getSharedPreferences("knowledge_display_v2", Context.MODE_PRIVATE)

    fun mode(): KnowledgeMode = runCatching {
        KnowledgeMode.valueOf(prefs.getString("mode", null).orEmpty())
    }.getOrDefault(KnowledgeMode.BASIC)

    fun depth(): KnowledgeDepth = runCatching {
        KnowledgeDepth.valueOf(prefs.getString("depth", null).orEmpty())
    }.getOrDefault(KnowledgeDepth.STANDARD)

    fun setMode(mode: KnowledgeMode) { prefs.edit().putString("mode", mode.name).apply() }
    fun setDepth(depth: KnowledgeDepth) { prefs.edit().putString("depth", depth.name).apply() }
}
