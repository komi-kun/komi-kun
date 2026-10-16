// SPDX-License-Identifier: GPL-3.0-or-later
package mihon.core.dictionary.ui

import android.content.Context
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import mihon.core.dictionary.model.DictionaryProfile
import mihon.core.dictionary.model.DictionaryStyle
import mihon.core.dictionary.model.FrequencyEntry
import mihon.core.dictionary.model.GlossaryEntry
import mihon.core.dictionary.model.KanjiEntry
import mihon.core.dictionary.model.LookupResult
import mihon.core.dictionary.model.dictionariesDir
import java.io.File

/** One entry in the scrollable lookup-history tab bar shown inside the WebView. */
data class TabInfo(val label: String, val active: Boolean)

/**
 * Full render signature — used as the diff key for [DictionaryWebViewState.flush] so an
 * unchanged lookup is a no-op (02 §3.3).
 */
data class DictionaryRenderSignature(
    val results: List<LookupResult>,
    val styles: List<DictionaryStyle>,
    val placeholder: String,
    val isDark: Boolean,
    val showFrequencyHarmonic: Boolean,
    val showFrequencyAverage: Boolean,
    val groupTerms: Boolean,
    val showPitchDiagram: Boolean,
    val showPitchNumber: Boolean,
    val showPitchText: Boolean,
    val activeProfile: DictionaryProfile,
    val tabs: List<TabInfo>,
    val recursiveNavMode: String,
    val renderRecursiveChrome: Boolean,
    val wordAudioEnabled: Boolean,
    val wordAudioAutoplay: Boolean,
    val showNavigationButtons: Boolean,
    val groupPitches: Boolean,
)

/** Resolves a user display name for a dictionary dir, or null when none is set. */
typealias DictionaryDisplayNameProvider = (dirName: String) -> String?

private fun buildResultEntryJson(
    result: LookupResult,
    index: Int,
    priorityMap: Map<String, Int>,
    groupPitches: Boolean,
): JsonObject {
    val glossaries = result.term.glossaries.sortedByDictionaryPriority(priorityMap) { it.dictName }
    val frequencies = result.term.frequencies.sortedByDictionaryPriority(priorityMap) { it.dictName }
    val pitches = result.term.pitches.sortedByDictionaryPriority(priorityMap) { it.dictName }
    val ruleTags = splitTags(result.term.rules).distinct()
    return buildJsonObject {
        put("index", index)
        put("matched", result.matched)
        put("deinflected", result.deinflected)
        putJsonArray("process") {
            for (p in result.process) {
                add(
                    buildJsonObject {
                        put("name", p.name)
                        put("description", p.description)
                    },
                )
            }
        }
        put(
            "term",
            buildJsonObject {
                put("expression", result.term.expression)
                put("reading", result.term.reading)
                put("rules", result.term.rules)
                putJsonArray("ruleTags") { for (tag in ruleTags) add(JsonPrimitive(tag)) }
                putJsonArray("glossaries") { for (g in glossaries) add(buildGlossaryPayload(g)) }
                putJsonArray("frequencies") {
                    for (group in frequencies) {
                        add(
                            buildJsonObject {
                                put("dictName", group.dictName)
                                put("displayValueText", frequencyDisplayText(group))
                                putJsonArray("frequencies") {
                                    for (item in group.frequencies) {
                                        add(
                                            buildJsonObject {
                                                put("value", item.value)
                                                put("displayValue", item.displayValue)
                                            },
                                        )
                                    }
                                }
                            },
                        )
                    }
                }
                harmonicRank(frequencies)?.let { put("frequencyHarmonicRank", it) }
                averageRank(frequencies)?.let { put("frequencyAverageRank", it) }
                putJsonArray("pitches") {
                    val priorityTitles = priorityMap.entries.sortedBy { it.value }.map { it.key }
                    if (groupPitches) {
                        val orderedPitches = LinkedHashSet<Int>()
                        for (title in priorityTitles) {
                            pitches.filter { it.dictName == title }
                                .forEach { group -> orderedPitches.addAll(group.pitchPositions.toList()) }
                        }
                        for (group in pitches) {
                            if (group.dictName !in priorityTitles) {
                                orderedPitches.addAll(group.pitchPositions.toList())
                            }
                        }
                        val allDictIds = pitches.map { it.dictName }.distinct()
                        if (orderedPitches.isNotEmpty()) {
                            val sortedTitles = allDictIds.sortedBy {
                                val idx = priorityTitles.indexOf(it)
                                if (idx == -1) Int.MAX_VALUE else idx
                            }
                            add(
                                buildJsonObject {
                                    put("dictName", sortedTitles.joinToString(", "))
                                    putJsonArray("pitchPositions") {
                                        for (pos in orderedPitches) add(JsonPrimitive(pos))
                                    }
                                },
                            )
                        }
                    } else {
                        for (group in pitches) {
                            add(
                                buildJsonObject {
                                    put("dictName", group.dictName)
                                    putJsonArray("pitchPositions") {
                                        for (pos in group.pitchPositions.distinct()) add(JsonPrimitive(pos))
                                    }
                                },
                            )
                        }
                    }
                }
            },
        )
    }
}

