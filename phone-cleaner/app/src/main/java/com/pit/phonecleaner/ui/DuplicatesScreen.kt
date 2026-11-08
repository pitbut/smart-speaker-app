package com.pit.phonecleaner.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoFixHigh
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pit.phonecleaner.core.FileOps
import com.pit.phonecleaner.core.filesCount
import com.pit.phonecleaner.core.formatDate
import com.pit.phonecleaner.core.formatSize
import com.pit.phonecleaner.files.DupKind
import com.pit.phonecleaner.files.FileItem
import com.pit.phonecleaner.files.FilesViewModel
import com.pit.phonecleaner.files.KeepRule

@Composable
fun DuplicatesScreen(vm: FilesViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val ui by vm.ui.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    var ruleMenu by remember { mutableStateOf(false) }
    var actionFor by remember { mutableStateOf<FileItem?>(null) }
    val groups = ui.dupGroups
    val selFiles = groups?.flatMap { g -> g.files.filter { it.path in ui.dupSelected } } ?: emptyList()

    ScreenScaffold(
        "Дубликаты", onBack,
        subtitle = groups?.let { g -> "${g.size} групп · можно освободить ${formatSize(g.sumOf { it.wasted })}" },
        bottomBar = {
            if (!groups.isNullOrEmpty()) SelectionBar(selFiles.size, selFiles.sumOf { it.size }, "Удалить") { confirm = true }
        }
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
        ) {
            if (ui.index == null) {
                if (ui.indexing) ProgressBox("Сканирую память…", filesCount(ui.indexedFiles))
                else EmptyBox("Анализ ещё не выполнялся", null, "Анализировать") { vm.buildIndex() }
                return@Column
            }
            ChipsRow(DupKind.entries, ui.dupKind, { it.title }, vm::setDupKind)
            Text(
                ui.dupKind.hint, Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            when {
                ui.dupRunning -> {
                    ProgressBox("Ищу: ${ui.dupKind.title.lowercase()}…", ui.dupProgress)
                    Box(Modifier.fillMaxWidth().padding(16.dp)) {
                        OutlinedButton(onClick = vm::cancelDuplicates, modifier = Modifier.fillMaxWidth()) { Text("Отмена") }
                    }
                }
                groups == null -> EmptyBox(
                    "Поиск ещё не запускался",
                    "Проверка содержимого больших файлов может занять несколько минут",
                    "Начать поиск"
                ) { vm.findDuplicates() }
                groups.isEmpty() -> EmptyBox("Дубликаты не найдены", null, "Искать снова") { vm.findDuplicates() }
                else -> {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Box(Modifier.weight(1f)) {
                            Button(onClick = { ruleMenu = true }, modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Filled.AutoFixHigh, null)
                                Text(" Автовыбор", maxLines = 1)
                            }
                            DropdownMenu(expanded = ruleMenu, onDismissRequest = { ruleMenu = false }) {
                                KeepRule.entries.forEach { r ->
                                    DropdownMenuItem(
                                        text = { Text(r.title, fontWeight = if (r == ui.keepRule) FontWeight.Bold else null) },
                                        onClick = { ruleMenu = false; vm.applyKeepRule(r) })
                                }
                            }
                        }
                        OutlinedButton(onClick = vm::clearDupSelection, modifier = Modifier.weight(1f)) { Text("Снять всё", maxLines = 1) }
                    }
                    LazyColumn(Modifier.fillMaxSize()) {
                        groups.forEach { g ->
                            item(key = "g_${g.id}") {
                                Text(
                                    "Группа ${g.id + 1}: ${filesCount(g.files.size)} · лишнее ${formatSize(g.wasted)}",
                                    Modifier.padding(start = 16.dp, top = 14.dp, bottom = 2.dp),
                                    style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary
                                )
                            }
                            items(g.files, key = { "g${g.id}|${it.path}" }) { f ->
                                SelectableRow(
                                    checked = f.path in ui.dupSelected,
                                    onToggle = { vm.toggleDup(f.path) },
                                    title = f.name,
                                    subtitle = f.parent,
                                    subtitle2 = formatDate(f.modified),
                                    trailing = formatSize(f.size),
                                    onLongClick = { actionFor = f },
                                    leading = { FileThumb(f.path, size = 52.dp) }
                                )
                            }
                            item(key = "d_${g.id}") { HorizontalDivider() }
                        }
                    }
                }
            }
        }
    }

    actionFor?.let { f ->
        FileActionsDialog(f.name, f.path, onDismiss = { actionFor = null },
            onOpen = { FileOps.open(ctx, f.path) }, onShare = { FileOps.share(ctx, f.path) },
            onDelete = { vm.deletePaths(mapOf(f.path to f.size)) })
    }
    ConfirmDialog(
        confirm, "Удалить дубликаты?",
        "Будет удалено ${filesCount(selFiles.size)} (${formatSize(selFiles.sumOf { it.size })}). " +
            "В каждой группе обязательно останется хотя бы один файл.",
        onConfirm = vm::deleteDuplicates, onDismiss = { confirm = false }
    )
    BusyOverlay(ui.busy)
    MessageDialog(ui.message, vm::dismissMessage)
}
