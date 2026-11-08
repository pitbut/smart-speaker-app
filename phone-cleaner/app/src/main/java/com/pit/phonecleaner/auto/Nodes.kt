package com.pit.phonecleaner.auto

import android.view.accessibility.AccessibilityNodeInfo

/** Поиск и нажатие элементов в окне «О приложении» разных прошивок (AOSP, Samsung, MIUI/HyperOS, ColorOS, EMUI…) */
object Nodes {

    /** Пункт «Хранилище» на экране «О приложении» */
    val STORAGE = setOf(
        "хранилище", "хранилище и кэш", "хранилище и кеш", "хранилище и кэш-память", "память",
        "память и кэш", "использование памяти", "использование хранилища", "накопитель", "хранилище данных",
        "storage", "storage & cache", "storage and cache", "storage usage", "storage & memory", "memory & storage",
    )

    /** Кнопка очистки ТОЛЬКО кэша */
    val CLEAR_CACHE = setOf(
        "очистить кэш", "очистить кеш", "очистить кэш-память", "удалить кэш", "удалить кеш",
        "стереть кэш", "стереть кеш", "очистка кэша", "очистка кеша", "очистить кэш приложения",
        "clear cache", "clear cached data", "delete cache", "wipe cache",
    )

    /** MIUI/HyperOS: кнопка «Очистить» открывает меню (Очистить всё / Очистить кэш) — сама ничего не удаляет */
    val MIUI_MENU = setOf("очистить", "очистить данные", "clear data", "clear")
    val MIUI_PACKAGES = setOf("com.miui.securitycenter", "com.miui.cleanmaster")

    /** Подтверждение в диалоге */
    val CONFIRM = setOf("ок", "ok", "да", "yes", "очистить", "удалить", "clear", "delete", "подтвердить", "confirm")

    /** Если окно содержит эти слова — это НЕ очистка кэша, никогда не подтверждаем */
    val DANGER = listOf(
        "все данные", "всех данных", "данные приложения", "данные этого приложения", "удалить данные",
        "очистить хранилище", "стереть данные", "сбросить", "безвозвратно удален",
        "all data", "app data", "app's data", "clear storage", "clear data?", "delete app data",
        "permanently deleted", "files, settings, accounts",
    )

    fun norm(s: CharSequence?): String =
        s?.toString()?.lowercase()?.replace('ё', 'е')?.replace(' ', ' ')
            ?.replace(Regex("\\s+"), " ")?.trim()?.trimEnd('.', '…') ?: ""

    private fun normSet(s: Set<String>) = s.map { norm(it) }.toSet()
    private val storageN = normSet(STORAGE)
    private val clearN = normSet(CLEAR_CACHE)
    private val miuiN = normSet(MIUI_MENU)
    private val confirmN = normSet(CONFIRM)

    fun textOf(n: AccessibilityNodeInfo): String {
        val t = norm(n.text)
        return t.ifEmpty { norm(n.contentDescription) }
    }

    /** Обход дерева в ширину (ограничен, чтобы не зависнуть на огромных окнах) */
    fun all(root: AccessibilityNodeInfo, limit: Int = 3000): List<AccessibilityNodeInfo> {
        val out = ArrayList<AccessibilityNodeInfo>()
        val q = ArrayDeque<AccessibilityNodeInfo>()
        q.add(root)
        while (q.isNotEmpty() && out.size < limit) {
            val n = q.removeFirst()
            out += n
            for (i in 0 until n.childCount) n.getChild(i)?.let { q.add(it) }
        }
        return out
    }

    fun findClearCache(nodes: List<AccessibilityNodeInfo>) = nodes.firstOrNull { textOf(it) in clearN }

    fun findStorage(nodes: List<AccessibilityNodeInfo>) = nodes.firstOrNull {
        val t = textOf(it)
        t in storageN
    }

    fun findMiuiMenu(nodes: List<AccessibilityNodeInfo>) = nodes.firstOrNull { textOf(it) in miuiN }

    fun findConfirm(nodes: List<AccessibilityNodeInfo>) =
        nodes.lastOrNull { textOf(it) in confirmN && (it.isClickable || it.parent?.isClickable == true) }

    fun windowText(nodes: List<AccessibilityNodeInfo>) = nodes.joinToString(" ") { textOf(it) }

    fun isDangerous(text: String) = DANGER.any { text.contains(norm(it)) }

    fun mentionsCache(text: String) = text.contains("кэш") || text.contains("кеш") || text.contains("cache")

    /** Нажать сам элемент или ближайшего кликабельного родителя */
    fun click(n: AccessibilityNodeInfo): Boolean {
        var x: AccessibilityNodeInfo? = n
        repeat(7) {
            val c = x ?: return false
            if (c.isClickable) return c.isEnabled && c.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            x = c.parent
        }
        return false
    }

    /** Включена ли кнопка (учитывая кликабельного родителя) */
    fun isEnabled(n: AccessibilityNodeInfo): Boolean {
        var x: AccessibilityNodeInfo? = n
        repeat(7) {
            val c = x ?: return n.isEnabled
            if (c.isClickable) return c.isEnabled && n.isEnabled
            x = c.parent
        }
        return n.isEnabled
    }

    fun scrollForward(nodes: List<AccessibilityNodeInfo>): Boolean =
        nodes.firstOrNull { it.isScrollable }?.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD) ?: false
}
