package com.enskygr234.eclyra

import android.app.Notification
import android.app.RemoteInput
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import android.speech.tts.TextToSpeech
import androidx.core.app.NotificationManagerCompat
import java.util.Locale

/** Telefona gelen bildirimleri tutar; Eclyra bunları okuyabilir ve mesajlara cevap verebilir. */
class NotifListener : NotificationListenerService() {

    data class Item(
        val id: Int,
        val key: String,
        val pkg: String,
        val app: String,
        val title: String,
        val text: String,
        val time: Long,
        val reply: Notification.Action?
    )

    companion object {
        private val items = ArrayList<Item>()
        private var seq = 0

        @Volatile
        var connected = false
            private set

        val MESSAGING = setOf(
            "com.whatsapp", "com.whatsapp.w4b", "org.telegram.messenger", "org.thunderdog.challegram",
            "com.google.android.apps.messaging", "com.samsung.android.messaging", "com.android.mms",
            "com.instagram.android", "com.facebook.orca", "com.discord", "com.snapchat.android",
            "com.microsoft.teams", "com.Slack", "com.google.android.gm"
        )

        fun hasAccess(ctx: Context) =
            NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

        fun recent(): List<Item> = synchronized(items) { items.toList().asReversed() }

        fun find(id: Int): Item? = synchronized(items) { items.firstOrNull { it.id == id } }

        fun clear() = synchronized(items) { items.clear() }

        /** Bildirimin "Cevapla" düğmesini doldurup gönderir. */
        fun reply(ctx: Context, item: Item, message: String) {
            val action = item.reply ?: throw ToolError("Bu bildirime uygulamadan cevap verilemiyor")
            val inputs = action.remoteInputs ?: throw ToolError("Bu bildirime uygulamadan cevap verilemiyor")
            val intent = Intent()
            val bundle = Bundle()
            inputs.forEach { bundle.putCharSequence(it.resultKey, message) }
            RemoteInput.addResultsToIntent(inputs, intent, bundle)
            try {
                action.actionIntent.send(ctx, 0, intent)
            } catch (e: Exception) {
                throw ToolError("Cevap gönderilemedi, bildirim kaybolmuş olabilir")
            }
        }
    }

    private var tts: TextToSpeech? = null
    private var ttsReady = false

    override fun onListenerConnected() {
        connected = true
        try {
            activeNotifications?.forEach { add(it, announce = false) }
        } catch (_: Exception) {
        }
    }

    override fun onListenerDisconnected() {
        connected = false
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        add(sbn, announce = true)
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        // Cevaplanan/okunan bildirim kalkınca listeden de çıkarmıyoruz; kullanıcı "son mesajlar" diye sorabilir.
    }

    private fun appName(pkg: String) = try {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    } catch (e: PackageManager.NameNotFoundException) {
        pkg
    }

    private fun add(sbn: StatusBarNotification, announce: Boolean) {
        if (sbn.packageName == packageName) return
        val n = sbn.notification ?: return
        if (sbn.isOngoing) return
        if (n.flags and Notification.FLAG_GROUP_SUMMARY != 0) return
        val ex = n.extras ?: return
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString()?.trim().orEmpty()
        var text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex.getCharSequence(Notification.EXTRA_TEXT))
            ?.toString()?.trim().orEmpty()
        // Mesajlaşma bildirimlerinde son mesajı al
        @Suppress("DEPRECATION")
        val msgs = ex.getParcelableArray(Notification.EXTRA_MESSAGES)
        if (msgs != null && msgs.isNotEmpty()) {
            (msgs.last() as? Bundle)?.getCharSequence("text")?.toString()?.let { text = it }
        }
        if (title.isEmpty() && text.isEmpty()) return

        val reply = n.actions?.firstOrNull { a ->
            a.remoteInputs?.any { it.allowFreeFormInput } == true
        }
        val item: Item
        synchronized(items) {
            // aynı içerik tekrar gelirse yinele
            if (items.any { it.key == sbn.key && it.text == text && it.title == title }) return
            item = Item(++seq, sbn.key, sbn.packageName, appName(sbn.packageName), title, text, sbn.postTime, reply)
            items.add(item)
            while (items.size > 60) items.removeAt(0)
        }

        if (announce && sbn.packageName in MESSAGING && Prefs(this).announceMessages && !MainActivity.visible) {
            speak("${item.app}, ${item.title}: ${item.text.take(200)}")
        }
    }

    private fun speak(text: String) {
        val t = tts
        if (t != null && ttsReady) {
            t.speak(text, TextToSpeech.QUEUE_ADD, null, "n${System.nanoTime()}")
            return
        }
        tts = TextToSpeech(applicationContext) { st ->
            if (st == TextToSpeech.SUCCESS) {
                tts?.setLanguage(Locale("tr", "TR"))
                ttsReady = true
                tts?.speak(text, TextToSpeech.QUEUE_ADD, null, "n0")
            }
        }
    }

    override fun onDestroy() {
        tts?.shutdown()
        tts = null
        super.onDestroy()
    }
}
