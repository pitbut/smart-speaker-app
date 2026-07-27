package com.pit.smartspeaker

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Handler
import android.os.Looper
import java.util.Calendar
import java.util.regex.Pattern

/**
 * Parses simple Russian phrases for timers ("таймер на 5 минут") and
 * alarms ("будильник на 7:30" / "разбуди в 7 утра").
 */
object TimeCommands {

    private val handler = Handler(Looper.getMainLooper())
    private val numberPattern = Pattern.compile("(\\d+)")
    private val clockPattern = Pattern.compile("(\\d{1,2})[:. ](\\d{2})")

    /** Returns a spoken confirmation, or null if the text didn't match a timer/alarm command. */
    fun tryHandle(context: Context, text: String, onFire: (String) -> Unit): String? {
        if (text.contains("таймер")) {
            return handleTimer(text, onFire)
        }
        if (text.contains("будильник") || text.contains("разбуди")) {
            return handleAlarm(context, text)
        }
        return null
    }

    private fun handleTimer(text: String, onFire: (String) -> Unit): String {
        val matcher = numberPattern.matcher(text)
        if (!matcher.find()) {
            return "На сколько поставить таймер? Скажи, например: таймер на 5 минут"
        }
        val amount = matcher.group(1)!!.toInt()
        val seconds = when {
            text.contains("час") -> amount * 3600
            text.contains("секунд") -> amount
            else -> amount * 60 // default: minutes
        }

        handler.postDelayed({
            onFire("Время вышло! Таймер на ${describeDuration(amount, text)} завершён")
        }, seconds * 1000L)

        return "Ставлю таймер на ${describeDuration(amount, text)}"
    }

    private fun describeDuration(amount: Int, text: String): String = when {
        text.contains("час") -> "$amount ч."
        text.contains("секунд") -> "$amount сек."
        else -> "$amount мин."
    }

    private fun handleAlarm(context: Context, text: String): String {
        val matcher = clockPattern.matcher(text)
        val hour: Int
        val minute: Int

        if (matcher.find()) {
            hour = matcher.group(1)!!.toInt()
            minute = matcher.group(2)!!.toInt()
        } else {
            val numMatcher = numberPattern.matcher(text)
            if (!numMatcher.find()) {
                return "На какое время поставить будильник? Скажи, например: будильник на 7:30"
            }
            hour = numMatcher.group(1)!!.toInt()
            minute = 0
        }

        if (hour !in 0..23 || minute !in 0..59) {
            return "Не понял время будильника"
        }

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            if (before(Calendar.getInstance())) {
                add(Calendar.DAY_OF_MONTH, 1)
            }
        }

        val intent = Intent(context, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context, calendar.timeInMillis.toInt(), intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        try {
            alarmManager.setExactAndAllow(
                AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent
            )
        } catch (e: SecurityException) {
            alarmManager.set(AlarmManager.RTC_WAKEUP, calendar.timeInMillis, pendingIntent)
        }

        val timeStr = String.format("%02d:%02d", hour, minute)
        return "Ставлю будильник на $timeStr"
    }

    private fun AlarmManager.setExactAndAllow(type: Int, triggerAtMillis: Long, pendingIntent: PendingIntent) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
            if (canScheduleExactAlarms()) {
                setExactAndAllowWhileIdle(type, triggerAtMillis, pendingIntent)
            } else {
                set(type, triggerAtMillis, pendingIntent)
            }
        } else {
            setExactAndAllowWhileIdle(type, triggerAtMillis, pendingIntent)
        }
    }
}
