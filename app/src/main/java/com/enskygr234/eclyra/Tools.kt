package com.enskygr234.eclyra

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.location.Geocoder
import android.location.Location
import android.location.LocationManager
import android.media.AudioManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Looper
import android.provider.AlarmClock
import android.provider.CalendarContract
import android.provider.ContactsContract
import android.provider.MediaStore
import android.provider.Settings
import android.telephony.SmsManager
import android.view.KeyEvent
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Calendar
import java.util.Locale
import kotlin.coroutines.resume

class ToolError(message: String) : Exception(message)

/** Bir aracın sonucu: modele dönen JSON ve sohbette gösterilecek kısa not. */
data class ToolResult(val data: JSONObject, val note: String? = null)

data class Contact(val name: String, val number: String)

private val TR = Locale("tr", "TR")

fun normTr(s: String): String = s.lowercase(TR)
    .replace('ı', 'i').replace('ğ', 'g').replace('ü', 'u')
    .replace('ş', 's').replace('ö', 'o').replace('ç', 'c')
    .replace(Regex("\\s+"), " ").trim()

/** Gemini'ye tanıtılan telefon araçları ve bunları çalıştıran kod. */
class Tools(private val ctx: Context, private val prefs: Prefs) {

    // ---------- Gemini'ye giden araç tanımları ----------

    private fun fn(
        name: String,
        desc: String,
        props: List<Triple<String, String, String>> = emptyList(),
        required: List<String> = emptyList()
    ): JSONObject {
        val o = JSONObject().put("name", name).put("description", desc)
        if (props.isNotEmpty()) {
            val p = JSONObject()
            props.forEach { (n, type, d) -> p.put(n, JSONObject().put("type", type).put("description", d)) }
            val params = JSONObject().put("type", "OBJECT").put("properties", p)
            if (required.isNotEmpty()) params.put("required", JSONArray(required))
            o.put("parameters", params)
        }
        return o
    }

    private fun s(n: String, d: String) = Triple(n, "STRING", d)
    private fun i(n: String, d: String) = Triple(n, "INTEGER", d)
    private fun b(n: String, d: String) = Triple(n, "BOOLEAN", d)

