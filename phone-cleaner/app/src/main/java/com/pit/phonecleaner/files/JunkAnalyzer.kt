package com.pit.phonecleaner.files

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import com.pit.phonecleaner.core.Prefs
import com.pit.phonecleaner.core.RootShell
import java.io.File

data class JunkItem(
    val path: String,
    val size: Long,
    val note: String? = null,
    val isDir: Boolean = false,
    val preselect: Boolean = true,
) {
    val name get() = path.substringAfterLast('/')
}

data class JunkCategory(
    val id: String,
    val title: String,
    val description: String,
    val items: List<JunkItem>,
) {
    val total get() = items.sumOf { it.size }
}

object JunkAnalyzer {

    private val TEMP_EXT = setOf("tmp", "temp", "chk", "dmp", "crdownload", "part", "partial", "download", "~tmp", "bak~")
    private val TEMP_NAMES = setOf("thumbs.db", ".ds_store", "desktop.ini", "._.ds_store")
    private val LOG_EXT = setOf("log", "trace", "anr", "xlog", "mlog")
    private val CACHE_DIR_NAMES = setOf("cache", ".cache", "caches", "temp", ".temp", "tmp", ".tmp", "_cache")

    /** Папки, где обычно лежат пользовательские файлы: их пустыми не трогаем */
    private val PROTECTED_TOP = setOf(
        "android", "dcim", "pictures", "movies", "music", "download", "documents", "alarms",
        "notifications", "ringtones", "podcasts", "audiobooks", "recordings"
    )

    fun analyze(ctx: Context, index: StorageIndex, installed: Set<String>): List<JunkCategory> {
        val prefs = Prefs.get(ctx)
        val pm = ctx.packageManager
        val cats = mutableListOf<JunkCategory>()

        // 1. Временные файлы
        val temp = index.files.filter {
            val n = it.name.lowercase()
            it.ext in TEMP_EXT || n in TEMP_NAMES || n.startsWith("~$") || n.endsWith(".tmp~")
        }.map { JunkItem(it.path, it.size) }
        cats += JunkCategory("temp", "Временные файлы", "Недокачанные загрузки, .tmp, служебные файлы", temp)

        // 2. Журналы
        val logs = index.files.filter { it.ext in LOG_EXT && "/Android/" !in it.path }
            .map { JunkItem(it.path, it.size) }
        cats += JunkCategory("logs", "Журналы (логи)", "Файлы .log и отчёты об ошибках, которые создают приложения", logs)

        // 3. Миниатюры
        val thumbs = index.dirs.filter { it.substringAfterLast('/').equals(".thumbnails", true) }
            .map { JunkItem(it, index.dirSizes[it] ?: 0, "Галерея создаст их заново", isDir = true) }
        cats += JunkCategory("thumbs", "Миниатюры галереи", "Папки .thumbnails — эскизы фото, создаются заново", thumbs)

        // 4. APK-файлы
        val apks = index.files.filter { it.ext == "apk" }.map { f ->
            var note = "Установочный файл"
            var pre = false
            try {
                @Suppress("DEPRECATION")
                val info = pm.getPackageArchiveInfo(f.path, 0)
                if (info != null) {
                    val inst = try {
                        pm.getPackageInfo(info.packageName, 0)
                    } catch (_: PackageManager.NameNotFoundException) {
                        null
                    }
                    if (inst != null) {
                        val vNew = verCode(info)
                        val vOld = verCode(inst)
                        note = if (vOld >= vNew) "Уже установлено (${inst.versionName ?: ""})" else "Новее установленной версии"
                        pre = vOld >= vNew
                    } else note = "Не установлено: ${info.packageName}"
                } else note = "Повреждённый APK"
            } catch (_: Exception) {
            }
            JunkItem(f.path, f.size, note, preselect = pre)
        }
        cats += JunkCategory("apk", "APK-файлы", "Установщики приложений. Отмечены уже установленные", apks)

        // 5. Кэш-папки в общей памяти (вне Android/)
        val cacheDirs = index.dirs.filter {
            it.substringAfterLast('/').lowercase() in CACHE_DIR_NAMES && "/Android/" !in "$it/" &&
                (index.dirSizes[it] ?: 0) > 0
        }.let { topMost(it) }
            .map { JunkItem(it, index.dirSizes[it] ?: 0, index.dirCounts[it]?.let { c -> "$c файл(ов)" }, isDir = true, preselect = false) }
        cats += JunkCategory("cachedirs", "Кэш в общей памяти", "Папки cache/.cache/temp, созданные приложениями в памяти телефона", cacheDirs)

        // 6. Остатки удалённых приложений (Android/data, Android/obb, Android/media)
        cats += JunkCategory(
            "residual", "Остатки удалённых приложений",
            "Папки приложений, которых уже нет на телефоне", residual(ctx, index, installed)
        )

        // 7. Пустые папки
        val nonEmpty = index.dirSizes.keys
        val empty = index.dirs.filter { d ->
            d !in nonEmpty && "/Android/" !in "$d/" && !isProtectedTop(index, d)
        }.let { topMost(it) }.map { JunkItem(it, 0, isDir = true) }
        cats += JunkCategory("empty", "Пустые папки", "Папки без единого файла", empty)

        // 8. Старые загрузки
        val days = prefs.oldDownloadsDays
        val border = System.currentTimeMillis() - days * 24L * 3600 * 1000
        val old = index.files.filter {
            ("/Download/" in it.path || "/Downloads/" in it.path) && it.modified in 1 until border
        }.map { JunkItem(it.path, it.size, "Старше $days дн.", preselect = false) }
        cats += JunkCategory("olddl", "Старые загрузки", "Файлы в «Загрузках», которые не менялись более $days дней", old)

        return cats.filter { it.items.isNotEmpty() }
    }

