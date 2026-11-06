package com.enskygr234.eclyra

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

class GeminiException(message: String, val code: Int) : Exception(message)

/** Gemini REST API'sine doğrudan istek atan küçük istemci (ek kütüphane yok). */
object Gemini {

    fun generate(
        apiKey: String,
        model: String,
        system: String?,
        contents: JSONArray,
        tools: JSONArray?,
        generationConfig: JSONObject? = null
    ): JSONObject {
        val m = URLEncoder.encode(model, "UTF-8")
        val url = URL("https://generativelanguage.googleapis.com/v1beta/models/$m:generateContent")
        val body = JSONObject().put("contents", contents)
        if (system != null) body.put("systemInstruction", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", system))))
        if (tools != null) body.put("tools", tools)
        if (generationConfig != null) body.put("generationConfig", generationConfig)

        val conn = url.openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.connectTimeout = 20_000
            conn.readTimeout = 120_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
            conn.setRequestProperty("x-goog-api-key", apiKey)
            conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
            if (code !in 200..299) {
                val msg = try {
                    JSONObject(text).getJSONObject("error").getString("message")
                } catch (e: Exception) {
                    text.take(300)
                }
                throw GeminiException(msg, code)
            }
            return JSONObject(text)
        } finally {
            conn.disconnect()
        }
    }

    /** Seçili modeli dener; model yoksa (404) listedeki diğerlerine geçer ve çalışanı kaydeder. */
    fun generateAuto(prefs: Prefs, system: String?, contents: JSONArray, tools: JSONArray?): JSONObject {
        val first = prefs.model.ifBlank { Prefs.DEFAULT_MODEL }
        val order = listOf(first) + Prefs.MODELS.filter { it != first }
        var last: Throwable? = null
        for (m in order) {
            try {
                val r = generate(prefs.apiKey, m, system, contents, tools)
                if (m != first) prefs.model = m
                return r
            } catch (e: GeminiException) {
                last = e
                if (e.code != 404) throw e
            }
        }
        throw last ?: GeminiException("Model bulunamadı", 404)
    }

    /** Cevaptaki düz metin parçalarını birleştirir (düşünce parçaları hariç). */
    fun textOf(resp: JSONObject): String {
        val parts = resp.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("content")?.optJSONArray("parts")
            ?: return ""
        val sb = StringBuilder()
        for (i in 0 until parts.length()) {
            val p = parts.getJSONObject(i)
            if (!p.optBoolean("thought", false) && p.has("text")) sb.append(p.getString("text"))
        }
        return sb.toString().trim()
    }

    /** Kullanıcıya gösterilecek anlaşılır hata metni. */
    fun friendly(e: Throwable): String = when {
        e is GeminiException && (e.code == 400 && e.message.orEmpty().contains("API key", true)) ->
            "API anahtarı geçersiz görünüyor. Ayarlardan kontrol et."
        e is GeminiException && (e.code == 401 || e.code == 403) ->
            "API anahtarı bu modele erişemiyor. Ayarlardan anahtarı ya da modeli değiştir."
        e is GeminiException && e.code == 429 ->
            "Gemini kotası doldu. Biraz bekle ya da ayarlardan daha hafif bir model seç."
        e is GeminiException && e.code >= 500 ->
            "Gemini şu an cevap vermiyor, birazdan tekrar dene."
        e is GeminiException -> "Gemini hatası: ${e.message}"
        e is java.net.UnknownHostException || e is java.net.ConnectException ->
            "İnternete bağlanamadım. Bağlantını kontrol et."
        e is java.net.SocketTimeoutException -> "Cevap çok gecikti, tekrar dener misin?"
        else -> "Bir hata oldu: ${e.message ?: e.javaClass.simpleName}"
    }
}
