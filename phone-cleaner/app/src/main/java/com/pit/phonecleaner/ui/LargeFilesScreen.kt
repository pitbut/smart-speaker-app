package com.pit.phonecleaner.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import com.pit.phonecleaner.core.FileOps
import com.pit.phonecleaner.core.FileType
import com.pit.phonecleaner.core.filesCount
import com.pit.phonecleaner.core.formatDate
import com.pit.phonecleaner.core.formatSize
import com.pit.phonecleaner.files.FileItem
import com.pit.phonecleaner.files.FileSort
import com.pit.phonecleaner.files.FilesViewModel

private val THRESHOLDS = listOf(10, 25, 50, 100, 250, 500, 1000)

@Composable
fun LargeFilesScreen(vm: FilesViewModel, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val ui by vm.ui.collectAsState()
    var confirm by remember { mutableStateOf(false) }
    var thrMenu by remember { mutableStateOf(false) }
    var actionFor by remember { mutableStateOf<FileItem?>(null) }
    val list = vm.largeFiles(ui)
    val sel = list.filter { it.path in ui.largeSelected }
    val thrMb = (ui.largeThreshold / (1024 * 1024)).toInt()

    ScreenScaffold(
        "Большие файлы", onBack,
        subtitle = "Больше $thrMb МБ · ${filesCount(list.size)} · ${formatSize(list.sumOf { it.size })}",
        actions = {
            IconButton(onClick = { thrMenu = true }) { Icon(Icons.Filled.Tune, "Порог") }
            DropdownMenu(expanded = thrMenu, onDismissRequest = { thrMenu = false }) {
                THRESHOLDS.forEach { mb ->
                    DropdownMenuItem(
                        text = { Text("Больше ${if (mb >= 1000) "1 ГБ" else "$mb МБ"}", fontWeight = if (mb == thrMb) FontWeight.Bold else null) },
                        onClick = { thrMenu = false; vm.setLargeThresholdMb(mb) })
                }
            }
            SortMenu(FileSort.entries, ui.largeSort, { it.title }, vm::setLargeSort)
        },
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
            val types: List<FileType?> = listOf(null) + FileType.entries
            ChipsRow(types, ui.largeType, { t ->
                val n = ui.index?.files?.count { it.size >= ui.largeThreshold && (t == null || FileType.ofExt(it.ext) == t) } ?: 0
                "${t?.title ?: "Все"} ($n)"
            }, vm::setLargeType)
            SelectAllRow(list.isNotEmpty() && sel.size == list.size, list.size, list.sumOf { it.size }, vm::toggleAllLarge)
            if (list.isEmpty()) {
                EmptyBox("Больших файлов нет", "Попробуйте уменьшить порог"); return@Column
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.path }) { f ->
                    SelectableRow(
                        checked = f.path in ui.largeSelected,
                        onToggle = { vm.toggleLarge(f.path) },
                        title = f.name,
                        subtitle = f.parent,
                        subtitle2 = formatDate(f.modified),
                        trailing = formatSize(f.size),
                        onClick = { vm.toggleLarge(f.path) },
                        onLongClick = { actionFor = f },
                        leading = { FileThumb(f.path) }
                    )
                    HorizontalDivider()
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
        confirm, "Удалить файлы?",
        "Будет удалено ${filesCount(sel.size)} (${formatSize(sel.sumOf { it.size })}). Действие нельзя отменить.",
        onConfirm = vm::deleteLarge, onDismiss = { confirm = false }
    )
    BusyOverlay(ui.busy)
    MessageDialog(ui.message, vm::dismissMessage)
}

@Composable
fun FileActionsDialog(
    name: String, path: String, onDismiss: () -> Unit,
    onOpen: (() -> Unit)?, onShare: (() -> Unit)?, onDelete: () -> Unit,
) {
    var confirmDel by remember { mutableStateOf(false) }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(name) },
        text = {
            Column {
                Text(path)
                if (onOpen != null) androidx.compose.material3.TextButton(onClick = { onDismiss(); onOpen() }) { Text("Открыть") }
                if (onShare != null) androidx.compose.material3.TextButton(onClick = { onDismiss(); onShare() }) { Text("Поделиться") }
                androidx.compose.material3.TextButton(onClick = { confirmDel = true }) {
                    Text("Удалить", color = androidx.compose.material3.MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Закрыть") } }
    )
    ConfirmDialog(confirmDel, "Удалить?", path, onConfirm = { onDismiss(); onDelete() }, onDismiss = { confirmDel = false })
}