    fun residual(ctx: Context, index: StorageIndex, installed: Set<String>): List<JunkItem> {
        val out = mutableListOf<JunkItem>()
        val own = ctx.packageName
        for (root in index.roots) {
            for (sub in listOf("Android/data", "Android/obb", "Android/media")) {
                val base = "$root/$sub"
                val names = File(base).list()?.toList() ?: emptyList()
                for (n in names) {
                    if (n == own || n in installed || !n.contains('.') || n.startsWith(".")) continue
                    val p = "$base/$n"
                    val size = index.dirSizes[p] ?: com.pit.phonecleaner.core.FileOps.dirSize(File(p))
                    out += JunkItem(p, size, "$sub · приложение удалено", isDir = true)
                }
            }
        }
        // Android 11+: Android/data/obb недоступны напрямую — смотрим через root, если он разрешён
        if (Build.VERSION.SDK_INT >= 30 && Prefs.get(ctx).useRoot && RootShell.isAvailable()) {
            for (sub in listOf("Android/data", "Android/obb")) {
                val base = "/data/media/0/$sub"
                val r = RootShell.run("for d in $base/*; do s=\$(du -sk \"\$d\" 2>/dev/null | cut -f1); echo \"\$s|\${d##*/}\"; done")
                r.out.lines().forEach { line ->
                    val kb = line.substringBefore('|').trim().toLongOrNull() ?: return@forEach
                    val n = line.substringAfter('|').trim()
                    if (n.isEmpty() || n == own || n in installed || !n.contains('.')) return@forEach
                    val p = "${index.roots.first()}/$sub/$n"
                    if (out.none { it.path == p }) out += JunkItem(p, kb * 1024, "$sub · приложение удалено (root)", isDir = true)
                }
            }
        }
        return out
    }

    private fun isProtectedTop(index: StorageIndex, d: String): Boolean {
        val root = index.rootOf(d) ?: return true
        val rel = d.removePrefix("$root/")
        return !rel.contains('/') && rel.lowercase() in PROTECTED_TOP
    }

    /** Оставить только самые верхние папки (вложенные удалятся вместе с родителем) */
    private fun topMost(paths: List<String>): List<String> {
        val sorted = paths.sorted()
        val out = mutableListOf<String>()
        for (p in sorted) {
            val last = out.lastOrNull()
            if (last != null && p.startsWith("$last/")) continue
            out += p
        }
        return out
    }

    private fun verCode(pi: android.content.pm.PackageInfo): Long =
        if (Build.VERSION.SDK_INT >= 28) pi.longVersionCode else @Suppress("DEPRECATION") pi.versionCode.toLong()
}
