package com.pit.phonecleaner.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.pit.phonecleaner.Screen
import com.pit.phonecleaner.core.FileOps
import com.pit.phonecleaner.core.filesCount
import com.pit.phonecleaner.core.formatDate
import com.pit.phonecleaner.core.formatSize
import com.pit.phonecleaner.files.FileSort
import com.pit.phonecleaner.files.FilesViewModel
import java.io.File

private data class Entry(val path: String, val name: String, val isDir: Boolean, val size: Long, val count: Int, val modified: Long)

/** Простой проводник: содержимое папки с размерами, переход внутрь, удаление */
@Composable
fun BrowseScreen(vm: FilesViewModel, path: String, onBack: () -> Unit, go: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val ui by vm.ui.collectAsState()
    var sort by remember { mutableStateOf(FileSort.SIZE_DESC) }
    var selected by remember(path) { mutableStateOf(setOf<String>()) }
    var confirm by remember { mutableStateOf(false) }
    var actionFor by remember { mutableStateOf<Entry?>(null) }

    val entries = remember(path, ui.index, sort) {
        val idx = ui.index
        val list = (File(path).listFiles() ?: emptyArray()).map { f ->
            if (f.isDirectory) Entry(
                f.path, f.name, true,
                idx?.dirSizes?.get(f.path) ?: 0L, idx?.dirCounts?.get(f.path) ?: 0, f.lastModified()
            ) else Entry(f.path, f.name, false, f.length(), 1, f.lastModified())
        }
        when (sort) {
            FileSort.SIZE_DESC -> list.sortedByDescending { it.size }
            FileSort.SIZE_ASC -> list.sortedBy { it.size }
            FileSort.NAME -> list.sortedWith(compareBy({ !it.isDir }, { it.name.lowercase() }))
            FileSort.DATE_NEW -> list.sortedByDescending { it.modified }
            FileSort.DATE_OLD -> list.sortedBy { it.modified }
        }
    }
    val sel = entries.filter { it.path in selected }

    ScreenScaffold(
        File(path).name, onBack, subtitle = path,
        actions = { SortMenu(FileSort.entries, sort, { it.title }) { sort = it } },
        bottomBar = {
            if (entries.isNotEmpty()) SelectionBar(sel.size, sel.sumOf { it.size }, "Удалить") { confirm = true }
        }
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
        ) {
            SelectAllRow(entries.isNotEmpty() && sel.size == entries.size, entries.size, entries.sumOf { it.size }, {
                selected = if (sel.size == entries.size) emptySet() else entries.map { it.path }.toSet()
            })
            if (entries.isEmpty()) {
                EmptyBox("Папка пуста"); return@Column
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(entries, key = { it.path }) { e ->
                    SelectableRow(
                        checked = e.path in selected,
                        onToggle = { selected = if (e.path in selected) selected - e.path else selected + e.path },
                        title = e.name,
                        subtitle = (if (e.isDir) filesCount(e.count) + " · " else "") + formatDate(e.modified),
                        trailing = formatSize(e.size),
                        onClick = { if (e.isDir) go(Screen.Browse(e.path)) else actionFor = e },
                        onLongClick = { actionFor = e },
                        leading = { FileThumb(e.path, e.isDir) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    actionFor?.let { e ->
        FileActionsDialog(
            e.name, e.path, onDismiss = { actionFor = null },
            onOpen = if (e.isDir) null else ({ FileOps.open(ctx, e.path) }),
            onShare = if (e.isDir) null else ({ FileOps.share(ctx, e.path) }),
            onDelete = { vm.deletePaths(mapOf(e.path to e.size)); selected = selected - e.path }
        )
    }
    ConfirmDialog(
        confirm, "Удалить выбранное?",
        "Будет удалено ${sel.size} элементов (${formatSize(sel.sumOf { it.size })}) вместе с содержимым.",
        onConfirm = {
            vm.deletePaths(sel.associate { it.path to it.size })
            selected = emptySet()
        },
        onDismiss = { confirm = false }
    )
    BusyOverlay(ui.busy)
    MessageDialog(ui.message, vm::dismissMessage)
}
