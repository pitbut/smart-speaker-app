package com.pit.phonecleaner.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pit.phonecleaner.Screen
import com.pit.phonecleaner.core.FileOps
import com.pit.phonecleaner.core.filesCount
import com.pit.phonecleaner.core.formatDate
import com.pit.phonecleaner.core.formatSize
import com.pit.phonecleaner.files.FilesViewModel

@Composable
fun ForeignScreen(vm: FilesViewModel, onBack: () -> Unit, go: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val ui by vm.ui.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    val list = vm.foreignVisible(ui)
    val sel = ui.foreign.filter { it.path in ui.foreignSelected }

    ScreenScaffold(
        "Посторонние папки", onBack,
        subtitle = "Не относятся к системе Android · ${formatSize(ui.foreign.sumOf { it.size })}",
        bottomBar = {
            if (list.isNotEmpty()) SelectionBar(sel.size, sel.sumOf { it.size }, "Удалить") { confirm = true }
        }
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
        ) {
            when {
                ui.indexing -> {
                    ProgressBox("Сканирую память…", "${filesCount(ui.indexedFiles)} · ${ui.indexDir}"); return@Column
                }
                ui.index == null -> {
                    EmptyBox("Анализ ещё не выполнялся", null, "Анализировать") { vm.buildIndex() }; return@Column
                }
            }
            Text(
                "Папки и файлы в корне памяти, созданные приложениями (не DCIM, Download, Music и т.п.). " +
                    "Нажмите на папку, чтобы посмотреть, что внутри.",
                Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Только без владельца (остатки)", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = ui.foreignOnlyOrphans, onCheckedChange = vm::setOnlyOrphans)
            }
            SelectAllRow(list.isNotEmpty() && list.all { it.path in ui.foreignSelected }, list.size, list.sumOf { it.size }, vm::toggleAllForeign)
            if (list.isEmpty()) {
                EmptyBox("Ничего не найдено"); return@Column
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.path }) { e ->
                    SelectableRow(
                        checked = e.path in ui.foreignSelected,
                        onToggle = { vm.toggleForeign(e.path) },
                        title = e.name,
                        subtitle = (if (e.isDir) filesCount(e.files) + " · " else "") + formatDate(e.modified),
                        subtitle2 = e.owner?.let { "Владелец: $it" } ?: "Владелец не найден — возможно, остатки",
                        trailing = formatSize(e.size),
                        onClick = { if (e.isDir) go(Screen.Browse(e.path)) else FileOps.open(ctx, e.path) },
                        leading = { FileThumb(e.path, e.isDir, 40.dp) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    ConfirmDialog(
        confirm, "Удалить выбранное?",
        "Будет удалено ${sel.size} элементов вместе со всем содержимым (${formatSize(sel.sumOf { it.size })}). " +
            "Убедитесь, что там нет нужных вам файлов.",
        onConfirm = vm::deleteForeign, onDismiss = { confirm = false }
    )
    BusyOverlay(ui.busy)
    MessageDialog(ui.message, vm::dismissMessage)
}
