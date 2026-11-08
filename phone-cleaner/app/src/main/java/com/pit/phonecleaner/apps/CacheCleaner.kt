package com.pit.phonecleaner.apps

import android.content.Context
import android.os.Build
import com.pit.phonecleaner.core.FileOps
import com.pit.phonecleaner.core.Perms
import com.pit.phonecleaner.core.RootShell
import com.pit.phonecleaner.core.SafAccess
import com.pit.phonecleaner.core.Volumes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

data class CleanOutcome(val ok: Int, val failed: Int, val method: String, val note: String? = null)

/**
 * Способы очистки кэша (от самого «тихого» к самому универсальному):
 *  1. root — чистит и видимый, и скрытый кэш любого приложения мгновенно;
 *  2. прямой доступ к Android/data (Android 10 и ниже) — видимый кэш;
 *  3. SAF-доступ к Android/data (Android 11–12) — видимый кэш;
 *  4. служба спец. возможностей — нажимает «Очистить кэш» в настройках (скрытый + видимый);
 *  5. системный диалог «Очистить кэш всех приложений» (Android 11+).
 */
object CacheCleaner {

    fun canCleanVisibleDirectly(ctx: Context): Boolean =
        (Build.VERSION.SDK_INT < 30 && Perms.hasAllFiles(ctx)) || SafAccess.hasDataAccess(ctx)

    /** Кэш самого ЧистоФона */
    fun cleanOwn(ctx: Context): Long {
        var freed = FileOps.clearDir(ctx.cacheDir)
        ctx.externalCacheDir?.let { freed += FileOps.clearDir(it) }
        ctx.codeCacheDir.let { freed += FileOps.clearDir(it) }
        return freed
    }

    suspend fun cleanVisible(ctx: Context, pkgs: List<String>, useRoot: Boolean): CleanOutcome =
        withContext(Dispatchers.IO) {
            cleanOwn(ctx)
            val others = pkgs.filter { it != ctx.packageName }
            when {
                useRoot && RootShell.isAvailable() -> {
                    val cmds = others.flatMap {
                        listOf(
                            RootShell.clearDirCmd("/data/media/0/Android/data/$it/cache"),
                            RootShell.clearDirCmd("/sdcard/Android/data/$it/cache"),
                        )
                    }
                    RootShell.run(*cmds.toTypedArray())
                    CleanOutcome(others.size, 0, "root")
                }

                Build.VERSION.SDK_INT < 30 && Perms.hasAllFiles(ctx) -> {
                    var ok = 0
                    var fail = 0
                    val roots = Volumes.roots(ctx)
                    for (p in others) {
                        var good = true
                        for (r in roots) {
                            val d = File(r, "Android/data/$p/cache")
                            if (d.exists()) {
                                FileOps.clearDir(d)
                                if ((d.listFiles()?.size ?: 0) > 0) good = false
                            }
                        }
                        if (good) ok++ else fail++
                    }
                    CleanOutcome(ok, fail, "прямой доступ")
                }

                SafAccess.hasDataAccess(ctx) -> {
                    val (ok, fail) = SafAccess.clearCaches(ctx, others)
                    CleanOutcome(ok, fail, "доступ к Android/data")
                }

                else -> CleanOutcome(
                    0, others.size, "нет",
                    "На Android ${Build.VERSION.RELEASE} папка Android/data закрыта для сторонних приложений. " +
                        "Используйте «Скрытый кэш (авто)» — он очищает весь кэш приложения, " +
                        "системную очистку всего кэша или режим root."
                )
            }
        }

    /** Root: полный кэш (скрытый + видимый) выбранных приложений */
    suspend fun cleanAllRoot(ctx: Context, pkgs: List<String>): CleanOutcome = withContext(Dispatchers.IO) {
        if (!RootShell.isAvailable()) return@withContext CleanOutcome(0, pkgs.size, "root", "Root недоступен")
        cleanOwn(ctx)
        val cmds = pkgs.filter { it != ctx.packageName }.flatMap {
            listOf(
                RootShell.clearDirCmd("/data/data/$it/cache"),
                RootShell.clearDirCmd("/data/data/$it/code_cache"),
                RootShell.clearDirCmd("/data/user_de/0/$it/cache"),
                RootShell.clearDirCmd("/data/user_de/0/$it/code_cache"),
                RootShell.clearDirCmd("/data/media/0/Android/data/$it/cache"),
            )
        }
        val r = RootShell.run(*cmds.toTypedArray(), timeoutSec = 300)
        CleanOutcome(pkgs.size, 0, "root", if (r.ok) null else r.out.take(300))
    }

    /** Root: системная очистка кэша всех приложений */
    suspend fun trimAllRoot(): Boolean = withContext(Dispatchers.IO) {
        RootShell.isAvailable() && RootShell.run("pm trim-caches 999G").ok
    }
}
