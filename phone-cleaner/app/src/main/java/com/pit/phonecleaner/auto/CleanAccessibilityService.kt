package com.pit.phonecleaner.auto

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.pit.phonecleaner.MainActivity

/**
 * Автоматическая очистка «скрытого» кэша. Для каждого выбранного приложения:
 * открывает «О приложении» → «Хранилище» → нажимает «Очистить кэш» → следующее приложение.
 * Поверх экрана показывается панель прогресса с кнопкой «Стоп».
 */
class CleanAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: CleanAccessibilityService? = null
            private set

        private const val PER_APP_TIMEOUT = 9000L
        private const val TICK = 350L
    }

    private enum class Step { STORAGE, CLEAR, CONFIRM }

    private val h = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<Pair<String, String>>()
    private var current: Pair<String, String>? = null
    private var step = Step.STORAGE
    private var pkgStarted = 0L
    private var stepStarted = 0L
    private var scrolls = 0
    private var running = false
    private var total = 0
    private var ok = 0
    private var fail = 0
    private val processed = mutableListOf<String>()

    private var overlay: View? = null
    private var overlayText: TextView? = null
    private var overlayBar: ProgressBar? = null

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: Intent?): Boolean {
        stopJob(byUser = false)
        instance = null
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        stopJob(byUser = false)
        instance = null
        super.onDestroy()
    }

    override fun onInterrupt() {}

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!running) return
        h.removeCallbacks(tick)
        h.postDelayed(tick, 200)
    }

    fun startJob(items: List<Pair<String, String>>) {
        h.post {
            if (running) return@post
            queue.clear()
            queue.addAll(items)
            total = items.size
            ok = 0
            fail = 0
            processed.clear()
            running = true
            showOverlay()
            next()
        }
    }

    fun stopJob(byUser: Boolean) {
        h.post {
            if (!running) return@post
            running = false
            queue.clear()
            h.removeCallbacksAndMessages(null)
            finish(byUser)
        }
    }

    private fun publish(finished: Boolean = false, byUser: Boolean = false) {
        AutoClean.mutable.value = AutoClean.State(
            running = running,
            total = total,
            done = ok + fail,
            currentLabel = current?.second,
            success = ok,
            failed = fail,
            finished = finished,
            stoppedByUser = byUser,
            processed = processed.toList(),
        )
    }

    private fun next() {
        if (!running) return
        val item = queue.removeFirstOrNull()
        if (item == null) {
            running = false
            finish(false)
            return
        }
        current = item
        step = Step.STORAGE
        scrolls = 0
        pkgStarted = SystemClock.uptimeMillis()
        stepStarted = pkgStarted
        publish()
        updateOverlay()
        val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", item.first, null))
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK or
                    Intent.FLAG_ACTIVITY_NO_HISTORY or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
            )
        try {
            startActivity(i)
        } catch (_: Exception) {
            done(false)
            return
        }
        h.postDelayed(tick, 800)
    }

    private val tick = Runnable { process() }

    private fun reschedule() {
        h.removeCallbacks(tick)
        h.postDelayed(tick, TICK)
    }

    private fun process() {
        if (!running) return
        val cur = current ?: return
        val now = SystemClock.uptimeMillis()
        if (now - pkgStarted > PER_APP_TIMEOUT) {
            done(false)
            return
        }
        val root = rootInActiveWindow
        if (root == null || root.packageName?.toString() == packageName) {
            reschedule(); return
        }
        val winPkg = root.packageName?.toString() ?: ""
        val nodes = Nodes.all(root)

        when (step) {
            Step.STORAGE -> {
                // На некоторых прошивках кнопка «Очистить кэш» есть прямо на первом экране
                val clear = Nodes.findClearCache(nodes)
                if (clear != null) {
                    pressClear(clear); return
                }
                val storage = Nodes.findStorage(nodes)
                if (storage != null && Nodes.click(storage)) {
                    step = Step.CLEAR; stepStarted = now; scrolls = 0
                } else if (winPkg in Nodes.MIUI_PACKAGES) {
                    Nodes.findMiuiMenu(nodes)?.let {
                        if (Nodes.click(it)) {
                            step = Step.CLEAR; stepStarted = now
                        }
                    }
                } else if (now - stepStarted > 900 && scrolls < 5) {
                    if (Nodes.scrollForward(nodes)) scrolls++
                    stepStarted = now
                }
            }

            Step.CLEAR -> {
                val clear = Nodes.findClearCache(nodes)
                if (clear != null) {
                    pressClear(clear); return
                }
                if (winPkg in Nodes.MIUI_PACKAGES && now - stepStarted > 700) {
                    Nodes.findMiuiMenu(nodes)?.let { Nodes.click(it); stepStarted = now }
                } else if (now - stepStarted > 1000 && scrolls < 5) {
                    if (Nodes.scrollForward(nodes)) scrolls++
                    stepStarted = now
                }
            }

            Step.CONFIRM -> {
                val text = Nodes.windowText(nodes)
                val confirm = Nodes.findConfirm(nodes)
                if (confirm != null) {
                    val dangerous = Nodes.isDangerous(text)
                    val isDialog = Nodes.findClearCache(nodes) == null
                    if (!dangerous && Nodes.mentionsCache(text)) {
                        // Диалог «Очистить кэш?» (MIUI, ColorOS и др.)
                        Nodes.click(confirm)
                        done(true)
                        return
                    }
                    if (isDialog) {
                        // Это не диалог очистки кэша — закрываем, ничего не подтверждая
                        performGlobalAction(GLOBAL_ACTION_BACK)
                        done(false)
                        return
                    }
                }
                if (now - stepStarted > 1300) {
                    done(true); return
                }
            }
        }
        reschedule()
    }

    private fun pressClear(btn: android.view.accessibility.AccessibilityNodeInfo) {
        if (!Nodes.isEnabled(btn)) {
            // Кнопка неактивна — кэш уже пуст
            done(true); return
        }
        if (Nodes.click(btn)) {
            step = Step.CONFIRM
            stepStarted = SystemClock.uptimeMillis()
        }
        reschedule()
    }

    private fun done(success: Boolean) {
        h.removeCallbacks(tick)
        current?.let { processed += it.first }
        if (success) ok++ else fail++
        current = null
        publish()
        updateOverlay()
        h.postDelayed({ next() }, 250)
    }

    private fun finish(byUser: Boolean) {
        current = null
        removeOverlay()
        publish(finished = true, byUser = byUser)
        try {
            startActivity(
                Intent(this, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            )
        } catch (_: Exception) {
        }
    }

    // ---------- Панель прогресса поверх экрана ----------

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun showOverlay() {
        if (overlay != null) return
        val wm = getSystemService(WindowManager::class.java) ?: return
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = GradientDrawable().apply {
                setColor(Color.parseColor("#EE14532D"))
                cornerRadius = dp(20).toFloat()
            }
        }
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val tv = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 14f
            text = "Очистка кэша…"
        }
        val btn = Button(this).apply {
            text = "Стоп"
            setOnClickListener { stopJob(byUser = true) }
        }
        row.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        row.addView(btn)
        val bar = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
        }
        box.addView(row)
        box.addView(bar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(8)))
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.BOTTOM
            x = 0
            y = dp(24)
            horizontalMargin = 0.03f
        }
        try {
            wm.addView(box, lp)
            overlay = box
            overlayText = tv
            overlayBar = bar
        } catch (_: Exception) {
        }
    }

    private fun updateOverlay() {
        val done = ok + fail
        overlayText?.text = buildString {
            append("Очистка кэша: $done из $total")
            current?.second?.let { append("\n").append(it) }
        }
        overlayBar?.progress = if (total > 0) done * 100 / total else 0
    }

    private fun removeOverlay() {
        val v = overlay ?: return
        try {
            getSystemService(WindowManager::class.java)?.removeView(v)
        } catch (_: Exception) {
        }
        overlay = null
        overlayText = null
        overlayBar = null
    }
}