fun orderLookupResultsForDisplay(
    results: List<LookupResult>,
    profile: DictionaryProfile,
    context: Context,
    displayNameProvider: DictionaryDisplayNameProvider = { null },
): List<LookupResult> {
    val priorityMap = dictionaryPriorityMap(profile, context, displayNameProvider)
    if (priorityMap.isEmpty()) return results
    if (results.size <= 1) return results.map { it.withOrderedDictionaries(priorityMap) }

    return results
        .map { it.withOrderedDictionaries(priorityMap) }
        .withIndex()
        .sortedWith(
            compareByDescending<IndexedValue<LookupResult>> { it.value.matched.length }
                .thenBy { dictionaryPriority(priorityMap, it.value.term.glossaries.firstOrNull()?.dictName) }
                .thenBy { it.index },
        )
        .map { it.value }
}

/**
 * Build the config portion of the render payload — everything except results. Results are
 * serialized separately and pulled lazily through `PayloadBridge.getEntry(index)`.
 */
fun buildConfigPayload(
    context: Context,
    styles: List<DictionaryStyle>,
    mediaDataUris: Map<String, String>,
    placeholder: String,
    isDark: Boolean,
    showFrequencyHarmonic: Boolean,
    showFrequencyAverage: Boolean,
    groupTerms: Boolean,
    showPitchDiagram: Boolean,
    showPitchNumber: Boolean,
    showPitchText: Boolean,
    wordAudioAutoplay: Boolean,
    activeProfile: DictionaryProfile,
    existingExpressions: Set<String> = emptySet(),
    tabs: List<TabInfo> = emptyList(),
    recursiveNavMode: String = "tabs",
    renderRecursiveChrome: Boolean = true,
    wordAudioEnabled: Boolean = true,
    showNavigationButtons: Boolean = true,
    groupPitches: Boolean = false,
    compactGlossary: Boolean = false,
    displayNameProvider: DictionaryDisplayNameProvider = { null },
): JsonObject = buildJsonObject {
    val orderedTitles = activeProfile.dictionaryOrder.map { getDictionaryTitle(context, it, displayNameProvider) }
    val displayModesByTitle = activeProfile.dictionaryDisplayModes
        .mapKeys { (dirName, _) -> getDictionaryTitle(context, dirName, displayNameProvider) }

    putJsonArray("dictionaryOrder") { for (title in orderedTitles) add(JsonPrimitive(title)) }
    // Anki mining is wired in the Anki phase (W5); the renderer only needs these two switches.
    put("ankiEnabled", false)
    put("ankiDupAction", "prevent")
    put("dictionaryCollapseMode", activeProfile.dictionaryCollapseMode)
    putJsonObject("dictionaryDisplayModes") { for ((title, mode) in displayModesByTitle) put(title, mode) }
    put("placeholder", placeholder)
    put("isDark", isDark)
    put("showFrequencyHarmonic", showFrequencyHarmonic)
    put("showFrequencyAverage", showFrequencyAverage)
    put("groupTerms", groupTerms)
    put("showPitchDiagram", showPitchDiagram)
    put("showPitchNumber", showPitchNumber)
    put("showPitchText", showPitchText)
    put("wordAudioAutoplay", wordAudioAutoplay)
    put("wordAudioEnabled", wordAudioEnabled)
    put("recursiveNavMode", recursiveNavMode)
    put("renderRecursiveChrome", renderRecursiveChrome)
    put("showNavigationButtons", showNavigationButtons)
    put("compactGlossary", compactGlossary)
    putJsonArray("tabs") {
        for (tab in tabs) {
            add(
                buildJsonObject {
                    put("label", tab.label)
                    put("active", tab.active)
                },
            )
        }
    }
    putJsonArray("existingExpressions") { for (expr in existingExpressions) add(JsonPrimitive(expr)) }
    putJsonArray("styles") {
        for (style in styles) {
            add(
                buildJsonObject {
                    put("dictName", style.dictName)
                    put("styles", style.styles)
                },
            )
        }
    }
    putJsonObject("mediaDataUris") { for ((key, value) in mediaDataUris) put(key, value) }
    putJsonArray("results") {}
}

/** Serialize result entries separately so `PayloadBridge.getEntry(index)` pulls one at a time. */
fun buildResultEntryJsonStrings(
    results: List<LookupResult>,
    profile: DictionaryProfile,
    context: Context,
    groupPitches: Boolean,
    displayNameProvider: DictionaryDisplayNameProvider = { null },
): List<String> {
    val priorityMap = dictionaryPriorityMap(profile, context, displayNameProvider)
    return results.mapIndexed { index, result ->
        buildResultEntryJson(result, index, priorityMap, groupPitches).toString()
    }
}

fun String.toJavascriptExpression(): String =
    replace("\u2028", "\\u2028").replace("\u2029", "\\u2029")

private val dictionaryTitleCache = java.util.concurrent.ConcurrentHashMap<String, String>()

