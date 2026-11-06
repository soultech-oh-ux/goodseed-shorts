package com.goodseed.shorts.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goodseed.shorts.AppViewModel
import com.goodseed.shorts.logic.FreeMusic
import com.goodseed.shorts.logic.KoreanTopics
import com.goodseed.shorts.logic.SizeCaps

// ⑤-2 무료 음악 찾기 (Openverse 음악: freesound·jamendo 등, 키 필요 없음) ----------------------------
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MusicFindScreen(vm: AppViewModel) {
    val m = vm.music
    var text by remember { mutableStateOf(m.query) }
    StepScaffold(5, "무료 음악 찾기", onBack = { vm.back() }, primaryText = if (vm.project.music != null) "돌아가기 (음악을 골랐습니다)" else "돌아가기", onPrimary = { vm.back() }) {
        ScrollBody {
            Note("API 키 없이 쓸 수 있습니다. 누구나 쓰도록 공개된 음악(CC0 · 공개 도메인 · CC BY)만 보여 줍니다. ※ 권리를 확인한 자료만 쓰세요.", Muted)
            vm.project.music?.let { cur ->
                Spacer(Modifier.height(8.dp))
                Text("지금 배경 음악: ♪ ${cur.label}", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = AccentDark)
            }
            Text("분위기 고르기", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                KoreanTopics.MUSIC_CHIPS.forEach { (ko, en) -> ChoiceChip(ko, m.query == ko, { text = ko; vm.searchMusic(ko, en) }, Modifier.widthIn(min = 72.dp)) }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = text, onValueChange = { text = it.take(40) }, singleLine = true, modifier = Modifier.weight(1f),
                    label = { Text("직접 입력 (예: 잔잔한 피아노)") }, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 18.sp))
                Spacer(Modifier.width(8.dp))
                BigButton("찾기", onClick = { vm.searchMusic(text) }, enabled = text.isNotBlank() && !m.busy && m.downloadId == null, icon = Icons.Filled.Search)
            }
            if (m.busy) {
                Row(Modifier.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(28.dp), color = Accent, strokeWidth = 3.dp)
                    Text("  찾는 중입니다… (몇 초 걸릴 수 있습니다)", fontSize = 18.sp)
                }
            }
            if (m.message.isNotBlank()) { Spacer(Modifier.height(8.dp)); Note(m.message, if (m.items.isEmpty() && !m.message.startsWith("배경 음악으로")) Warn else Muted) }
            m.items.forEach { t -> Spacer(Modifier.height(10.dp)); MusicCard(vm, t) }
            Spacer(Modifier.height(10.dp))
            Text("자료 제공: Openverse(freesound·jamendo 등). 고른 곡의 제목·작가·이용 조건은 앱이 자동으로 기록해 영상 끝 글자와 설명란 문구로 만들어 줍니다. 곡이 영상보다 짧으면 처음부터 반복됩니다.", fontSize = 14.sp, color = Muted, lineHeight = 20.sp)
            Spacer(Modifier.height(8.dp))
        }
    }
}

@Composable
private fun MusicCard(vm: AppViewModel, t: FreeMusic) {
    val m = vm.music
    val a = t.attribution
    val playing = m.previewId == t.id
    val busy = m.downloadId == t.id
    SoftCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(a.title, fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Ink, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 25.sp)
            Text("작가: ${a.author}", fontSize = 16.sp, color = Ink, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(a.licenseLabel, fontSize = 16.sp, color = AccentDark, fontWeight = FontWeight.Bold)
                Text("  ·  길이 ${t.durationText()}" + (if (t.bytes > 0) "  ·  ${SizeCaps.mb(t.bytes)}" else ""), fontSize = 16.sp, color = Muted)
            }
            Spacer(Modifier.height(8.dp))
            if (busy) {
                Text(if (m.percent in 1..99) "내려받는 중… ${m.percent}%" else "내려받는 중…", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                if (m.percent in 1..99) LinearProgressIndicator(progress = { m.percent / 100f }, modifier = Modifier.fillMaxWidth().height(12.dp), color = Accent)
                else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(12.dp), color = Accent)
                Spacer(Modifier.height(8.dp))
                BigButton("받기 취소", onClick = { vm.cancelMusicDownload() }, modifier = Modifier.fillMaxWidth(), filled = false)
            } else {
                val isCurrent = vm.project.music?.attribution?.sourceUrl == a.sourceUrl
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BigButton(if (playing && m.previewLoading) "불러오는 중…" else if (playing) "멈춤" else "미리듣기", onClick = { vm.previewMusic(t) }, modifier = Modifier.weight(1f), filled = false,
                        icon = if (playing) Icons.Filled.Close else Icons.Filled.PlayArrow, enabled = m.downloadId == null)
                    BigButton(if (isCurrent) "넣었습니다" else "넣기", onClick = { vm.addMusic(t) }, modifier = Modifier.weight(1f), enabled = !isCurrent && m.downloadId == null, icon = Icons.Filled.Add)
                }
            }
        }
    }
}
