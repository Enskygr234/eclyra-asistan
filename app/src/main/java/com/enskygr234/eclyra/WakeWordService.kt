package com.enskygr234.eclyra

import android.Manifest
import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.concurrent.thread

/** Cihaz üstünde çalışan küçük Türkçe ses modeli (Vosk). İnternete ses göndermez. */
object WakeModel {
    private const val URL_TR = "https://alphacephei.com/vosk/models/vosk-model-small-tr-0.3.zip"

    fun dir(ctx: Context) = File(ctx.filesDir, "vosk-tr")

    fun ready(ctx: Context) = File(dir(ctx), "am").isDirectory && File(dir(ctx), "conf").isDirectory

    /** Modeli indirip açar. progress: 0..1 */
    fun download(ctx: Context, progress: (Float) -> Unit) {
        val tmp = File(ctx.cacheDir, "vosk-tr.zip")
        val c = URL(URL_TR).openConnection() as HttpURLConnection
        c.connectTimeout = 20_000
        c.readTimeout = 60_000
        try {
            val total = c.contentLengthLong.takeIf { it > 0 } ?: 36_000_000L
            c.inputStream.use { input ->
                FileOutputStream(tmp).use { out ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        done += n
                        progress((done.toFloat() / total * 0.9f).coerceAtMost(0.9f))
                    }
                }
            }
        } finally {
            c.disconnect()
        }
        val target = dir(ctx)
        target.deleteRecursively()
        target.mkdirs()
        ZipInputStream(tmp.inputStream().buffered()).use { zip ->
            while (true) {
                val e = zip.nextEntry ?: break
                // ilk klasör adını (vosk-model-small-tr-0.3/) at
                val rel = e.name.substringAfter('/', "")
                if (rel.isEmpty()) continue
                val f = File(target, rel)
                if (!f.canonicalPath.startsWith(target.canonicalPath)) continue
                if (e.isDirectory) f.mkdirs() else {
                    f.parentFile?.mkdirs()
                    FileOutputStream(f).use { zip.copyTo(it) }
                }
            }
        }
        tmp.delete()
        progress(1f)
        if (!ready(ctx)) throw IllegalStateException("Model dosyaları eksik")
    }

    // Küçük Türkçe model "Eclyra"yı sözlüğünde bilmediği için ona en yakın sesleri yazar
    private val STEMS = listOf(
        "eklira", "eklıra", "ekleyra", "eklera", "eklara", "ekilira", "ekiira", "eklir", "klira", "kılıra",
        "eklayra", "ekleyre", "eklere", "heyekl", "heyekil", "hey ek", "eklyra", "eclyra"
    )

    fun isWake(text: String): Boolean {
        val low = text.lowercase(Locale("tr", "TR")).replace(Regex("\\s+"), " ").trim()
        if (low.isEmpty()) return false
        val glued = low.replace(" ", "")
        return STEMS.any { st -> if (st.contains(' ')) " $low ".contains(" $st") else glued.contains(st) }
    }
}

/** "Hey Eclyra" sözünü arka planda dinleyen ön plan servisi. */
class WakeWordService : Service(), RecognitionListener {