    fun declarations(): JSONArray {
        val f = JSONArray()
        f.put(fn("set_alarm", "Telefonun saat uygulamasında alarm kurar.",
            listOf(i("hour", "Saat, 0-23"), i("minute", "Dakika, 0-59"), s("label", "Alarm adı"),
                s("days", "Tekrar günleri, ör. 'pzt,sal,çar,per,cum' ya da 'her gün'. Tek seferlikse boş bırak.")),
            listOf("hour", "minute")))
        f.put(fn("set_timer", "Geri sayım sayacı kurar.",
            listOf(i("seconds", "Toplam süre, saniye"), s("label", "Sayaç adı")), listOf("seconds")))
        f.put(fn("set_reminder", "Belirli bir zamanda bildirimle hatırlatma kurar. Göreli zamanları (yarın, 2 saat sonra) şu anki zamana göre hesapla.",
            listOf(s("text", "Hatırlatılacak şey"), s("datetime", "Yerel saatle yyyy-MM-ddTHH:mm")),
            listOf("text", "datetime")))
        f.put(fn("list_reminders", "Kurulu hatırlatmaları listeler."))
        f.put(fn("cancel_reminder", "Bir hatırlatmayı siler.", listOf(i("id", "Hatırlatma numarası")), listOf("id")))
        f.put(fn("find_contact", "Rehberde kişi arar, adını ve numarasını döndürür.",
            listOf(s("name", "Aranan isim")), listOf("name")))
        f.put(fn("call", "Bir kişiyi ya da numarayı arar.",
            listOf(s("contact", "Rehberdeki isim ya da telefon numarası")), listOf("contact")))
        f.put(fn("send_sms", "SMS mesajı hazırlar ya da gönderir.",
            listOf(s("contact", "İsim ya da numara"), s("message", "Mesaj metni")), listOf("contact", "message")))
        f.put(fn("send_whatsapp", "WhatsApp'ta kişinin sohbetini mesaj yazılı halde açar.",
            listOf(s("contact", "İsim ya da numara"), s("message", "Mesaj metni")), listOf("contact", "message")))
        f.put(fn("share_location", "Kullanıcının şu anki konumunu harita linki olarak paylaşır.",
            listOf(s("contact", "Kime gönderileceği (isim/numara). Boşsa paylaşım menüsü açılır."),
                s("via", "whatsapp, sms ya da paylas"), s("note", "Linkin önüne eklenecek kısa not")),
            emptyList()))
        f.put(fn("where_am_i", "Kullanıcının şu anki konumunu ve adresini bulur."))
        f.put(fn("get_weather", "Hava durumunu ve birkaç günlük tahmini getirir.",
            listOf(s("city", "Şehir/ilçe. Boşsa kullanıcının konumu kullanılır."), i("days", "Kaç günlük tahmin, 1-7"))))
        f.put(fn("navigate", "Haritada bir yere yol tarifi başlatır.",
            listOf(s("destination", "Gidilecek yer"), s("mode", "araba, yuruyus ya da toplu_tasima")),
            listOf("destination")))
        f.put(fn("search_places", "Haritada yakındaki yerleri arar (ör. eczane, benzinlik, kafe).",
            listOf(s("query", "Aranacak yer türü ya da adı")), listOf("query")))
        f.put(fn("open_app", "Telefondaki bir uygulamayı adıyla açar.",
            listOf(s("name", "Uygulama adı")), listOf("name")))
        f.put(fn("flashlight", "Feneri açar ya da kapatır.", listOf(b("on", "true açar, false kapatır")), listOf("on")))
        f.put(fn("set_volume", "Ses seviyesini ayarlar.",
            listOf(s("action", "artir, azalt, ayarla, sessiz (titreşim) ya da normal"), i("level", "ayarla için yüzde 0-100")),
            listOf("action")))
        f.put(fn("media_control", "Çalan müziği/videoyu kontrol eder.",
            listOf(s("action", "oynat, duraklat, sonraki ya da onceki")), listOf("action")))
        f.put(fn("play_music", "Şarkı, sanatçı ya da video arar ve çalar.",
            listOf(s("query", "Ne çalınacak"), s("app", "youtube, spotify, youtube_music ya da varsayilan")),
            listOf("query")))
        f.put(fn("web_lookup", "Google'da araştırıp güncel bilgiyi bulur ve özetler: haberler, maç skorları, döviz/altın, fiyatlar, nöbetçi eczane, etkinlikler, güncel her şey.",
            listOf(s("query", "Ne araştırılacak; yer ve zaman bilgisini de ekle")), listOf("query")))
        f.put(fn("web_search", "Kullanıcı özellikle tarayıcıda açmak isterse Google aramasını tarayıcıda açar.", listOf(s("query", "Arama")), listOf("query")))
        f.put(fn("open_url", "Bir web adresini açar.", listOf(s("url", "Adres")), listOf("url")))
        f.put(fn("add_calendar_event", "Takvime etkinlik ekleme ekranını doldurup açar.",
            listOf(s("title", "Başlık"), s("start", "yyyy-MM-ddTHH:mm"), s("end", "yyyy-MM-ddTHH:mm, boşsa 1 saat"),
                s("location", "Yer")),
            listOf("title", "start")))
        f.put(fn("open_settings", "Telefon ayarlarının bir sayfasını açar.",
            listOf(s("page", "wifi, bluetooth, internet, ses, ekran, pil, konum, uygulamalar, bildirimler ya da genel")),
            listOf("page")))
        f.put(fn("read_notifications", "Telefona gelen son bildirimleri/mesajları okur (WhatsApp, SMS, Instagram, e-posta vb.).",
            listOf(s("app", "Sadece bu uygulamanınkiler, ör. whatsapp. Boşsa hepsi."),
                s("from", "Sadece bu kişiden/başlıktan gelenler"),
                i("count", "Kaç tane, varsayılan 5"),
                b("only_messages", "Sadece mesajlaşma uygulamaları"))))
        f.put(fn("reply_notification", "Bir mesaj bildirimine uygulamayı açmadan cevap gönderir. Önce kullanıcıdan onay al.",
            listOf(i("id", "read_notifications'tan gelen bildirim numarası"), s("message", "Gönderilecek cevap")),
            listOf("id", "message")))
        f.put(fn("device_info", "Pil yüzdesi, şarj durumu, internet bağlantısı, tarih ve saat bilgisini verir."))
        f.put(fn("remember_fact", "Kullanıcının hatırlamanı istediği kalıcı bir bilgiyi kaydeder.",
            listOf(s("fact", "Kaydedilecek bilgi")), listOf("fact")))
        f.put(fn("forget_facts", "Kaydedilmiş bilgileri siler.",
            listOf(s("contains", "Sadece bu kelimeyi içerenleri sil. Boşsa hepsini sil."))))
        return JSONArray().put(JSONObject().put("functionDeclarations", f))
    }

    // ---------- Çalıştırma ----------

    suspend fun run(name: String, args: JSONObject): ToolResult = try {
        withContext(Dispatchers.IO) { dispatch(name, args) }
    } catch (e: ToolError) {
        ToolResult(JSONObject().put("error", e.message), "⚠️ ${e.message}")
    } catch (e: SecurityException) {
        ToolResult(JSONObject().put("error", "İzin yok: ${e.message}"), "⚠️ Bu işlem için izin gerekiyor")
    } catch (e: Exception) {
        ToolResult(JSONObject().put("error", e.message ?: e.javaClass.simpleName), "⚠️ İşlem yapılamadı")
    }

