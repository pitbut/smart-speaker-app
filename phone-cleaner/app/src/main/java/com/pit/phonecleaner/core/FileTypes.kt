package com.pit.phonecleaner.core

import android.webkit.MimeTypeMap

enum class FileType(val title: String) {
    VIDEO("Видео"),
    AUDIO("Аудио"),
    IMAGE("Фото"),
    ARCHIVE("Архивы"),
    APK("APK"),
    DOCUMENT("Документы"),
    OTHER("Другое");

    companion object {
        private val map = HashMap<String, FileType>().apply {
            listOf("mp4", "mkv", "avi", "mov", "3gp", "webm", "m4v", "ts", "flv", "wmv", "mpg", "mpeg")
                .forEach { put(it, VIDEO) }
            listOf("mp3", "m4a", "aac", "ogg", "opus", "wav", "flac", "amr", "wma", "mid", "m4b")
                .forEach { put(it, AUDIO) }
            listOf("jpg", "jpeg", "png", "webp", "heic", "heif", "gif", "bmp", "dng", "raw", "tif", "tiff")
                .forEach { put(it, IMAGE) }
            listOf("zip", "rar", "7z", "tar", "gz", "tgz", "bz2", "xz", "iso", "img", "cab")
                .forEach { put(it, ARCHIVE) }
            listOf("apk", "xapk", "apks", "apkm", "obb").forEach { put(it, APK) }
            listOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt", "rtf", "odt", "epub", "fb2", "djvu", "csv")
                .forEach { put(it, DOCUMENT) }
        }

        fun ofExt(ext: String): FileType = map[ext.lowercase()] ?: OTHER
        fun ofName(name: String): FileType = ofExt(name.substringAfterLast('.', ""))
    }
}

fun mimeOf(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
}
