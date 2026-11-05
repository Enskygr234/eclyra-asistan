package com.enskygr234.eclyra

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_REMINDER, "Hatırlatmalar", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Eclyra'ya kurdurduğun hatırlatmalar"
                enableVibration(true)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_WAKE, "\"Hey Eclyra\" dinleme", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Uyandırma sözü açıkken görünen kalıcı bildirim"
                setShowBadge(false)
            }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_WAKE_HIT, "Eclyra çağrıldı", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "\"Hey Eclyra\" duyulunca asistanı açan bildirim"
            }
        )
    }

    companion object {
        const val CH_REMINDER = "reminders"
        const val CH_WAKE = "wake"
        const val CH_WAKE_HIT = "wake_hit"
    }
}
