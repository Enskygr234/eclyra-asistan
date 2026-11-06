package com.enskygr234.eclyra

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File

data class ChatMeta(val id: String, val title: String, val updated: Long, val count: Int)

/** Sohbetleri telefonda JSON dosyası olarak saklar. */
object ChatStore {

    private fun dir(ctx: Context) = File(ctx.filesDir, "chats").apply { mkdirs() }

    fun newId() = System.currentTimeMillis().toString(36)

    fun list(ctx: Context): List<ChatMeta> =
        dir(ctx).listFiles { f -> f.name.endsWith(".json") }.orEmpty().mapNotNull { f ->
            try {
                val o = JSONObject(f.readText())
                ChatMeta(o.getString("id"), o.optString("title"), o.optLong("updated"), o.optJSONArray("messages")?.length() ?: 0)
            } catch (e: Exception) {
                null
            }
        }.sortedByDescending { it.updated }

    fun save(ctx: Context, id: String, messages: List<Msg>, history: List<JSONObject>) {
        if (messages.none { it.role == Role.USER }) return
        val title = messages.firstOrNull { it.role == Role.USER }?.text?.replace('\n', ' ')?.take(60) ?: "Sohbet"
        val ms = JSONArray()
        messages.forEach { m ->
            val o = JSONObject().put("role", m.role.name).put("text", m.text)
            m.image?.let { o.put("thumb", thumb(it)) }
            ms.put(o)
        }
        val hs = JSONArray()
        history.forEach { h ->
            // Görsel verisini kaydetme, sadece işaret bırak
            val copy = JSONObject(h.toString())
            copy.optJSONArray("parts")?.let { parts ->
                for (k in 0 until parts.length()) {
                    val p = parts.getJSONObject(k)
                    if (p.has("inline_data")) {
                        p.remove("inline_data"); p.put("text", "[daha önce gönderilen görsel]")
                    }
                }
            }
            hs.put(copy)
        }
        val o = JSONObject()
            .put("id", id).put("title", title).put("updated", System.currentTimeMillis())
            .put("messages", ms).put("history", hs)
        val f = File(dir(ctx), "$id.json")
        val tmp = File(dir(ctx), "$id.tmp")
        tmp.writeText(o.toString())
        tmp.renameTo(f)
    }

    fun load(ctx: Context, id: String): Pair<List<Msg>, List<JSONObject>>? = try {
        val o = JSONObject(File(dir(ctx), "$id.json").readText())
        val ms = o.getJSONArray("messages")
        val msgs = List(ms.length()) { k ->
            val m = ms.getJSONObject(k)
            val img = m.optString("thumb").takeIf { it.isNotBlank() }?.let {
                val b = Base64.decode(it, Base64.DEFAULT)
                BitmapFactory.decodeByteArray(b, 0, b.size)
            }
            Msg(k + 1L, Role.valueOf(m.getString("role")), m.getString("text"), img)
        }
        val hs = o.optJSONArray("history") ?: JSONArray()
        msgs to List(hs.length()) { hs.getJSONObject(it) }
    } catch (e: Exception) {
        null
    }

    fun delete(ctx: Context, id: String) {
        File(dir(ctx), "$id.json").delete()
    }

    fun deleteAll(ctx: Context) {
        dir(ctx).listFiles()?.forEach { it.delete() }
    }

    private fun thumb(b: Bitmap): String {
        val s = 320f / maxOf(b.width, b.height)
        val t = if (s < 1f) Bitmap.createScaledBitmap(b, (b.width * s).toInt(), (b.height * s).toInt(), true) else b
        val out = ByteArrayOutputStream()
        t.compress(Bitmap.CompressFormat.JPEG, 70, out)
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }
}
