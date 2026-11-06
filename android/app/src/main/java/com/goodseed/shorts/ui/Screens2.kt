package com.goodseed.shorts.ui

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goodseed.shorts.AppViewModel
import com.goodseed.shorts.Screen
import com.goodseed.shorts.data.MediaOrigin

// ④ 배경·음악 ------------------------------------------------------------
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MediaScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val pickPhone = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris -> if (uris.isNotEmpty()) vm.addMedia(uris, MediaOrigin.PHONE) }
    val pickFiles = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        uris.forEach { runCatching { ctx.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        if (uris.isNotEmpty()) vm.addMedia(uris, MediaOrigin.IMPORTED)
    }
    val pickMusic = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri?.let { runCatching { ctx.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }; vm.chooseMusic(it) }
    }
    val p = vm.project
    StepScaffold(5, "배경과 음악", onBack = { vm.back() }, primaryText = "다음: 미리보기·수정", onPrimary = { vm.go(Screen.PREVIEW) }) {
        ScrollBody {
            Text("※ 권리를 확인한 자료만 쓰세요.", fontSize = 17.sp, color = Warn, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
            vm.notice?.let { Note(it, Warn) }

            SectionTitle("배경 사진·영상 (선택)")
            Note("고른 순서대로 장면에 돌아가며 들어갑니다. 아무것도 고르지 않으면 고른 스타일의 기본 배경이 쓰입니다.")
            Spacer(Modifier.height(6.dp))
            BigButton("내 사진·영상 고르기", onClick = { pickPhone.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }, modifier = Modifier.fillMaxWidth(), filled = false)
            Spacer(Modifier.height(8.dp))
            BigButton("파일에서 가져오기 (굿씨드다운으로 받은 영상)", onClick = { pickFiles.launch(arrayOf("video/*", "image/*")) }, modifier = Modifier.fillMaxWidth(), filled = false)
            if (p.media.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                Text("고른 자료 ${p.media.size}개 (번호 = 순서)", fontSize = 18.sp, fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
                    p.media.forEachIndexed { i, m ->
                        Box(Modifier.size(width = 112.dp, height = 168.dp).clip(RoundedCornerShape(14.dp))) {
                            Thumb(m, Modifier.fillMaxSize())
                            Box(Modifier.align(Alignment.TopStart).padding(6.dp).size(32.dp).clip(CircleShape).background(Accent), contentAlignment = Alignment.Center) {
                                Text("${i + 1}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            }
                            if (m.isVideo) Text("영상", Modifier.align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xCC000000)).padding(horizontal = 6.dp, vertical = 2.dp), fontSize = 14.sp, color = Color.White)
                            Box(Modifier.align(Alignment.TopEnd).size(56.dp).clickable(role = androidx.compose.ui.semantics.Role.Button) { vm.removeMedia(m.id) }, contentAlignment = Alignment.Center) {
                                Box(Modifier.size(34.dp).clip(CircleShape).background(Color(0xCC000000)), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Close, "빼기", tint = Color.White) }
                            }
                        }
                    }
                }
                TextButton(onClick = { vm.clearMedia() }, modifier = Modifier.heightIn(min = 56.dp)) { Text("모두 빼기", fontSize = 17.sp) }
            }

            SectionTitle("무료 사진·영상 찾기 (키 필요 없음)")
            Note("위키미디어 공용·Openverse 에서 누구나 쓰도록 공개된 사진·영상을 찾아 넣습니다. 작가와 이용 조건은 앱이 자동으로 기록합니다.")
            Spacer(Modifier.height(6.dp))
            BigButton("무료 사진·영상 찾기", onClick = { vm.openFree() }, modifier = Modifier.fillMaxWidth())
            CreditSection(vm)

            SectionTitle("배경 음악 (선택)")
            BigButton(if (p.music == null) "내 폰에서 음악 고르기" else "다른 음악 고르기", onClick = { pickMusic.launch(arrayOf("audio/*")) }, modifier = Modifier.fillMaxWidth(), filled = false)
            Spacer(Modifier.height(8.dp))
            BigButton("무료 음악 찾기 (키 필요 없음)", onClick = { vm.openMusic() }, modifier = Modifier.fillMaxWidth(), filled = false)
            p.music?.let { m ->
                Spacer(Modifier.height(8.dp))
                Text("♪ ${m.label}", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                Note("낭독이 나올 때는 음악이 자동으로 작아지고, 낭독이 끝나면 다시 커집니다.")
                Spacer(Modifier.height(6.dp))
                Text("음악 크기", fontSize = 17.sp, fontWeight = FontWeight.Bold)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf("작게" to 0.3f, "보통" to 0.5f, "크게" to 0.8f).forEach { (l, v) ->
                        ChoiceChip(l, kotlin.math.abs(p.musicVolume - v) < 0.01f, { vm.setMusicVolume(v) }, Modifier.weight(1f))
                    }
                }
                TextButton(onClick = { vm.clearMusic() }, modifier = Modifier.heightIn(min = 56.dp)) { Text("음악 빼기", fontSize = 17.sp) }
            }
            SectionTitle("그 밖의 선택 기능")
            PexelsSection(vm)
            Spacer(Modifier.height(10.dp))
        }
    }
}

