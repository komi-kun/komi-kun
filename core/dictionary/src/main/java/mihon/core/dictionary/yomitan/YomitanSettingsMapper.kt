// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Pure, Android-free mapper from a Yomitan settings backup (`yomitan-settings-*.json`) to a typed
// [YomitanSettingsImport]. It only reads keys that have a real komi-kun equivalent and records
// every other key (or whole group) as a skipped report, so the UI can show the user exactly what
// changes. Unknown/newer keys are ignored, never fatal. See DESIGN §7 for the mapping rationale.
package mihon.core.dictionary.yomitan

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import mihon.core.dictionary.model.DictionaryProfile

object YomitanSettingsMapper {

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        explicitNulls = false
    }

    /** Popup/display keys that have a komi-kun counterpart (everything else is reported skipped). */
    private val MODELED_GENERAL = setOf(
        "popupWidth", "popupHeight", "fontSize", "fontFamily", "popupTheme", "customPopupCss",
        "showPitchAccentGraph", "showPitchAccentPositionNotation", "showPitchAccentDownstepNotation",
        "averageFrequency", "resultOutputMode", "glossaryLayoutMode", "language",
    )

    /** Anki keys that have a komi-kun counterpart. */
    private val MODELED_ANKI = setOf(
        "enable",
        "tags",
        "checkForDuplicates",
        "duplicateScope",
        "duplicateBehavior",
    )

    /** Whole option groups komi-kun cannot represent at all. */
    private val WHOLLY_UNMAPPED_GROUPS =
        listOf("audio", "parsing", "sentenceParsing", "inputs", "clipboard", "accessibility")

    /** `cardFormats` gets its own explanatory report, so it is not duplicated as a generic key. */
    private const val ANKI_CARD_FORMATS = "cardFormats"

    private const val MAX_INDIVIDUAL_SKIPS = 12

    fun map(raw: String): YomitanImportResult {
        val root = runCatching { json.parseToJsonElement(raw) }.getOrElse {
            return YomitanImportResult.Invalid("Not valid JSON: ${it.message}")
        }.asObjectOrNull() ?: return YomitanImportResult.Invalid("Backup root is not a JSON object")

        val options = root["options"].asObjectOrNull()
            ?: return YomitanImportResult.Invalid("Missing 'options' — not a Yomitan settings backup")

        val profileArray = options["profiles"].asArrayOrNull()
            ?: return YomitanImportResult.Invalid("Missing 'options.profiles'")
        if (profileArray.isEmpty()) return YomitanImportResult.Invalid("The backup contains no profiles")

        val reports = mutableListOf<YomitanImportReport>()
        val warnings = mutableListOf<String>()

        val optionsVersion = options["version"].intOr(0)
        if (optionsVersion > YomitanSettingsImport.KNOWN_OPTIONS_VERSION) {
            warnings += "options.version $optionsVersion is newer than the newest supported " +
                "${YomitanSettingsImport.KNOWN_OPTIONS_VERSION}; unrecognised keys were ignored."
        }

        // --- Profiles + dictionary selection ---------------------------------------------
        val currentIndex = options["profileCurrent"].intOr(0)
        val activeIndex = currentIndex.coerceIn(0, profileArray.lastIndex)
        val profiles = profileArray.mapIndexed { index, element ->
            mapProfile(element.asObjectOrNull(), index, index == activeIndex)
        }
        profiles.forEach { p ->
            reports += YomitanImportReport(
                group = "profiles",
                key = p.name,
                detail = "${p.dictionaryOrder.size} dictionaries, ${p.enabledDictionaries.size} enabled",
                applied = true,
            )
        }

        // --- Per-dictionary aliases (union across profiles, first wins) -------------------
        val aliases = LinkedHashMap<String, String>()
        profileArray.forEach { element ->
            element.asObjectOrNull()?.get("options").asObjectOrNull()
                ?.get("dictionaries").asArrayOrNull()
                ?.forEach { entry -> collectAlias(entry.asObjectOrNull(), aliases) }
        }
        if (aliases.isNotEmpty()) {
            reports += YomitanImportReport("dictionaries", "alias", "${aliases.size}", applied = true)
        }

        // --- Popup + Anki from the active profile ----------------------------------------
        val active = profiles[activeIndex]
        val activeOptions = profileArray[activeIndex].asObjectOrNull()?.get("options").asObjectOrNull()
        val popup = mapPopup(activeOptions, reports)
        val anki = mapAnki(activeOptions, reports)

        // --- Aggregated "not imported" reports -------------------------------------------
        val conditionCount = profileArray.sumOf { element ->
            element.asObjectOrNull()?.get("conditionGroups").asArrayOrNull()?.size ?: 0
        }
        reportUnmapped(profileArray, activeOptions, conditionCount, reports)

        return YomitanImportResult.Imported(
            YomitanSettingsImport(
                formatVersion = root["version"].intOr(0),
                optionsVersion = optionsVersion,
                activeProfileName = active.name,
                profiles = profiles,
                popup = popup,
                anki = anki,
                aliases = aliases,
                reports = reports,
                warnings = warnings,
            ),
        )
    }

    // -------------------------------------------------------------------------------------
    // Profiles
    // -------------------------------------------------------------------------------------

    private fun mapProfile(profile: JsonObject?, index: Int, isActive: Boolean): YomitanImportProfile {
        val obj = profile ?: JsonObject(emptyMap())
        val name = obj["name"].stringOrNull()?.takeIf { it.isNotBlank() } ?: "Profile ${index + 1}"
        val options = obj["options"].asObjectOrNull()

        val order = mutableListOf<String>()
        val enabled = mutableSetOf<String>()
        val displayModes = LinkedHashMap<String, String>()
        options?.get("dictionaries").asArrayOrNull()?.forEach { element ->
            val entry = element.asObjectOrNull() ?: return@forEach
            val dictName = entry["name"].stringOrNull()?.takeIf { it.isNotBlank() } ?: return@forEach
            order += dictName
            if (entry["enabled"].booleanOr(true)) enabled += dictName
            displayModeFor(entry["definitionsCollapsible"].stringOrNull())?.let { displayModes[dictName] = it }
        }

        val searchResolution = options?.get("translation").asObjectOrNull()
            ?.get("searchResolution").stringOrNull()
            ?.takeIf { it == DictionaryProfile.SEARCH_RESOLUTION_LETTER || it == DictionaryProfile.SEARCH_RESOLUTION_WORD }
            .orEmpty()
        val scanResolution = options?.get("scanning").asObjectOrNull()
            ?.get("scanResolution").stringOrNull()
            ?.takeIf { it == DictionaryProfile.SCAN_RESOLUTION_CHARACTER || it == DictionaryProfile.SCAN_RESOLUTION_WORD }
            .orEmpty()

        return YomitanImportProfile(
            name = name,
            dictionaryOrder = order,
            enabledDictionaries = enabled,
            dictionaryDisplayModes = displayModes,
            languageCode = options?.get("general").asObjectOrNull()?.get("language").stringOrNull()?.trim().orEmpty(),
            searchResolution = searchResolution,
            scanResolution = scanResolution,
            isActive = isActive,
        )
    }

    private fun collectAlias(entry: JsonObject?, into: MutableMap<String, String>) {
        if (entry == null) return
        val name = entry["name"].stringOrNull()?.takeIf { it.isNotBlank() } ?: return
        val alias = entry["alias"].stringOrNull()?.takeIf { it.isNotBlank() } ?: return
        if (alias != name) into.putIfAbsent(name, alias)
    }

    private fun displayModeFor(value: String?): String? = when (value) {
        "not-collapsible", "expanded", "force-expanded" -> DictionaryProfile.DICTIONARY_DISPLAY_ALWAYS_EXPANDED
        "collapsed", "force-collapsed" -> DictionaryProfile.DICTIONARY_DISPLAY_ALWAYS_COLLAPSED
        else -> null
    }

    // -------------------------------------------------------------------------------------
    // Popup / display preferences
    // -------------------------------------------------------------------------------------

    private fun mapPopup(options: JsonObject?, reports: MutableList<YomitanImportReport>): YomitanImportPopup {
        val general = options?.get("general").asObjectOrNull() ?: JsonObject(emptyMap())
        val popupWindow = options?.get("popupWindow").asObjectOrNull()

        val width = general["popupWidth"].intOrNull() ?: popupWindow?.get("width").intOrNull()
        val height = general["popupHeight"].intOrNull() ?: popupWindow?.get("height").intOrNull()
        val fontSize = general["fontSize"].intOrNull()
        val fontFamily = general["fontFamily"].stringOrNull()?.takeIf { it.isNotBlank() }
        val customCss = general["customPopupCss"].stringOrNull()?.takeIf { it.isNotBlank() }
        val themeMode = when (general["popupTheme"].stringOrNull()) {
            "dark" -> "dark"
            "light" -> "light"
            else -> null
        }
        val groupTerms = when (general["resultOutputMode"].stringOrNull()) {
            "group" -> true
            "split", "merge" -> false
            else -> null
        }
        val compactGlossary = when (general["glossaryLayoutMode"].stringOrNull()) {
            "compact" -> true
            "default" -> false
            else -> null
        }

        width?.let { reports += applied("popup", "general.popupWidth", it.coerceIn(200, 600)) }
        height?.let { reports += applied("popup", "general.popupHeight", it.coerceIn(200, 800)) }
        fontSize?.let { reports += applied("popup", "general.fontSize", it.coerceIn(10, 32)) }
        fontFamily?.let { reports += applied("popup", "general.fontFamily", it) }
        customCss?.let { reports += applied("popup", "general.customPopupCss", "${it.length} chars") }
        themeMode?.let { reports += applied("popup", "general.popupTheme", it) }
        general["showPitchAccentGraph"].booleanOrNull()?.let { reports += applied("popup", "general.showPitchAccentGraph", it) }
        general["showPitchAccentPositionNotation"].booleanOrNull()?.let {
            reports += applied("popup", "general.showPitchAccentPositionNotation", it)
        }
        general["showPitchAccentDownstepNotation"].booleanOrNull()?.let {
            reports += applied("popup", "general.showPitchAccentDownstepNotation", it)
        }
        general["averageFrequency"].booleanOrNull()?.let { reports += applied("popup", "general.averageFrequency", it) }
        groupTerms?.let {
            reports += applied("popup", "general.resultOutputMode", general["resultOutputMode"].stringOrNull().orEmpty())
        }
        compactGlossary?.let {
            reports += applied("popup", "general.glossaryLayoutMode", general["glossaryLayoutMode"].stringOrNull().orEmpty())
        }

        return YomitanImportPopup(
            width = width?.coerceIn(200, 600),
            height = height?.coerceIn(200, 800),
            fontSize = fontSize?.coerceIn(10, 32),
            fontFamily = fontFamily,
            themeMode = themeMode,
            customCss = customCss,
            showPitchDiagram = general["showPitchAccentGraph"].booleanOrNull(),
            showPitchNumber = general["showPitchAccentPositionNotation"].booleanOrNull(),
            showPitchText = general["showPitchAccentDownstepNotation"].booleanOrNull(),
            showFrequencyAverage = general["averageFrequency"].booleanOrNull(),
            groupTerms = groupTerms,
            compactGlossary = compactGlossary,
        )
    }

    // -------------------------------------------------------------------------------------
    // Anki preferences
    // -------------------------------------------------------------------------------------

    private fun mapAnki(options: JsonObject?, reports: MutableList<YomitanImportReport>): YomitanImportAnki {
        val anki = options?.get("anki").asObjectOrNull() ?: return YomitanImportAnki()

        val enabled = anki["enable"].booleanOrNull()
        val tags = anki["tags"].asArrayOrNull()
            ?.mapNotNull { it.stringOrNull()?.takeIf { tag -> tag.isNotBlank() } }
            ?.takeIf { it.isNotEmpty() }
            ?.joinToString(" ")
        val duplicateCheck = anki["checkForDuplicates"].booleanOrNull()
        val duplicateScope = anki["duplicateScope"].stringOrNull()?.takeIf { it == "deck" || it == "collection" }
        // komi-kun can only prevent/open/overwrite; Yomitan's "new"/"duplicate" (always add) has no
        // equivalent, so only map the overwrite behaviour and report the rest as skipped.
        val duplicateAction = anki["duplicateBehavior"].stringOrNull()?.takeIf { it == "overwrite" }

        enabled?.let { reports += applied("anki", "anki.enable", it) }
        tags?.let { reports += applied("anki", "anki.tags", it) }
        duplicateCheck?.let { reports += applied("anki", "anki.checkForDuplicates", it) }
        duplicateScope?.let { reports += applied("anki", "anki.duplicateScope", it) }
        duplicateAction?.let { reports += applied("anki", "anki.duplicateBehavior", it) }

        anki["duplicateBehavior"].stringOrNull()
            ?.takeIf { it != "overwrite" }
            ?.let { reports += skipped("anki", "anki.duplicateBehavior", it) }

        // Anki note-model / field handlebars: no equivalent, never auto-converted.
        anki[ANKI_CARD_FORMATS].asArrayOrNull()?.takeIf { it.isNotEmpty() }?.let {
            reports += skipped("anki", "anki.cardFormats", "${it.size} formats")
        }

        return YomitanImportAnki(
            enabled = enabled,
            tags = tags,
            duplicateCheck = duplicateCheck,
            duplicateScope = duplicateScope,
            duplicateAction = duplicateAction,
        )
    }

    // -------------------------------------------------------------------------------------
    // "Not imported" groups
    // -------------------------------------------------------------------------------------

    private fun reportUnmapped(
        profileArray: JsonArray,
        activeOptions: JsonObject?,
        conditionCount: Int,
        reports: MutableList<YomitanImportReport>,
    ) {
        // Unmodeled keys inside partly-mapped groups, reported individually (capped).
        reportUnmodeledKeys(activeOptions?.get("general").asObjectOrNull(), MODELED_GENERAL, emptySet(), "general", reports)
        reportUnmodeledKeys(activeOptions?.get("anki").asObjectOrNull(), MODELED_ANKI, setOf(ANKI_CARD_FORMATS), "anki", reports)

        // Single aggregate lines for whole groups komi-kun has no equivalent for.
        val groupKeys = unionGroupKeys(profileArray)
        WHOLLY_UNMAPPED_GROUPS.forEach { group ->
            groupKeys[group]?.takeIf { it.isNotEmpty() }?.let { keys ->
                reports += YomitanImportReport(group, group, "${keys.size} keys", applied = false)
            }
        }

        // popupWindow: detached-window geometry; width/height may seed the popup size when general omits them.
        groupKeys["popupWindow"]?.takeIf { it.isNotEmpty() }?.let { keys ->
            val general = activeOptions?.get("general").asObjectOrNull()
            val window = activeOptions?.get("popupWindow").asObjectOrNull()
            val fallbackUsed = setOfNotNull(
                "width".takeIf { general?.get("popupWidth").intOrNull() == null && window?.get("width").intOrNull() != null },
                "height".takeIf { general?.get("popupHeight").intOrNull() == null && window?.get("height").intOrNull() != null },
            )
            val skippedCount = (keys.size - fallbackUsed.size).coerceAtLeast(0)
            if (skippedCount > 0) {
                reports += YomitanImportReport("popupWindow", "popupWindow", "$skippedCount keys", applied = false)
            }
        }

        // scanning: the one mapped key is scanResolution (per profile); everything else is a
        // mouse-scanning gesture with no manga equivalent.
        groupKeys["scanning"]?.let { keys ->
            (keys - "scanResolution").takeIf { it.isNotEmpty() }?.let {
                reports += YomitanImportReport("scanning", "scanning", "${it.size} keys", applied = false)
            }
        }

        // translation: only textReplacements is unmodeled.
        groupKeys["translation"]?.contains("textReplacements")?.takeIf { it }?.let {
            reports += YomitanImportReport("translation", "translation.textReplacements", "1 keys", applied = false)
        }

        if (conditionCount > 0) {
            reports += YomitanImportReport("conditionGroups", "conditionGroups", "$conditionCount conditions", applied = false)
        }
    }

    private fun reportUnmodeledKeys(
        group: JsonObject?,
        modeled: Set<String>,
        suppressed: Set<String>,
        groupName: String,
        reports: MutableList<YomitanImportReport>,
    ) {
        if (group == null) return
        val unrecognised = group.keys.filterNot { it in modeled || it in suppressed }
        unrecognised.take(MAX_INDIVIDUAL_SKIPS).forEach { key ->
            reports += skipped(groupName, "$groupName.$key", previewValue(group[key]))
        }
        if (unrecognised.size > MAX_INDIVIDUAL_SKIPS) {
            reports += skipped(
                groupName,
                "$groupName (+${unrecognised.size - MAX_INDIVIDUAL_SKIPS} more)",
                "${unrecognised.size} keys",
            )
        }
    }

    /** Union of every option-group key across profiles (groups are serialised per profile). */
    private fun unionGroupKeys(profileArray: JsonArray): Map<String, Set<String>> {
        val groups = LinkedHashMap<String, MutableSet<String>>()
        profileArray.forEach { element ->
            val options = element.asObjectOrNull()?.get("options").asObjectOrNull() ?: return@forEach
            options.forEach { (group, value) ->
                val keys = (value as? JsonObject)?.keys ?: emptySet()
                groups.getOrPut(group) { mutableSetOf() }.addAll(keys)
            }
        }
        return groups
    }

    // -------------------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------------------

    private fun applied(group: String, key: String, detail: Any): YomitanImportReport =
        YomitanImportReport(group, key, detail.toString(), applied = true)

    private fun skipped(group: String, key: String, detail: String): YomitanImportReport =
        YomitanImportReport(group, key, detail, applied = false)

    private fun previewValue(element: JsonElement?): String = when (element) {
        null -> "null"
        is JsonPrimitive -> element.contentOrNull ?: "null"
        is JsonArray -> "array[${element.size}]"
        is JsonObject -> "object[${element.size}]"
    }
}

// -----------------------------------------------------------------------------------------
// kotlinx.serialization null-safe accessors
// -----------------------------------------------------------------------------------------

private fun JsonElement?.asObjectOrNull(): JsonObject? = this as? JsonObject

private fun JsonElement?.asArrayOrNull(): JsonArray? = this as? JsonArray

private fun JsonElement?.stringOrNull(): String? = (this as? JsonPrimitive)?.contentOrNull

private fun JsonElement?.intOr(default: Int): Int = (this as? JsonPrimitive)?.intOrNull ?: default

private fun JsonElement?.intOrNull(): Int? = (this as? JsonPrimitive)?.intOrNull

private fun JsonElement?.booleanOr(default: Boolean): Boolean = (this as? JsonPrimitive)?.booleanOrNull ?: default

private fun JsonElement?.booleanOrNull(): Boolean? = (this as? JsonPrimitive)?.booleanOrNull
// KUN <--
