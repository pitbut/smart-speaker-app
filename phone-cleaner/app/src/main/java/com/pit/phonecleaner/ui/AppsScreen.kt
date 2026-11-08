package com.pit.phonecleaner.ui

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.storage.StorageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import com.pit.phonecleaner.apps.AppFilter
import com.pit.phonecleaner.apps.AppInfo
import com.pit.phonecleaner.apps.AppSort
import com.pit.phonecleaner.apps.AppsViewModel
import com.pit.phonecleaner.apps.CacheKind
import com.pit.phonecleaner.apps.cacheOf
import com.pit.phonecleaner.core.Perms
import com.pit.phonecleaner.core.Prefs
import com.pit.phonecleaner.core.formatSize

@Composable
fun AppsScreen(vm: AppsViewModel, initialKind: CacheKind, onBack: () -> Unit, go: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val ui by vm.ui.collectAsState()
    var searchOpen by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var needAccess by remember { mutableStateOf(false) }
    var details by remember { mutableStateOf<AppInfo?>(null) }

    LaunchedEffect(initialKind) {
        vm.setKind(initialKind)
        if (!ui.scanned && !ui.loading) vm.scan()
    }

    val systemClear = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vm.afterSystemClear()
    }
    val uninstall = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        vm.onPackageRemoved()
    }

    val list = vm.visible(ui)
    val selectedApps = vm.selectedApps(ui)
    val selSize = selectedApps.sumOf { it.cacheOf(ui.kind) }
    val visTotal = list.sumOf { it.cacheOf(ui.kind) }
    val allSel = list.isNotEmpty() && list.all { it.pkg in ui.selected }

    ScreenScaffold(
        title = if (initialKind == CacheKind.HIDDEN) "Скрытый кэш" else "Кэш приложений",
        subtitle = if (ui.scanned) "Всего кэша: ${formatSize(ui.apps.sumOf { it.cacheBytes })}" else null,
        onBack = onBack,
        actions = {
            IconButton(onClick = { searchOpen = !searchOpen; if (!searchOpen) vm.setQuery("") }) {
                Icon(if (searchOpen) Icons.Filled.Close else Icons.Filled.Search, "Поиск")
            }
            SortMenu(AppSort.entries, ui.sort, { it.title }, vm::setSort)
            IconButton(onClick = { menuOpen = true }) { Icon(Icons.Filled.MoreVert, "Ещё") }
            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                DropdownMenuItem(text = { Text("Обновить список") }, onClick = { menuOpen = false; vm.scan() })
                if (Build.VERSION.SDK_INT >= 30) DropdownMenuItem(
                    text = { Text("Системная очистка ВСЕГО кэша") },
                    onClick = {
                        menuOpen = false
                        if (!Perms.hasAllFiles(ctx)) go(Screen.Permissions)
                        else try {
                            systemClear.launch(Intent(StorageManager.ACTION_CLEAR_APP_CACHE))
                        } catch (_: Exception) {
                        }
                    })
                if (Prefs.get(ctx).useRoot) DropdownMenuItem(
                    text = { Text("Root: очистить кэш всех приложений") },
                    onClick = { menuOpen = false; vm.trimAllRoot() })
                DropdownMenuItem(text = { Text("Разрешения") }, onClick = { menuOpen = false; go(Screen.Permissions) })
            }
        },
        bottomBar = {
            if (ui.scanned) SelectionBar(
                count = ui.selected.size,
                size = selSize,
                actionText = "Скрытый кэш (авто)",
                secondaryText = "Видимый кэш",
                danger = false,
                onSecondary = { vm.cleanVisible() },
                onAction = {
                    if (vm.cleanHidden() == AppsViewModel.HiddenResult.NEED_ACCESSIBILITY) needAccess = true
                }
            )
        }
    ) { pad ->
        Column(
            Modifier
                .padding(pad)
                .fillMaxSize()
        ) {
            if (ui.loading && !ui.scanned) {
                ProgressBox("Сканирую приложения…", "${(ui.progress * 100).toInt()}%", ui.progress)
                return@Column
            }
            if (!ui.hasUsageAccess) {
                Card(
                    onClick = { go(Screen.Permissions) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Text(
                        "Размер кэша неизвестен: выдайте «Доступ к истории использования». Нажмите здесь.",
                        Modifier.padding(12.dp)
                    )
                }
            }
            if (searchOpen) OutlinedTextField(
                value = ui.query, onValueChange = vm::setQuery,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                placeholder = { Text("Название или пакет") }, singleLine = true
            )
            Spacer(Modifier.height(6.dp))
            ChipsRow(AppFilter.entries, ui.filter, { f ->
                val n = when (f) {
                    AppFilter.USER -> ui.apps.count { !it.isSystem }
                    AppFilter.SYSTEM -> ui.apps.count { it.isSystem }
                    AppFilter.ALL -> ui.apps.size
                }
                "${f.title} ($n)"
            }, vm::setFilter)
            ChipsRow(CacheKind.entries, ui.kind, { it.title }, vm::setKind)
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Скрыть приложения без кэша", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                Switch(checked = ui.hideEmpty, onCheckedChange = vm::setHideEmpty)
            }
            if (ui.loading) androidx.compose.material3.LinearProgressIndicator(Modifier.fillMaxWidth())
            SelectAllRow(allSel, list.size, visTotal, vm::toggleAll)

            if (list.isEmpty()) {
                EmptyBox("Кэша нет", "В этой группе нет приложений с кэшем")
                return@Column
            }
            LazyColumn(Modifier.fillMaxSize()) {
                items(list, key = { it.pkg }) { a ->
                    val size = a.cacheOf(ui.kind)
                    SelectableRow(
                        checked = a.pkg in ui.selected,
                        onToggle = { vm.toggle(a.pkg) },
                        title = a.label,
                        subtitle = a.pkg,
                        subtitle2 = if (a.statsKnown)
                            "Скрытый ${formatSize(a.hiddenCacheBytes)} · видимый ${formatSize(a.externalCacheBytes)} · всего ${formatSize(a.totalBytes)}"
                        else null,
                        trailing = if (a.statsKnown || size > 0) formatSize(size) else "—",
                        onLongClick = { details = a },
                        leading = { AppIcon(a.pkg) }
                    )
                    HorizontalDivider()
                }
            }
        }
    }

    if (needAccess) AlertDialog(
        onDismissRequest = { needAccess = false },
        title = { Text("Включите автоочистку") },
        text = {
            Text(
                "Скрытый кэш хранится во внутренней памяти приложений, и Android не даёт его удалять напрямую. " +
                    "ЧистоФон может сам открыть настройки каждого выбранного приложения и нажать «Очистить кэш».\n\n" +
                    "Откройте «Спец. возможности» → «Установленные приложения/службы» → «ЧистоФон — автоочистка кэша» и включите. " +
                    "Затем вернитесь и нажмите кнопку ещё раз."
            )
        },
        confirmButton = {
            TextButton(onClick = { needAccess = false; Perms.launch(ctx, Perms.accessibilityIntent()) }) { Text("Открыть") }
        },
        dismissButton = { TextButton(onClick = { needAccess = false }) { Text("Отмена") } }
    )

    details?.let { a ->
        AlertDialog(
            onDismissRequest = { details = null },
            title = { Text(a.label) },
            text = {
                Column {
                    Text(a.pkg, style = MaterialTheme.typography.bodySmall)
                    Spacer(Modifier.height(8.dp))
                    Text("Версия: ${a.versionName ?: "—"}")
                    Text("Тип: " + if (a.isSystem) (if (a.isUpdatedSystem) "системное (обновлено)" else "системное") else "пользовательское")
                    Text("Приложение: ${formatSize(a.appBytes)}")
                    Text("Данные: ${formatSize(a.dataBytes)}")
                    Text("Кэш всего: ${formatSize(a.cacheBytes)}")
                    Text("  скрытый: ${formatSize(a.hiddenCacheBytes)}")
                    Text("  видимый: ${formatSize(a.externalCacheBytes)}")
                    Spacer(Modifier.height(12.dp))
                    TextButton(onClick = { details = null; Perms.launch(ctx, Perms.appDetailsIntent(a.pkg)) }) {
                        Text("О приложении (системный экран)")
                    }
                    ctx.packageManager.getLaunchIntentForPackage(a.pkg)?.let { li ->
                        TextButton(onClick = { details = null; Perms.launch(ctx, li) }) { Text("Открыть приложение") }
                    }
                    if (!a.isSystem || a.isUpdatedSystem) TextButton(onClick = {
                        details = null
                        @Suppress("DEPRECATION")
                        uninstall.launch(
                            Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.fromParts("package", a.pkg, null))
                                .putExtra(Intent.EXTRA_RETURN_RESULT, true)
                        )
                    }) { Text(if (a.isSystem) "Удалить обновления" else "Удалить приложение", color = MaterialTheme.colorScheme.error) }
                }
            },
            confirmButton = { TextButton(onClick = { details = null }) { Text("Закрыть") } }
        )
    }

    BusyOverlay(ui.busy)
    MessageDialog(ui.message, vm::dismissMessage)
}
