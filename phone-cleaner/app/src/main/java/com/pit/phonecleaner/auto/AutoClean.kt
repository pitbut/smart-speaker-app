package com.pit.phonecleaner.auto

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Состояние автоматической очистки (общая точка между UI и службой) */
object AutoClean {
    data class State(
        val running: Boolean = false,
        val total: Int = 0,
        val done: Int = 0,
        val currentLabel: String? = null,
        val success: Int = 0,
        val failed: Int = 0,
        val finished: Boolean = false,
        val stoppedByUser: Boolean = false,
        val processed: List<String> = emptyList(),
    )

    internal val mutable = MutableStateFlow(State())
    val state: StateFlow<State> = mutable

    val serviceConnected get() = CleanAccessibilityService.instance != null

    /** items: пары (пакет, название) */
    fun start(items: List<Pair<String, String>>): Boolean {
        val s = CleanAccessibilityService.instance ?: return false
        if (items.isEmpty()) return false
        s.startJob(items)
        return true
    }

    fun stop() {
        CleanAccessibilityService.instance?.stopJob(byUser = true)
    }

    fun consume() {
        if (!mutable.value.running) mutable.value = State()
    }
}
