package com.pit.phonecleaner.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pit.phonecleaner.core.FileOps
import com.pit.phonecleaner.core.filesCount
import com.pit.phonecleaner.core.formatSize
import com.pit.phonecleaner.files.FilesViewModel

@Composable
fun JunkScreen(vm: FilesViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val ui by vm.ui.collectAsState()
    var expanded by remember { mutableStateOf(setOf<String>()) }
    var confirm by remember { mutableStateOf(false) }
    val selSize = vm.junkSelectedSize(ui)

    ScreenScaffold(
        "Мусорные файлы", onBack,
        subtitle = if (ui.index != null) "Найдено: ${formatSize(ui.junk.sumOf { it.total })}" else null,
        bottomBar = {
            if (ui.index != null && ui.junk.isNotEmpty())
                SelectionBar(ui.junkSelected.size, selSize, "Удалить") { confirm = true }
        }
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
        ) {
            when {
                ui.indexing -> ProgressBox("Сканирую память…", "${filesCount(ui.indexedFiles)} · ${ui.indexDir}")
                ui.index == null -> EmptyBox("Анализ ещё не выполнялся", null, "Анализировать") { vm.buildIndex() }
                ui.junk.isEmpty() -> EmptyBox("Мусор не найден", "Телефон чист 🎉", "Повторить") { vm.buildIndex() }
                else -> LazyColumn(Modifier.fillMaxSize()) {
                    ui.junk.forEach { cat ->
                        item(key = "cat_${cat.id}") {
                            val selCount = cat.items.count { it.path in ui.junkSelected }
                            val state = when (selCount) {
                                0 -> ToggleableState.Off
                                cat.items.size -> ToggleableState.On
                                else -> ToggleableState.Indeterminate
                            }
                            val open = cat.id in expanded
                            Row(
                                Modifier
                                    .fillMaxWidth()
                                    .clickable { expanded = if (open) expanded - cat.id else expanded + cat.id }
                                    .padding(horizontal = 8.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null)
                                Column(
                                    Modifier
                                        .weight(1f)
                                        .padding(start = 8.dp)
                                ) {
                                    Text(cat.title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "${cat.description} · ${cat.items.size} шт.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Text(formatSize(cat.total), fontWeight = FontWeight.Bold)
                                TriStateCheckbox(state = state, onClick = { vm.toggleJunkCategory(cat) })
                            }
                            HorizontalDivider()
                        }
                        if (cat.id in expanded) {
                            items(cat.items, key = { "${cat.id}|${it.path}" }) { it ->
                                SelectableRow(
                                    checked = it.path in ui.junkSelected,
                                    onToggle = { vm.toggleJunk(it.path) },
                                    title = it.name.ifEmpty { it.path },
                                    subtitle = it.path,
                                    subtitle2 = it.note,
                                    trailing = if (it.isDir && it.size == 0L) null else formatSize(it.size),
                                    onLongClick = { if (!it.isDir) FileOps.open(ctx, it.path) },
                                    leading = { FileThumb(it.path, it.isDir, 40.dp) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    ConfirmDialog(
        confirm, "Удалить мусор?",
        "Будет удалено ${ui.junkSelected.size} элементов (${formatSize(selSize)}). Действие нельзя отменить.",
        onConfirm = vm::deleteJunk, onDismiss = { confirm = false }
    )
    BusyOverlay(ui.busy)
    MessageDialog(ui.message, vm::dismissMessage)
}

