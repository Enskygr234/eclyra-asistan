package com.enskygr234.eclyra.ui

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
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.enskygr234.eclyra.AssistantViewModel
import com.enskygr234.eclyra.R
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable
fun HistoryScreen(vm: AssistantViewModel, onBack: () -> Unit) {
    LaunchedEffect(Unit) { vm.refreshChats() }
    val tr = Locale("tr", "TR")
    val zone = ZoneId.systemDefault()
    fun whenText(t: Long): String {
        val dt = Instant.ofEpochMilli(t).atZone(zone)
        val today = LocalDate.now(zone)
        return when (dt.toLocalDate()) {
            today -> "Bugün " + dt.format(DateTimeFormatter.ofPattern("HH:mm"))
            today.minusDays(1) -> "Dün " + dt.format(DateTimeFormatter.ofPattern("HH:mm"))
            else -> dt.format(DateTimeFormatter.ofPattern("d MMM, HH:mm", tr))
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .background(C.Night)
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) { Icon(painterResource(R.drawable.ic_back), "Geri", tint = C.Text) }
            Text("Sohbetler", color = C.Text, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
        }

        // yeni sohbet
        Row(
            Modifier
                .padding(horizontal = 16.dp, vertical = 4.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(Brush.linearGradient(listOf(C.Violet, C.VioletDeep)))
                .clickable { vm.newChat(); onBack() }
                .padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(painterResource(R.drawable.ic_add), null, tint = Color.White)
            Spacer(Modifier.width(10.dp))
            Text("Yeni sohbet", color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
        }

        if (vm.chatList.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Henüz kayıtlı sohbet yok", color = C.Muted, fontSize = 14.sp)
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                items(vm.chatList, key = { it.id }) { c ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(16.dp))
                            .background(C.Card)
                            .border(1.dp, C.Line, RoundedCornerShape(16.dp))
                            .clickable { vm.openChat(c.id); onBack() }
                            .padding(start = 14.dp, top = 10.dp, bottom = 10.dp, end = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(c.title, color = C.Text, fontSize = 15.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${whenText(c.updated)} · ${c.count} mesaj", color = C.Muted, fontSize = 12.sp)
                        }
                        IconButton(onClick = { vm.deleteChat(c.id) }) {
                            Icon(painterResource(R.drawable.ic_delete), "Sil", tint = C.Muted, modifier = Modifier.size(20.dp))
                        }
                    }
                }
            }
        }
    }
}
