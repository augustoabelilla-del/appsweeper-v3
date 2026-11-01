package com.example.appsweeper

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import java.util.Calendar

object Scheduler {

    private fun pendingIntent(ctx: Context, id: Int): PendingIntent {
        val i = Intent(ctx, AlarmReceiver::class.java).putExtra("id", id)
        return PendingIntent.getBroadcast(
            ctx, id, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun nextTrigger(s: Schedule, now: Long = System.currentTimeMillis()): Long {
        if (s.intervalMode) return now + s.intervalMinutes * 60_000L
        val c = Calendar.getInstance().apply {
            timeInMillis = now
            set(Calendar.HOUR_OF_DAY, s.hour)
            set(Calendar.MINUTE, s.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            if (timeInMillis <= now) add(Calendar.DAY_OF_YEAR, 1)
        }
        return c.timeInMillis
    }

    fun schedule(ctx: Context, s: Schedule) {
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val pi = pendingIntent(ctx, s.id)
        if (!s.enabled) {
            am.cancel(pi)
            return
        }
        val at = nextTrigger(s)
        val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        if (canExact) {
            am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        } else {
            // Falls back to an approximate alarm if exact alarms aren't allowed
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }

    fun cancel(ctx: Context, s: Schedule) {
        (ctx.getSystemService(Context.ALARM_SERVICE) as AlarmManager).cancel(pendingIntent(ctx, s.id))
    }

    fun rescheduleAll(ctx: Context) {
        Store(ctx).schedules().forEach { schedule(ctx, it) }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getIntExtra("id", -1)
        val pending = goAsync()
        Thread {
            try {
                val s = Store(context).schedules().firstOrNull { it.id == id }
                if (s != null && s.enabled) {
                    Killer.sweep(context)
                    Scheduler.schedule(context, s) // set up the next run
                }
            } finally {
                pending.finish()
            }
        }.start()
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Scheduler.rescheduleAll(context)
    }
}
