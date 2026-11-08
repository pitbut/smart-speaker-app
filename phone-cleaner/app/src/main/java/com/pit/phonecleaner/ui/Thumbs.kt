package com.pit.phonecleaner.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.ThumbnailUtils
import android.os.Build
import android.provider.MediaStore
import android.util.LruCache
import android.util.Size
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.filled.AudioFile
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.graphics.drawable.toBitmap
import com.pit.phonecleaner.core.FileType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

object Thumbs {
    private val cache = LruCache<String, ImageBitmap>(250)
    private val missing: MutableSet<String> = java.util.Collections.synchronizedSet(HashSet())

    fun cached(key: String): ImageBitmap? = cache.get(key)

    suspend fun file(ctx: Context, path: String): ImageBitmap? = withContext(Dispatchers.IO) {
        cache.get(path)?.let { return@withContext it }
        if (path in missing) return@withContext null
        val bmp: Bitmap? = try {
            when (FileType.ofName(path)) {
                FileType.IMAGE -> decodeSampled(path, 128)
                FileType.VIDEO -> if (Build.VERSION.SDK_INT >= 29)
                    ThumbnailUtils.createVideoThumbnail(File(path), Size(128, 128), null)
                else @Suppress("DEPRECATION") ThumbnailUtils.createVideoThumbnail(path, MediaStore.Video.Thumbnails.MINI_KIND)
                FileType.APK -> {
                    val pm = ctx.packageManager
                    @Suppress("DEPRECATION")
                    pm.getPackageArchiveInfo(path, 0)?.applicationInfo?.let {
                        it.sourceDir = path
                        it.publicSourceDir = path
                        it.loadIcon(pm).toBitmap(96, 96)
                    }
                }
                else -> null
            }
        } catch (_: Throwable) {
            null
        }
        val ib = bmp?.asImageBitmap()
        if (ib != null) cache.put(path, ib) else missing += path
        ib
    }

    suspend fun app(ctx: Context, pkg: String): ImageBitmap? = withContext(Dispatchers.IO) {
        val key = "app:$pkg"
        cache.get(key)?.let { return@withContext it }
        try {
            ctx.packageManager.getApplicationIcon(pkg).toBitmap(96, 96).asImageBitmap().also { cache.put(key, it) }
        } catch (_: Throwable) {
            null
        }
    }

    private fun decodeSampled(path: String, req: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, o)
        if (o.outWidth <= 0) return null
        var s = 1
        while (o.outWidth / (s * 2) >= req && o.outHeight / (s * 2) >= req) s *= 2
        return BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = s })
    }
}

fun typeIcon(type: FileType): ImageVector = when (type) {
    FileType.VIDEO -> Icons.Filled.Movie
    FileType.AUDIO -> Icons.Filled.AudioFile
    FileType.IMAGE -> Icons.Filled.Image
    FileType.ARCHIVE -> Icons.Filled.Archive
    FileType.APK -> Icons.Filled.Android
    FileType.DOCUMENT -> Icons.Filled.Description
    FileType.OTHER -> Icons.AutoMirrored.Filled.InsertDriveFile
}

@Composable
fun FileThumb(path: String, isDir: Boolean = false, size: Dp = 44.dp) {
    val ctx = LocalContext.current
    val type = FileType.ofName(path)
    val wantsThumb = !isDir && type in setOf(FileType.IMAGE, FileType.VIDEO, FileType.APK)
    val bmp by produceState(initialValue = if (wantsThumb) Thumbs.cached(path) else null, path) {
        if (wantsThumb && value == null) value = Thumbs.file(ctx, path)
    }
    Box(
        Modifier
            .size(size)
            .clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center
    ) {
        val b = bmp
        if (b != null) {
            Image(b, null, Modifier.size(size), contentScale = ContentScale.Crop)
        } else {
            Icon(
                if (isDir) Icons.Filled.Folder else typeIcon(type), null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
fun AppIcon(pkg: String, size: Dp = 44.dp) {
    val ctx = LocalContext.current
    val bmp by produceState(initialValue = Thumbs.cached("app:$pkg"), pkg) {
        if (value == null) value = Thumbs.app(ctx, pkg)
    }
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        val b = bmp
        if (b != null) Image(b, null, Modifier.size(size))
        else Icon(Icons.Filled.Android, null, tint = MaterialTheme.colorScheme.primary)
    }
}