    private suspend fun dispatch(name: String, a: JSONObject): ToolResult = when (name) {
        "set_alarm" -> setAlarm(a)
        "set_timer" -> setTimer(a)
        "set_reminder" -> setReminder(a)
        "list_reminders" -> listReminders()
        "cancel_reminder" -> cancelReminder(a)
        "find_contact" -> findContact(a)
        "call" -> call(a)
        "send_sms" -> sendSms(a)
        "send_whatsapp" -> sendWhatsapp(a)
        "share_location" -> shareLocation(a)
        "where_am_i" -> whereAmI()
        "get_weather" -> weather(a)
        "navigate" -> navigate(a)
        "search_places" -> searchPlaces(a)
        "open_app" -> openApp(a)
        "flashlight" -> flashlight(a)
        "set_volume" -> volume(a)
        "media_control" -> media(a)
        "play_music" -> playMusic(a)
        "web_lookup" -> webLookup(a)
        "web_search" -> {
            val q = a.optString("query")
            start(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=" + enc(q))))
            ok("Arama açıldı", "🔎 \"$q\" aranıyor")
        }
        "open_url" -> {
            var u = a.optString("url").trim()
            if (!u.startsWith("http")) u = "https://$u"
            start(Intent(Intent.ACTION_VIEW, Uri.parse(u)))
            ok("Açıldı", "🌐 $u")
        }
        "add_calendar_event" -> calendar(a)
        "open_settings" -> openSettings(a)
        "device_info" -> deviceInfo()
        "read_notifications" -> readNotifications(a)
        "reply_notification" -> replyNotification(a)
        "remember_fact" -> {
            val fact = a.optString("fact").trim()
            if (fact.isEmpty()) throw ToolError("Kaydedilecek bilgi boş")
            prefs.facts = (prefs.facts + fact).takeLast(50)
            ok("Kaydedildi", "🧠 Hatırlayacağım: $fact")
        }
        "forget_facts" -> {
            val c = a.optString("contains").trim()
            val before = prefs.facts
            prefs.facts = if (c.isEmpty()) emptyList() else before.filterNot { normTr(it).contains(normTr(c)) }
            ok("${before.size - prefs.facts.size} bilgi silindi", "🧠 Bilgiler silindi")
        }
        else -> throw ToolError("Bilinmeyen işlem: $name")
    }

    // ---------- yardımcılar ----------

    private fun ok(msg: String, note: String? = null, extra: JSONObject? = null): ToolResult {
        val o = extra ?: JSONObject()
        o.put("ok", true).put("result", msg)
        return ToolResult(o, note)
    }

    private fun has(p: String) = ContextCompat.checkSelfPermission(ctx, p) == PackageManager.PERMISSION_GRANTED

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    private fun start(i: Intent) {
        i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        try {
            ctx.startActivity(i)
        } catch (e: ActivityNotFoundException) {
            throw ToolError("Bunu açabilecek bir uygulama bulunamadı")
        }
    }

    private fun parseTime(s: String): LocalDateTime {
        val t = s.trim().replace(' ', 'T')
        val patterns = listOf("yyyy-MM-dd'T'HH:mm", "yyyy-MM-dd'T'HH:mm:ss", "yyyy-MM-dd'T'H:mm")
        for (p in patterns) {
            try {
                return LocalDateTime.parse(t.take(if (p.endsWith("ss")) 19 else 16), DateTimeFormatter.ofPattern(p))
            } catch (_: Exception) {
            }
        }
        throw ToolError("Zamanı anlayamadım: $s")
    }

    private val niceFmt = DateTimeFormatter.ofPattern("d MMMM EEEE HH:mm", TR)

    // ---------- alarm / sayaç / hatırlatma ----------

    private fun setAlarm(a: JSONObject): ToolResult {
        val h = a.getInt("hour")
        val m = a.optInt("minute", 0)
        val label = a.optString("label").ifBlank { "Eclyra alarmı" }
        val i = Intent(AlarmClock.ACTION_SET_ALARM)
            .putExtra(AlarmClock.EXTRA_HOUR, h)
            .putExtra(AlarmClock.EXTRA_MINUTES, m)
            .putExtra(AlarmClock.EXTRA_MESSAGE, label)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        val days = parseDays(a.optString("days"))
        if (days.isNotEmpty()) i.putIntegerArrayListExtra(AlarmClock.EXTRA_DAYS, ArrayList(days))
        start(i)
        val hm = String.format(Locale.US, "%02d:%02d", h, m)
        return ok("Alarm $hm için kuruldu", "⏰ Alarm: $hm" + if (days.isNotEmpty()) " (tekrarlı)" else "")
    }

    private fun parseDays(s: String): List<Int> {
        val n = normTr(s)
        if (n.isBlank()) return emptyList()
        if (n.contains("her gun")) return listOf(
            Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY,
            Calendar.FRIDAY, Calendar.SATURDAY, Calendar.SUNDAY
        )
        if (n.contains("hafta ici")) return listOf(
            Calendar.MONDAY, Calendar.TUESDAY, Calendar.WEDNESDAY, Calendar.THURSDAY, Calendar.FRIDAY
        )
        if (n.contains("hafta sonu")) return listOf(Calendar.SATURDAY, Calendar.SUNDAY)
        val map = listOf(
            "pzt" to Calendar.MONDAY, "pazartesi" to Calendar.MONDAY,
            "sal" to Calendar.TUESDAY, "car" to Calendar.WEDNESDAY, "per" to Calendar.THURSDAY,
            "cum" to Calendar.FRIDAY, "cmt" to Calendar.SATURDAY, "cumartesi" to Calendar.SATURDAY,
            "paz" to Calendar.SUNDAY
        )
        val out = mutableSetOf<Int>()
        n.split(',', ' ', ';').map { it.trim() }.filter { it.isNotEmpty() }.forEach { tok ->
            when {
                tok.startsWith("pazartesi") || tok == "pzt" -> out += Calendar.MONDAY
                tok.startsWith("cumartesi") || tok == "cmt" -> out += Calendar.SATURDAY
                else -> map.firstOrNull { tok.startsWith(it.first) }?.let { out += it.second }
            }
        }
        return out.toList()
    }

    private fun setTimer(a: JSONObject): ToolResult {
        val sec = a.getInt("seconds")
        if (sec <= 0) throw ToolError("Süre sıfırdan büyük olmalı")
        val i = Intent(AlarmClock.ACTION_SET_TIMER)
            .putExtra(AlarmClock.EXTRA_LENGTH, sec)
            .putExtra(AlarmClock.EXTRA_SKIP_UI, true)
        a.optString("label").takeIf { it.isNotBlank() }?.let { i.putExtra(AlarmClock.EXTRA_MESSAGE, it) }
        start(i)
        val txt = when {
            sec >= 3600 -> "${sec / 3600} sa ${(sec % 3600) / 60} dk"
            sec >= 60 -> "${sec / 60} dk" + if (sec % 60 != 0) " ${sec % 60} sn" else ""
            else -> "$sec sn"
        }
        return ok("Sayaç kuruldu: $txt", "⏱️ Sayaç: $txt")
    }

    private fun setReminder(a: JSONObject): ToolResult {
        val text = a.optString("text").ifBlank { "Hatırlatma" }
        val t = parseTime(a.getString("datetime"))
        val at = t.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        if (at < System.currentTimeMillis() - 60_000) throw ToolError("Bu zaman geçmişte kalıyor")
        val r = Reminders.add(ctx, at, text)
        return ok(
            "Hatırlatma #${r.id} kuruldu: ${t.format(niceFmt)}",
            "🔔 ${t.format(niceFmt)} — $text"
        )
    }

    private fun listReminders(): ToolResult {
        val arr = JSONArray()
        Reminders.all(ctx).forEach {
            val t = LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(it.at), ZoneId.systemDefault())
            arr.put(JSONObject().put("id", it.id).put("when", t.format(niceFmt)).put("text", it.text))
        }
        return ToolResult(JSONObject().put("reminders", arr).put("count", arr.length()))
    }

