// SPDX-License-Identifier: GPL-3.0-or-later
// KUN -->
package eu.kanade.tachiyomi.ui.dictionary

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import mihon.core.dictionary.model.DictionaryProfile
import mihon.core.dictionary.model.TYPE_SUBDIRS
import mihon.core.dictionary.model.allDictionaryNames
import mihon.core.dictionary.model.dictionariesDir
import mihon.core.dictionary.model.dictionaryTypeDir
import mihon.core.dictionary.model.matchDictionaryName
import mihon.core.dictionary.model.resolveDictionaryOrder
import mihon.core.dictionary.repository.DictionaryRepository
import mihon.core.dictionary.ui.invalidateDictionaryTitle
import mihon.icons.materialsymbols.MaterialSymbols
import mihon.icons.materialsymbols.rounded.Add
import mihon.icons.materialsymbols.rounded.ArrowDownward
import mihon.icons.materialsymbols.rounded.ArrowDropDown
import mihon.icons.materialsymbols.rounded.ArrowUpward
import mihon.icons.materialsymbols.rounded.Book
import mihon.icons.materialsymbols.rounded.Close
import mihon.icons.materialsymbols.rounded.Delete
import mihon.icons.materialsymbols.rounded.Edit
import org.json.JSONObject
import tachiyomi.i18n.MR
import tachiyomi.i18n.kun.KUNMR
import tachiyomi.presentation.core.components.material.Scaffold
import tachiyomi.presentation.core.i18n.stringResource
import java.io.File

/**
 * Dedicated dictionary management screen mirroring Yomitan's settings:
 *  - Priority reordering (move up/down)
 *  - Enable/disable toggle for the active profile
 *  - Display alias editor (custom tag label)
 *  - Type badges (Term, Freq, Pitch, Kanji) and revision display
 *  - Safe deletion with confirmation dialog
 *  - Definition display mode selector
 *  - Active profile selector
 *  - Direct ZIP import
 */
class DictionaryManagementScreen : Screen() {

    @Composable
    override fun Content() {
        val navigator = LocalNavigator.currentOrThrow
        val viewModel = metroViewModel<DictionaryManagementViewModel>()
        val state by viewModel.state.collectAsState()

        var editingAliasItem by remember { mutableStateOf<DictionaryItem?>(null) }
        var deletingItem by remember { mutableStateOf<DictionaryItem?>(null) }

        val importLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenMultipleDocuments(),
        ) { uris: List<Uri> ->
            if (uris.isNotEmpty()) {
                viewModel.importZips(uris)
            }
        }

        Scaffold(
            topBar = {
                AppBar(
                    title = stringResource(KUNMR.strings.pref_manage_dictionaries),
                    navigateUp = { navigator.pop() },
                    actions = {
                        IconButton(
                            onClick = {
                                importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed"))
                            },
                        ) {
                            Icon(
                                imageVector = MaterialSymbols.Rounded.Add,
                                contentDescription = stringResource(KUNMR.strings.pref_import_dictionary),
                            )
                        }
                    },
                )
            },
        ) { paddingValues ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
            ) {
                if (state.isImporting) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }

