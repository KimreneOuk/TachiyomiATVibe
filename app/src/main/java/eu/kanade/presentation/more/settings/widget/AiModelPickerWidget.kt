package eu.kanade.presentation.more.settings.widget

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.secondaryItemAlpha
import tachiyomi.presentation.core.components.ScrollbarLazyColumn

sealed interface AiModelListState {
    data object Idle : AiModelListState
    data class Loading(val apiKey: String) : AiModelListState
    data class Loaded(val models: List<String>) : AiModelListState
    data class Failed(val message: String) : AiModelListState
}

@Composable
fun AiModelPickerWidget(
    title: String,
    currentModel: String,
    recentModels: List<String>,
    listState: AiModelListState,
    hasApiKey: Boolean,
    onFetchModels: () -> Unit,
    onSelectModel: (String) -> Unit,
    onManualModel: (String) -> Unit,
) {
    var isDialogShown by remember { mutableStateOf(false) }

    TextPreferenceWidget(
        title = title,
        subtitle = currentModel.ifBlank { "—" },
        onPreferenceClick = { isDialogShown = true },
    )

    if (isDialogShown) {
        var query by remember { mutableStateOf("") }
        var manualDraft by remember(currentModel) { mutableStateOf(currentModel) }

        AlertDialog(
            onDismissRequest = { isDialogShown = false },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(text = title, modifier = Modifier.weight(1f))
                    IconButton(
                        enabled = hasApiKey && listState !is AiModelListState.Loading,
                        onClick = onFetchModels,
                    ) {
                        if (listState is AiModelListState.Loading) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                            )
                        } else {
                            Icon(imageVector = Icons.Default.Refresh, contentDescription = "Reload models")
                        }
                    }
                }
            },
            text = {
                Column {
                    OutlinedTextField(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Search models") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    )

                    PickerStatusLine(listState, hasApiKey)

                    Box(modifier = Modifier.weight(1f, fill = false)) {
                        val state = rememberLazyListState()
                        ScrollbarLazyColumn(state = state) {
                            val filteredRecent = recentModels.filter { matchesQuery(it, query) }
                            val filteredAll = (listState as? AiModelListState.Loaded)?.models
                                ?.filter { it !in recentModels && matchesQuery(it, query) }
                                ?: emptyList()

                            if (filteredRecent.isEmpty() && filteredAll.isEmpty()) {
                                item {
                                    Text(
                                        text = emptyMessage(listState, hasApiKey, recentModels),
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier
                                            .secondaryItemAlpha()
                                            .padding(vertical = 8.dp),
                                    )
                                }
                            } else {
                                if (filteredRecent.isNotEmpty()) {
                                    item(key = "header_recent") { SectionHeader("Recent") }
                                    items(filteredRecent, key = { "recent_$it" }) { id ->
                                        ModelRow(
                                            modelId = id,
                                            isSelected = id == currentModel,
                                            onSelected = {
                                                onSelectModel(it)
                                                isDialogShown = false
                                            },
                                        )
                                    }
                                }
                                if (filteredAll.isNotEmpty()) {
                                    item(key = "header_all") { SectionHeader("All models") }
                                    items(filteredAll, key = { "all_$it" }) { id ->
                                        ModelRow(
                                            modelId = id,
                                            isSelected = id == currentModel,
                                            onSelected = {
                                                onSelectModel(it)
                                                isDialogShown = false
                                            },
                                        )
                                    }
                                }
                            }
                        }
                        if (state.canScrollBackward) HorizontalDivider(modifier = Modifier.align(Alignment.TopCenter))
                        if (state.canScrollForward) HorizontalDivider(modifier = Modifier.align(Alignment.BottomCenter))
                    }

                    Spacer(Modifier.size(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        OutlinedTextField(
                            modifier = Modifier.weight(1f),
                            value = manualDraft,
                            onValueChange = { manualDraft = it },
                            label = { Text("Manual model ID") },
                            singleLine = true,
                        )
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(
                            enabled = manualDraft.isNotBlank() && manualDraft.trim() != currentModel,
                            onClick = {
                                onManualModel(manualDraft.trim())
                                isDialogShown = false
                            },
                        ) {
                            Text("Save")
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { isDialogShown = false }) {
                    Text(text = stringResource(MR.strings.action_cancel))
                }
            },
        )
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier
            .secondaryItemAlpha()
            .padding(start = 0.dp, top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun ModelRow(
    modelId: String,
    isSelected: Boolean,
    onSelected: (String) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .selectable(
                selected = isSelected,
                onClick = { if (!isSelected) onSelected(modelId) },
            )
            .fillMaxWidth()
            .minimumInteractiveComponentSize(),
    ) {
        RadioButton(selected = isSelected, onClick = null)
        Text(
            text = modelId,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 24.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun PickerStatusLine(listState: AiModelListState, hasApiKey: Boolean) {
    val message = when {
        !hasApiKey -> "Enter an API key to fetch models"
        listState is AiModelListState.Failed -> "Failed to fetch models: ${listState.message}"
        else -> return
    }
    Text(
        text = message,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = Modifier
            .padding(bottom = 8.dp)
            .secondaryItemAlpha(),
    )
}

private fun matchesQuery(id: String, query: String): Boolean {
    if (query.isBlank()) return true
    return id.contains(query.trim(), ignoreCase = true)
}

private fun emptyMessage(
    listState: AiModelListState,
    hasApiKey: Boolean,
    recentModels: List<String>,
): String = when {
    !hasApiKey -> "Enter an API key to fetch models"
    listState is AiModelListState.Failed -> "Failed to fetch models: ${listState.message}"
    listState is AiModelListState.Loading -> "Fetching…"
    listState is AiModelListState.Loaded && listState.models.isEmpty() && recentModels.isEmpty() ->
        "No models returned"
    recentModels.isEmpty() -> "Tap reload to fetch available models"
    else -> "No models match your search"
}
