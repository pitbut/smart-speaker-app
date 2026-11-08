package com.pit.phonecleaner.files

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.pit.phonecleaner.core.FileType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest

enum class DupKind(val title: String, val hint: String) {
    EXACT("Точные копии", "Файлы с полностью одинаковым содержимым (проверка MD5)"),
    SIMILAR("Похожие фото", "Серии снимков, пересжатые копии и скриншоты одного и того же"),
    SAME_NAME("Одинаковые имена", "Файлы с одинаковым именем в разных папках"),
    MEDIA_COPY("Копии медиа", "Одинаковые фото/видео/музыка с разными именами (размер + начало/конец файла)"),
}

data class DupGroup(val id: Int, val kind: DupKind, val files: List<FileItem>) {
    val total get() = files.sumOf { it.size }
    /** Сколько освободится, если оставить по одному */
    val wasted get() = total - (files.maxOfOrNull { it.size } ?: 0)
}

enum class KeepRule(val title: String) {
    OLDEST("Оставить самый старый"),
    NEWEST("Оставить самый новый"),
    SHORTEST_PATH("Оставить с самым коротким путём"),
    IN_DCIM("Оставить в DCIM/Camera"),
    LARGEST("Оставить самый большой");

    fun keep(files: List<FileItem>): FileItem = when (this) {
        OLDEST -> files.minBy { it.modified }
        NEWEST -> files.maxBy { it.modified }
        SHORTEST_PATH -> files.minBy { it.path.length }
        IN_DCIM -> files.firstOrNull { "/DCIM/" in it.path } ?: files.minBy { it.modified }
        LARGEST -> files.maxBy { it.size }
    }
}

object DuplicateFinder {

    private val IGNORE_NAMES = setOf(".nomedia", "thumbs.db", ".ds_store", "desktop.ini", "index.html", "config.json")

    suspend fun find(
        index: StorageIndex,
        kind: DupKind,
        minSize: Long,
        similarThreshold: Int,
        progress: (String) -> Unit,
    ): List<DupGroup> = withContext(Dispatchers.IO) {
        val candidates = index.files.filter {
            it.size >= minSize && it.name.lowercase() !in IGNORE_NAMES && "/Android/data/" !in it.path
        }
        val groups: List<List<FileItem>> = when (kind) {
            DupKind.EXACT -> exact(candidates, progress)
            DupKind.MEDIA_COPY -> mediaCopies(candidates, progress)
            DupKind.SAME_NAME -> candidates.groupBy { it.name.lowercase() }.values.filter { it.size > 1 }
            DupKind.SIMILAR -> similar(candidates, similarThreshold, progress)
        }
        groups.sortedByDescending { g -> g.sumOf { it.size } - g.maxOf { it.size } }
            .mapIndexed { i, g -> DupGroup(i, kind, g.sortedBy { it.modified }) }
    }

    // ---------- Точные копии: размер → быстрый хэш (начало+конец) → полный MD5 ----------

    private suspend fun exact(files: List<FileItem>, progress: (String) -> Unit): List<List<FileItem>> =
        withContext(Dispatchers.IO) {
            val bySize = files.groupBy { it.size }.values.filter { it.size > 1 }
            val total = bySize.sumOf { it.size }
            var done = 0
            val out = mutableListOf<List<FileItem>>()
            for (g in bySize) {
                ensureActive()
                val byQuick = g.groupBy { f -> done++; if (done % 20 == 0) progress("Быстрая проверка: $done из $total"); quickHash(f) }
                for ((qh, sub) in byQuick) {
                    if (qh == null || sub.size < 2) continue
                    // Маленькие файлы quickHash уже прочитал целиком
                    if (sub.first().size <= QUICK_CHUNK * 2) {
                        out += sub; continue
                    }
                    progress("Сверяю содержимое: ${sub.first().name}")
                    sub.groupBy { fullHash(it) }.forEach { (h, s) -> if (h != null && s.size > 1) out += s }
                }
            }
            out
        }

    private suspend fun mediaCopies(files: List<FileItem>, progress: (String) -> Unit): List<List<FileItem>> =
        withContext(Dispatchers.IO) {
            val media = files.filter { FileType.ofExt(it.ext) in setOf(FileType.IMAGE, FileType.VIDEO, FileType.AUDIO) }
            val bySize = media.groupBy { it.size }.values.filter { it.size > 1 }
            var done = 0
            val out = mutableListOf<List<FileItem>>()
            for (g in bySize) {
                ensureActive()
                done += g.size
                if (done % 20 == 0) progress("Проверено $done медиафайлов")
                g.groupBy { quickHash(it) }.forEach { (h, s) ->
                    // только разные имена — одинаковые имена покажет «Одинаковые имена»/«Точные копии»
                    if (h != null && s.size > 1 && s.map { it.name.lowercase() }.toSet().size > 1) out += s
                }
            }
            out
        }

