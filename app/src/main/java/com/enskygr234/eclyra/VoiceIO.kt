package com.enskygr234.eclyra

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
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
    }

    private val main = Handler(Looper.getMainLooper())
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

    fun startListening() = main.post {
        stopSpeaking()
        if (!available()) {
            cb.onListenEnd("Bu telefonda ses tanıma hizmeti yok. Google uygulamasını yükleyip güncelle.")
            return@post
        }
        if (recognizer == null) {
            recognizer = SpeechRecognizer.createSpeechRecognizer(ctx).also { it.setRecognitionListener(listener) }
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
            cb.onListenEnd("Mikrofon başlatılamadı")
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
            val msg = when (error) {
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> null
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Mikrofon izni yok"
                SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "Ses tanıma için internet gerekiyor"
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "Mikrofon meşgul, tekrar dene"
                SpeechRecognizer.ERROR_CLIENT -> null
                else -> "Seni duyamadım (hata $error)"
            }
            if (error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == SpeechRecognizer.ERROR_CLIENT) {
                recognizer?.destroy()
                recognizer = null
            }
            cb.onListenEnd(msg)
        }

        override fun onResults(results: Bundle?) {
            listening = false
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
            if (text.isBlank()) cb.onListenEnd(null) else cb.onFinal(text)
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

    fun isSpeaking() = tts?.isSpeaking == true

    fun stopSpeaking() {
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
