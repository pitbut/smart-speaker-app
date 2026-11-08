package com.pit.phonecleaner.files

import com.pit.phonecleaner.apps.AppInfo
import java.io.File

/** Папка или файл в корне памяти, не относящиеся к стандартной структуре Android */
data class ForeignEntry(
    val path: String,
    val name: String,
    val isDir: Boolean,
    val size: Long,
    val files: Int,
    val owner: String?,
    val ownerPkg: String?,
    val hidden: Boolean,
    val modified: Long,
) {
    /** Владелец не найден — скорее всего остатки удалённой программы */
    val orphan get() = owner == null
}

object ForeignAnalyzer {

    /** Стандартные папки Android — их не показываем */
    val STANDARD = setOf(
        "android", "dcim", "pictures", "movies", "music", "download", "downloads", "documents",
        "alarms", "notifications", "ringtones", "podcasts", "audiobooks", "recordings", "screenshots",
        "lost.dir", ".android_secure", "lost+found",
    )

    /** Известные папки → пакет-владелец */
    private val KNOWN = mapOf(
        "telegram" to "org.telegram.messenger",
        "whatsapp" to "com.whatsapp",
        "viber" to "com.viber.voip",
        "tencent" to "com.tencent.mm",
        "vk" to "com.vkontakte.android",
        "vkontakte" to "com.vkontakte.android",
        "yandex" to "ru.yandex.searchplugin",
        "yandexmaps" to "ru.yandex.yandexmaps",
        "kate" to "com.perm.kate_new_6",
        "snapseed" to "com.niksoftware.snapseed",
        "instashot" to "com.camerasideas.instashot",
        "capcut" to "com.lemon.lvoverseas",
        "tiktok" to "com.zhiliaoapp.musically",
        "bluetooth" to null,
        "mipush" to null,
        "miui" to null,
        "xiaomi" to null,
        "huawei" to null,
        "samsung" to null,
        "oppo" to null,
        "coloros" to null,
        "backups" to null,
    )

    private val SYSTEMISH = setOf("bluetooth", "mipush", "miui", "xiaomi", "huawei", "samsung", "oppo", "coloros", "backups")

    private val IGNORED_SEGMENTS = setOf(
        "com", "org", "net", "ru", "io", "me", "app", "apps", "android", "mobile", "client", "lite", "pro",
        "free", "www", "inc", "ltd", "official", "google", "main", "plus", "global", "ui", "the"
    )

    private fun clean(s: String) = s.lowercase().filter { it.isLetterOrDigit() }

    fun analyze(index: StorageIndex, apps: List<AppInfo>): List<ForeignEntry> {
        val byPkg = apps.associateBy { it.pkg }
        data class Token(val token: String, val app: AppInfo)

        val tokens = ArrayList<Token>()
        for (a in apps) {
            val label = clean(a.label)
            if (label.length >= 3) tokens += Token(label, a)
            a.pkg.split('.').map { clean(it) }
                .filter { it.length >= 3 && it !in IGNORED_SEGMENTS }
                .forEach { tokens += Token(it, a) }
        }

        fun findOwner(name: String): Pair<String?, String?> {
            val n = clean(name)
            if (n.isEmpty()) return null to null
            if (n in SYSTEMISH || name.lowercase() in SYSTEMISH) return "Система / прошивка" to null
            KNOWN[n]?.let { pkg -> byPkg[pkg]?.let { return it.label to it.pkg } }
            byPkg[name]?.let { return it.label to it.pkg }
            tokens.firstOrNull { it.token == n }?.let { return it.app.label to it.app.pkg }
            if (n.length >= 4) {
                tokens.firstOrNull { it.token.length >= 4 && (n.contains(it.token) || it.token.contains(n)) }
                    ?.let { return it.app.label to it.app.pkg }
            }
            return null to null
        }

        val out = mutableListOf<ForeignEntry>()
        for (root in index.roots) {
            val children = File(root).listFiles() ?: continue
            for (c in children) {
                if (c.name.lowercase() in STANDARD) continue
                val (owner, pkg) = findOwner(c.name.trimStart('.'))
                if (c.isDirectory) {
                    out += ForeignEntry(
                        c.path, c.name, true, index.dirSizes[c.path] ?: 0, index.dirCounts[c.path] ?: 0,
                        owner, pkg, c.name.startsWith("."), c.lastModified()
                    )
                } else {
                    out += ForeignEntry(c.path, c.name, false, c.length(), 1, owner, pkg, c.name.startsWith("."), c.lastModified())
                }
            }
        }
        return out.sortedByDescending { it.size }
    }
}
