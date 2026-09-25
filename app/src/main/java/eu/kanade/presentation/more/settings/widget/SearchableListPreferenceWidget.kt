package eu.kanade.presentation.more.settings.widget

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import tachiyomi.i18n.MR
import tachiyomi.presentation.core.components.ScrollbarLazyColumn
import tachiyomi.presentation.core.i18n.stringResource
import tachiyomi.presentation.core.util.secondaryItemAlpha

@Composable
fun <T> SearchableListPreferenceWidget(
    value: T,
    title: String,
    subtitle: String?,
    icon: ImageVector?,
    entries: Map<out T, String>,
    recentItems: List<T> = emptyList(),
    onValueChange: (T) -> Unit,
) {
    var isDialogShown by remember { mutableStateOf(false) }

    TextPreferenceWidget(
        title = title,
        subtitle = subtitle,
        icon = icon,
        onPreferenceClick = { isDialogShown = true },
    )

    if (isDialogShown) {
        var query by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { isDialogShown = false },
            title = { Text(text = title) },
            text = {
                Column {
                    OutlinedTextField(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 8.dp),
                        value = query,
                        onValueChange = { query = it },
                        label = { Text("Search") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    )

                    Box(modifier = Modifier.weight(1f, fill = false)) {
                        val state = rememberLazyListState()
                        ScrollbarLazyColumn(state = state) {
                            val filteredEntries = entries.filter {
                                query.isBlank() || it.value.contains(query, ignoreCase = true)
                            }
                            val filteredRecent = recentItems.filter {
                                (query.isBlank() || entries[it]?.contains(query, ignoreCase = true) == true) &&
                                    entries.containsKey(it)
                            }
                            val filteredAll = filteredEntries.keys.filter { it !in filteredRecent }

                            if (filteredRecent.isNotEmpty()) {
                                item(key = "header_recent") { SectionHeader("Recent") }
                                filteredRecent.forEach { currentKey ->
                                    val isSelected = value == currentKey
                                    item(key = "recent_$currentKey") {
                                        DialogRow(
                                            label = entries[currentKey] ?: "",
                                            isSelected = isSelected,
                                            onSelected = {
                                                onValueChange(currentKey)
                                                isDialogShown = false
                                            },
                                        )
                                    }
                                }
                            }

                            if (filteredAll.isNotEmpty()) {
                                item(key = "header_all") { SectionHeader("All") }
                                filteredAll.forEach { currentKey ->
                                    val isSelected = value == currentKey
                                    item(key = "all_$currentKey") {
                                        DialogRow(
                                            label = entries[currentKey] ?: "",
                                            isSelected = isSelected,
                                            onSelected = {
                                                onValueChange(currentKey)
                                                isDialogShown = false
                                            },
                                        )
                                    }
                                }
                            }

                            if (filteredRecent.isEmpty() && filteredAll.isEmpty()) {
                                item {
                                    Text(
                                        text = "No results found",
                                        style = MaterialTheme.typography.bodySmall,
                                        modifier = Modifier
                                            .secondaryItemAlpha()
                                            .padding(vertical = 8.dp),
                                    )
                                }
                            }
                        }
                        if (state.canScrollBackward) HorizontalDivider(modifier = Modifier.align(Alignment.TopCenter))
                        if (state.canScrollForward) HorizontalDivider(modifier = Modifier.align(Alignment.BottomCenter))
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
private fun DialogRow(
    label: String,
    isSelected: Boolean,
    onSelected: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .clip(MaterialTheme.shapes.small)
            .selectable(
                selected = isSelected,
                onClick = { if (!isSelected) onSelected() },
            )
            .fillMaxWidth()
            .minimumInteractiveComponentSize(),
    ) {
        RadioButton(
            selected = isSelected,
            onClick = null,
        )
        Text(
            text = label,
            style = MaterialTheme.typography.bodyLarge.merge(),
            modifier = Modifier.padding(start = 24.dp),
        )
    }
}
