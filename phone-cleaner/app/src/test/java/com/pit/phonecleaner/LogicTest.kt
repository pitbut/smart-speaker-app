package com.pit.phonecleaner

import com.pit.phonecleaner.core.formatSize
import com.pit.phonecleaner.core.plural
import com.pit.phonecleaner.files.DupKind
import com.pit.phonecleaner.files.DuplicateFinder
import com.pit.phonecleaner.files.FileItem
import com.pit.phonecleaner.files.KeepRule
import com.pit.phonecleaner.files.StorageIndex
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.random.Random

class LogicTest {
    @get:Rule
    val tmp = TemporaryFolder()

    @Test
    fun sizes() {
        assertEquals("512 Б", formatSize(512))
        assertEquals("1,5 КБ", formatSize(1536))
        assertEquals("100 МБ", formatSize(100L * 1024 * 1024))
        assertEquals("5 файлов", plural(5, "файл", "файла", "файлов"))
        assertEquals("21 файл", plural(21, "файл", "файла", "файлов"))
        assertEquals("12 файлов", plural(12, "файл", "файла", "файлов"))
        assertEquals("3 файла", plural(3, "файл", "файла", "файлов"))
    }

    private fun write(path: String, bytes: ByteArray, time: Long): FileItem {
        val f = File(tmp.root, path)
        f.parentFile!!.mkdirs()
        f.writeBytes(bytes)
        f.setLastModified(time)
        return FileItem(f.path, f.length(), f.lastModified())
    }

    @Test
    fun exactDuplicatesAndIndex() = runBlocking {
        val big = Random(1).nextBytes(100_000)
        val bigOther = big.copyOf().also { it[50_000] = (it[50_000] + 1).toByte() } // отличие в середине
        val small = Random(2).nextBytes(5_000)
        val files = listOf(
            write("DCIM/a.jpg", big, 1_000_000),
            write("Download/a_copy.jpg", big, 2_000_000),
            write("Telegram/x.jpg", bigOther, 3_000_000),
            write("Docs/s1.bin", small, 1_000_000),
            write("Docs/sub/s2.bin", small, 1_000_000),
        )
        val dirs = listOf("DCIM", "Download", "Telegram", "Docs", "Docs/sub").map { File(tmp.root, it).path }
        val idx = StorageIndex(listOf(tmp.root.path), files, dirs)

        assertEquals(small.size * 2L, idx.dirSizes[File(tmp.root, "Docs").path])
        assertEquals(2, idx.dirCounts[File(tmp.root, "Docs").path])

        val groups = DuplicateFinder.find(idx, DupKind.EXACT, 1024, 6) {}
        assertEquals(2, groups.size)
        val bigGroup = groups.first()
        assertEquals(2, bigGroup.files.size)
        assertTrue(bigGroup.files.none { it.name == "x.jpg" })
        assertEquals(big.size.toLong(), bigGroup.wasted)
        assertEquals("a.jpg", KeepRule.OLDEST.keep(bigGroup.files).name)
        assertEquals("a_copy.jpg", KeepRule.NEWEST.keep(bigGroup.files).name)

        val names = DuplicateFinder.find(idx, DupKind.SAME_NAME, 1, 6) {}
        assertTrue(names.isEmpty())

        val without = idx.without(setOf(File(tmp.root, "Docs").path))
        assertEquals(3, without.files.size)
        assertEquals(null, without.dirSizes[File(tmp.root, "Docs").path])
    }
}
