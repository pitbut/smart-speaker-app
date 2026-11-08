package com.pit.phonecleaner.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private val RU = Locale("ru")

/** 1536 -> "1,5 КБ" */
fun formatSize(bytes: Long): String {
    if (bytes < 0) return "—"
    if (bytes < 1024) return "$bytes Б"
    val units = arrayOf("КБ", "МБ", "ГБ", "ТБ")
    var v = bytes / 1024.0
    var i = 0
    while (v >= 1024 && i < units.size - 1) {
        v /= 1024
        i++
    }
    val pattern = if (v >= 100) "%.0f %s" else "%.1f %s"
    return String.format(RU, pattern, v, units[i])
}

fun formatDate(millis: Long): String =
    if (millis <= 0) "" else SimpleDateFormat("dd.MM.yyyy HH:mm", RU).format(Date(millis))

/** Склонение: plural(5, "файл", "файла", "файлов") */
fun plural(n: Int, one: String, few: String, many: String): String {
    val n10 = n % 10
    val n100 = n % 100
    val w = when {
        n10 == 1 && n100 != 11 -> one
        n10 in 2..4 && n100 !in 12..14 -> few
        else -> many
    }
    return "$n $w"
}

fun filesCount(n: Int) = plural(n, "файл", "файла", "файлов")
fun appsCount(n: Int) = plural(n, "приложение", "приложения", "приложений")
