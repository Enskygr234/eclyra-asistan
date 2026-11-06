package com.enskygr234.eclyra

import android.app.Application
import android.graphics.Bitmap
import android.util.Base64
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class Phase { IDLE, LISTENING, THINKING, SPEAKING }
enum class Role { USER, ASSISTANT, ACTION, ERROR }

data class Msg(val id: Long, val role: Role, val text: String, val image: Bitmap? = null)

class AssistantViewModel(app: Application) : AndroidViewModel(app), VoiceIO.Callback {

    val prefs = Prefs(app)
    private val tools = Tools(app, prefs)
    private val voice = VoiceIO(app, this)

    val messages = mutableStateListOf<Msg>()
    var phase by mutableStateOf(Phase.IDLE)
        private set
    var level by mutableStateOf(0f)
        private set
    var partial by mutableStateOf("")
        private set
    var pendingImage by mutableStateOf<Bitmap?>(null)
    var hint by mutableStateOf<String?>(null)
        private set

    /** MainActivity tarafından ayarlanır: Google ses penceresini açar. */
    var onDialogNeeded: (() -> Unit)? = null

    private val history = mutableListOf<JSONObject>()
    private var job: Job? = null
    private var idSeq = 0L
    private var voiceTurn = false

    private fun add(role: Role, text: String, image: Bitmap? = null) {
        messages += Msg(++idSeq, role, text, image)
    }

    // ---------- dış arayüz ----------

    fun hasKey() = prefs.apiKey.isNotBlank()

    fun toggleMic() {
        when (phase) {
            Phase.LISTENING -> voice.stopListening()
            Phase.SPEAKING -> {
                voice.stopSpeaking()
                phase = Phase.IDLE
                listen()
            }
            Phase.THINKING -> stopAll()
            Phase.IDLE -> listen()
        }
    }

    fun startListening() {
        if (phase == Phase.THINKING) return
        listen()
    }

    private fun listen() {
        val dialog = onDialogNeeded
        if (prefs.useDialog && dialog != null) {
            voice.stopSpeaking()
            phase = Phase.IDLE
            dialog()
        } else voice.startListening()
    }

    /** Google ses penceresinden gelen sonuç. */
    fun onDialogResult(text: String?) {
        if (text.isNullOrBlank()) {
            hint = "Seni duyamadım, tekrar dener misin?"
            return
        }
        hint = null
        voiceTurn = true
        submit(text, pendingImage)
    }

    override fun onNeedDialog() {
        // Telefonun arka plan tanıması çalışmadı: bundan sonra Google penceresini kullan
        prefs.useDialog = true
        onDialogNeeded?.invoke()
    }

    fun stopAll() {
        job?.cancel()
        voice.cancelListening()
        voice.stopSpeaking()
        phase = Phase.IDLE
        level = 0f
        partial = ""
    }

    /** Uygulama arka plana geçince sadece mikrofonu bırak; düşünme/konuşma sürsün. */
    fun stopListeningOnly() {
        if (phase == Phase.LISTENING) voice.cancelListening()
    }

    fun sendText(text: String) {
        val t = text.trim()
        val img = pendingImage
        if (t.isEmpty() && img == null) return
        voiceTurn = false
        submit(t, img)
    }

    // ---------- "Hey Eclyra" ----------

    var wakeProgress by mutableStateOf<Float?>(null)
        private set
    var wakeError by mutableStateOf<String?>(null)
        private set
    var wakeOn by mutableStateOf(prefs.wakeWord)
        private set

    fun setWake(on: Boolean) {
        val ctx = getApplication<Application>()
        wakeError = null
        if (!on) {
            prefs.wakeWord = false
            wakeOn = false
            WakeWordService.stop(ctx)
            return
        }
        prefs.wakeWord = true
        wakeOn = true
        if (WakeModel.ready(ctx)) {
            WakeWordService.startPaused(ctx)
            return
        }
        viewModelScope.launch {
            wakeProgress = 0f
            try {
                withContext(Dispatchers.IO) {
                    var lastShown = 0f
                    WakeModel.download(ctx) { p ->
                        if (p - lastShown >= 0.01f || p >= 1f) {
                            lastShown = p
                            wakeProgress = p
                        }
                    }
                }
                WakeWordService.startPaused(ctx)
            } catch (e: Exception) {
                wakeError = "Ses modeli indirilemedi: ${e.message ?: "bağlantı hatası"}"
                prefs.wakeWord = false
                wakeOn = false
            } finally {
                wakeProgress = null
            }
        }
    }

    // ---------- uyandırma sözü testi ----------