    private const val QUICK_CHUNK = 16 * 1024

    private fun quickHash(f: FileItem): String? = try {
        RandomAccessFile(f.path, "r").use { raf ->
            val md = MessageDigest.getInstance("MD5")
            val len = raf.length()
            val buf = ByteArray(QUICK_CHUNK)
            val n1 = raf.read(buf, 0, minOf(QUICK_CHUNK.toLong(), len).toInt())
            if (n1 > 0) md.update(buf, 0, n1)
            if (len > QUICK_CHUNK * 2) {
                raf.seek(len - QUICK_CHUNK)
                val n2 = raf.read(buf)
                if (n2 > 0) md.update(buf, 0, n2)
            } else if (len > QUICK_CHUNK) {
                val n2 = raf.read(buf)
                if (n2 > 0) md.update(buf, 0, n2)
            }
            md.update(len.toString().toByteArray())
            md.digest().toHex()
        }
    } catch (_: Exception) {
        null
    }

    private fun fullHash(f: FileItem): String? = try {
        File(f.path).inputStream().buffered(256 * 1024).use { s ->
            val md = MessageDigest.getInstance("MD5")
            val buf = ByteArray(256 * 1024)
            while (true) {
                val n = s.read(buf)
                if (n <= 0) break
                md.update(buf, 0, n)
            }
            md.digest().toHex()
        }
    } catch (_: Exception) {
        null
    }

    private fun ByteArray.toHex() = joinToString("") { "%02x".format(it) }

    // ---------- Похожие фото: разностный хэш (dHash) 64 бит + расстояние Хэмминга ----------

    private suspend fun similar(files: List<FileItem>, threshold: Int, progress: (String) -> Unit): List<List<FileItem>> =
        withContext(Dispatchers.IO) {
            val imgs = files.filter {
                it.ext in setOf("jpg", "jpeg", "png", "webp", "heic", "heif", "bmp") && it.size >= 10 * 1024 &&
                    "/.thumbnails/" !in it.path
            }
            val hashes = ArrayList<Pair<FileItem, Long>>(imgs.size)
            imgs.forEachIndexed { i, f ->
                ensureActive()
                if (i % 10 == 0) progress("Анализ фото: ${i + 1} из ${imgs.size}")
                dHash(f.path)?.let { h ->
                    val bits = java.lang.Long.bitCount(h)
                    if (bits in 4..60) hashes += f to h // однотонные картинки дают ложные совпадения
                }
            }
            progress("Сравниваю ${hashes.size} фото…")
            val parent = IntArray(hashes.size) { it }
            fun find(x: Int): Int {
                var r = x
                while (parent[r] != r) r = parent[r]
                var c = x
                while (parent[c] != r) {
                    val n = parent[c]; parent[c] = r; c = n
                }
                return r
            }
            for (i in hashes.indices) {
                if (i % 200 == 0) ensureActive()
                val hi = hashes[i].second
                for (j in i + 1 until hashes.size) {
                    if (java.lang.Long.bitCount(hi xor hashes[j].second) <= threshold) {
                        val a = find(i)
                        val b = find(j)
                        if (a != b) parent[a] = b
                    }
                }
            }
            hashes.indices.groupBy { find(it) }.values.filter { it.size > 1 }.map { g -> g.map { hashes[it].first } }
        }

    fun dHash(path: String): Long? {
        return try {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(path, o)
            if (o.outWidth <= 0 || o.outHeight <= 0) return null
            var s = 1
            while (o.outWidth / (s * 2) >= 64 && o.outHeight / (s * 2) >= 64) s *= 2
            val bm = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply {
                inSampleSize = s
                inPreferredConfig = Bitmap.Config.RGB_565
            }) ?: return null
            val small = Bitmap.createScaledBitmap(bm, 9, 8, true)
            if (small != bm) bm.recycle()
            var h = 0L
            var bit = 0
            for (y in 0 until 8) for (x in 0 until 8) {
                if (lum(small.getPixel(x, y)) > lum(small.getPixel(x + 1, y))) h = h or (1L shl bit)
                bit++
            }
            small.recycle()
            h
        } catch (_: Throwable) {
            null
        }
    }

    private fun lum(c: Int): Int = ((c shr 16 and 0xff) * 299 + (c shr 8 and 0xff) * 587 + (c and 0xff) * 114) / 1000
}