    companion object {
        const val ACTION_PAUSE = "com.enskygr234.eclyra.PAUSE"
        const val ACTION_RESUME = "com.enskygr234.eclyra.RESUME"
        const val ACTION_STOP = "com.enskygr234.eclyra.STOP"
        const val EXTRA_WAKE = "from_wake"
        private const val NOTIF_ID = 7
        private const val HIT_ID = 8

        @Volatile
        var running = false
            private set

        /** Uygulama ön plandayken çağrılır: servisi başlatır ama dinlemeyi uygulama kapanana kadar bekletir. */
        fun startPaused(ctx: Context) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, WakeWordService::class.java).setAction(ACTION_PAUSE))
            } catch (_: Exception) {
            }
        }

        fun stop(ctx: Context) {
            ctx.stopService(Intent(ctx, WakeWordService::class.java))
        }

        fun send(ctx: Context, action: String) {
            if (!running) return
            try {
                ctx.startService(Intent(ctx, WakeWordService::class.java).setAction(action))
            } catch (_: Exception) {
            }
        }
    }

    private val main = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var speech: SpeechService? = null
    private var paused = false
    private var loading = false
    private var lastHit = 0L

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            Prefs(this).wakeWord = false
            stopSelf()
            return START_NOT_STICKY
        }
        // startForegroundService ile gelen her istekte (ilk başlatma ya da PAUSE) ön plana geçilmeli
        if (!running || action == null || action == ACTION_PAUSE) {
            if (!goForeground()) {
                stopSelf()
                return START_NOT_STICKY
            }
            running = true
        }
        when (action) {
            ACTION_PAUSE -> {
                paused = true
                stopListening()
            }
            ACTION_RESUME -> {
                paused = false
                startListening()
            }
            else -> if (!paused) startListening()
        }
        return START_NOT_STICKY
    }

    private fun goForeground(): Boolean {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return false
        val stopPi = PendingIntent.getService(
            this, 1, Intent(this, WakeWordService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE
        )
        val openPi = PendingIntent.getActivity(
            this, 2, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        val n: Notification = NotificationCompat.Builder(this, App.CH_WAKE)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("\"Hey Eclyra\" dinleniyor")
            .setContentText("Ses telefonda işlenir, internete gönderilmez.")
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openPi)
            .addAction(0, "Kapat", stopPi)
            .build()
        return try {
            ServiceCompat.startForeground(
                this, NOTIF_ID, n,
                if (Build.VERSION.SDK_INT >= 30) ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE else 0
            )
            true
        } catch (e: Exception) {
            false
        }
    }

    private fun startListening() {
        if (speech != null || loading || paused) return
        if (!WakeModel.ready(this)) {
            stopSelf()
            return
        }
        loading = true
        thread(name = "vosk-load") {
            try {
                val m = model ?: Model(WakeModel.dir(this).absolutePath).also { model = it }
                main.post {
                    loading = false
                    if (paused || !running) return@post
                    try {
                        val rec = Recognizer(m, 16000f)
                        recognizer = rec
                        speech = SpeechService(rec, 16000f).also { it.startListening(this) }
                    } catch (e: Exception) {
                        cleanupRecognizer()
                    }
                }
            } catch (e: Throwable) {
                main.post { loading = false; stopSelf() }
            }
        }
    }

    private fun stopListening() {
        cleanupRecognizer()
    }

    private fun cleanupRecognizer() {
        try {
            speech?.stop()
            speech?.shutdown()
        } catch (_: Exception) {
        }
        speech = null
        try {
            recognizer?.close()
        } catch (_: Exception) {
        }
        recognizer = null
    }

    private fun check(json: String?, key: String) {
        val text = try {
            JSONObject(json ?: return).optString(key)
        } catch (e: Exception) {
            return
        }
        if (text.isBlank()) return
        if (WakeModel.isWake(text)) {
            val now = System.currentTimeMillis()
            if (now - lastHit < 3000) return
            lastHit = now
            onWake()
        }
    }

    private fun onWake() {
        paused = true
        stopListening()
        val i = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(EXTRA_WAKE, true)
        var opened = false
        if (Settings.canDrawOverlays(this)) {
            try {
                startActivity(i)
                opened = true
            } catch (_: Exception) {
            }
        }
        if (!opened) {
            val pi = PendingIntent.getActivity(this, 3, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            val n = NotificationCompat.Builder(this, App.CH_WAKE_HIT)
                .setSmallIcon(R.drawable.ic_mic)
                .setContentTitle("Eclyra seni duydu")
                .setContentText("Konuşmak için dokun")
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_CALL)
                .setAutoCancel(true)
                .setTimeoutAfter(15_000)
                .setContentIntent(pi)
                .setFullScreenIntent(pi, true)
                .build()
            try {
                NotificationManagerCompat.from(this).notify(HIT_ID, n)
            } catch (_: SecurityException) {
            }
            // Kullanıcı açmazsa 15 sn sonra dinlemeye dön
            main.postDelayed({ if (paused && !MainActivity.visible) { paused = false; startListening() } }, 15_000)
        }
    }

    // ---- Vosk geri çağrıları ----
    override fun onPartialResult(hypothesis: String?) = check(hypothesis, "partial")
    override fun onResult(hypothesis: String?) = check(hypothesis, "text")
    override fun onFinalResult(hypothesis: String?) = check(hypothesis, "text")
    override fun onError(exception: Exception?) {
        cleanupRecognizer()
        main.postDelayed({ startListening() }, 2000)
    }

    override fun onTimeout() {
        cleanupRecognizer()
        startListening()
    }

    override fun onDestroy() {
        running = false
        cleanupRecognizer()
        try {
            model?.close()
        } catch (_: Exception) {
        }
        model = null
        super.onDestroy()
    }
}