/** Invalidate the cached title for a single directory (after a display-name/index change). */
fun invalidateDictionaryTitle(dirName: String) {
    dictionaryTitleCache.remove(dirName)
}

fun getDictionaryTitle(
    context: Context,
    dirName: String,
    displayNameProvider: DictionaryDisplayNameProvider = { null },
): String {
    return dictionaryTitleCache.getOrPut(dirName) {
        displayNameProvider(dirName)?.let { return@getOrPut it }

        val dictionariesDir = dictionariesDir(context)
        for (type in listOf("term", "frequency", "pitch", "kanji")) {
            val indexFile = File(File(dictionariesDir, type), "$dirName/index.json")
            if (indexFile.exists()) {
                runCatching {
                    val title = org.json.JSONObject(indexFile.readText()).optString("title", dirName)
                    if (title.isNotBlank()) return@getOrPut title
                }
            }
        }
        dirName
    }
}

private fun dictionaryPriorityMap(
    profile: DictionaryProfile,
    context: Context,
    displayNameProvider: DictionaryDisplayNameProvider,
): Map<String, Int> = profile.dictionaryOrder
    .map { getDictionaryTitle(context, it, displayNameProvider) }
    .distinct()
    .withIndex()
    .associate { it.value to it.index }

private fun dictionaryPriority(priorityMap: Map<String, Int>, dictName: String?): Int =
    dictName?.let { priorityMap[it] } ?: Int.MAX_VALUE

private fun buildGlossaryPayload(glossary: GlossaryEntry): JsonObject = buildJsonObject {
    put("dictName", glossary.dictName)
    put("glossary", glossary.glossary)
    put("definitionTags", glossary.definitionTags)
    putJsonArray("definitionTagList") {
        for (tag in splitTags(glossary.definitionTags)) add(JsonPrimitive(tag))
    }
    put("termTags", glossary.termTags)
}

private fun splitTags(value: String): List<String> = value.split(Regex("\\s+")).filter { it.isNotBlank() }

private fun frequencyDisplayText(group: FrequencyEntry): String = group.frequencies
    .mapNotNull { item -> item.displayValue.takeIf { it.isNotBlank() } ?: item.value.takeIf { it > 0 }?.toString() }
    .joinToString(", ")

private fun harmonicRank(frequencies: List<FrequencyEntry>): Int? {
    val values = uniqueFrequencyValues(frequencies)
    if (values.isEmpty()) return null
    val reciprocalSum = values.sumOf { 1.0 / it }
    if (reciprocalSum <= 0.0) return null
    return kotlin.math.floor(values.size / reciprocalSum).toInt()
}

private fun averageRank(frequencies: List<FrequencyEntry>): Double? {
    val values = uniqueFrequencyValues(frequencies)
    if (values.isEmpty()) return null
    return values.average()
}

private fun uniqueFrequencyValues(frequencies: List<FrequencyEntry>): List<Int> {
    val seen = HashSet<String>()
    return frequencies.mapNotNull { group ->
        if (!seen.add(group.dictName)) return@mapNotNull null
        group.frequencies.firstOrNull { it.value > 0 }?.value
    }
}

private fun LookupResult.withOrderedDictionaries(priorityMap: Map<String, Int>): LookupResult {
    if (priorityMap.isEmpty()) return this
    return copy(
        term = term.copy(
            glossaries = term.glossaries.sortedByDictionaryPriority(priorityMap) { it.dictName },
            frequencies = term.frequencies.sortedByDictionaryPriority(priorityMap) { it.dictName },
            pitches = term.pitches.sortedByDictionaryPriority(priorityMap) { it.dictName },
        ),
    )
}

private fun <T> List<T>.sortedByDictionaryPriority(
    priorityMap: Map<String, Int>,
    dictName: (T) -> String,
): List<T> {
    if (priorityMap.isEmpty() || size <= 1) return this
    return withIndex()
        .sortedWith(
            compareBy<IndexedValue<T>> { dictionaryPriority(priorityMap, dictName(it.value)) }
                .thenBy { it.index },
        )
        .map { it.value }
}

fun buildKanjiEntryJson(character: String, entry: KanjiEntry): JsonObject = buildJsonObject {
    put("matched", character)
    put("deinflected", character)
    put(
        "kanji",
        buildJsonObject {
            put("character", character)
            put("dictName", entry.dictName)
            putJsonArray("onyomi") {
                for (reading in entry.onyomi.split(",").map { it.trim() }.filter { it.isNotEmpty() }) {
                    add(JsonPrimitive(reading))
                }
            }
            putJsonArray("kunyomi") {
                for (reading in entry.kunyomi.split(",").map { it.trim() }.filter { it.isNotEmpty() }) {
                    add(JsonPrimitive(reading))
                }
            }
            put("tags", entry.tags)
            putJsonArray("definitions") { for (def in entry.definitions) add(JsonPrimitive(def)) }
            putJsonObject("stats") { for ((key, value) in entry.stats) put(key, value) }
        },
    )
}
