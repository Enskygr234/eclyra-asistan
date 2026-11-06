package com.enskygr234.eclyra

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.content.ComponentName
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/** Konuşmayı yazıya (SpeechRecognizer) ve yazıyı konuşmaya (TextToSpeech) çeviren katman. */
class VoiceIO(private val ctx: Context, private val cb: Callback) {

    interface Callback {
        fun onListenStart()
        fun onRms(level: Float)
        fun onPartial(text: String)
        fun onFinal(text: String)
        fun onListenEnd(error: String?)
        fun onSpeakStart()
        fun onSpeakDone()
        /** Arka plan ses tanıma çalışmadı; Google'ın ses penceresiyle denenmeli. */
        fun onNeedDialog()
        /** Kullanıcıya kısa bilgi (ör. doğal ses kullanılamadı). */
        fun onNotice(msg: String)
    }

    private val main = Handler(Looper.getMainLooper())
    private val player = PcmPlayer()

    @Volatile
    private var speakGen = 0
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var pendingSpeech: Pair<String, Float>? = null
    var listening = false
        private set

    init {
        tts = TextToSpeech(ctx.applicationContext) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.setLanguage(Locale("tr", "TR"))
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        main.post { cb.onSpeakStart() }
                    }

                    override fun onDone(utteranceId: String?) {
                        if (utteranceId == LAST) main.post { cb.onSpeakDone() }
                    }

                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) {
                        main.post { cb.onSpeakDone() }
                    }

                    override fun onStop(utteranceId: String?, interrupted: Boolean) {
                        main.post { cb.onSpeakDone() }
                    }
                })
                ttsReady = true
                pendingSpeech?.let { (t, r) -> speak(t, r) }
                pendingSpeech = null
            }
        }
    }

    fun available() = SpeechRecognizer.isRecognitionAvailable(ctx)

    /** Türkçeyi en iyi tanıyan Google hizmetini tercih et (Samsung/Xiaomi varsayılanları çoğu zaman Türkçe desteklemiyor). */
    private fun preferredService(): ComponentName? = try {
        @Suppress("DEPRECATION")
        val list = ctx.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
        val order = listOf("com.google.android.googlequicksearchbox", "com.google.android.tts", "com.google.android.as")
        val best = order.firstNotNullOfOrNull { pkg -> list.firstOrNull { it.serviceInfo.packageName == pkg } }
        best?.let { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
    } catch (e: Exception) {
        null
    }

    private var failures = 0

    fun startListening() = main.post {
        stopSpeaking()
        if (!available()) {
            cb.onListenEnd(null)
            cb.onNeedDialog()
            return@post
        }
        if (recognizer == null) {
            val comp = preferredService()
            recognizer = (if (comp != null) SpeechRecognizer.createSpeechRecognizer(ctx, comp)
            else SpeechRecognizer.createSpeechRecognizer(ctx)).also { it.setRecognitionListener(listener) }
        }
        val i = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "tr-TR")
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "tr-TR")
            .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            .putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500L)
        listening = true
        cb.onListenStart()
        try {
            recognizer?.startListening(i)
        } catch (e: Exception) {
            listening = false
            cb.onListenEnd(null)
            cb.onNeedDialog()
        }
    }

    fun stopListening() = main.post {
        if (listening) recognizer?.stopListening()
    }

    fun cancelListening() = main.post {
        if (listening) {
            recognizer?.cancel()
            listening = false
            cb.onListenEnd(null)
        }
    }

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {
            cb.onRms(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))
        }

        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            listening = false
            val quiet = error == SpeechRecognizer.ERROR_NO_MATCH || error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            val msg = when {
                quiet -> "Seni duyamadım, küreye dokunup tekrar konuş"
                error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mikrofon izni yok"
                error == SpeechRecognizer.ERROR_NETWORK || error == SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                    "Ses tanıma için internet gerekiyor"
                else -> null
            }
            recognizer?.destroy()
            recognizer = null
            if (quiet || msg != null) {
                failures = 0
                cb.onListenEnd(msg)
                return
            }
            // Dil desteklenmiyor (12/13), istemci/sunucu hatası vb.: Google ses penceresine geç
            failures++
            cb.onListenEnd(null)
            cb.onNeedDialog()
        }

        override fun onResults(results: Bundle?) {
            listening = false
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (text.isBlank()) cb.onListenEnd("Seni duyamadım, tekrar dener misin?") else {
                failures = 0
                cb.onFinal(text)
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val text = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (text.isNotBlank()) cb.onPartial(text)
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    fun speak(text: String, rate: Float) {
        val t = cleanForSpeech(text)
        if (t.isBlank()) {
            main.post { cb.onSpeakDone() }
            return
        }
        if (!ttsReady) {
            pendingSpeech = text to rate
            return
        }
        val engine = tts ?: return
        engine.setSpeechRate(rate)
        // Uzun metni cümle cümle sıraya koy (motorların uzunluk sınırı var)
        val chunks = splitSentences(t)
        chunks.forEachIndexed { idx, c ->
            val id = if (idx == chunks.lastIndex) LAST else "c$idx"
            engine.speak(c, if (idx == 0) TextToSpeech.QUEUE_FLUSH else TextToSpeech.QUEUE_ADD, null, id)
        }
    }

    /** Gemini'nin doğal sesiyle konuş; olmazsa telefonun sesine dön. */
    fun speakNatural(text: String, rate: Float, apiKey: String, voice: String) {
        val t = cleanForSpeech(text)
        if (t.isBlank()) {
            main.post { cb.onSpeakDone() }
            return
        }
        stopSpeaking()
        val gen = speakGen
        kotlin.concurrent.thread(name = "natural-voice") {
            try {
                val audio = NaturalVoice.synth(apiKey, t, voice)
                if (gen != speakGen) return@thread
                main.post { cb.onSpeakStart() }
                player.playBlocking(audio, rate)
                if (gen == speakGen) main.post { cb.onSpeakDone() }
            } catch (e: Exception) {
                if (gen != speakGen) return@thread
                main.post {
                    cb.onNotice("Doğal ses şu an kullanılamadı, telefonun sesiyle konuşuyorum. (${Gemini.friendly(e)})")
                    speak(text, rate)
                }
            }
        }
    }

    fun isSpeaking() = tts?.isSpeaking == true || player.playing

    fun stopSpeaking() {
        speakGen++
        player.stop()
        if (tts?.isSpeaking == true) tts?.stop()
    }

    fun release() {
        main.post {
            recognizer?.destroy()
            recognizer = null
        }
        tts?.stop()
        tts?.shutdown()
        tts = null
    }

    companion object {
        private const val LAST = "last"

        /** Markdown, emoji ve formülleri sesli okumaya uygun hale getirir; çok uzunsa kısaltır. */
        fun cleanForSpeech(src: String): String {
            var s = src
            s = s.replace(Regex("```[\\s\\S]*?```"), " ")
            s = s.replace(Regex("\\$\\$[\\s\\S]*?\\$\\$"), " ")
            s = s.replace(Regex("\\$([^$]+)\\$"), "$1")
            s = s.replace(Regex("!?\\[([^\\]]*)]\\([^)]*\\)"), "$1")
            s = s.replace(Regex("https?://\\S+"), "")
            s = s.replace(Regex("[*_#`>|~]"), "")
            s = s.replace(Regex("\\\\[a-zA-Z]+"), " ")
            s = s.replace(Regex("[\\p{So}\\p{Cn}\\x{1F000}-\\x{1FFFF}]"), "")
            s = s.replace(Regex("^\\s*[-•]\\s+", RegexOption.MULTILINE), "")
            s = s.replace(Regex("\\s+"), " ").trim()
            if (s.length > 600) {
                val cut = s.take(600)
                val end = maxOf(cut.lastIndexOf(". "), cut.lastIndexOf("? "), cut.lastIndexOf("! "))
                s = (if (end > 200) cut.take(end + 1) else cut) + " Devamı ekranda."
            }
            return s
        }

        private fun splitSentences(s: String): List<String> {
            val out = mutableListOf<String>()
            val sb = StringBuilder()
            for (part in s.split(Regex("(?<=[.!?])\\s+"))) {
                if (sb.length + part.length > 350 && sb.isNotEmpty()) {
                    out += sb.toString(); sb.clear()
                }
                if (sb.isNotEmpty()) sb.append(' ')
                sb.append(part)
            }
            if (sb.isNotEmpty()) out += sb.toString()
            return out
        }
    }
}