                state.importMessage?.let { msg ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = msg,
                                style = MaterialTheme.typography.bodyMedium,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { viewModel.clearImportMessage() }) {
                                Icon(MaterialSymbols.Rounded.Close, contentDescription = null)
                            }
                        }
                    }
                }

                // A failed import says why, through the same dismissible card as the success message.
                state.importFailure?.let { failure ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = stringResource(failure.message),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                modifier = Modifier.weight(1f),
                            )
                            IconButton(onClick = { viewModel.clearImportMessage() }) {
                                Icon(MaterialSymbols.Rounded.Close, contentDescription = null)
                            }
                        }
                    }
                }

                if (state.dictionaries.isEmpty() && !state.isImporting) {
                    EmptyState(
                        onImport = {
                            importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed"))
                        },
                        onImportBackup = {
                            navigator.push(DictionaryBackupImportScreen())
                        },
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (state.profiles.size > 1) {
                            item(key = "header_profile") {
                                ProfileSelectorCard(
                                    profiles = state.profiles,
                                    activeProfile = state.activeProfile,
                                    onSelect = { viewModel.setActiveProfile(it) },
                                )
                            }
                        }

                        item(key = "header_collapse_mode") {
                            CollapseModeCard(
                                activeMode = state.activeProfile.dictionaryCollapseMode,
                                onSelect = { viewModel.setCollapseMode(it) },
                            )
                        }

                        itemsIndexed(
                            items = state.dictionaries,
                            key = { _, item -> item.name },
                        ) { index, item ->
                            DictionaryCard(
                                index = index,
                                totalCount = state.dictionaries.size,
                                item = item,
                                onToggle = { viewModel.toggleEnabled(item.name, it) },
                                onMoveUp = { viewModel.move(index, index - 1) },
                                onMoveDown = { viewModel.move(index, index + 1) },
                                onEditAlias = { editingAliasItem = item },
                                onDelete = { deletingItem = item },
                            )
                        }

                        item(key = "footer_actions") {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                OutlinedButton(
                                    onClick = {
                                        importLauncher.launch(arrayOf("application/zip", "application/x-zip-compressed"))
                                    },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Icon(MaterialSymbols.Rounded.Add, contentDescription = null)
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text(stringResource(KUNMR.strings.pref_import_dictionary))
                                }
                                OutlinedButton(
                                    onClick = { navigator.push(DictionaryBackupImportScreen()) },
                                    modifier = Modifier.weight(1f),
                                ) {
                                    Text(stringResource(KUNMR.strings.pref_import_yomitan_backup))
                                }
                            }
                        }
                    }
                }
            }
        }

        editingAliasItem?.let { item ->
            EditAliasDialog(
                item = item,
                onDismiss = { editingAliasItem = null },
                onConfirm = { alias -> viewModel.setAlias(item.name, alias) },
            )
        }

        deletingItem?.let { item ->
            DeleteConfirmDialog(
                item = item,
                onDismiss = { deletingItem = null },
                onConfirm = { viewModel.delete(item.name) },
            )
        }
    }

    @Composable
    private fun EmptyState(
        onImport: () -> Unit,
        onImportBackup: () -> Unit,
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Icon(
                imageVector = MaterialSymbols.Rounded.Book,
                contentDescription = null,
                modifier = Modifier.size(64.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = stringResource(KUNMR.strings.pref_dict_none_imported),
                style = MaterialTheme.typography.titleLarge,
                textAlign = TextAlign.Center,
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onImport) {
                Icon(MaterialSymbols.Rounded.Add, contentDescription = null)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(KUNMR.strings.pref_import_dictionary))
            }
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedButton(onClick = onImportBackup) {
                Text(stringResource(KUNMR.strings.pref_import_yomitan_backup))
            }
        }
    }

    @Composable
    private fun ProfileSelectorCard(
        profiles: List<DictionaryProfile>,
        activeProfile: DictionaryProfile,
        onSelect: (String) -> Unit,
    ) {
        var expanded by remember { mutableStateOf(false) }
        Box {
            OutlinedCard(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(KUNMR.strings.pref_dictionary_profiles),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = stringResource(KUNMR.strings.pref_dict_active_profile, activeProfile.name),
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(MaterialSymbols.Rounded.ArrowDropDown, contentDescription = null)
                }
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                profiles.forEach { profile ->
                    DropdownMenuItem(
                        text = { Text(profile.name) },
                        onClick = {
                            onSelect(profile.id)
                            expanded = false
                        },
                    )
                }
            }
        }
    }

    @Composable
    private fun CollapseModeCard(
        activeMode: String,
        onSelect: (String) -> Unit,
    ) {
        var expanded by remember { mutableStateOf(false) }
        Box {
            OutlinedCard(
                onClick = { expanded = true },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(KUNMR.strings.pref_dict_collapse_mode),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            text = when (activeMode) {
                                DictionaryProfile.DICTIONARY_COLLAPSE_EXPAND_FIRST_AVAILABLE ->
                                    stringResource(KUNMR.strings.pref_dict_collapse_mode_expand_first)
                                DictionaryProfile.DICTIONARY_COLLAPSE_COLLAPSE_ALL ->
                                    stringResource(KUNMR.strings.pref_dict_collapse_mode_collapse_all)
                                else ->
                                    stringResource(KUNMR.strings.pref_dict_collapse_mode_expand_all)
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Icon(MaterialSymbols.Rounded.ArrowDropDown, contentDescription = null)
                }
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = { expanded = false },
            ) {
                DropdownMenuItem(
                    text = { Text(stringResource(KUNMR.strings.pref_dict_collapse_mode_expand_all)) },
                    onClick = {
                        onSelect(DictionaryProfile.DICTIONARY_COLLAPSE_EXPAND_ALL)
                        expanded = false
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(KUNMR.strings.pref_dict_collapse_mode_expand_first)) },
                    onClick = {
                        onSelect(DictionaryProfile.DICTIONARY_COLLAPSE_EXPAND_FIRST_AVAILABLE)
                        expanded = false
                    },
                )
                DropdownMenuItem(
                    text = { Text(stringResource(KUNMR.strings.pref_dict_collapse_mode_collapse_all)) },
                    onClick = {
                        onSelect(DictionaryProfile.DICTIONARY_COLLAPSE_COLLAPSE_ALL)
                        expanded = false
                    },
                )
            }
        }
    }

    @Composable
    private fun DictionaryCard(
        index: Int,
        totalCount: Int,
        item: DictionaryItem,
        onToggle: (Boolean) -> Unit,
        onMoveUp: () -> Unit,
        onMoveDown: () -> Unit,
        onEditAlias: () -> Unit,
        onDelete: () -> Unit,
    ) {
        ElevatedCard(
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Surface(
                        shape = RoundedCornerShape(8.dp),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Text(
                            text = "#${index + 1}",
                            style = MaterialTheme.typography.labelLarge,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            fontWeight = FontWeight.Bold,
                        )
                    }
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = item.displayTitle,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                        if (item.alias != null) {
                            Text(
                                text = stringResource(KUNMR.strings.pref_dict_alias_original, item.title),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                    Switch(
                        checked = item.enabled,
                        onCheckedChange = onToggle,
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if ("term" in item.types) {
                        TypeBadge(
                            label = stringResource(KUNMR.strings.pref_dict_type_term),
                            containerColor = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        )
                    }
                    if ("frequency" in item.types) {
                        TypeBadge(
                            label = stringResource(KUNMR.strings.pref_dict_type_freq),
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                        )
                    }
                    if ("pitch" in item.types) {
                        TypeBadge(
                            label = stringResource(KUNMR.strings.pref_dict_type_pitch),
                            containerColor = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                        )
                    }
                    if ("kanji" in item.types) {
                        TypeBadge(
                            label = stringResource(KUNMR.strings.pref_dict_type_kanji),
                            containerColor = MaterialTheme.colorScheme.surfaceVariant,
                            contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (!item.revision.isNullOrBlank()) {
                        Text(
                            text = stringResource(KUNMR.strings.pref_dict_revision_label, item.revision),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(
                            onClick = onMoveUp,
                            enabled = index > 0,
                        ) {
                            Icon(
                                MaterialSymbols.Rounded.ArrowUpward,
                                contentDescription = stringResource(KUNMR.strings.pref_dict_move_up),
                            )
                        }
                        IconButton(
                            onClick = onMoveDown,
                            enabled = index < totalCount - 1,
                        ) {
                            Icon(
                                MaterialSymbols.Rounded.ArrowDownward,
                                contentDescription = stringResource(KUNMR.strings.pref_dict_move_down),
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        IconButton(onClick = onEditAlias) {
                            Icon(
                                MaterialSymbols.Rounded.Edit,
                                contentDescription = stringResource(KUNMR.strings.pref_dict_edit_alias),
                            )
                        }
                        IconButton(onClick = onDelete) {
                            Icon(
                                MaterialSymbols.Rounded.Delete,
                                contentDescription = stringResource(MR.strings.action_delete),
                                tint = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun TypeBadge(
        label: String,
        containerColor: Color,
        contentColor: Color,
    ) {
        Surface(
            shape = RoundedCornerShape(4.dp),
            color = containerColor,
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                color = contentColor,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
            )
        }
    }

    @Composable
    private fun EditAliasDialog(
        item: DictionaryItem,
        onDismiss: () -> Unit,
        onConfirm: (String?) -> Unit,
    ) {
        var aliasText by remember { mutableStateOf(item.alias.orEmpty()) }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(text = stringResource(KUNMR.strings.pref_dict_edit_alias)) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = stringResource(KUNMR.strings.pref_dict_alias_original, item.title),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = aliasText,
                        onValueChange = { aliasText = it },
                        label = { Text(text = stringResource(KUNMR.strings.pref_dict_alias)) },
                        placeholder = { Text(text = stringResource(KUNMR.strings.pref_dict_alias_hint)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onConfirm(aliasText.trim().ifEmpty { null })
                        onDismiss()
                    },
                ) {
                    Text(text = stringResource(MR.strings.action_save))
                }
            },
            dismissButton = {
                Row {
                    if (item.alias != null) {
                        TextButton(
                            onClick = {
                                onConfirm(null)
                                onDismiss()
                            },
                        ) {
                            Text(text = stringResource(MR.strings.action_reset))
                        }
                    }
                    TextButton(onClick = onDismiss) {
                        Text(text = stringResource(MR.strings.action_cancel))
                    }
                }
            },
        )
    }

    @Composable
    private fun DeleteConfirmDialog(
        item: DictionaryItem,
        onDismiss: () -> Unit,
        onConfirm: () -> Unit,
    ) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text(text = stringResource(MR.strings.action_delete)) },
            text = {
                Text(
                    text = stringResource(
                        KUNMR.strings.pref_dict_delete_confirm,
                        item.displayTitle,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onConfirm()
                        onDismiss()
                    },
                ) {
                    Text(
                        text = stringResource(MR.strings.action_delete),
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text(text = stringResource(MR.strings.action_cancel))
                }
            },
        )
    }
}

@Immutable
data class DictionaryItem(
    val name: String,
    val title: String,
    val alias: String?,
    val enabled: Boolean,
    val types: List<String>,
    val revision: String?,
    val author: String?,
) {
    val displayTitle: String get() = alias ?: title
}

@Inject
@ViewModelKey
@ContributesIntoMap(AppScope::class, binding = binding<ViewModel>())
class DictionaryManagementViewModel(
    private val context: Context,
    private val repository: DictionaryRepository,
    private val prefs: DictionaryPreferences,
) : ViewModel() {

    @Immutable
    data class State(
        val dictionaries: List<DictionaryItem> = emptyList(),
        val profiles: List<DictionaryProfile> = emptyList(),
        val activeProfile: DictionaryProfile = DictionaryProfile.EMPTY,
        val isImporting: Boolean = false,
        val importMessage: String? = null,
        val importFailure: DictionaryImportFailure? = null,
    )

    // KUN -->
    // Loading reads every installed dictionary's `index.json`, so it must not run on the main
    // thread from the constructor (the jank grew with the dictionary count). Start empty and fill
    // in off the main thread; the transient import fields a concurrent action may already have set
    // are preserved rather than overwritten by the initial load.
    private val stateFlow = MutableStateFlow(State())
    val state: StateFlow<State> = stateFlow

    init {
        viewModelScope.launch(Dispatchers.IO) {
            val loaded = loadData()
            stateFlow.update { current ->
                loaded.copy(
                    isImporting = current.isImporting,
                    importMessage = current.importMessage,
                    importFailure = current.importFailure,
                )
            }
        }
    }
    // KUN <--

    private fun loadData(
        isImporting: Boolean = false,
        importMessage: String? = null,
        importFailure: DictionaryImportFailure? = null,
    ): State {
        val profiles = prefs.profileStore.getProfiles()
        val activeProfile = prefs.profileStore.getActiveProfile()
        val allNames = allDictionaryNames(context)
        val orderedNames = resolveDictionaryOrder(allNames, activeProfile.dictionaryOrder, context)

        val rootDir = dictionariesDir(context)
        val isAllEnabled = activeProfile.enabledDictionaries.isEmpty()
        val resolvedEnabled = if (isAllEnabled) {
            emptySet()
        } else {
            activeProfile.enabledDictionaries.mapNotNull { matchDictionaryName(it, allNames, context) }.toSet()
                .ifEmpty { activeProfile.enabledDictionaries }
        }

        val items = orderedNames.map { name ->
            var originalTitle = name
            var revision: String? = null
            var author: String? = null
            val types = TYPE_SUBDIRS.filter { type ->
                dictionaryTypeDir(context, type, name).exists()
            }
            for (type in TYPE_SUBDIRS) {
                val indexFile = File(File(rootDir, type), "$name/index.json")
                if (indexFile.exists()) {
                    runCatching {
                        val json = JSONObject(indexFile.readText())
                        val t = json.optString("title", "")
                        if (t.isNotBlank()) originalTitle = t
                        revision = json.optString("revision", "").ifEmpty { null }
                        author = json.optString("author", "").ifEmpty { null }
                    }
                    break
                }
            }
            val alias = prefs.getDisplayName(name)
            val enabled = if (isAllEnabled) true else name in resolvedEnabled
            DictionaryItem(
                name = name,
                title = originalTitle,
                alias = alias,
                enabled = enabled,
                types = types,
                revision = revision,
                author = author,
            )
        }

        return State(
            dictionaries = items,
            profiles = profiles,
            activeProfile = activeProfile,
            isImporting = isImporting,
            importMessage = importMessage,
            importFailure = importFailure,
        )
    }

    fun refresh() {
        val current = stateFlow.value
        stateFlow.value = loadData(
            isImporting = current.isImporting,
            importMessage = current.importMessage,
            importFailure = current.importFailure,
        )
    }

    fun toggleEnabled(name: String, enabled: Boolean) {
        val active = prefs.profileStore.getActiveProfile()
        val allNames = allDictionaryNames(context)
        val currentEnabled = active.enabledDictionaries
        val newEnabled = if (currentEnabled.isEmpty()) {
            if (!enabled) {
                val remaining = allNames.toSet() - name
                if (remaining.isEmpty()) setOf(DISABLED_SENTINEL) else remaining
            } else {
                emptySet()
            }
        } else {
            if (enabled) {
                val updated = currentEnabled - DISABLED_SENTINEL + name
                if (updated.containsAll(allNames)) emptySet() else updated
            } else {
                val remaining = currentEnabled - name
                if (remaining.isEmpty()) setOf(DISABLED_SENTINEL) else remaining
            }
        }
        prefs.profileStore.updateProfile(active.copy(enabledDictionaries = newEnabled))
        refresh()
    }

    fun move(fromIndex: Int, toIndex: Int) {
        val current = stateFlow.value.dictionaries
        if (fromIndex !in current.indices || toIndex !in current.indices) return
        val list = current.map { it.name }.toMutableList()
        val item = list.removeAt(fromIndex)
        list.add(toIndex, item)
        val active = prefs.profileStore.getActiveProfile()
        prefs.profileStore.updateProfile(active.copy(dictionaryOrder = list))
        refresh()
    }

    fun setAlias(name: String, alias: String?) {
        val clean = alias?.trim()?.ifEmpty { null }
        prefs.setDisplayName(name, clean)
        invalidateDictionaryTitle(name)
        refresh()
    }

    fun delete(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            DictionaryImportCoordinator.delete(context, name, prefs, repository)
            invalidateDictionaryTitle(name)
            refresh()
        }
    }

    fun setCollapseMode(mode: String) {
        val active = prefs.profileStore.getActiveProfile()
        prefs.profileStore.updateProfile(active.copy(dictionaryCollapseMode = mode))
        refresh()
    }

    fun setActiveProfile(id: String) {
        prefs.profileStore.setActiveProfile(id)
        refresh()
    }

    fun importZips(uris: List<Uri>) {
        if (uris.isEmpty()) return
        // `isImporting` drives the progress bar and suppresses the empty state, and both batches write
        // the same state fields: a second batch would clear the first one's message and switch the bar
        // off as soon as *either* finished, so a batch started while one runs is dropped instead of
        // interleaving with the coordinator.
        if (stateFlow.value.isImporting) return
        viewModelScope.launch {
            stateFlow.update { it.copy(isImporting = true, importMessage = null, importFailure = null) }
            val outcomes = withContext(Dispatchers.IO) {
                uris.map { uri ->
                    DictionaryImportCoordinator.importFromUri(context, uri, prefs, repository)
                }
            }
            val imported = outcomes.filterIsInstance<DictionaryImportOutcome.Imported>()
                .map { it.dictionary.title }
            val failure = outcomes.filterIsInstance<DictionaryImportOutcome.Failed>()
                .firstOrNull()
                ?.reason
            val message = if (imported.isNotEmpty()) {
                imported.joinToString(", ")
            } else {
                null
            }
            stateFlow.update { it.copy(isImporting = false, importMessage = message, importFailure = failure) }
            refresh()
        }
    }

    fun clearImportMessage() {
        stateFlow.update { it.copy(importMessage = null, importFailure = null) }
    }

    private companion object {
        const val DISABLED_SENTINEL = "__none__"
    }
}
// KUN <--
