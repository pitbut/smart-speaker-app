package com.pit.phonecleaner.core

import android.app.usage.StorageStatsManager
import android.content.Context
import android.os.Environment
import android.os.StatFs
import android.os.storage.StorageManager
import java.io.File

data class VolumeInfo(val name: String, val root: File, val total: Long, val free: Long) {
    val used get() = (total - free).coerceAtLeast(0)
    val usedFraction get() = if (total > 0) used.toFloat() / total else 0f
}

object Volumes {
    /** Корни всех доступных хранилищ: внутренняя память + SD-карты */
    fun roots(ctx: Context): List<File> {
        val res = mutableListOf<File>()
        ctx.getExternalFilesDirs(null).filterNotNull().forEach { f ->
            val p = f.absolutePath
            val i = p.indexOf("/Android/data/")
            if (i > 0) res += File(p.substring(0, i))
        }
        if (res.isEmpty()) res += Environment.getExternalStorageDirectory()
        return res.distinctBy { it.absolutePath }
    }

    fun primary(): File = Environment.getExternalStorageDirectory()

    fun info(ctx: Context): List<VolumeInfo> {
        val list = mutableListOf<VolumeInfo>()
        val roots = roots(ctx)
        roots.forEachIndexed { i, root ->
            if (i == 0) {
                // Для внутренней памяти берём «честные» цифры как в настройках
                var total = 0L
                var free = 0L
                try {
                    val ssm = ctx.getSystemService(StorageStatsManager::class.java)
                    total = ssm.getTotalBytes(StorageManager.UUID_DEFAULT)
                    free = ssm.getFreeBytes(StorageManager.UUID_DEFAULT)
                } catch (_: Exception) {
                    val st = StatFs(Environment.getDataDirectory().path)
                    total = st.totalBytes
                    free = st.availableBytes
                }
                list += VolumeInfo("Внутренняя память", root, total, free)
            } else {
                try {
                    val st = StatFs(root.path)
                    list += VolumeInfo(if (roots.size > 2) "SD-карта $i" else "SD-карта", root, st.totalBytes, st.availableBytes)
                } catch (_: Exception) {
                }
            }
        }
        return list
    }

    /** Корень хранилища, которому принадлежит путь */
    fun rootOf(ctx: Context, path: String): File? =
        roots(ctx).firstOrNull { path == it.path || path.startsWith(it.path + "/") }
}
