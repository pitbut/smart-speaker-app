package com.pit.phonecleaner.apps

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.pit.phonecleaner.auto.AutoClean
import com.pit.phonecleaner.core.Perms
import com.pit.phonecleaner.core.Prefs
import com.pit.phonecleaner.core.RootShell
import com.pit.phonecleaner.core.appsCount
import com.pit.phonecleaner.core.formatSize
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class AppFilter(val title: String) { USER("Пользовательские"), SYSTEM("Системные"), ALL("Все") }

enum class AppSort(val title: String) {
    CACHE_DESC("По объёму кэша ↓"),
    CACHE_ASC("По объёму кэша ↑"),
    NAME_ASC("По имени А → Я"),
    NAME_DESC("По имени Я → А"),
    TOTAL_DESC("По общему размеру ↓"),
    INSTALL_DESC("Недавно установленные"),
}

enum class CacheKind(val title: String) { ALL("Весь кэш"), HIDDEN("Скрытый"), VISIBLE("Видимый") }

data class AppsUi(
    val loading: Boolean = false,
    val progress: Float = 0f,
    val scanned: Boolean = false,
    val apps: List<AppInfo> = emptyList(),
    val filter: AppFilter = AppFilter.USER,
    val sort: AppSort = AppSort.CACHE_DESC,
    val kind: CacheKind = CacheKind.ALL,
    val hideEmpty: Boolean = true,
    val query: String = "",
    val selected: Set<String> = emptySet(),
    val busy: String? = null,
    val message: String? = null,
    val hasUsageAccess: Boolean = true,
)

fun AppInfo.cacheOf(kind: CacheKind) = when (kind) {
    CacheKind.ALL -> cacheBytes
    CacheKind.HIDDEN -> hiddenCacheBytes
    CacheKind.VISIBLE -> externalCacheBytes
}

class AppsViewModel(app: Application) : AndroidViewModel(app) {
    private val ctx get() = getApplication<Application>()
    private val _ui = MutableStateFlow(AppsUi())
    val ui: StateFlow<AppsUi> = _ui

    private var scanJob: Job? = null
    /** Кэш выбранных приложений до запуска автоочистки — чтобы посчитать освобождённое */
    private var autoBefore: Map<String, Long> = emptyMap()

    init {
        viewModelScope.launch {
            AutoClean.state.collect { st ->
                if (st.finished) onAutoFinished(st)
            }
        }
    }

    fun visible(u: AppsUi = _ui.value): List<AppInfo> {
        val q = u.query.trim().lowercase()
        var list = u.apps.asSequence()
            .filter {
                when (u.filter) {
                    AppFilter.USER -> !it.isSystem
                    AppFilter.SYSTEM -> it.isSystem
                    AppFilter.ALL -> true
                }
            }
            .filter { !u.hideEmpty || it.cacheOf(u.kind) > 0 || !it.statsKnown }
            .filter { q.isEmpty() || it.label.lowercase().contains(q) || it.pkg.contains(q) }
            .toList()
        list = when (u.sort) {
            AppSort.CACHE_DESC -> list.sortedByDescending { it.cacheOf(u.kind) }
            AppSort.CACHE_ASC -> list.sortedBy { it.cacheOf(u.kind) }
            AppSort.NAME_ASC -> list.sortedBy { it.label.lowercase() }
            AppSort.NAME_DESC -> list.sortedByDescending { it.label.lowercase() }
            AppSort.TOTAL_DESC -> list.sortedByDescending { it.totalBytes }
            AppSort.INSTALL_DESC -> list.sortedByDescending { it.installTime }
        }
        return list
    }

    fun scan() {
        scanJob?.cancel()
        scanJob = viewModelScope.launch {
            _ui.update { it.copy(loading = true, progress = 0f, hasUsageAccess = Perms.hasUsageAccess(ctx)) }
            val apps = AppScanner.scan(ctx) { d, t -> _ui.update { it.copy(progress = d.toFloat() / t) } }
            _ui.update { u ->
                val pkgs = apps.mapTo(HashSet()) { it.pkg }
                u.copy(loading = false, scanned = true, apps = apps, selected = u.selected.filterTo(HashSet()) { it in pkgs })
            }
        }
    }

    fun setFilter(f: AppFilter) = _ui.update { it.copy(filter = f) }
    fun setSort(s: AppSort) = _ui.update { it.copy(sort = s) }
    fun setKind(k: CacheKind) = _ui.update { it.copy(kind = k) }
    fun setHideEmpty(v: Boolean) = _ui.update { it.copy(hideEmpty = v) }
    fun setQuery(q: String) = _ui.update { it.copy(query = q) }
    fun dismissMessage() = _ui.update { it.copy(message = null) }

    fun toggle(pkg: String) = _ui.update {
        it.copy(selected = if (pkg in it.selected) it.selected - pkg else it.selected + pkg)
    }

    /** Выбрать все видимые (или снять выбор, если все уже выбраны) */
    fun toggleAll() = _ui.update { u ->
        val vis = visible(u).map { it.pkg }.toSet()
        if (vis.isNotEmpty() && u.selected.containsAll(vis)) u.copy(selected = u.selected - vis)
        else u.copy(selected = u.selected + vis)
    }

    fun selectedApps(u: AppsUi = _ui.value) = u.apps.filter { it.pkg in u.selected }