    var testing by mutableStateOf(false)
        private set
    var testHeard by mutableStateOf("")
        private set
    var testHit by mutableStateOf(false)
        private set
    private var testModel: Model? = null
    private var testRec: Recognizer? = null
    private var testSpeech: SpeechService? = null

    fun startWakeTest() {
        val ctx = getApplication<Application>()
        if (testing || !WakeModel.ready(ctx)) return
        voice.cancelListening()
        testing = true
        testHit = false
        testHeard = "Ses modeli yükleniyor…"
        viewModelScope.launch {
            try {
                val m = withContext(Dispatchers.IO) {
                    testModel ?: Model(WakeModel.dir(ctx).absolutePath).also { testModel = it }
                }
                if (!testing) return@launch
                val rec = Recognizer(m, 16000f)
                testRec = rec
                testSpeech = SpeechService(rec, 16000f).also { it.startListening(testListener) }
                testHeard = "Şimdi \"Hey Eclyra\" de…"
            } catch (e: Throwable) {
                testHeard = "Test başlatılamadı: ${e.message}"
                stopWakeTest()
            }
        }
    }

    fun stopWakeTest() {
        testing = false
        try {
            testSpeech?.stop()
            testSpeech?.shutdown()
        } catch (_: Exception) {
        }
        testSpeech = null
        try {
            testRec?.close()
        } catch (_: Exception) {
        }
        testRec = null
    }

    private fun onTestText(json: String?, key: String) {
        val t = try {
            JSONObject(json ?: return).optString(key)
        } catch (e: Exception) {
            return
        }
        if (t.isBlank()) return
        testHeard = t
        if (WakeModel.isWake(t)) testHit = true
    }

    private val testListener = object : RecognitionListener {
        override fun onPartialResult(hypothesis: String?) = onTestText(hypothesis, "partial")
        override fun onResult(hypothesis: String?) = onTestText(hypothesis, "text")
        override fun onFinalResult(hypothesis: String?) = onTestText(hypothesis, "text")
        override fun onError(exception: Exception?) {
            testHeard = "Mikrofon hatası: ${exception?.message}"
            stopWakeTest()
        }

        override fun onTimeout() = stopWakeTest()
    }

    // ---------- sohbet geçmişi ----------

    private var chatId: String? = null
    var chatList by mutableStateOf(listOf<ChatMeta>())
        private set

    fun refreshChats() {
        chatList = ChatStore.list(getApplication())
    }

    private fun saveChat() {
        val id = chatId ?: return
        try {
            ChatStore.save(getApplication(), id, messages.toList(), history.toList())
        } catch (_: Exception) {
        }
    }

    fun newChat() {
        stopAll()
        saveChat()
        messages.clear()
        history.clear()
        pendingImage = null
        hint = null
        chatId = null
    }

    fun openChat(id: String) {
        stopAll()
        saveChat()
        val data = ChatStore.load(getApplication(), id) ?: return
        messages.clear()
        messages.addAll(data.first)
        history.clear()
        history.addAll(data.second)
        idSeq = (messages.maxOfOrNull { it.id } ?: 0L)
        chatId = id
        hint = null
    }

    fun deleteChat(id: String) {
        ChatStore.delete(getApplication(), id)
        if (id == chatId) {
            chatId = null
            newChat()
        }
        refreshChats()
    }

    fun deleteAllChats() {
        ChatStore.deleteAll(getApplication())
        chatId = null
        newChat()
        refreshChats()
    }

    fun clearChat() = newChat()

    // ---------- VoiceIO geri çağrıları ----------

    override fun onListenStart() {
        phase = Phase.LISTENING
        partial = ""
        hint = null
    }

    override fun onRms(level: Float) {
        this.level = this.level * 0.6f + level * 0.4f
    }

    override fun onPartial(text: String) {
        partial = text
    }

    override fun onFinal(text: String) {
        partial = ""
        level = 0f
        voiceTurn = true
        submit(text, pendingImage)
    }

    override fun onListenEnd(error: String?) {
        if (phase == Phase.LISTENING) phase = Phase.IDLE
        level = 0f
        partial = ""
        if (error != null) hint = error
    }

    override fun onSpeakStart() {
        if (phase != Phase.THINKING) phase = Phase.SPEAKING
    }

    override fun onSpeakDone() {
        if (phase == Phase.SPEAKING) {
            phase = Phase.IDLE
            val last = messages.lastOrNull { it.role == Role.ASSISTANT }?.text?.trim().orEmpty()
            if (voiceTurn && prefs.autoContinue && last.endsWith("?")) listen()
        }
    }

