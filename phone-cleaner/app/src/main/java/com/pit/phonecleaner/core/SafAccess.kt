package com.pit.phonecleaner.core

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.DocumentsContract
import androidx.documentfile.provider.DocumentFile

/**
 * Android 11–12: папка Android/data закрыта даже при полном доступе к файлам,
 * но её можно открыть через системный выбор папки (SAF). Так чистится «видимый» кэш
 * без root и без спец. возможностей.
 */
object SafAccess {
    private const val AUTH = "com.android.externalstorage.documents"
    private const val DOC_ID = "primary:Android/data"

    val supported get() = Build.VERSION.SDK_INT in 30..32

    fun treeUri(): Uri = DocumentsContract.buildTreeDocumentUri(AUTH, DOC_ID)

    fun hasDataAccess(ctx: Context): Boolean {
        if (!supported) return false
        val t = treeUri()
        return ctx.contentResolver.persistedUriPermissions.any { it.uri == t && it.isWritePermission }
    }

    fun requestIntent(): Intent = Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
        putExtra(DocumentsContract.EXTRA_INITIAL_URI, DocumentsContract.buildDocumentUri(AUTH, DOC_ID))
        addFlags(
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
        )
    }

    fun takePermission(ctx: Context, uri: Uri): Boolean {
        if (uri != treeUri()) return false
        return try {
            ctx.contentResolver.takePersistableUriPermission(
                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            true
        } catch (_: Exception) {
            false
        }
    }

    /** Очистить Android/data/<pkg>/cache для пакетов. Возвращает (успешно, ошибок). */
    fun clearCaches(ctx: Context, pkgs: Collection<String>): Pair<Int, Int> {
        val tree = DocumentFile.fromTreeUri(ctx, treeUri()) ?: return 0 to pkgs.size
        val children = try {
            tree.listFiles().associateBy { it.name }
        } catch (_: Exception) {
            return 0 to pkgs.size
        }
        var ok = 0
        var fail = 0
        for (p in pkgs) {
            val cache = children[p]?.findFile("cache")
            if (cache == null) {
                ok++; continue
            }
            var allOk = true
            cache.listFiles().forEach { if (!it.delete()) allOk = false }
            if (allOk) ok++ else fail++
        }
        return ok to fail
    }

    /** Список папок в Android/data (для поиска остатков удалённых приложений) */
    fun listDataFolders(ctx: Context): List<String> {
        if (!hasDataAccess(ctx)) return emptyList()
        val tree = DocumentFile.fromTreeUri(ctx, treeUri()) ?: return emptyList()
        return try {
            tree.listFiles().mapNotNull { it.name }
        } catch (_: Exception) {
            emptyList()
        }
    }
}
