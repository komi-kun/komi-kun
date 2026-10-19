// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
// Word-audio settings screen, mirroring Yomitan's `Settings > Audio` (enable, auto-play, volume,
// fallback sound, and the ordered `audio.sources[]` list with add/edit/remove + a test-play).
// Sources are stored in the same `{type, url, voice}` shape Yomitan uses, so the Yomitan settings
// import can fill them and a hand-configured Local Audio Server can be tested here.
package eu.kanade.tachiyomi.ui.dictionary

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.ContributesIntoMap
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.binding
import dev.zacsweers.metrox.viewmodel.ViewModelKey
import dev.zacsweers.metrox.viewmodel.metroViewModel
import eu.kanade.presentation.components.AppBar
import eu.kanade.presentation.util.Screen
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import mihon.core.dictionary.audio.AudioFallbackSound
import mihon.core.dictionary.audio.AudioResolver
import mihon.core.dictionary.audio.AudioSourceConfig
import mihon.core.dictionary.audio.AudioSourceTypes
import mihon.core.dictionary.audio.OkHttpAudioHttp
import mihon.core.dictionary.audio.WordAudioPlayer
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Add
import mihon.icons.materialsymbols.rounded.ArrowDownward
import mihon.icons.materialsymbols.rounded.ArrowUpward
import mihon.icons.materialsymbols.rounded.Close
import tachiyomi.i18n.kmk.KMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource

class DictionaryAudioSettingsScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = metroViewModel<DictionaryAudioSettingsViewModel>()
        val state by viewModel.state.collectAsState()
        var typeMenuExpanded by remember { mutableStateOf(false) }

        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(KMR.strings.pref_category_dictionary_audio),
                    navigateUp = { navigator.pop() },
                    scrollBehavior = it,
                )
            },
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ToggleRow(
                    label = stringResource(KMR.strings.pref_dict_audio_enabled),
                    subtitle = stringResource(KMR.strings.pref_dict_audio_enabled_summary),
                    checked = state.enabled,
                    onCheckedChange = viewModel::setEnabled,
                )
                ToggleRow(
                    label = stringResource(KMR.strings.pref_dict_audio_autoplay),
                    subtitle = stringResource(KMR.strings.pref_dict_audio_autoplay_summary),
                    checked = state.autoPlay,
                    onCheckedChange = viewModel::setAutoPlay,
                )
                Text(stringResource(KMR.strings.pref_dict_audio_volume))
                Slider(
                    value = state.volume.toFloat(),
                    onValueChange = { viewModel.setVolume(it.toInt()) },
                    valueRange = 0f..100f,
                )
                ToggleRow(
                    label = stringResource(KMR.strings.pref_dict_audio_default_sources),
                    subtitle = stringResource(KMR.strings.pref_dict_audio_default_sources_summary),
                    checked = state.enableDefaultSources,
                    onCheckedChange = viewModel::setDefaultSources,
                )

                Text(stringResource(KMR.strings.pref_dict_audio_fallback))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FallbackChoice(
                        label = stringResource(KMR.strings.pref_dict_audio_fallback_none),
                        selected = state.fallbackSound == AudioFallbackSound.NONE,
                        onClick = { viewModel.setFallbackSound(AudioFallbackSound.NONE) },
                    )
                    FallbackChoice(
                        label = stringResource(KMR.strings.pref_dict_audio_fallback_click),
                        selected = state.fallbackSound == AudioFallbackSound.CLICK,
                        onClick = { viewModel.setFallbackSound(AudioFallbackSound.CLICK) },
                    )
                    FallbackChoice(
                        label = stringResource(KMR.strings.pref_dict_audio_fallback_bloop),
                        selected = state.fallbackSound == AudioFallbackSound.BLOOP,
                        onClick = { viewModel.setFallbackSound(AudioFallbackSound.BLOOP) },
                    )
                }

                Text(
                    text = stringResource(KMR.strings.pref_dict_audio_sources),
                    style = MaterialTheme.typography.titleMedium,
                )
                state.sources.forEachIndexed { index, source ->
                    SourceCard(
                        index = index,
                        source = source,
                        canMoveUp = index > 0,
                        canMoveDown = index < state.sources.lastIndex,
                        onUrlChange = { viewModel.updateSource(index, source.copy(url = it)) },
                        onMoveUp = { viewModel.moveSource(index, index - 1) },
                        onMoveDown = { viewModel.moveSource(index, index + 1) },
                        onRemove = { viewModel.removeSource(index) },
                    )
                }

                Column {
                    OutlinedButton(onClick = { typeMenuExpanded = true }) {
                        Icon(MaterialSymbols.Rounded.Add, contentDescription = null)
                        Text(stringResource(KMR.strings.pref_dict_audio_source_add))
                    }
                    DropdownMenu(expanded = typeMenuExpanded, onDismissRequest = { typeMenuExpanded = false }) {
                        AudioSourceTypes.ALL.forEach { type ->
                            DropdownMenuItem(
                                text = { Text(AudioSourceTypes.displayName(type)) },
                                onClick = {
                                    typeMenuExpanded = false
                                    viewModel.addSource(type)
                                },
                            )
                        }
                    }
                }

                Card(modifier = Modifier.fillMaxWidth()) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = stringResource(KMR.strings.pref_dict_audio_test),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        OutlinedTextField(
                            value = state.testTerm,
                            onValueChange = viewModel::setTestTerm,
                            label = { Text(stringResource(KMR.strings.pref_dict_audio_test_term)) },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                        )
                        TextButton(
                            enabled = !state.testing,
                            onClick = viewModel::testPlay,
                        ) {
                            Text(
                                stringResource(
                                    if (state.testing) {
                                        KMR.strings.pref_dict_audio_test_playing
                                    } else {
                                        KMR.strings.pref_dict_audio_test_play
                                    },
                                ),
                            )
                        }
                        state.testResult?.let { result ->
                            Text(
                                text = when (result) {
                                    is DictionaryAudioSettingsViewModel.TestResult.Playing ->
                                        stringResource(KMR.strings.pref_dict_audio_test_played, result.sourceName)
                                    is DictionaryAudioSettingsViewModel.TestResult.NotFound ->
                                        stringResource(KMR.strings.pref_dict_audio_test_none, result.term)
                                },
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun ToggleRow(
        label: String,
        subtitle: String?,
        checked: Boolean,
        onCheckedChange: (Boolean) -> Unit,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(label)
                if (subtitle != null) {
                    Text(text = subtitle, style = MaterialTheme.typography.bodySmall)
                }
            }
            Switch(checked = checked, onCheckedChange = onCheckedChange)
        }
    }

    @Composable
    private fun FallbackChoice(label: String, selected: Boolean, onClick: () -> Unit) {
        OutlinedButton(onClick = onClick) {
            Text(if (selected) "\u2713 $label" else label)
        }
    }

    @Composable
    private fun SourceCard(
        index: Int,
        source: AudioSourceConfig,
        canMoveUp: Boolean,
        canMoveDown: Boolean,
        onUrlChange: (String) -> Unit,
        onMoveUp: () -> Unit,
        onMoveDown: () -> Unit,
        onRemove: () -> Unit,
    ) {
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = "${index + 1}. ${AudioSourceTypes.displayName(source.type)}",
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onMoveUp, enabled = canMoveUp) {
                        Icon(MaterialSymbols.Rounded.ArrowUpward, contentDescription = stringResource(KMR.strings.pref_dict_audio_source_move_up))
                    }
                    IconButton(onClick = onMoveDown, enabled = canMoveDown) {
                        Icon(MaterialSymbols.Rounded.ArrowDownward, contentDescription = stringResource(KMR.strings.pref_dict_audio_source_move_down))
                    }
                    IconButton(onClick = onRemove) {
                        Icon(MaterialSymbols.Rounded.Close, contentDescription = stringResource(KMR.strings.pref_dict_audio_source_remove))
                    }
                }
                if (source.type == AudioSourceTypes.CUSTOM || source.type == AudioSourceTypes.CUSTOM_JSON) {
                    OutlinedTextField(
                        value = source.url,
                        onValueChange = onUrlChange,
                        label = { Text(stringResource(KMR.strings.pref_dict_audio_source_url)) },
                        placeholder = {
                            Text(
                                if (source.type == AudioSourceTypes.CUSTOM_JSON) {
                                    stringResource(KMR.strings.pref_dict_audio_source_url_hint)
                                } else {
                                    "https://example.com/{term}.mp3"
                                },
                            )
                        },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (source.type == AudioSourceTypes.CUSTOM_JSON) {
                        Text(
                            text = stringResource(KMR.strings.pref_dict_audio_source_custom_json_hint),
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
}

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class DictionaryAudioSettingsViewModel(
    private val context: Context,
    private val prefs: DictionaryPreferences,
) : ViewModel() {

    data class State(
        val enabled: Boolean = true,
        val autoPlay: Boolean = false,
        val volume: Int = 100,
        val enableDefaultSources: Boolean = true,
        val fallbackSound: String = AudioFallbackSound.CLICK,
        val sources: List<AudioSourceConfig> = emptyList(),
        val testTerm: String = "日本",
        val testing: Boolean = false,
        val testResult: TestResult? = null,
    )

    /** Outcome of a test-play, rendered by the screen (the VM holds no string resources). */
    sealed interface TestResult {
        data class Playing(val sourceName: String) : TestResult

        data class NotFound(val term: String) : TestResult
    }

    private val audio = prefs.audioPreferences()
    private val http = OkHttpAudioHttp()
    private val player = WordAudioPlayer(context)

    private val stateFlow = MutableStateFlow(
        State(
            enabled = audio.enabled,
            autoPlay = audio.autoPlay,
            volume = audio.volumePercent,
            enableDefaultSources = audio.enableDefaultAudioSources,
            fallbackSound = audio.fallbackSoundType,
            sources = audio.sources,
        ),
    )
    val state: StateFlow<State> = stateFlow

    fun setEnabled(value: Boolean) {
        prefs.audioEnabled().set(value)
        stateFlow.value = stateFlow.value.copy(enabled = value)
    }

    fun setAutoPlay(value: Boolean) {
        prefs.audioAutoPlay().set(value)
        stateFlow.value = stateFlow.value.copy(autoPlay = value)
    }

    fun setVolume(value: Int) {
        val clamped = value.coerceIn(0, 100)
        prefs.audioVolume().set(clamped)
        stateFlow.value = stateFlow.value.copy(volume = clamped)
    }

    fun setDefaultSources(value: Boolean) {
        prefs.audioEnableDefaultSources().set(value)
        stateFlow.value = stateFlow.value.copy(enableDefaultSources = value)
    }

    fun setFallbackSound(value: String) {
        prefs.audioFallbackSound().set(value)
        stateFlow.value = stateFlow.value.copy(fallbackSound = value)
    }

    fun addSource(type: String) {
        persist(stateFlow.value.sources + AudioSourceConfig(type = type))
    }

    fun updateSource(index: Int, source: AudioSourceConfig) {
        val next = stateFlow.value.sources.toMutableList()
        if (index !in next.indices) return
        next[index] = source
        persist(next)
    }

    fun removeSource(index: Int) {
        val next = stateFlow.value.sources.toMutableList()
        if (index !in next.indices) return
        next.removeAt(index)
        persist(next)
    }

    fun moveSource(from: Int, to: Int) {
        val next = stateFlow.value.sources.toMutableList()
        if (from !in next.indices || to !in next.indices || from == to) return
        val item = next.removeAt(from)
        next.add(to, item)
        persist(next)
    }

    fun setTestTerm(term: String) {
        stateFlow.value = stateFlow.value.copy(testTerm = term)
    }

    fun testPlay() {
        val snapshot = stateFlow.value
        val term = snapshot.testTerm.trim()
        if (term.isBlank() || snapshot.testing) return
        stateFlow.value = snapshot.copy(testing = true, testResult = null)
        viewModelScope.launch {
            val language = prefs.profileStore.getActiveProfile().languageCode.ifBlank { "ja" }
            val resolver = AudioResolver(http)
            val candidates = runCatching {
                resolver.resolveAll(
                    sources = snapshot.sources,
                    term = term,
                    reading = term,
                    languageIso = language,
                    enableDefaultSources = snapshot.enableDefaultSources,
                )
            }.getOrDefault(emptyList())
            val first = candidates.firstOrNull()
            if (first == null) {
                stateFlow.value = stateFlow.value.copy(
                    testing = false,
                    testResult = TestResult.NotFound(term),
                )
                return@launch
            }
            player.play(first.url, snapshot.volume / 100f)
            stateFlow.value = stateFlow.value.copy(
                testing = false,
                testResult = TestResult.Playing(AudioSourceTypes.displayName(first.sourceType)),
            )
        }
    }

    private fun persist(sources: List<AudioSourceConfig>) {
        prefs.setAudioSources(sources)
        stateFlow.value = stateFlow.value.copy(sources = sources)
    }

    override fun onCleared() {
        player.stop()
        super.onCleared()
    }
}
// KUN <--