@Composable
private fun PexelsSection(vm: AppViewModel) {
    var keyInput by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    var show by remember { mutableStateOf(false) }
    val ctx = LocalContext.current
    Text("Pexels 영상 찾기 (선택 · 내 API 키가 있을 때만)", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = AccentDark, modifier = Modifier.padding(bottom = 4.dp))
    if (vm.pexelsKey.isBlank()) {
        Note("Pexels 사이트에서 무료로 받은 API 키를 넣으면 영상 검색을 쓸 수 있습니다. 키가 없으면 이 기능은 꺼져 있습니다.")
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(value = keyInput, onValueChange = { keyInput = it }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            label = { Text("Pexels API 키") }, visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 18.sp))
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 6.dp)) {
            BigButton(if (show) "키 가리기" else "키 보기", onClick = { show = !show }, modifier = Modifier.weight(1f), filled = false)
            BigButton("키 저장", onClick = { vm.savePexelsKey(keyInput) }, modifier = Modifier.weight(1f), enabled = keyInput.isNotBlank())
        }
    } else {
        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true, modifier = Modifier.weight(1f),
                label = { Text("찾을 낱말 (예: 바다, 숲, 새벽)") }, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 18.sp))
            Spacer(Modifier.width(8.dp))
            BigButton("찾기", onClick = { vm.searchPexels(query) }, enabled = query.isNotBlank() && !vm.pexels.busy)
        }
        if (vm.pexels.message.isNotBlank()) Note(vm.pexels.message, if (vm.pexels.results.isEmpty()) Warn else Muted)
        vm.pexels.results.forEach { v ->
            Row(Modifier.fillMaxWidth().padding(vertical = 5.dp).clip(RoundedCornerShape(12.dp)).background(CardColor).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val img = rememberUrlImage(v.thumbUrl)
                Box(Modifier.size(width = 60.dp, height = 90.dp).clip(RoundedCornerShape(8.dp)).background(AccentSoft)) {
                    if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                }
                Column(Modifier.weight(1f).padding(horizontal = 10.dp)) {
                    Text("${v.durationSec}초 · ${v.photographer}", fontSize = 16.sp)
                    Text("${v.width}x${v.height}", fontSize = 14.sp, color = Muted)
                }
                BigButton("추가", onClick = { vm.addPexels(v) }, enabled = !vm.pexels.busy)
            }
        }
        TextButton(onClick = { vm.savePexelsKey("") }, modifier = Modifier.heightIn(min = 56.dp)) { Text("키 지우기", fontSize = 16.sp) }
    }
    Spacer(Modifier.height(4.dp))
    Box(Modifier.heightIn(min = 56.dp).clickable(role = androidx.compose.ui.semantics.Role.Button) { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.pexels.com")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }, contentAlignment = Alignment.CenterStart) {
        Text("Provided by Pexels", fontSize = 15.sp, color = Accent, fontWeight = FontWeight.SemiBold)
    }
}
