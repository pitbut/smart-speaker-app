package com.pit.phonecleaner.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.LazyGridScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoDelete
import androidx.compose.material.icons.filled.CleaningServices
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.DeleteSweep
import androidx.compose.material.icons.filled.FolderOff
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pit.phonecleaner.Screen
import com.pit.phonecleaner.apps.AppsViewModel
import com.pit.phonecleaner.apps.CacheKind
import com.pit.phonecleaner.core.Volumes
import com.pit.phonecleaner.core.appsCount
import com.pit.phonecleaner.core.filesCount
import com.pit.phonecleaner.core.formatSize
import com.pit.phonecleaner.files.FilesViewModel

@Composable
fun HomeScreen(appsVm: AppsViewModel, filesVm: FilesViewModel, go: (Screen) -> Unit) {
    val ctx = LocalContext.current
    val apps by appsVm.ui.collectAsState()
    val files by filesVm.ui.collectAsState()
    val tick = rememberResumeTick()
    val perms = remember(tick) { permState(ctx) }
    val volumes = remember(tick, files.index, apps.message, files.message) { Volumes.info(ctx) }
    var confirmQuick by remember { mutableStateOf(false) }

    LaunchedEffect(perms.usage) {
        // Первое открытие или только что выдан доступ к статистике — пересканировать
        if ((!apps.scanned || (perms.usage && !apps.hasUsageAccess)) && !apps.loading) appsVm.scan()
    }

    val cacheTotal = apps.apps.sumOf { it.cacheBytes }
    val hiddenTotal = apps.apps.sumOf { it.hiddenCacheBytes }
    val junkTotal = files.junk.sumOf { it.total }
    val large = filesVm.largeFiles(files)
    val foreignTotal = files.foreign.sumOf { it.size }
    val analyzing = files.indexing || apps.loading

    ScreenScaffold("ЧистоФон", onBack = null, actions = {
        IconButton(onClick = { go(Screen.Settings) }) { Icon(Icons.Filled.Settings, "Настройки") }
    }) { pad ->
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(
                start = 12.dp, end = 12.dp, top = pad.calculateTopPadding() + 4.dp,
                bottom = pad.calculateBottomPadding() + 24.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            full {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    volumes.forEach { v ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(16.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(Icons.Filled.Storage, null, tint = MaterialTheme.colorScheme.primary)
                                    Spacer(Modifier.width(8.dp))
                                    Text(v.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                    Text("${(v.usedFraction * 100).toInt()}%", fontWeight = FontWeight.Bold)
                                }
                                Spacer(Modifier.height(10.dp))
                                LinearProgressIndicator(progress = { v.usedFraction }, modifier = Modifier.fillMaxWidth().height(8.dp))
                                Spacer(Modifier.height(6.dp))
                                Text(
                                    "Занято ${formatSize(v.used)} из ${formatSize(v.total)} · свободно ${formatSize(v.free)}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }

            if (!perms.essentialOk) full {
                Card(
                    Modifier
                        .fillMaxWidth()
                        .clickable { go(Screen.Permissions) },
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
                ) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Filled.Warning, null)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Нужны разрешения", fontWeight = FontWeight.Bold)
                            Text(
                                buildList {
                                    if (!perms.usage) add("размер кэша")
                                    if (!perms.files) add("доступ к файлам")
                                }.joinToString(", ", prefix = "Без них недоступно: "),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
            }

            full {
                Column {
                    Button(
                        onClick = {
                            appsVm.scan()
                            filesVm.buildIndex(apps.apps)
                        },
                        enabled = !analyzing && perms.files,
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 52.dp)
                    ) {
                        Icon(Icons.Filled.CleaningServices, null)
                        Spacer(Modifier.width(8.dp))
                        Text(if (files.index == null) "Анализировать телефон" else "Повторить анализ")
                    }
                    if (analyzing) {
                        Spacer(Modifier.height(8.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth())
                        Text(
                            if (files.indexing) "Просканировано ${filesCount(files.indexedFiles)}…"
                            else "Сканирую приложения… ${(apps.progress * 100).toInt()}%",
                            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                    if (files.index != null && !analyzing) {
                        val rec = filesVm.junkSelectedSize(files)
                        if (rec > 0) {
                            Spacer(Modifier.height(8.dp))
                            Button(
                                onClick = { confirmQuick = true },
                                modifier = Modifier.fillMaxWidth()
                            ) { Text("Быстрая очистка рекомендуемого · ${formatSize(rec)}") }
                        }
                    }
                }
            }

            item {
                Tile(Icons.Filled.CleaningServices, "Кэш приложений",
                    if (apps.scanned) "${appsCount(apps.apps.count { it.cacheBytes > 0 })} · ${formatSize(cacheTotal)}" else "Сканирую…"
                ) { go(Screen.Apps(CacheKind.ALL)) }
            }
            item {
                Tile(Icons.Filled.VisibilityOff, "Скрытый кэш",
                    if (apps.scanned) formatSize(hiddenTotal) + " · автоочистка" else "Автоочистка"
                ) { go(Screen.Apps(CacheKind.HIDDEN)) }
            }
            item {
                Tile(Icons.Filled.DeleteSweep, "Мусорные файлы",
                    if (files.index != null) formatSize(junkTotal) else "Нужен анализ"
                ) { go(Screen.Junk) }
            }
            item {
                Tile(Icons.Filled.AutoDelete, "Большие файлы",
                    if (files.index != null) "${filesCount(large.size)} · ${formatSize(large.sumOf { it.size })}" else "Нужен анализ"
                ) { go(Screen.Large) }
            }
            item {
                Tile(Icons.Filled.FolderOff, "Посторонние папки",
                    if (files.index != null) "${files.foreign.size} шт. · ${formatSize(foreignTotal)}" else "Нужен анализ"
                ) { go(Screen.Foreign) }
            }
            item {
                Tile(Icons.Filled.ContentCopy, "Дубликаты",
                    files.dupGroups?.let { g -> "${g.size} групп · ${formatSize(g.sumOf { it.wasted })}" }
                        ?: if (files.index != null) "Копии, похожие фото…" else "Нужен анализ"
                ) { go(Screen.Duplicates) }
            }
            item {
                Tile(Icons.Filled.Security, "Разрешения",
                    if (perms.essentialOk && perms.access) "Все выданы" else "Проверить"
                ) { go(Screen.Permissions) }
            }
            item {
                Tile(Icons.Filled.Settings, "Настройки", "Root, пороги, поиск") { go(Screen.Settings) }
            }
        }
    }

    ConfirmDialog(
        show = confirmQuick,
        title = "Быстрая очистка",
        text = "Будут удалены отмеченные по умолчанию элементы из «Мусорных файлов» " +
            "(временные файлы, логи, миниатюры, пустые папки, установленные APK, остатки удалённых приложений) " +
            "и очищен видимый кэш всех приложений, если это возможно.",
        confirm = "Очистить",
        onConfirm = {
            filesVm.deleteJunk()
            val pkgs = apps.apps.filter { it.externalCacheBytes > 0 }.map { it.pkg }
            if (pkgs.isNotEmpty() && com.pit.phonecleaner.apps.CacheCleaner.canCleanVisibleDirectly(ctx)) appsVm.cleanVisible(pkgs)
        },
        onDismiss = { confirmQuick = false }
    )
    BusyOverlay(files.busy ?: apps.busy)
    MessageDialog(files.message, filesVm::dismissMessage)
    if (files.message == null) MessageDialog(apps.message, appsVm::dismissMessage)
}

private fun LazyGridScope.full(content: @Composable () -> Unit) =
    item(span = { GridItemSpan(maxLineSpan) }) { content() }

@Composable
private fun Tile(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 120.dp)
    ) {
        Column(Modifier.padding(14.dp)) {
            Icon(icon, null, Modifier.size(30.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(10.dp))
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

