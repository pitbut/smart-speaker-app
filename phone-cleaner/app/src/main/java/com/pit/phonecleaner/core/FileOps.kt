package com.pit.phonecleaner.core

import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import androidx.core.content.FileProvider
import java.io.File

data class DeleteResult(val deleted: Int, val failed: Int, val freed: Long, val deletedPaths: Set<String>)

object FileOps {

    fun dirSize(dir: File): Long {
        var total = 0L
        val stack = ArrayDeque<File>()
        stack.add(dir)
        while (stack.isNotEmpty()) {
            val d = stack.removeLast()
            val children = d.listFiles() ?: continue
            for (c in children) {
                if (c.isDirectory) stack.add(c) else total += c.length()
            }
        }
        return total
    }

    /** Удалить содержимое каталога, не трогая сам каталог */
    fun clearDir(dir: File): Long {
        var freed = 0L
        dir.listFiles()?.forEach { c ->
            val size = if (c.isDirectory) dirSize(c) else c.length()
            if (c.deleteRecursively()) freed += size
        }
        return freed
    }

    /**
     * Удаляет файлы/папки. Если обычное удаление не удалось и разрешён root — удаляет через su.
     * sizes — известные размеры элементов (для подсчёта освобождённого места).
     */
    fun delete(ctx: Context, items: Map<String, Long>, useRoot: Boolean): DeleteResult {
        var deleted = 0
        var failed = 0
        var freed = 0L
        val ok = HashSet<String>()
        val rootNeeded = mutableListOf<String>()
        for ((path, size) in items) {
            val f = File(path)
            val success = if (!f.exists()) true else f.deleteRecursively() || !f.exists()
            if (success) {
                deleted++; freed += size; ok += path
            } else rootNeeded += path
        }
        if (rootNeeded.isNotEmpty() && useRoot && RootShell.isAvailable()) {
            RootShell.run(*rootNeeded.map { "rm -rf ${RootShell.q(it)}" }.toTypedArray())
            for (p in rootNeeded) {
                if (!File(p).exists()) {
                    deleted++; freed += items[p] ?: 0; ok += p
                } else failed++
            }
        } else failed += rootNeeded.size

        // Обновляем медиатеку, чтобы удалённые фото/видео исчезли из галереи
        val media = ok.filter { FileType.ofName(it) in setOf(FileType.IMAGE, FileType.VIDEO, FileType.AUDIO) }
        if (media.isNotEmpty()) {
            try {
                MediaScannerConnection.scanFile(ctx, media.toTypedArray(), null, null)
            } catch (_: Exception) {
            }
        }
        return DeleteResult(deleted, failed, freed, ok)
    }

    fun open(ctx: Context, path: String) {
        val f = File(path)
        try {
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
            val i = Intent(Intent.ACTION_VIEW).setDataAndType(uri, mimeOf(f.name))
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(Intent.createChooser(i, "Открыть с помощью").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }

    fun share(ctx: Context, path: String) {
        val f = File(path)
        try {
            val uri = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)
            val i = Intent(Intent.ACTION_SEND).setType(mimeOf(f.name)).putExtra(Intent.EXTRA_STREAM, uri)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.startActivity(Intent.createChooser(i, "Поделиться").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }
}
