package com.pit.phonecleaner.files

import android.content.Context
import android.os.Build
import com.pit.phonecleaner.core.Volumes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.file.Files

class FileItem(val path: String, val size: Long, val modified: Long) {
    val name: String get() = path.substringAfterLast('/')
    val ext: String get() = name.substringAfterLast('.', "").lowercase()
    val parent: String get() = path.substringBeforeLast('/')
    fun file() = File(path)
}

/** Снимок файловой системы: строится один раз и используется всеми разделами */
class StorageIndex(
    val roots: List<String>,
    val files: List<FileItem>,
    val dirs: List<String>,
    val builtAt: Long = System.currentTimeMillis(),
) {
    /** Суммарный размер каждой папки (рекурсивно) */
    val dirSizes: Map<String, Long>
    /** Количество файлов в каждой папке (рекурсивно) */
    val dirCounts: Map<String, Int>

    init {
        val sizes = HashMap<String, Long>(dirs.size * 2)
        val counts = HashMap<String, Int>(dirs.size * 2)
        val rootSet = roots.toSet()
        for (f in files) {
            var p = f.parent
            while (true) {
                sizes[p] = (sizes[p] ?: 0L) + f.size
                counts[p] = (counts[p] ?: 0) + 1
                if (p in rootSet) break
                val i = p.lastIndexOf('/')
                if (i <= 0) break
                p = p.substring(0, i)
            }
        }
        dirSizes = sizes
        dirCounts = counts
    }

    val totalSize: Long get() = files.sumOf { it.size }

    fun rootOf(path: String) = roots.firstOrNull { path == it || path.startsWith("$it/") }

    /** Индекс без удалённых путей (и всего, что лежало внутри удалённых папок) */
    fun without(deleted: Set<String>): StorageIndex {
        if (deleted.isEmpty()) return this
        val prefixes = deleted.map { "$it/" }
        fun gone(p: String) = p in deleted || prefixes.any { p.startsWith(it) }
        return StorageIndex(roots, files.filterNot { gone(it.path) }, dirs.filterNot { gone(it) }, builtAt)
    }
}

object FileScanner {

    suspend fun build(ctx: Context, scanHidden: Boolean, progress: (files: Int, dir: String) -> Unit): StorageIndex =
        withContext(Dispatchers.IO) {
            val roots = Volumes.roots(ctx)
            val files = ArrayList<FileItem>(50_000)
            val dirs = ArrayList<String>(5_000)
            var counter = 0
            for (root in roots) {
                // На Android 11+ Android/data и Android/obb недоступны — не тратим время
                val skip = if (Build.VERSION.SDK_INT >= 30)
                    setOf("${root.path}/Android/data", "${root.path}/Android/obb") else emptySet()
                val stack = ArrayDeque<File>()
                stack.add(root)
                while (stack.isNotEmpty()) {
                    ensureActive()
                    val dir = stack.removeLast()
                    val children = dir.listFiles() ?: continue
                    if (dir != root) dirs += dir.path
                    for (c in children) {
                        if (!scanHidden && c.name.startsWith(".")) continue
                        if (isSymlink(c)) continue
                        if (c.isDirectory) {
                            if (c.path !in skip) stack.add(c)
                        } else {
                            files += FileItem(c.path, c.length(), c.lastModified())
                            if (++counter % 400 == 0) progress(counter, dir.path)
                        }
                    }
                }
            }
            progress(counter, "")
            StorageIndex(roots.map { it.path }, files, dirs)
        }

    private fun isSymlink(f: File): Boolean = try {
        Files.isSymbolicLink(f.toPath())
    } catch (_: Throwable) {
        false
    }
}
