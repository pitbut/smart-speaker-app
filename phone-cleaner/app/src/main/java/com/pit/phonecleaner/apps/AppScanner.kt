package com.pit.phonecleaner.apps

import android.app.usage.StorageStatsManager
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.os.Build
import android.os.Process
import com.pit.phonecleaner.core.FileOps
import com.pit.phonecleaner.core.Perms
import com.pit.phonecleaner.core.Volumes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File

data class AppInfo(
    val pkg: String,
    val label: String,
    val isSystem: Boolean,
    val isUpdatedSystem: Boolean,
    val enabled: Boolean,
    /** Весь кэш (внутренний + внешний) */
    val cacheBytes: Long,
    /** Видимый кэш: Android/data/<pkg>/cache */
    val externalCacheBytes: Long,
    val dataBytes: Long,
    val appBytes: Long,
    /** Удалось ли получить точные цифры через StorageStatsManager */
    val statsKnown: Boolean,
    val installTime: Long,
    val updateTime: Long,
    val versionName: String?,
) {
    /** Скрытый кэш: /data/data/<pkg>/cache и code_cache — доступен только системе */
    val hiddenCacheBytes get() = (cacheBytes - externalCacheBytes).coerceAtLeast(0)
    val totalBytes get() = appBytes + dataBytes
}

object AppScanner {

    @Suppress("DEPRECATION")
    fun installedApps(pm: PackageManager): List<ApplicationInfo> =
        if (Build.VERSION.SDK_INT >= 33) pm.getInstalledApplications(PackageManager.ApplicationInfoFlags.of(0))
        else pm.getInstalledApplications(0)

    fun installedPackages(ctx: Context): Set<String> =
        installedApps(ctx.packageManager).mapTo(HashSet()) { it.packageName }

    suspend fun scan(
        ctx: Context,
        only: Collection<String>? = null,
        progress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): List<AppInfo> = withContext(Dispatchers.IO) {
        val pm = ctx.packageManager
        var apps = installedApps(pm)
        if (only != null) {
            val set = only.toSet()
            apps = apps.filter { it.packageName in set }
        }
        val ssm = ctx.getSystemService(StorageStatsManager::class.java)
        val user = Process.myUserHandle()
        val hasUsage = Perms.hasUsageAccess(ctx)
        val extRoot = Volumes.primary()
        val canReadExtData = Build.VERSION.SDK_INT < 30 && Perms.hasAllFiles(ctx)

        apps.mapIndexed { i, ai ->
            ensureActive()
            progress(i + 1, apps.size)
            var cache = 0L
            var ext = 0L
            var data = 0L
            var app = 0L
            var known = false
            if (hasUsage) {
                try {
                    val st = ssm.queryStatsForPackage(ai.storageUuid, ai.packageName, user)
                    cache = st.cacheBytes
                    data = st.dataBytes
                    app = st.appBytes
                    if (Build.VERSION.SDK_INT >= 31) ext = st.externalCacheBytes
                    known = true
                } catch (_: Exception) {
                }
            }
            if (Build.VERSION.SDK_INT < 31 && canReadExtData) {
                ext = FileOps.dirSize(File(extRoot, "Android/data/${ai.packageName}/cache"))
                if (!known) cache = ext
            }
            if (ext > cache) cache = ext

            val pi = try {
                pm.getPackageInfo(ai.packageName, 0)
            } catch (_: Exception) {
                null
            }
            val sys = ai.flags and ApplicationInfo.FLAG_SYSTEM != 0
            AppInfo(
                pkg = ai.packageName,
                label = try {
                    ai.loadLabel(pm).toString()
                } catch (_: Exception) {
                    ai.packageName
                },
                isSystem = sys,
                isUpdatedSystem = ai.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP != 0,
                enabled = ai.enabled,
                cacheBytes = cache,
                externalCacheBytes = ext,
                dataBytes = data,
                appBytes = app,
                statsKnown = known,
                installTime = pi?.firstInstallTime ?: 0,
                updateTime = pi?.lastUpdateTime ?: 0,
                versionName = pi?.versionName,
            )
        }
    }
}
