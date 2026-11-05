package com.enskygr234.eclyra.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.enskygr234.eclyra.AssistantViewModel
import com.enskygr234.eclyra.Msg
import com.enskygr234.eclyra.Phase
import com.enskygr234.eclyra.R
import com.enskygr234.eclyra.Role

private val SUGGESTIONS = listOf(
    "Yarın 7'de alarm kur",
    "Hava nasıl?",
    "Anneme konumumu at",
    "Feneri aç",
    "Akşam 8'de ilaç içmemi hatırlat",
    "Spotify'da lo-fi çal",
    "En yakın eczaneye yol tarifi",
    "Bana bir fıkra anlat",
)

@Composable
fun ChatScreen(
    vm: AssistantViewModel,
    onSettings: () -> Unit,
    onMic: () -> Unit,
    onCamera: () -> Unit,
    onGallery: () -> Unit,
) {
    var input by remember { mutableStateOf("") }
    val list = rememberLazyListState()
    val empty = vm.messages.isEmpty()
    val orbSize by animateDpAsState(if (empty) 230.dp else 96.dp, label = "orbSize")

    LaunchedEffect(vm.messages.size, vm.partial) {
        val n = vm.messages.size + (if (vm.partial.isNotEmpty()) 1 else 0)
        if (n > 0) list.animateScrollToItem(n - 1)
    }

    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(C.Night2, C.Night, C.Night)))
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .imePadding()
        ) {
            // üst çubuk
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 8.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    Modifier
                        .size(10.dp)
                        .clip(CircleShape)
                        .background(Brush.linearGradient(listOf(C.Violet, C.Ice)))
                )
                Spacer(Modifier.width(10.dp))
                Text("Eclyra", color = C.Text, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                if (!empty) {
                    IconButton(onClick = { vm.clearChat() }) {
                        Icon(painterResource(R.drawable.ic_delete), "Sohbeti temizle", tint = C.Muted)
                    }
                }
                IconButton(onClick = onSettings) {
                    Icon(painterResource(R.drawable.ic_settings), "Ayarlar", tint = C.Muted)
                }
            }

            // küre + durum
            Column(
                Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                if (empty) Spacer(Modifier.height(24.dp))
                Orb(
                    vm.phase, vm.level,
                    Modifier
                        .size(orbSize)
                        .clip(CircleShape)
                        .clickable { onMic() }
                )
                Text(
                    statusText(vm),
                    color = C.Muted,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 24.dp)
                )
                vm.hint?.let {
                    Text(it, color = C.Error, fontSize = 13.sp, modifier = Modifier.padding(top = 4.dp))
                }
            }

            if (!vm.hasKey()) {
                KeyCard(vm)
            }

            // mesajlar
            Box(Modifier.weight(1f)) {
                if (empty) {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .padding(top = 20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text("Şunu söylemeyi dene", color = C.Muted, fontSize = 13.sp)
                        Spacer(Modifier.height(10.dp))
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = 16.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(SUGGESTIONS) { s ->
                                Chip(s) { vm.sendText(s) }
                            }
                        }
                    }
                } else {
                    LazyColumn(
                        state = list,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(vm.messages, key = { it.id }) { m -> Bubble(m) }
                        if (vm.partial.isNotEmpty()) {
                            item(key = "partial") {
                                Bubble(Msg(-1, Role.USER, vm.partial + " …"), ghost = true)
                            }
                        }
                    }
                }
            }

            // bekleyen görsel
            vm.pendingImage?.let { bmp ->
                Row(
                    Modifier.padding(start = 16.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Image(
                        bmp.asImageBitmap(), null,
                        Modifier
                            .size(64.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Crop
                    )
                    Text(
                        "Görsel eklendi. Sorunu yaz ya da söyle.",
                        color = C.Muted, fontSize = 13.sp,
                        modifier = Modifier
                            .padding(horizontal = 10.dp)
                            .weight(1f)
                    )
                    IconButton(onClick = { vm.pendingImage = null }) {
                        Icon(painterResource(R.drawable.ic_close), "Kaldır", tint = C.Muted)
                    }
                }
            }

            // giriş çubuğu
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 10.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onCamera) {
                    Icon(painterResource(R.drawable.ic_camera), "Fotoğraf çek", tint = C.Violet)
                }
                IconButton(onClick = onGallery) {
                    Icon(painterResource(R.drawable.ic_image), "Galeriden seç", tint = C.Violet)
                }
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    placeholder = { Text("Bir şey yaz…", color = C.Muted) },
                    modifier = Modifier
                        .weight(1f)
                        .heightIn(min = 48.dp, max = 140.dp),
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = C.Violet,
                        unfocusedBorderColor = C.Line,
                        focusedContainerColor = C.Card,
                        unfocusedContainerColor = C.Card,
                    ),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = {
                        vm.sendText(input); input = ""
                    })
                )
                Spacer(Modifier.width(8.dp))
                val typing = input.isNotBlank() || vm.pendingImage != null
                val busy = vm.phase != Phase.IDLE
                Box(
                    Modifier
                        .size(52.dp)
                        .clip(CircleShape)
                        .background(
                            if (vm.phase == Phase.LISTENING) Brush.linearGradient(listOf(C.Rose, C.VioletDeep))
                            else Brush.linearGradient(listOf(C.Violet, C.VioletDeep))
                        )
                        .clickable {
                            if (typing && !busy) {
                                vm.sendText(input); input = ""
                            } else onMic()
                        },
                    contentAlignment = Alignment.Center
                ) {
                    val icon = when {
                        typing && !busy -> R.drawable.ic_send
                        busy -> R.drawable.ic_stop
                        else -> R.drawable.ic_mic
                    }
                    Icon(painterResource(icon), "Konuş", tint = Color.White)
                }
            }
        }
    }
}

