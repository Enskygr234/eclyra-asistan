package com.enskygr234.eclyra

import android.Manifest
import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject

data class Reminder(val id: Int, val at: Long, val text: String)

/** Eclyra'nın kendi hatırlatmaları: AlarmManager ile kurulur, telefon yeniden başlayınca tekrar kurulur. */
object Reminders {
    private const val KEY = "reminders"

    private fun sp(ctx: Context) = ctx.getSharedPreferences("eclyra_reminders", Context.MODE_PRIVATE)

    fun all(ctx: Context): List<Reminder> {
        val raw = sp(ctx).getString(KEY, "[]") ?: "[]"
        return try {
            val a = JSONArray(raw)
            List(a.length()) {
                val o = a.getJSONObject(it)
                Reminder(o.getInt("id"), o.getLong("at"), o.getString("text"))
            }.sortedBy { it.at }
        } catch (e: Exception) {
            emptyList()
        }
    }

    private fun save(ctx: Context, list: List<Reminder>) {
        val a = JSONArray()
        list.forEach { a.put(JSONObject().put("id", it.id).put("at", it.at).put("text", it.text)) }
        sp(ctx).edit().putString(KEY, a.toString()).apply()
    }

    fun add(ctx: Context, at: Long, text: String): Reminder {
        val list = all(ctx)
        val id = (list.maxOfOrNull { it.id } ?: 0) + 1
        val r = Reminder(id, at, text)
        save(ctx, list + r)
        schedule(ctx, r)
        return r
    }

    fun remove(ctx: Context, id: Int): Boolean {
        val list = all(ctx)
        val r = list.find { it.id == id } ?: return false
        save(ctx, list - r)
        ctx.getSystemService(AlarmManager::class.java).cancel(pending(ctx, r))
        return true
    }

    private fun pending(ctx: Context, r: Reminder): PendingIntent {
        val i = Intent(ctx, ReminderReceiver::class.java)
            .putExtra("id", r.id)
            .putExtra("text", r.text)
        return PendingIntent.getBroadcast(
            ctx, r.id, i,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    fun schedule(ctx: Context, r: Reminder) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        val pi = pending(ctx, r)
        val exact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
        try {
            if (exact) am.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, pi)
            else am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, pi)
        } catch (e: SecurityException) {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, r.at, pi)
        }
    }

    fun rescheduleAll(ctx: Context) {
        val now = System.currentTimeMillis()
        val list = all(ctx)
        list.forEach {
            if (it.at > now) schedule(ctx, it)
            else ReminderReceiver.notify(ctx, it.id, it.text)
        }
        save(ctx, list.filter { it.at > now })
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val id = intent.getIntExtra("id", 0)
        val text = intent.getStringExtra("text") ?: "Hatırlatma"
        notify(ctx, id, text)
        Reminders.remove(ctx, id) // çaldı, listeden çıkar
    }

    companion object {
        fun notify(ctx: Context, id: Int, text: String) {
            if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
            ) return
            val open = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = NotificationCompat.Builder(ctx, App.CH_REMINDER)
                .setSmallIcon(R.drawable.ic_notif)
                .setContentTitle("Eclyra hatırlatıyor")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_REMINDER)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setAutoCancel(true)
                .setContentIntent(open)
                .build()
            try {
                NotificationManagerCompat.from(ctx).notify(1000 + id, n)
            } catch (e: SecurityException) {
                // bildirim izni yok
            }
        }
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        Reminders.rescheduleAll(ctx)
    }
}
