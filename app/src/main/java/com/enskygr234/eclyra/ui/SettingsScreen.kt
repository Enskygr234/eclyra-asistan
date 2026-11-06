package com.enskygr234.eclyra.ui

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.enskygr234.eclyra.AssistantViewModel
import com.enskygr234.eclyra.BuildConfig
import com.enskygr234.eclyra.NaturalVoice
import com.enskygr234.eclyra.Prefs
import com.enskygr234.eclyra.R

@Composable
fun SettingsScreen(
    vm: AssistantViewModel,
    onBack: () -> Unit,
    onRequestPermissions: () -> Unit,
    onRequestSms: () -> Unit,
    overlayGranted: Boolean,
    notifAccess: Boolean,
) {
    val ctx = LocalContext.current
    val p = vm.prefs
    // Prefs düz SharedPreferences; ekranın yenilenmesi için küçük bir sayaç kullanıyoruz
    var tick by remember { mutableIntStateOf(0) }
    fun bump() { tick++ }
    tick.let { } // okuyarak yeniden çizimi tetikler

    var key by remember { mutableStateOf(p.userKey) }
    var showKey by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(p.userName) }
    var customModel by remember { mutableStateOf(if (p.model in Prefs.MODELS) "" else p.model) }

    Column(
        Modifier
            .fillMaxSize()
            .background(C.Night)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), "Geri", tint = C.Text) }
            Text("Ayarlar", color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
        }

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Section("Yapay zekâ (Gemini)") {
                OutlinedTextField(
                    value = key,
                    onValueChange = { key = it.trim(); p.apiKey = key },
                    label = { Text(if (p.hasBuiltInKey) "API anahtarı (boş = gömülü anahtar)" else "API anahtarı") },
                    singleLine = true,
                    visualTransformation = if (showKey) VisualTransformation.None else PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                    trailingIcon = {
                        Text(
                            if (showKey) "Gizle" else "Göster", color = C.Ice, fontSize = 12.sp,
                            modifier = Modifier
                                .clickable { showKey = !showKey }
                                .padding(8.dp)
                        )
                    }
                )
                if (p.hasBuiltInKey && key.isBlank()) {
                    Text("✓ Uygulamaya gömülü kişisel anahtar kullanılıyor", color = C.Ice, fontSize = 13.sp)
                }
                Link("Ücretsiz anahtar al (aistudio.google.com)") {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
                }
                Spacer(Modifier.height(6.dp))
                Text("Model", color = C.Muted, fontSize = 13.sp)
                Prefs.MODELS.forEach { m ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { p.model = m; customModel = ""; bump() },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = p.model == m && customModel.isBlank(),
                            onClick = { p.model = m; customModel = ""; bump() },
                            colors = RadioButtonDefaults.colors(selectedColor = C.Violet)
                        )
                        Column {
                            Text(m, color = C.Text, fontSize = 14.sp)
                            Text(modelNote(m), color = C.Muted, fontSize = 12.sp)
                        }
                    }
                }
                OutlinedTextField(
                    value = customModel,
                    onValueChange = { customModel = it.trim(); if (customModel.isNotBlank()) p.model = customModel; bump() },
                    label = { Text("Başka model adı (isteğe bağlı)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }

            Section("Sen") {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it; p.userName = it },
                    label = { Text("Sana nasıl hitap edeyim?") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                val facts = p.facts
                Text(
                    if (facts.isEmpty()) "Henüz hatırladığım bir şey yok. \"Bunu hatırla: …\" diyebilirsin."
                    else "Hatırladıklarım:\n" + facts.joinToString("\n") { "• $it" },
                    color = C.Muted, fontSize = 13.sp
                )
                if (facts.isNotEmpty()) Link("Hepsini unut") { p.facts = emptyList(); bump() }
            }

            Section("\"Hey Eclyra\"") {
                Toggle(
                    "Uyandırma sözüyle dinle",
                    "Uygulama kapalıyken de \"Hey Eclyra\" deyince açılır. Ses telefonda işlenir. İlk açılışta ~35 MB Türkçe ses modeli indirilir.",
                    vm.wakeOn
                ) { on ->
                    if (on) onRequestPermissions()
                    vm.setWake(on)
                }
                vm.wakeProgress?.let { pr ->
                    Text("Ses modeli indiriliyor… %${(pr * 100).toInt()}", color = C.Ice, fontSize = 13.sp)
                    LinearProgressIndicator(progress = { pr }, modifier = Modifier.fillMaxWidth(), color = C.Ice)
                }
                vm.wakeError?.let { Text(it, color = C.Error, fontSize = 13.sp) }
                if (vm.wakeOn && vm.wakeProgress == null) {
                    // Uyandırma testi: modelin ne duyduğunu canlı gösterir
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(C.Night)
                            .padding(12.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("Uyandırma testi", color = C.Text, fontSize = 14.sp, modifier = Modifier.weight(1f))
                            OutlinedButton(onClick = { if (vm.testing) vm.stopWakeTest() else vm.startWakeTest() }) {
                                Text(if (vm.testing) "Durdur" else "Başlat")
                            }
                        }
                        if (vm.testing || vm.testHeard.isNotEmpty()) {
                            Text("Duyduğum: ${vm.testHeard}", color = C.Muted, fontSize = 13.sp)
                            if (vm.testHit) Text("✓ \"Hey Eclyra\" algılandı!", color = C.Ice, fontSize = 14.sp)
                        }
                    }
                }
                if (vm.wakeOn) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (overlayGranted) "✓ Diğer uygulamaların üzerinde açılabilir"
                            else "Kilit ekranında ve başka uygulamadayken doğrudan açılması için \"üstte gösterme\" izni ver.",
                            color = if (overlayGranted) C.Ice else C.Muted, fontSize = 13.sp,
                            modifier = Modifier.weight(1f)
                        )
                        if (!overlayGranted) {
                            OutlinedButton(onClick = {
                                ctx.startActivity(
                                    Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + ctx.packageName))
                                )
                            }) { Text("İzin ver") }
                        }
                    }
                    Link("Pil kısıtlamasını kaldır (arka planda kapanmasın)") {
                        ctx.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))
                        )
                    }
                }
                Link("Eclyra'yı varsayılan asistan yap (ana ekran tuşuna uzun bas)") {
                    try {
                        ctx.startActivity(Intent(Settings.ACTION_VOICE_INPUT_SETTINGS))
                    } catch (e: Exception) {
                        ctx.startActivity(Intent(Settings.ACTION_SETTINGS))
                    }
                }
            }

            Section("Ses") {
                Toggle(
                    "Doğal ses (Gemini)",
                    "İnsan gibi konuşan yapay zekâ sesi. Kapalıysa telefonun kendi sesi kullanılır. Kota dolarsa kendiliğinden telefonun sesine geçer.",
                    p.naturalVoice
                ) { p.naturalVoice = it; bump() }
                if (p.naturalVoice) {
                    Text("Ses seçimi", color = C.Muted, fontSize = 13.sp)
                    NaturalVoice.VOICES.forEach { (v, desc) ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable { p.voiceName = v; bump() },
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = p.voiceName == v,
                                onClick = { p.voiceName = v; bump() },
                                colors = RadioButtonDefaults.colors(selectedColor = C.Violet)
                            )
                            Text(v, color = C.Text, fontSize = 14.sp, modifier = Modifier.width(80.dp))
                            Text(desc, color = C.Muted, fontSize = 12.sp)
                        }
                    }
                }
                OutlinedButton(onClick = { vm.previewVoice() }, modifier = Modifier.fillMaxWidth()) {
                    Text("Sesi dinle")
                }
                Toggle(
                    "Google ses penceresini kullan",
                    "Mikrofon seni duymuyorsa aç. Konuşurken Google'ın ses penceresi açılır; çoğu telefonda daha sağlam çalışır.",
                    p.useDialog
                ) { p.useDialog = it; bump() }
                Toggle("Sohbete devam et", "Soru sorduğumda cevabını otomatik dinlerim.", p.autoContinue) {
                    p.autoContinue = it; bump()
                }
                Toggle("Yazınca da sesli cevap ver", "Kapalıysa sadece konuşunca sesli cevap veririm.", p.speakTyped) {
                    p.speakTyped = it; bump()
                }
                Text("Konuşma hızı: ${"%.1f".format(p.speechRate)}x", color = C.Text, fontSize = 14.sp)
                Slider(
                    value = p.speechRate,
                    onValueChange = { p.speechRate = (it * 10).toInt() / 10f; bump() },
                    valueRange = 0.6f..1.8f
                )
            }

            Section("Bildirimler") {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (notifAccess) "✓ Bildirimleri okuyabiliyorum. \"Son mesajlarımı oku\" ya da \"Ahmet ne yazmış?\" diyebilirsin."
                        else "\"Son mesajlarımı oku\" ve mesajlara sesle cevap için bildirim erişimi ver.",
                        color = if (notifAccess) C.Ice else C.Muted, fontSize = 13.sp,
                        modifier = Modifier.weight(1f)
                    )
                    if (!notifAccess) {
                        OutlinedButton(onClick = {
                            ctx.startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
                        }) { Text("Erişim ver") }
                    }
                }
                if (!notifAccess) {
                    Text(
                        "Listede Eclyra gri/kısıtlı görünürse: Ayarlar > Uygulamalar > Eclyra > sağ üstteki ⋮ > \"Kısıtlanmış ayarlara izin ver\", sonra tekrar dene.",
                        color = C.Muted, fontSize = 12.sp
                    )
                }
                Toggle(
                    "Gelen mesajları sesli oku",
                    "Eclyra kapalıyken WhatsApp, SMS, Telegram vb. mesajlar gelince kimden geldiğini ve mesajı sesli okur.",
                    p.announceMessages
                ) { p.announceMessages = it; bump() }
            }

            Section("Sohbet geçmişi") {
                Text("Sohbetler sadece bu telefonda saklanır. Üstteki saat simgesinden eski sohbetlere dönebilirsin.", color = C.Muted, fontSize = 13.sp)
                Link("Tüm sohbetleri sil") { vm.deleteAllChats() }
            }

            Section("Telefon") {
                Toggle(
                    "SMS'leri doğrudan gönder",
                    "Kapalıyken mesaj ekranı hazır açılır, gönder tuşuna sen basarsın. Açıkken direkt gönderirim.",
                    p.directSms
                ) {
                    p.directSms = it; bump()
                    if (it) onRequestSms()
                }
                OutlinedButton(onClick = onRequestPermissions, modifier = Modifier.fillMaxWidth()) {
                    Text("İzinleri ver (mikrofon, rehber, arama, konum, bildirim)")
                }
                Link("Uygulama izinlerini telefon ayarlarında aç") {
                    ctx.startActivity(
                        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + ctx.packageName))
                    )
                }
            }

            Section("Hakkında") {
                Text("Eclyra Asistan ${BuildConfig.VERSION_NAME}", color = C.Text, fontSize = 14.sp)
                Text("Fikir ve tasarım: Enes (Enskygr234) · Kodlama: Claude ile birlikte", color = C.Muted, fontSize = 13.sp)
                Link("GitHub'da aç") {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Enskygr234/eclyra-asistan")))
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

private fun modelNote(m: String) = when (m) {
    "gemini-flash-lite-latest" -> "Varsayılan · en hızlı, kotası en geniş"
    "gemini-3.5-flash-lite" -> "Lite, sabit sürüm"
    "gemini-flash-latest" -> "Daha akıllı, zor sorular için"
    "gemini-2.5-flash" -> "Eski ama kararlı"
    else -> ""
}

@Composable
private fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(C.Card)
            .border(1.dp, C.Line, RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(title, color = C.Violet, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)
        content()
    }
}

@Composable
private fun Toggle(title: String, desc: String, value: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onChange(!value) },
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = C.Text, fontSize = 15.sp)
            Text(desc, color = C.Muted, fontSize = 12.sp)
        }
        Spacer(Modifier.width(10.dp))
        Switch(
            checked = value, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(checkedTrackColor = C.VioletDeep, checkedThumbColor = C.Text)
        )
    }
}

@Composable
private fun Link(text: String, onClick: () -> Unit) {
    Text(
        text, color = C.Ice, fontSize = 13.sp,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(vertical = 4.dp)
    )
}