    // ---------- sohbet döngüsü ----------

    private fun submit(text: String, image: Bitmap?) {
        if (!hasKey()) {
            add(Role.ERROR, "Önce ayarlardan Gemini API anahtarını gir.")
            return
        }
        pendingImage = null
        if (chatId == null) chatId = ChatStore.newId()
        val shown = text.ifEmpty { "Bu soruyu adım adım çöz." }
        add(Role.USER, shown, image)

        val parts = JSONArray()
        if (image != null) {
            parts.put(JSONObject().put("inline_data", JSONObject().put("mime_type", "image/jpeg").put("data", toB64(image))))
        }
        parts.put(JSONObject().put("text", shown))
        history += JSONObject().put("role", "user").put("parts", parts)

        job?.cancel()
        job = viewModelScope.launch { runTurn() }
    }

    private suspend fun runTurn() {
        phase = Phase.THINKING
        var finalText = ""
        try {
            for (step in 0 until 8) {
                val resp = withContext(Dispatchers.IO) { callModel() }
                val cand = resp.optJSONArray("candidates")?.optJSONObject(0)
                if (cand == null) {
                    val reason = resp.optJSONObject("promptFeedback")?.optString("blockReason")
                    throw GeminiException("Cevap gelmedi" + if (!reason.isNullOrBlank()) " ($reason)" else "", 0)
                }
                val content = cand.optJSONObject("content") ?: JSONObject().put("parts", JSONArray())
                content.put("role", "model")
                if (!content.has("parts")) content.put("parts", JSONArray())
                history += content

                val parts = content.getJSONArray("parts")
                val texts = StringBuilder()
                val calls = mutableListOf<JSONObject>()
                for (k in 0 until parts.length()) {
                    val p = parts.getJSONObject(k)
                    if (p.optBoolean("thought", false)) continue
                    p.optJSONObject("functionCall")?.let { calls += it }
                    if (p.has("text")) texts.append(p.getString("text"))
                }
                val t = texts.toString().trim()
                if (t.isNotEmpty()) {
                    add(Role.ASSISTANT, t)
                    finalText = t
                }
                if (calls.isEmpty()) {
                    if (t.isEmpty() && cand.optString("finishReason") == "SAFETY") {
                        add(Role.ERROR, "Bu isteğe cevap veremedim.")
                    }
                    break
                }

                val responses = JSONArray()
                for (c in calls) {
                    val name = c.optString("name")
                    val args = c.optJSONObject("args") ?: JSONObject()
                    val r = tools.run(name, args)
                    r.note?.let { add(Role.ACTION, it) }
                    val fr = JSONObject().put("name", name).put("response", r.data)
                    if (c.has("id")) fr.put("id", c.getString("id"))
                    responses.put(JSONObject().put("functionResponse", fr))
                }
                history += JSONObject().put("role", "user").put("parts", responses)
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Throwable) {
            add(Role.ERROR, Gemini.friendly(e))
            // başarısız turu geçmişten temizle ki sonraki istekler bozulmasın
            trimBrokenTail()
        } finally {
            compactHistory()
            saveChat()
            if (phase == Phase.THINKING) phase = Phase.IDLE
        }

        if (finalText.isNotEmpty() && (voiceTurn || prefs.speakTyped)) {
            phase = Phase.SPEAKING
            speak(finalText)
        }
    }

    private fun speak(text: String) {
        if (prefs.naturalVoice && prefs.apiKey.isNotBlank()) {
            voice.speakNatural(text, prefs.speechRate, prefs.apiKey, prefs.voiceName)
        } else voice.speak(text, prefs.speechRate)
    }

    /** Ayarlarda sesi denemek için. */
    fun previewVoice() {
        voiceTurn = false
        phase = Phase.SPEAKING
        val name = prefs.userName.ifBlank { null }
        speak("Merhaba${if (name != null) " $name" else ""}! Ben Eclyra. Sesim böyle, beğendin mi?")
    }

    override fun onNotice(msg: String) {
        hint = msg
    }

    private fun callModel(): JSONObject {
        val contents = JSONArray()
        history.forEach { contents.put(it) }
        return Gemini.generateAuto(prefs, systemPrompt(), contents, tools.declarations())
    }

    private fun trimBrokenTail() {
        // Sonda kalan cevapsız functionCall ya da tek başına kullanıcı mesajını at
        while (history.isNotEmpty()) {
            val last = history.last()
            val parts = last.optJSONArray("parts") ?: JSONArray()
            val hasCall = (0 until parts.length()).any { parts.getJSONObject(it).has("functionCall") }
            if (last.optString("role") == "model" && hasCall) history.removeAt(history.lastIndex)
            else if (last.optString("role") == "user") history.removeAt(history.lastIndex)
            else break
        }
    }

    private fun compactHistory() {
        // Eski görselleri metinle değiştir (her istekte tekrar gönderilmesin)
        for (idx in 0 until (history.size - 2).coerceAtLeast(0)) {
            val parts = history[idx].optJSONArray("parts") ?: continue
            for (k in 0 until parts.length()) {
                val p = parts.getJSONObject(k)
                if (p.has("inline_data")) {
                    p.remove("inline_data")
                    p.put("text", "[daha önce gönderilen görsel]")
                }
            }
        }
        // En fazla ~40 kayıt tut; kesme noktası düz bir kullanıcı mesajı olmalı
        if (history.size > 40) {
            var cut = history.size - 40
            while (cut < history.size) {
                val h = history[cut]
                val parts = h.optJSONArray("parts") ?: JSONArray()
                val isPlainUser = h.optString("role") == "user" &&
                    (0 until parts.length()).none { parts.getJSONObject(it).has("functionResponse") }
                if (isPlainUser) break
                cut++
            }
            repeat(cut) { history.removeAt(0) }
        }
    }

    private fun systemPrompt(): String {
        val tr = Locale("tr", "TR")
        val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("d MMMM yyyy EEEE, HH:mm", tr))
        val name = prefs.userName.ifBlank { null }
        val facts = prefs.facts
        return buildString {
            appendLine("Sen Eclyra'sın: kullanıcının Android telefonunda çalışan, Türkçe konuşan, sıcak ve zeki bir sesli asistan.")
            appendLine("Şu an: $now (saat dilimi ${ZoneId.systemDefault().id}).")
            if (name != null) appendLine("Kullanıcının adı: $name.")
            appendLine()
            appendLine("Kurallar:")
            appendLine("- Cevapların sesli okunacak. Günlük sorularda kısa ve doğal konuş: 1-3 cümle. Gereksiz giriş yapma.")
            appendLine("- Sesli cevaplarda madde işareti, tablo, emoji ve markdown kullanma.")
            appendLine("- Ders sorusu ya da görseldeki soru gelirse adım adım, düzenli çöz; işlemleri açıkça yaz. İlk cümlede sonucu kısaca söyle (sesli okunacak), ayrıntılar ekranda kalır.")
            appendLine("- Telefonla ilgili bir istek gelince (alarm, arama, mesaj, konum, uygulama açma, fener, ses, müzik, hava durumu, yol tarifi, hatırlatma) uygun aracı hemen kullan; ne yaptığını tek cümleyle söyle.")
            appendLine("- 'yarın', '2 saat sonra', 'akşam' gibi zamanları şu anki zamana göre hesapla. 'Akşam' belirsizse 20:00, 'sabah' 09:00 kabul et.")
            appendLine("- Mesajda ya da aramada kişi belirsizse ya da araç birden fazla kişi döndürürse hangisi olduğunu sor.")
            appendLine("- Mesaj içeriği belli değilse kullanıcının söylediğini doğal bir mesaja çevir.")
            appendLine("- Bir araç hata verirse nedenini kısaca söyle ve çözüm öner.")
            appendLine("- Güncel bilgi gereken her soruda (haber, maç skoru, döviz/altın, fiyat, nöbetçi eczane, etkinlik, son gelişmeler, 'şu an' ile ilgili her şey) uydurma; web_lookup aracıyla Google'da araştır, cevabı kısaca söyle ve kaynağın adını an.")
            appendLine("- Kullanıcı bildirimlerini/mesajlarını sorarsa read_notifications kullan. Bir bildirime cevap göndermeden önce göndereceğin mesajı kullanıcıya söyleyip onay al, onay gelince reply_notification kullan.")
            appendLine("- Kullanıcı bir şeyi hatırlamanı isterse remember_fact kullan.")
            if (facts.isNotEmpty()) {
                appendLine()
                appendLine("Kullanıcı hakkında hatırladıkların:")
                facts.forEach { appendLine("- $it") }
            }
        }
    }

    private fun toB64(bmp: Bitmap): String {
        val out = ByteArrayOutputStream()
        bmp.compress(Bitmap.CompressFormat.JPEG, 85, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    override fun onCleared() {
        stopWakeTest()
        try {
            testModel?.close()
        } catch (_: Exception) {
        }
        voice.release()
        super.onCleared()
    }
}