private fun statusText(vm: AssistantViewModel): String = when (vm.phase) {
    Phase.LISTENING -> "Dinliyorum…"
    Phase.THINKING -> "Düşünüyorum…"
    Phase.SPEAKING -> "Konuşuyorum · durdurmak için dokun"
    Phase.IDLE -> if (vm.wakeOn) "Küreye dokun ya da \"Hey Eclyra\" de" else "Konuşmak için küreye dokun"
}

@Composable
private fun Chip(text: String, onClick: () -> Unit) {
    Text(
        text,
        color = C.Text,
        fontSize = 14.sp,
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(C.Card)
            .border(1.dp, C.Line, RoundedCornerShape(20.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp)
    )
}

@Composable
private fun Bubble(m: Msg, ghost: Boolean = false) {
    when (m.role) {
        Role.ACTION -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Text(
                m.text, color = C.Ice, fontSize = 13.sp,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(C.Ice.copy(alpha = 0.08f))
                    .padding(horizontal = 12.dp, vertical = 6.dp)
            )
        }

        Role.ERROR -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
            Text(
                m.text, color = C.Error, fontSize = 13.sp, textAlign = TextAlign.Center,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(C.Error.copy(alpha = 0.08f))
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }

        Role.USER -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            Column(
                Modifier
                    .widthIn(max = 300.dp)
                    .clip(RoundedCornerShape(20.dp, 20.dp, 6.dp, 20.dp))
                    .background(
                        if (ghost) Brush.linearGradient(listOf(C.Card2, C.Card2))
                        else Brush.linearGradient(listOf(C.VioletDeep, Color(0xFF5B21B6)))
                    )
                    .padding(12.dp),
                horizontalAlignment = Alignment.End
            ) {
                m.image?.let {
                    Image(
                        it.asImageBitmap(), null,
                        Modifier
                            .widthIn(max = 260.dp)
                            .heightIn(max = 260.dp)
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Fit
                    )
                    Spacer(Modifier.height(8.dp))
                }
                Text(m.text, color = if (ghost) C.Muted else Color.White, fontSize = 15.sp)
            }
        }

        Role.ASSISTANT -> Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
            SelectionContainer {
                Text(
                    markdown(m.text),
                    color = C.Text, fontSize = 15.sp, lineHeight = 22.sp,
                    modifier = Modifier
                        .widthIn(max = 320.dp)
                        .clip(RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp))
                        .background(C.Card)
                        .border(1.dp, C.Line, RoundedCornerShape(20.dp, 20.dp, 20.dp, 6.dp))
                        .padding(12.dp)
                )
            }
        }
    }
}

/** **kalın**, `kod` ve # başlıkları basitçe biçimlendirir. */
private fun markdown(src: String): AnnotatedString = buildAnnotatedString {
    src.lines().forEachIndexed { li, rawLine ->
        if (li > 0) append('\n')
        var line = rawLine
        var heading = false
        val h = Regex("^#{1,6}\\s+").find(line)
        if (h != null) {
            line = line.substring(h.range.last + 1); heading = true
        }
        if (line.trimStart().startsWith("* ") || line.trimStart().startsWith("- ")) {
            line = "• " + line.trimStart().substring(2)
        }
        val start = length
        var k = 0
        while (k < line.length) {
            when {
                line.startsWith("**", k) -> {
                    val end = line.indexOf("**", k + 2)
                    if (end > 0) {
                        pushStyle(SpanStyle(fontWeight = FontWeight.Bold, color = Color.White))
                        append(line.substring(k + 2, end)); pop(); k = end + 2
                    } else {
                        append("**"); k += 2
                    }
                }
                line[k] == '`' -> {
                    val end = line.indexOf('`', k + 1)
                    if (end > 0) {
                        pushStyle(SpanStyle(color = C.Ice, background = C.Night))
                        append(line.substring(k + 1, end)); pop(); k = end + 1
                    } else {
                        append('`'); k++
                    }
                }
                else -> {
                    append(line[k]); k++
                }
            }
        }
        if (heading) addStyle(SpanStyle(fontWeight = FontWeight.Bold, color = C.Violet, fontSize = 16.sp), start, length)
    }
}

@Composable
private fun KeyCard(vm: AssistantViewModel) {
    val ctx = LocalContext.current
    var key by remember { mutableStateOf("") }
    var saved by remember { mutableStateOf(false) }
    AnimatedVisibility(!saved, enter = fadeIn(), exit = fadeOut()) {
        Column(
            Modifier
                .padding(16.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(C.Card)
                .border(1.dp, C.Line, RoundedCornerShape(20.dp))
                .padding(16.dp)
        ) {
            Text("Başlamak için bir adım", color = C.Text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            Spacer(Modifier.height(6.dp))
            Text(
                "Eclyra, Gemini ile düşünür. Google AI Studio'dan ücretsiz bir API anahtarı alıp buraya yapıştır.",
                color = C.Muted, fontSize = 13.sp
            )
            Spacer(Modifier.height(10.dp))
            OutlinedTextField(
                value = key, onValueChange = { key = it.trim() },
                singleLine = true,
                placeholder = { Text("AIza…") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp)
            )
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://aistudio.google.com/apikey")))
                }) { Text("Anahtar al →", color = C.Ice) }
                Spacer(Modifier.weight(1f))
                Button(
                    onClick = { vm.prefs.apiKey = key; saved = true },
                    enabled = key.length > 20,
                    colors = ButtonDefaults.buttonColors(containerColor = C.Violet)
                ) { Text("Kaydet") }
            }
        }
    }
}