    private fun cancelReminder(a: JSONObject): ToolResult {
        val id = a.getInt("id")
        if (!Reminders.remove(ctx, id)) throw ToolError("$id numaralı hatırlatma yok")
        return ok("Silindi", "🔕 Hatırlatma #$id silindi")
    }

    // ---------- rehber / arama / mesaj ----------

    private fun allContacts(): List<Contact> {
        if (!has(Manifest.permission.READ_CONTACTS)) throw ToolError("Rehber izni verilmemiş. Ayarlar > İzinler'den ver.")
        val out = mutableListOf<Contact>()
        ctx.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(
                ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
                ContactsContract.CommonDataKinds.Phone.NUMBER
            ), null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val n = c.getString(0) ?: continue
                val num = c.getString(1) ?: continue
                out += Contact(n, num)
            }
        }
        return out
    }

    private fun searchContacts(query: String): List<Contact> {
        val q = normTr(query)
        if (q.isEmpty()) return emptyList()
        // "annem", "annemi", "ahmet'i" gibi ekleri tolere et
        val qs = listOf(q, q.removeSuffix("m"), q.removeSuffix("mi"), q.removeSuffix("i"), q.substringBefore("'"))
            .filter { it.length >= 2 }.distinct()
        val scored = allContacts().mapNotNull { c ->
            val n = normTr(c.name)
            val words = n.split(" ")
            val score = qs.maxOf { x ->
                when {
                    n == x -> 100
                    words.any { it == x } -> 80
                    n.startsWith(x) -> 70
                    words.any { it.startsWith(x) } -> 60
                    n.contains(x) -> 40
                    else -> 0
                }
            }
            if (score > 0) score to c else null
        }
        return scored.sortedByDescending { it.first }
            .map { it.second }
            .distinctBy { it.number.filter(Char::isDigit).takeLast(10) }
    }

    private fun resolve(contact: String): Contact {
        val raw = contact.trim()
        val digits = raw.filter { it.isDigit() }
        if (digits.length >= 7 && raw.all { it.isDigit() || it in "+ ()-" }) return Contact(raw, raw.filter { it.isDigit() || it == '+' })
        val list = searchContacts(raw)
        if (list.isEmpty()) throw ToolError("Rehberde \"$raw\" bulunamadı")
        val names = list.map { it.name }.distinct()
        if (names.size > 1 && normTr(names[0]) != normTr(raw)) {
            val top = names.take(5).joinToString(", ")
            throw ToolError("Birden fazla kişi eşleşti: $top. Hangisi olduğunu kullanıcıya sor.")
        }
        return list[0]
    }

    private fun intlNumber(n: String): String {
        var d = n.filter { it.isDigit() }
        if (n.trim().startsWith("+")) return d
        if (d.startsWith("00")) d = d.drop(2)
        else if (d.startsWith("0")) d = "90" + d.drop(1)
        else if (d.length == 10 && d.startsWith("5")) d = "90$d"
        return d
    }

    private fun findContact(a: JSONObject): ToolResult {
        val list = searchContacts(a.optString("name")).take(5)
        val arr = JSONArray()
        list.forEach { arr.put(JSONObject().put("name", it.name).put("number", it.number)) }
        return ToolResult(JSONObject().put("matches", arr))
    }

    private fun call(a: JSONObject): ToolResult {
        val c = resolve(a.getString("contact"))
        val uri = Uri.parse("tel:" + Uri.encode(c.number))
        if (has(Manifest.permission.CALL_PHONE)) start(Intent(Intent.ACTION_CALL, uri))
        else start(Intent(Intent.ACTION_DIAL, uri))
        return ok("${c.name} aranıyor", "📞 ${c.name} aranıyor")
    }

    private fun sendSms(a: JSONObject): ToolResult {
        val c = resolve(a.getString("contact"))
        val msg = a.getString("message")
        if (prefs.directSms && has(Manifest.permission.SEND_SMS)) {
            val sms: SmsManager = if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(SmsManager::class.java)
            else @Suppress("DEPRECATION") SmsManager.getDefault()
            val parts = sms.divideMessage(msg)
            sms.sendMultipartTextMessage(c.number, null, parts, null, null)
            return ok("SMS gönderildi", "✉️ ${c.name} kişisine SMS gönderildi")
        }
        start(Intent(Intent.ACTION_SENDTO, Uri.parse("smsto:" + Uri.encode(c.number))).putExtra("sms_body", msg))
        return ok("Mesaj ekranı hazır, kullanıcı gönder tuşuna basacak", "✉️ ${c.name} için SMS hazırlandı")
    }

    private fun whatsappIntent(number: String?, text: String): Intent {
        val url = if (number != null) "https://api.whatsapp.com/send?phone=$number&text=${enc(text)}"
        else "https://api.whatsapp.com/send?text=${enc(text)}"
        val i = Intent(Intent.ACTION_VIEW, Uri.parse(url))
        val pkg = listOf("com.whatsapp", "com.whatsapp.w4b").firstOrNull { installed(it) }
        if (pkg != null) i.setPackage(pkg)
        return i
    }

    private fun installed(pkg: String) = try {
        ctx.packageManager.getPackageInfo(pkg, 0); true
    } catch (e: Exception) {
        false
    }

    private fun sendWhatsapp(a: JSONObject): ToolResult {
        val c = resolve(a.getString("contact"))
        start(whatsappIntent(intlNumber(c.number), a.getString("message")))
        return ok("WhatsApp sohbeti mesaj yazılı açıldı, kullanıcı gönder tuşuna basacak", "💬 WhatsApp: ${c.name}")
    }

    // ---------- konum ----------

    @SuppressLint("MissingPermission")
    private suspend fun currentLocation(): Location {
        if (!has(Manifest.permission.ACCESS_FINE_LOCATION) && !has(Manifest.permission.ACCESS_COARSE_LOCATION))
            throw ToolError("Konum izni verilmemiş. Ayarlar > İzinler'den ver.")
        val lm = ctx.getSystemService(LocationManager::class.java)
        if (!LocationManagerCompat.isLocationEnabled(lm)) throw ToolError("Telefonun konumu kapalı, önce konumu aç.")

        val enabled = lm.getProviders(true)
        val provider = when {
            Build.VERSION.SDK_INT >= 31 && LocationManager.FUSED_PROVIDER in enabled -> LocationManager.FUSED_PROVIDER
            LocationManager.GPS_PROVIDER in enabled && has(Manifest.permission.ACCESS_FINE_LOCATION) -> LocationManager.GPS_PROVIDER
            LocationManager.NETWORK_PROVIDER in enabled -> LocationManager.NETWORK_PROVIDER
            else -> enabled.firstOrNull { it != LocationManager.PASSIVE_PROVIDER }
        }

        val fresh: Location? = if (provider == null) null else withTimeoutOrNull(15_000) {
            suspendCancellableCoroutine<Location?> { cont ->
                if (Build.VERSION.SDK_INT >= 30) {
                    lm.getCurrentLocation(provider, null, ContextCompat.getMainExecutor(ctx)) { loc ->
                        if (cont.isActive) cont.resume(loc)
                    }
                } else {
                    @Suppress("DEPRECATION")
                    lm.requestSingleUpdate(provider, { loc: Location -> if (cont.isActive) cont.resume(loc) }, Looper.getMainLooper())
                }
            }
        }
        if (fresh != null) return fresh
        val last = lm.getProviders(true).mapNotNull { p ->
            try {
                lm.getLastKnownLocation(p)
            } catch (e: SecurityException) {
                null
            }
        }.maxByOrNull { it.time }
        return last ?: throw ToolError("Konum alınamadı. Açık bir alanda tekrar dene.")
    }

    private fun address(loc: Location): String? = try {
        @Suppress("DEPRECATION")
        val list = Geocoder(ctx, TR).getFromLocation(loc.latitude, loc.longitude, 1)
        list?.firstOrNull()?.let { ad ->
            (0..ad.maxAddressLineIndex).joinToString(", ") { ad.getAddressLine(it) }
        }
    } catch (e: Exception) {
        null
    }

    private fun mapsLink(loc: Location) =
        String.format(Locale.US, "https://maps.google.com/?q=%.6f,%.6f", loc.latitude, loc.longitude)

    private suspend fun whereAmI(): ToolResult {
        val loc = currentLocation()
        val addr = address(loc)
        val o = JSONObject()
            .put("lat", loc.latitude).put("lng", loc.longitude)
            .put("accuracy_m", loc.accuracy.toInt())
            .put("address", addr ?: "bilinmiyor")
            .put("map", mapsLink(loc))
        return ok("Konum bulundu", "📍 ${addr ?: mapsLink(loc)}", o)
    }

    private suspend fun shareLocation(a: JSONObject): ToolResult {
        val loc = currentLocation()
        val link = mapsLink(loc)
        val note = a.optString("note").ifBlank { "Konumum:" }
        val text = "$note $link"
        val contact = a.optString("contact").trim()
        val via = normTr(a.optString("via"))
        if (contact.isEmpty() || via.startsWith("payl")) {
            val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, text)
            start(Intent.createChooser(send, "Konumu paylaş"))
            return ok("Paylaşım menüsü açıldı", "📍 Konum paylaşılıyor")
        }
        val c = resolve(contact)
        return if (via.startsWith("sms")) {
            sendSms(JSONObject().put("contact", c.number).put("message", text))
            ok("Konum SMS ile ${c.name} kişisine hazırlandı/gönderildi", "📍 Konum → ${c.name} (SMS)")
        } else {
            start(whatsappIntent(intlNumber(c.number), text))
            ok("Konum WhatsApp'ta ${c.name} için hazır, kullanıcı gönder tuşuna basacak", "📍 Konum → ${c.name} (WhatsApp)")
        }
    }

    // ---------- hava durumu (open-meteo, anahtarsız) ----------

    private fun httpGet(url: String): JSONObject {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 20_000
        return try {
            JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally {
            c.disconnect()
        }
    }

    private fun wmo(code: Int): String = when (code) {
        0 -> "açık"
        1 -> "çoğunlukla açık"
        2 -> "parçalı bulutlu"
        3 -> "kapalı"
        45, 48 -> "sisli"
        51, 53, 55 -> "çiseleme"
        56, 57 -> "donan çiseleme"
        61 -> "hafif yağmurlu"
        63 -> "yağmurlu"
        65 -> "kuvvetli yağmur"
        66, 67 -> "donan yağmur"
        71 -> "hafif kar"
        73 -> "karlı"
        75 -> "yoğun kar"
        77 -> "kar taneleri"
        80, 81 -> "sağanak"
        82 -> "şiddetli sağanak"
        85, 86 -> "kar sağanağı"
        95 -> "gök gürültülü fırtına"
        96, 99 -> "dolulu fırtına"
        else -> "bilinmiyor"
    }

    private suspend fun weather(a: JSONObject): ToolResult {
        val city = a.optString("city").trim()
        val days = a.optInt("days", 3).coerceIn(1, 7)
        val lat: Double
        val lng: Double
        val place: String
        if (city.isNotEmpty()) {
            val g = httpGet("https://geocoding-api.open-meteo.com/v1/search?count=1&language=tr&name=" + enc(city))
            val r = g.optJSONArray("results")?.optJSONObject(0) ?: throw ToolError("\"$city\" bulunamadı")
            lat = r.getDouble("latitude"); lng = r.getDouble("longitude")
            place = listOfNotNull(r.optString("name"), r.optString("admin1").takeIf { it.isNotBlank() }).joinToString(", ")
        } else {
            val loc = currentLocation()
            lat = loc.latitude; lng = loc.longitude
            place = address(loc)?.split(",")?.takeLast(2)?.joinToString(",")?.trim() ?: "bulunduğun yer"
        }
        val w = httpGet(
            String.format(
                Locale.US,
                "https://api.open-meteo.com/v1/forecast?latitude=%.4f&longitude=%.4f&timezone=auto&forecast_days=%d" +
                    "&current=temperature_2m,apparent_temperature,relative_humidity_2m,weather_code,wind_speed_10m" +
                    "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max",
                lat, lng, days
            )
        )
        val cur = w.getJSONObject("current")
        val out = JSONObject().put("place", place)
        out.put(
            "now", JSONObject()
                .put("temp_c", cur.optDouble("temperature_2m"))
                .put("feels_like_c", cur.optDouble("apparent_temperature"))
                .put("humidity", cur.optInt("relative_humidity_2m"))
                .put("wind_kmh", cur.optDouble("wind_speed_10m"))
                .put("sky", wmo(cur.optInt("weather_code")))
        )
        val d = w.getJSONObject("daily")
        val arr = JSONArray()
        val dates = d.getJSONArray("time")
        for (k in 0 until dates.length()) {
            arr.put(
                JSONObject()
                    .put("date", dates.getString(k))
                    .put("sky", wmo(d.getJSONArray("weather_code").optInt(k)))
                    .put("max_c", d.getJSONArray("temperature_2m_max").optDouble(k))
                    .put("min_c", d.getJSONArray("temperature_2m_min").optDouble(k))
                    .put("rain_chance", d.getJSONArray("precipitation_probability_max").optInt(k))
            )
        }
        out.put("daily", arr)
        val t = cur.optDouble("temperature_2m")
        return ToolResult(out, "🌤️ $place: ${t.toInt()}°, ${wmo(cur.optInt("weather_code"))}")
    }

    // ---------- harita ----------

    private fun navigate(a: JSONObject): ToolResult {
        val dest = a.getString("destination")
        val m = when (normTr(a.optString("mode"))) {
            "yuruyus", "yuruyerek" -> "w"
            "toplu_tasima", "toplu tasima", "otobus", "metro" -> "r"
            "bisiklet" -> "b"
            else -> "d"
        }
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("google.navigation:q=${enc(dest)}&mode=$m"))
        try {
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            ctx.startActivity(i)
        } catch (e: ActivityNotFoundException) {
            start(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${enc(dest)}")))
        }
        return ok("Yol tarifi başladı", "🧭 Yol tarifi: $dest")
    }

    private fun searchPlaces(a: JSONObject): ToolResult {
        val q = a.getString("query")
        start(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${enc(q)}")))
        return ok("Haritada aranıyor", "🗺️ Yakındaki: $q")
    }

    // ---------- güncel bilgi (Google arama ile) ----------

    private fun webLookup(a: JSONObject): ToolResult {
        val q = a.optString("query").trim()
        if (q.isEmpty()) throw ToolError("Ne arayacağım belli değil")
        val now = LocalDateTime.now().format(DateTimeFormatter.ofPattern("d MMMM yyyy EEEE HH:mm", TR))
        val prompt = "Şu an $now (Türkiye). Aşağıdakini Google'da araştır ve en güncel bilgiyle Türkçe, kısa ve net cevapla. " +
            "Sayıları, saatleri ve tarihleri açıkça yaz. Emin olmadığın şeyi uydurma.\n\nSoru: $q"
        val contents = JSONArray().put(
            JSONObject().put("role", "user").put("parts", JSONArray().put(JSONObject().put("text", prompt)))
        )
        val tools = JSONArray().put(JSONObject().put("google_search", JSONObject()))
        val resp = Gemini.generateAuto(prefs, null, contents, tools)
        val answer = Gemini.textOf(resp)
        if (answer.isEmpty()) throw ToolError("Aramadan sonuç çıkmadı")
        val sources = JSONArray()
        val names = mutableListOf<String>()
        resp.optJSONArray("candidates")?.optJSONObject(0)?.optJSONObject("groundingMetadata")
            ?.optJSONArray("groundingChunks")?.let { ch ->
                for (k in 0 until minOf(ch.length(), 4)) {
                    val w = ch.getJSONObject(k).optJSONObject("web") ?: continue
                    val title = w.optString("title")
                    sources.put(JSONObject().put("title", title).put("url", w.optString("uri")))
                    if (title.isNotBlank()) names += title
                }
            }
        val note = "🔎 Google: $q" + if (names.isNotEmpty()) "\nKaynak: " + names.distinct().take(3).joinToString(", ") else ""
        return ToolResult(JSONObject().put("answer", answer).put("sources", sources), note)
    }

    // ---------- uygulamalar / cihaz ----------

    private fun openApp(a: JSONObject): ToolResult {
        val q = normTr(a.getString("name"))
        val pm = ctx.packageManager
        val main = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val apps = pm.queryIntentActivities(main, 0)
        val best = apps.mapNotNull { ri ->
            val label = normTr(ri.loadLabel(pm).toString())
            val score = when {
                label == q -> 100
                label.startsWith(q) -> 80
                label.split(" ").any { it == q } -> 70
                label.contains(q) -> 50
                q.contains(label) && label.length >= 3 -> 40
                normTr(ri.activityInfo.packageName).contains(q.replace(" ", "")) -> 30
                else -> 0
            }
            if (score > 0) score to ri else null
        }.maxByOrNull { it.first }?.second ?: throw ToolError("\"${a.getString("name")}\" adında uygulama bulunamadı")
        val launch = pm.getLaunchIntentForPackage(best.activityInfo.packageName)
            ?: throw ToolError("Uygulama açılamadı")
        start(launch)
        return ok("${best.loadLabel(pm)} açıldı", "📱 ${best.loadLabel(pm)} açılıyor")
    }

    private fun flashlight(a: JSONObject): ToolResult {
        val on = a.optBoolean("on", true)
        val cm = ctx.getSystemService(CameraManager::class.java)
        val id = cm.cameraIdList.firstOrNull {
            cm.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
        } ?: throw ToolError("Bu telefonda fener yok")
        cm.setTorchMode(id, on)
        return ok(if (on) "Fener açıldı" else "Fener kapandı", if (on) "🔦 Fener açık" else "🔦 Fener kapalı")
    }

    private fun volume(a: JSONObject): ToolResult {
        val am = ctx.getSystemService(AudioManager::class.java)
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        val action = normTr(a.optString("action"))
        when {
            action.startsWith("art") -> am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_RAISE, AudioManager.FLAG_SHOW_UI)
            action.startsWith("azal") || action.startsWith("kis") ->
                am.adjustStreamVolume(AudioManager.STREAM_MUSIC, AudioManager.ADJUST_LOWER, AudioManager.FLAG_SHOW_UI)
            action.startsWith("sess") || action.startsWith("titre") -> {
                am.ringerMode = AudioManager.RINGER_MODE_VIBRATE
                return ok("Telefon titreşime alındı", "📳 Titreşim modu")
            }
            action.startsWith("normal") -> {
                am.ringerMode = AudioManager.RINGER_MODE_NORMAL
                return ok("Zil sesi açıldı", "🔔 Zil sesi açık")
            }
            else -> {
                val lvl = a.optInt("level", 50).coerceIn(0, 100)
                am.setStreamVolume(AudioManager.STREAM_MUSIC, (max * lvl / 100.0).toInt(), AudioManager.FLAG_SHOW_UI)
            }
        }
        val pct = am.getStreamVolume(AudioManager.STREAM_MUSIC) * 100 / max
        return ok("Medya sesi şimdi %$pct", "🔊 Ses %$pct")
    }

    private fun media(a: JSONObject): ToolResult {
        val code = when (normTr(a.getString("action"))) {
            "oynat", "devam", "baslat" -> KeyEvent.KEYCODE_MEDIA_PLAY
            "duraklat", "durdur", "dur" -> KeyEvent.KEYCODE_MEDIA_PAUSE
            "sonraki", "gec", "ileri" -> KeyEvent.KEYCODE_MEDIA_NEXT
            "onceki", "geri" -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            else -> KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE
        }
        val am = ctx.getSystemService(AudioManager::class.java)
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        am.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
        return ok("Tamam", "🎵 ${a.getString("action")}")
    }

    private fun playMusic(a: JSONObject): ToolResult {
        val q = a.getString("query")
        val app = normTr(a.optString("app"))
        val i = when {
            app.startsWith("spot") -> Intent(Intent.ACTION_VIEW, Uri.parse("spotify:search:" + Uri.encode(q)))
            app.contains("music") -> Intent(Intent.ACTION_VIEW, Uri.parse("https://music.youtube.com/search?q=" + enc(q)))
            app.startsWith("you") -> Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + enc(q)))
            else -> Intent(MediaStore.INTENT_ACTION_MEDIA_PLAY_FROM_SEARCH)
                .putExtra(MediaStore.EXTRA_MEDIA_FOCUS, "vnd.android.cursor.item/*")
                .putExtra(android.app.SearchManager.QUERY, q)
        }
        try {
            start(i)
        } catch (e: ToolError) {
            start(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.youtube.com/results?search_query=" + enc(q))))
        }
        return ok("Çalınıyor/aranıyor", "🎵 $q")
    }

    private fun calendar(a: JSONObject): ToolResult {
        val st = parseTime(a.getString("start"))
        val en = a.optString("end").takeIf { it.isNotBlank() }?.let { parseTime(it) } ?: st.plusHours(1)
        val z = ZoneId.systemDefault()
        val i = Intent(Intent.ACTION_INSERT, CalendarContract.Events.CONTENT_URI)
            .putExtra(CalendarContract.Events.TITLE, a.getString("title"))
            .putExtra(CalendarContract.EXTRA_EVENT_BEGIN_TIME, st.atZone(z).toInstant().toEpochMilli())
            .putExtra(CalendarContract.EXTRA_EVENT_END_TIME, en.atZone(z).toInstant().toEpochMilli())
        a.optString("location").takeIf { it.isNotBlank() }?.let { i.putExtra(CalendarContract.Events.EVENT_LOCATION, it) }
        start(i)
        return ok("Takvim ekranı açıldı, kullanıcı Kaydet'e basacak", "📅 ${a.getString("title")} — ${st.format(niceFmt)}")
    }

    private fun openSettings(a: JSONObject): ToolResult {
        val p = normTr(a.getString("page"))
        val action = when {
            p.startsWith("wifi") || p.startsWith("wi-fi") -> Settings.ACTION_WIFI_SETTINGS
            p.startsWith("blue") -> Settings.ACTION_BLUETOOTH_SETTINGS
            p.startsWith("internet") || p.startsWith("mobil") ->
                if (Build.VERSION.SDK_INT >= 29) Settings.Panel.ACTION_INTERNET_CONNECTIVITY else Settings.ACTION_WIRELESS_SETTINGS
            p.startsWith("ses") -> Settings.ACTION_SOUND_SETTINGS
            p.startsWith("ekran") || p.startsWith("parlak") -> Settings.ACTION_DISPLAY_SETTINGS
            p.startsWith("pil") || p.startsWith("batarya") -> Intent.ACTION_POWER_USAGE_SUMMARY
            p.startsWith("konum") -> Settings.ACTION_LOCATION_SOURCE_SETTINGS
            p.startsWith("uygulama") -> Settings.ACTION_APPLICATION_SETTINGS
            p.startsWith("bildirim") -> Settings.ACTION_APP_NOTIFICATION_SETTINGS
            else -> Settings.ACTION_SETTINGS
        }
        val i = Intent(action)
        if (action == Settings.ACTION_APP_NOTIFICATION_SETTINGS) i.putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
        start(i)
        return ok("Ayarlar açıldı", "⚙️ Ayarlar: ${a.getString("page")}")
    }

    // ---------- bildirimler ----------

    private fun needNotifAccess() {
        if (!NotifListener.hasAccess(ctx))
            throw ToolError("Bildirimleri okuma izni yok. Ayarlar > Bildirimler kısmından \"Bildirim erişimi ver\" ile açılabilir.")
    }

    private fun ago(t: Long): String {
        val m = ((System.currentTimeMillis() - t) / 60_000).toInt()
        return when {
            m < 1 -> "az önce"
            m < 60 -> "$m dk önce"
            m < 1440 -> "${m / 60} sa önce"
            else -> "${m / 1440} gün önce"
        }
    }

    private fun readNotifications(a: JSONObject): ToolResult {
        needNotifAccess()
        val app = normTr(a.optString("app"))
        val from = normTr(a.optString("from"))
        val count = a.optInt("count", 5).coerceIn(1, 20)
        val onlyMsg = a.optBoolean("only_messages", false)
        val list = NotifListener.recent().filter { n ->
            (!onlyMsg || n.pkg in NotifListener.MESSAGING) &&
                (app.isEmpty() || normTr(n.app).contains(app) || n.pkg.contains(app.replace(" ", ""))) &&
                (from.isEmpty() || normTr(n.title).contains(from))
        }.take(count)
        val arr = JSONArray()
        list.forEach {
            arr.put(
                JSONObject().put("id", it.id).put("app", it.app).put("title", it.title)
                    .put("text", it.text.take(500)).put("when", ago(it.time)).put("can_reply", it.reply != null)
            )
        }
        val note = if (list.isEmpty()) "🔔 Uygun bildirim yok" else "🔔 ${list.size} bildirim okundu"
        return ToolResult(JSONObject().put("notifications", arr).put("count", arr.length()), note)
    }

    private fun replyNotification(a: JSONObject): ToolResult {
        needNotifAccess()
        val item = NotifListener.find(a.getInt("id")) ?: throw ToolError("Bu bildirim artık yok")
        val msg = a.getString("message")
        NotifListener.reply(ctx, item, msg)
        return ok("Cevap gönderildi", "↩️ ${item.app} · ${item.title}: $msg")
    }

    private fun deviceInfo(): ToolResult {
        val bm = ctx.getSystemService(BatteryManager::class.java)
        val cm = ctx.getSystemService(ConnectivityManager::class.java)
        val caps = cm.getNetworkCapabilities(cm.activeNetwork)
        val net = when {
            caps == null -> "bağlantı yok"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "mobil veri"
            else -> "bağlı"
        }
        val o = JSONObject()
            .put("battery_percent", bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY))
            .put("charging", bm.isCharging)
            .put("network", net)
            .put("now", LocalDateTime.now().format(DateTimeFormatter.ofPattern("d MMMM yyyy EEEE HH:mm", TR)))
            .put("device", "${Build.MANUFACTURER} ${Build.MODEL}, Android ${Build.VERSION.RELEASE}")
        return ToolResult(o)
    }
}
