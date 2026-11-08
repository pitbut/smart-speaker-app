package com.pit.phonecleaner.core

import android.content.Context

class Prefs private constructor(ctx: Context) {
    private val sp = ctx.getSharedPreferences("settings", Context.MODE_PRIVATE)

    /** Порог «большого файла», МБ */
    var largeThresholdMb: Int
        get() = sp.getInt("large_mb", 50)
        set(v) = sp.edit().putInt("large_mb", v).apply()

    /** Использовать root, если доступен */
    var useRoot: Boolean
        get() = sp.getBoolean("use_root", false)
        set(v) = sp.edit().putBoolean("use_root", v).apply()

    /** Минимальный размер файла для поиска дубликатов, КБ */
    var dupMinKb: Int
        get() = sp.getInt("dup_min_kb", 4)
        set(v) = sp.edit().putInt("dup_min_kb", v).apply()

    /** Порог похожести фото (расстояние Хэмминга 0..16) */
    var similarThreshold: Int
        get() = sp.getInt("similar", 6)
        set(v) = sp.edit().putInt("similar", v).apply()

    /** Загрузки старше N дней считаются «старыми» */
    var oldDownloadsDays: Int
        get() = sp.getInt("old_dl_days", 90)
        set(v) = sp.edit().putInt("old_dl_days", v).apply()

    /** Сканировать скрытые файлы и папки (начинающиеся с точки) */
    var scanHidden: Boolean
        get() = sp.getBoolean("scan_hidden", true)
        set(v) = sp.edit().putBoolean("scan_hidden", v).apply()

    companion object {
        @Volatile
        private var inst: Prefs? = null
        fun get(ctx: Context): Prefs =
            inst ?: synchronized(this) { inst ?: Prefs(ctx.applicationContext).also { inst = it } }
    }
}
