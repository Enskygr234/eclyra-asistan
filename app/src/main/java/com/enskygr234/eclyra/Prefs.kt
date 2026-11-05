package com.enskygr234.eclyra

import android.content.Context
import org.json.JSONArray

class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("eclyra", Context.MODE_PRIVATE)

    var apiKey: String
        get() = sp.getString("api_key", "") ?: ""
        set(v) { sp.edit().putString("api_key", v.trim()).apply() }

    var model: String
        get() = sp.getString("model", DEFAULT_MODEL) ?: DEFAULT_MODEL
        set(v) { sp.edit().putString("model", v.trim()).apply() }

    var userName: String
        get() = sp.getString("user_name", "") ?: ""
        set(v) { sp.edit().putString("user_name", v.trim()).apply() }

    var wakeWord: Boolean
        get() = sp.getBoolean("wake_word", false)
        set(v) { sp.edit().putBoolean("wake_word", v).apply() }

    var directSms: Boolean
        get() = sp.getBoolean("direct_sms", false)
        set(v) { sp.edit().putBoolean("direct_sms", v).apply() }

    var autoContinue: Boolean
        get() = sp.getBoolean("auto_continue", true)
        set(v) { sp.edit().putBoolean("auto_continue", v).apply() }

    var speakTyped: Boolean
        get() = sp.getBoolean("speak_typed", false)
        set(v) { sp.edit().putBoolean("speak_typed", v).apply() }

    var speechRate: Float
        get() = sp.getFloat("speech_rate", 1.0f)
        set(v) { sp.edit().putFloat("speech_rate", v).apply() }

    /** Telefonun arka plan ses tanıması Türkçe çalışmıyorsa Google'ın ses penceresi kullanılır. */
    var useDialog: Boolean
        get() = sp.getBoolean("use_dialog", false)
        set(v) { sp.edit().putBoolean("use_dialog", v).apply() }

    var askedPermissions: Boolean
        get() = sp.getBoolean("asked_perms", false)
        set(v) { sp.edit().putBoolean("asked_perms", v).apply() }

    /** Kullanıcının "bunu hatırla" dediği bilgiler. */
    var facts: List<String>
        get() {
            val raw = sp.getString("facts", "[]") ?: "[]"
            return try {
                val a = JSONArray(raw)
                List(a.length()) { a.getString(it) }
            } catch (e: Exception) {
                emptyList()
            }
        }
        set(v) { sp.edit().putString("facts", JSONArray(v).toString()).apply() }

    companion object {
        const val DEFAULT_MODEL = "gemini-flash-lite-latest"
        val MODELS = listOf(
            "gemini-flash-lite-latest",
            "gemini-3.5-flash-lite",
            "gemini-flash-latest",
            "gemini-2.5-flash"
        )
    }
}
