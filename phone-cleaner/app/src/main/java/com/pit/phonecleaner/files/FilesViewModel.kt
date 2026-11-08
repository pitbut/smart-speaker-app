package com.pit.phonecleaner.files

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pit.phonecleaner.apps.AppInfo
import com.pit.phonecleaner.apps.AppScanner
import com.pit.phonecleaner.core.FileOps
import com.pit.phonecleaner.core.FileType
import com.pit.phonecleaner.core.Prefs
import com.pit.phonecleaner.core.filesCount
import com.pit.phonecleaner.core.formatSize
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class FileSort(val title: String) {
    SIZE_DESC("По размеру ↓"), SIZE_ASC("По размеру ↑"), NAME("По имени"), DATE_NEW("Сначала новые"), DATE_OLD("Сначала старые")
}

data class FilesUi(
    val indexing: Boolean = false,
    val indexedFiles: Int = 0,
    val indexDir: String = "",
    val index: StorageIndex? = null,

    val junk: List<JunkCategory> = emptyList(),
    val junkSelected: Set<String> = emptySet(),

    val largeThreshold: Long = 50L * 1024 * 1024,
    val largeType: FileType? = null,
    val largeSort: FileSort = FileSort.SIZE_DESC,
    val largeSelected: Set<String> = emptySet(),

    val foreign: List<ForeignEntry> = emptyList(),
    val foreignSelected: Set<String> = emptySet(),
    val foreignOnlyOrphans: Boolean = false,

    val dupKind: DupKind = DupKind.EXACT,
    val dupRunning: Boolean = false,
    val dupProgress: String = "",
    val dupGroups: List<DupGroup>? = null,
    val dupSelected: Set<String> = emptySet(),
    val keepRule: KeepRule = KeepRule.OLDEST,

    val busy: String? = null,
    val message: String? = null,
)

class FilesViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    private val prefs get() = Prefs.get(ctx)
    private val _ui = MutableStateFlow(FilesUi(largeThreshold = Prefs.get(app).largeThresholdMb * 1024L * 1024))
    val ui: StateFlow<FilesUi> = _ui

    private var indexJob: Job? = null
    private var dupJob: Job? = null
    private var apps: List<AppInfo> = emptyList()

    fun dismissMessage() = _ui.update { it.copy(message = null) }

    /** Полное сканирование памяти. apps — уже просканированные приложения (если есть) */
    fun buildIndex(knownApps: List<AppInfo>? = null) {
        if (_ui.value.indexing) return
        indexJob = viewModelScope.launch {
            _ui.update { it.copy(indexing = true, indexedFiles = 0, dupGroups = null) }
            apps = knownApps?.takeIf { it.isNotEmpty() } ?: AppScanner.scan(ctx)
            val idx = FileScanner.build(ctx, prefs.scanHidden) { n, d ->
                _ui.update { it.copy(indexedFiles = n, indexDir = d) }
            }
            applyIndex(idx)
            _ui.update { it.copy(indexing = false) }
        }
    }

    private suspend fun applyIndex(idx: StorageIndex) {
        val installed = apps.mapTo(HashSet()) { it.pkg }
        val (junk, foreign) = withContext(Dispatchers.Default) {
            JunkAnalyzer.analyze(ctx, idx, installed) to ForeignAnalyzer.analyze(idx, apps)
        }
        val filePaths = idx.files.mapTo(HashSet()) { it.path }
        val junkPaths = junk.flatMapTo(HashSet()) { c -> c.items.map { it.path } }
        val foreignPaths = foreign.mapTo(HashSet()) { it.path }
        _ui.update { u ->
            val junkSel = if (u.index == null) junk.flatMap { c -> c.items.filter { it.preselect }.map { it.path } }.toSet()
            else u.junkSelected.filterTo(HashSet()) { it in junkPaths }
            u.copy(
                index = idx, junk = junk, junkSelected = junkSel, foreign = foreign,
                largeThreshold = prefs.largeThresholdMb * 1024L * 1024,
                largeSelected = u.largeSelected.filterTo(HashSet()) { it in filePaths },
                foreignSelected = u.foreignSelected.filterTo(HashSet()) { it in foreignPaths },
            )
        }
    }

    // ---------- Мусор ----------

    fun toggleJunk(path: String) = _ui.update {
        it.copy(junkSelected = if (path in it.junkSelected) it.junkSelected - path else it.junkSelected + path)
    }

    fun toggleJunkCategory(cat: JunkCategory) = _ui.update { u ->
        val paths = cat.items.map { it.path }.toSet()
        if (u.junkSelected.containsAll(paths)) u.copy(junkSelected = u.junkSelected - paths)
        else u.copy(junkSelected = u.junkSelected + paths)
    }

    fun junkSelectedSize(u: FilesUi = _ui.value): Long =
        u.junk.sumOf { c -> c.items.filter { it.path in u.junkSelected }.sumOf { it.size } }

    fun deleteJunk() {
        val u = _ui.value
        val items = u.junk.flatMap { it.items }.filter { it.path in u.junkSelected }.associate { it.path to it.size }
        delete(items, "Мусор удалён")
    }

    // ---------- Большие файлы ----------

    fun largeFiles(u: FilesUi = _ui.value): List<FileItem> {
        val idx = u.index ?: return emptyList()
        val list = idx.files.filter { it.size >= u.largeThreshold && (u.largeType == null || FileType.ofExt(it.ext) == u.largeType) }
        return sortFiles(list, u.largeSort)
    }

    fun sortFiles(list: List<FileItem>, s: FileSort) = when (s) {
        FileSort.SIZE_DESC -> list.sortedByDescending { it.size }
        FileSort.SIZE_ASC -> list.sortedBy { it.size }
        FileSort.NAME -> list.sortedBy { it.name.lowercase() }
        FileSort.DATE_NEW -> list.sortedByDescending { it.modified }
        FileSort.DATE_OLD -> list.sortedBy { it.modified }
    }

    fun setLargeThresholdMb(mb: Int) {
        prefs.largeThresholdMb = mb
        _ui.update { it.copy(largeThreshold = mb * 1024L * 1024) }
    }

    fun setLargeType(t: FileType?) = _ui.update { it.copy(largeType = t) }
    fun setLargeSort(s: FileSort) = _ui.update { it.copy(largeSort = s) }
    fun toggleLarge(path: String) = _ui.update {
        it.copy(largeSelected = if (path in it.largeSelected) it.largeSelected - path else it.largeSelected + path)
    }

    fun toggleAllLarge() = _ui.update { u ->
        val all = largeFiles(u).map { it.path }.toSet()
        if (all.isNotEmpty() && u.largeSelected.containsAll(all)) u.copy(largeSelected = u.largeSelected - all)
        else u.copy(largeSelected = u.largeSelected + all)
    }

    fun deleteLarge() {
        val u = _ui.value
        val items = largeFiles(u).filter { it.path in u.largeSelected }.associate { it.path to it.size }
        delete(items, "Большие файлы удалены")
    }

    // ---------- Посторонние папки ----------

    fun foreignVisible(u: FilesUi = _ui.value) = if (u.foreignOnlyOrphans) u.foreign.filter { it.orphan } else u.foreign
    fun setOnlyOrphans(v: Boolean) = _ui.update { it.copy(foreignOnlyOrphans = v) }
    fun toggleForeign(path: String) = _ui.update {
        it.copy(foreignSelected = if (path in it.foreignSelected) it.foreignSelected - path else it.foreignSelected + path)
    }

    fun toggleAllForeign() = _ui.update { u ->
        val all = foreignVisible(u).map { it.path }.toSet()
        if (all.isNotEmpty() && u.foreignSelected.containsAll(all)) u.copy(foreignSelected = u.foreignSelected - all)
        else u.copy(foreignSelected = u.foreignSelected + all)
    }

    fun deleteForeign() {
        val u = _ui.value
        val items = u.foreign.filter { it.path in u.foreignSelected }.associate { it.path to it.size }
        delete(items, "Удалено")
    }

    // ---------- Дубликаты ----------

    fun setDupKind(k: DupKind) {
        if (k == _ui.value.dupKind) return
        dupJob?.cancel()
        _ui.update { it.copy(dupKind = k, dupGroups = null, dupSelected = emptySet(), dupRunning = false) }
    }

    fun findDuplicates() {
        val idx = _ui.value.index ?: return
        dupJob?.cancel()
        dupJob = viewModelScope.launch {
            val kind = _ui.value.dupKind
            _ui.update { it.copy(dupRunning = true, dupProgress = "Подготовка…", dupGroups = null, dupSelected = emptySet()) }
            val groups = DuplicateFinder.find(idx, kind, prefs.dupMinKb * 1024L, prefs.similarThreshold) { p ->
                _ui.update { it.copy(dupProgress = p) }
            }
            // Точные копии выделяем сразу (кроме одного в группе); остальное — по желанию пользователя
            val sel = if (kind == DupKind.EXACT || kind == DupKind.MEDIA_COPY) autoSelect(groups, _ui.value.keepRule) else emptySet()
            _ui.update { it.copy(dupRunning = false, dupGroups = groups, dupSelected = sel) }
        }
    }

    fun cancelDuplicates() {
        dupJob?.cancel()
        _ui.update { it.copy(dupRunning = false) }
    }

    private fun autoSelect(groups: List<DupGroup>, rule: KeepRule): Set<String> =
        groups.flatMap { g -> val keep = rule.keep(g.files); g.files.filter { it !== keep }.map { it.path } }.toSet()

    fun applyKeepRule(rule: KeepRule) = _ui.update { u ->
        u.copy(keepRule = rule, dupSelected = autoSelect(u.dupGroups ?: emptyList(), rule))
    }

    fun clearDupSelection() = _ui.update { it.copy(dupSelected = emptySet()) }

    fun toggleDup(path: String) = _ui.update {
        it.copy(dupSelected = if (path in it.dupSelected) it.dupSelected - path else it.dupSelected + path)
    }

    fun deleteDuplicates() {
        val u = _ui.value
        val groups = u.dupGroups ?: return
        // Защита: в каждой группе должен остаться хотя бы один файл
        val safe = groups.flatMap { g ->
            val sel = g.files.filter { it.path in u.dupSelected }
            if (sel.size == g.files.size) sel.drop(1) else sel
        }
        val items = safe.associate { it.path to it.size }
        delete(items, "Дубликаты удалены")
    }

    // ---------- Общее удаление ----------

    fun deletePaths(items: Map<String, Long>, title: String = "Удалено") = delete(items, title)

    private fun delete(items: Map<String, Long>, title: String) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            _ui.update { it.copy(busy = "Удаляю ${filesCount(items.size)}…") }
            val r = withContext(Dispatchers.IO) { FileOps.delete(ctx, items, prefs.useRoot) }
            val idx = _ui.value.index?.without(r.deletedPaths)
            if (idx != null) applyIndex(idx)
            _ui.update { u ->
                u.copy(
                    busy = null,
                    junkSelected = u.junkSelected - r.deletedPaths,
                    largeSelected = u.largeSelected - r.deletedPaths,
                    foreignSelected = u.foreignSelected - r.deletedPaths,
                    dupSelected = u.dupSelected - r.deletedPaths,
                    dupGroups = u.dupGroups?.mapNotNull { g ->
                        val rest = g.files.filter { it.path !in r.deletedPaths }
                        if (rest.size > 1) g.copy(files = rest) else null
                    },
                    message = "$title: ${r.deleted}" + (if (r.failed > 0) ", не удалось: ${r.failed}" else "") +
                        "\nОсвобождено: ${formatSize(r.freed)}",
                )
            }
        }
    }
}