    private fun cacheSum(pkgs: Collection<String>) = _ui.value.apps.filter { it.pkg in pkgs }.sumOf { it.cacheBytes }

    private suspend fun refreshAndReport(pkgs: List<String>, before: Long, title: String, extra: String? = null) {
        val fresh = AppScanner.scan(ctx, pkgs).associateBy { it.pkg }
        _ui.update { u -> u.copy(apps = u.apps.map { fresh[it.pkg] ?: it }) }
        val after = cacheSum(pkgs)
        val freed = (before - after).coerceAtLeast(0)
        val msg = buildString {
            append(title).append("\n")
            if (Perms.hasUsageAccess(ctx)) append("Освобождено: ${formatSize(freed)}") else append("Готово")
            extra?.let { append("\n\n").append(it) }
        }
        _ui.update { it.copy(busy = null, message = msg, selected = emptySet()) }
    }

    /** Видимый кэш (Android/data/<pkg>/cache) */
    fun cleanVisible(pkgs: List<String> = _ui.value.selected.toList()) {
        if (pkgs.isEmpty()) return
        viewModelScope.launch {
            _ui.update { it.copy(busy = "Очищаю видимый кэш…") }
            val before = cacheSum(pkgs)
            val r = CacheCleaner.cleanVisible(ctx, pkgs, Prefs.get(ctx).useRoot)
            if (r.ok == 0 && r.note != null) {
                _ui.update { it.copy(busy = null, message = r.note) }
                return@launch
            }
            refreshAndReport(pkgs, before, "Видимый кэш очищен: ${appsCount(r.ok)} (${r.method})",
                if (r.failed > 0) "Не удалось: ${r.failed}" else null)
        }
    }

    /** Скрытый (весь) кэш: root — сразу, иначе автоматически через спец. возможности */
    fun cleanHidden(): HiddenResult {
        val sel = selectedApps()
        if (sel.isEmpty()) return HiddenResult.NOTHING
        val prefs = Prefs.get(ctx)
        if (prefs.useRoot) {
            viewModelScope.launch {
                _ui.update { it.copy(busy = "Очищаю кэш через root…") }
                if (!RootShell.isAvailable()) {
                    _ui.update { it.copy(busy = null) }
                    if (!AutoClean.serviceConnected || !startAuto(sel)) _ui.update {
                        it.copy(message = "Root недоступен. Отключите режим root в настройках или включите автоочистку в «Спец. возможностях».")
                    }
                    return@launch
                }
                val pkgs = sel.map { it.pkg }
                val before = cacheSum(pkgs)
                val r = CacheCleaner.cleanAllRoot(ctx, pkgs)
                refreshAndReport(pkgs, before, "Скрытый и видимый кэш очищен: ${appsCount(r.ok)} (root)", r.note)
            }
            return HiddenResult.STARTED
        }
        if (!Perms.isAccessibilityEnabled(ctx) || !AutoClean.serviceConnected) return HiddenResult.NEED_ACCESSIBILITY
        return if (startAuto(sel)) HiddenResult.STARTED else HiddenResult.NEED_ACCESSIBILITY
    }

    private fun startAuto(sel: List<AppInfo>): Boolean {
        val items = sel.filter { it.pkg != ctx.packageName }.map { it.pkg to it.label }
        autoBefore = sel.associate { it.pkg to it.cacheBytes }
        CacheCleaner.cleanOwn(ctx)
        return AutoClean.start(items)
    }

    private fun onAutoFinished(st: AutoClean.State) {
        AutoClean.consume()
        val pkgs = st.processed
        if (pkgs.isEmpty()) {
            if (st.stoppedByUser) _ui.update { it.copy(message = "Очистка остановлена") }
            return
        }
        viewModelScope.launch {
            _ui.update { it.copy(busy = "Подсчитываю результат…") }
            val before = pkgs.sumOf { autoBefore[it] ?: 0L }
            val title = (if (st.stoppedByUser) "Очистка остановлена.\n" else "") +
                "Обработано: ${appsCount(st.done)}, успешно: ${st.success}" +
                (if (st.failed > 0) ", пропущено: ${st.failed}" else "")
            refreshAndReport(pkgs, before, title,
                if (st.failed > 0) "Пропущенные приложения можно очистить вручную (долгое нажатие → «О приложении»)." else null)
        }
    }

    /** Root: системная очистка кэша всех приложений */
    fun trimAllRoot() {
        viewModelScope.launch {
            _ui.update { it.copy(busy = "Системная очистка через root…") }
            val before = _ui.value.apps.sumOf { it.cacheBytes }
            val ok = CacheCleaner.trimAllRoot()
            if (!ok) {
                _ui.update { it.copy(busy = null, message = "Root недоступен или команда не выполнена") }
                return@launch
            }
            refreshAndReport(_ui.value.apps.map { it.pkg }, before, "Кэш всех приложений очищен (root)")
        }
    }

    /** Вызывается после системного диалога «Очистить кэш всех приложений» */
    fun afterSystemClear() {
        viewModelScope.launch {
            val before = _ui.value.apps.sumOf { it.cacheBytes }
            _ui.update { it.copy(busy = "Подсчитываю результат…") }
            refreshAndReport(_ui.value.apps.map { it.pkg }, before, "Системная очистка кэша завершена")
        }
    }

    fun onPackageRemoved() = scan()

    enum class HiddenResult { STARTED, NEED_ACCESSIBILITY, NOTHING }
}
