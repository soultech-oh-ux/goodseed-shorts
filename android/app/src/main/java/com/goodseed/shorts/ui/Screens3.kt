package com.goodseed.shorts.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.goodseed.shorts.AppViewModel
import com.goodseed.shorts.Phase
import com.goodseed.shorts.Screen
import com.goodseed.shorts.data.MediaOrigin
import com.goodseed.shorts.data.Scene
import com.goodseed.shorts.render.Fonts
import com.goodseed.shorts.render.SubFont
import com.goodseed.shorts.render.SubSize
import com.goodseed.shorts.render.SubtitleLayout

// ⑤ 미리보기·수정 ---------------------------------------------------------
@Composable
fun PreviewScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val p = vm.project
    var sel by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<Long?>(null) }
    if (sel >= p.scenes.size) sel = (p.scenes.size - 1).coerceAtLeast(0)
    val scene = p.scenes.getOrNull(sel)
    val tf = remember(p.font) { Fonts.typeface(ctx, p.font) }

    StepScaffold(5, "미리보기와 수정", onBack = { vm.back() }, primaryText = "다음: 저장하기", primaryEnabled = p.scenes.isNotEmpty(), onPrimary = { vm.go(Screen.EXPORT) }) {
        if (scene == null) { Note("장면이 없습니다. 이전 단계로 돌아가 글을 넣어 주세요.", Warn); return@StepScaffold }
        val media = p.mediaFor(scene, sel)
        val bg = rememberThumb(media, 720)
        val lines = remember(scene.text, scene.size, p.font) { SubtitleLayout.lines(scene.text, tf, scene.size) }
        val pages = remember(lines, scene.size) { SubtitleLayout.pages(lines, scene.size) }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.Top) {
            ScenePreview(bg, pages.firstOrNull().orEmpty(), p.font, scene.size, Modifier.height(300.dp))
            Column(Modifier.padding(start = 12.dp)) {
                Text("${sel + 1}번째 장면 / ${p.scenes.size}", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = GreenDark)
                if (pages.size > 1) Note("글이 길어 자막이 ${pages.size}번에 나뉘어 나옵니다. (지금은 첫 장)")
                Spacer(Modifier.height(6.dp))
                Text("자막 글꼴", fontSize = 16.sp, fontWeight = FontWeight.Bold)
                SubFont.entries.forEach { f -> ChoiceChip(f.label, p.font == f, { vm.setFont(f) }, Modifier.fillMaxWidth().padding(top = 6.dp)) }
                Spacer(Modifier.height(6.dp))
                BigButton("▶ 듣기", onClick = { vm.synth.select(p.voice, p.speed.rate); vm.synth.speakPreview(scene.text) }, modifier = Modifier.fillMaxWidth(), filled = false)
            }
        }
        Spacer(Modifier.height(8.dp))
        Text("문장을 누르면 글자·사진·자막 크기를 고칠 수 있습니다", fontSize = 16.sp, color = Muted)
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp), contentPadding = PaddingValues(vertical = 8.dp)) {
            itemsIndexed(p.scenes, key = { _, s -> s.id }) { i, s ->
                val m = p.mediaFor(s, i)
                Row(
                    Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                        .background(if (i == sel) GreenSoft else Color.White)
                        .border(if (i == sel) BorderStroke(2.dp, Green) else BorderStroke(0.dp, Color.Transparent), RoundedCornerShape(12.dp))
                        .clickable { sel = i; editing = s.id }.padding(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("${i + 1}", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Green, modifier = Modifier.width(32.dp))
                    Text(s.text, fontSize = 18.sp, maxLines = 3, modifier = Modifier.weight(1f))
                    Spacer(Modifier.width(8.dp))
                    Thumb(m, Modifier.size(width = 44.dp, height = 66.dp).clip(RoundedCornerShape(6.dp)))
                }
            }
        }
    }
    editing?.let { id ->
        p.scenes.firstOrNull { it.id == id }?.let { s -> SceneEditor(vm, s, onClose = { editing = null }) } ?: run { editing = null }
    }
}

@Composable
private fun SceneEditor(vm: AppViewModel, s: Scene, onClose: () -> Unit) {
    val p = vm.project
    val pickMore = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia()) { uris -> if (uris.isNotEmpty()) vm.addMedia(uris, MediaOrigin.PHONE) }
    var text by remember(s.id) { mutableStateOf(s.text) }
    Dialog(onDismissRequest = onClose) {
        Surface(shape = RoundedCornerShape(20.dp), color = Paper) {
            Column(Modifier.padding(18.dp).heightIn(max = 640.dp).verticalScrollCompat()) {
                Text("장면 고치기", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = GreenDark)
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = text, onValueChange = { text = it; vm.updateScene(s.id) { sc -> sc.copy(text = it) } }, modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(fontSize = 20.sp, lineHeight = 28.sp), label = { Text("글자 (자막과 낭독에 모두 쓰입니다)") })
                Text("자막 크기", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SubSize.entries.forEach { z -> ChoiceChip(z.label, s.size == z, { vm.updateScene(s.id) { it.copy(size = z) } }, Modifier.weight(1f)) }
                }
                Text("배경 사진·영상", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
                LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item {
                        ChoiceBox(selected = s.mediaId == null, onClick = { vm.updateScene(s.id) { it.copy(mediaId = null) } }) { Text("자동", fontSize = 16.sp, fontWeight = FontWeight.Bold) }
                    }
                    items(p.media, key = { it.id }) { m ->
                        ChoiceBox(selected = s.mediaId == m.id, onClick = { vm.updateScene(s.id) { it.copy(mediaId = m.id) } }) { Thumb(m, Modifier.fillMaxSize()) }
                    }
                }
                Spacer(Modifier.height(6.dp))
                BigButton("사진·영상 더 고르기", onClick = { pickMore.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }, modifier = Modifier.fillMaxWidth(), filled = false)
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    BigButton("장면 지우기", onClick = { vm.deleteScene(s.id); onClose() }, modifier = Modifier.weight(1f), filled = false)
                    BigButton("완료", onClick = onClose, modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun ChoiceBox(selected: Boolean, onClick: () -> Unit, content: @Composable BoxScope.() -> Unit) {
    Box(
        Modifier.size(width = 72.dp, height = 108.dp).clip(RoundedCornerShape(10.dp)).background(GreenSoft)
            .border(if (selected) BorderStroke(4.dp, Green) else BorderStroke(1.dp, Color(0xFFCCCCCC)), RoundedCornerShape(10.dp))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center, content = content,
    )
}

@Composable
private fun Modifier.verticalScrollCompat(): Modifier = this.then(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()))

// ⑥ 저장·공유 --------------------------------------------------------------
@Composable
fun ExportScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    val e = vm.export
    val running = e.phase == Phase.NARRATION || e.phase == Phase.RENDER || e.phase == Phase.SAVING
    val view = LocalView.current
    DisposableEffect(running) { view.keepScreenOn = running; onDispose { view.keepScreenOn = false } }
    val p = vm.project
    StepScaffold(6, "영상 만들어 저장하기", onBack = { vm.back() },
        primaryText = when (e.phase) { Phase.DONE -> "처음으로"; Phase.ERROR -> "다시 시도"; else -> if (running) "만드는 중…" else "영상 만들기 시작" },
        primaryEnabled = !running,
        onPrimary = { when (e.phase) { Phase.DONE -> { vm.export = com.goodseed.shorts.ExportUi(); vm.go(Screen.HOME) }; else -> vm.startExport() } }) {
        ScrollBody {
            Note("영상 크기: 1080 x 1920 (세로 9:16) MP4 · 갤러리의 'Movies/굿씨드묵상숏츠' 에 저장됩니다.")
            Spacer(Modifier.height(8.dp))
            Text("장면 ${p.scenes.size}개 · 목소리 ${if (p.voice != null) "선택함" else "없음"} · 배경 ${p.media.size}개 · 음악 ${if (p.music != null) "있음" else "없음"}", fontSize = 18.sp)
            Spacer(Modifier.height(16.dp))
            when (e.phase) {
                Phase.IDLE -> {
                    if (e.message.isNotBlank()) Note(e.message)
                    Note("[영상 만들기 시작]을 누르면 낭독을 만든 뒤 영상을 합칩니다. 장면이 많으면 몇 분 걸릴 수 있습니다. 만드는 동안 이 앱을 켜 두세요.")
                }
                Phase.NARRATION, Phase.RENDER, Phase.SAVING -> {
                    Text(e.message, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(10.dp))
                    LinearProgressIndicator(progress = { e.percent / 100f }, modifier = Modifier.fillMaxWidth().height(14.dp), color = Green)
                    Text("${e.percent}%", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = GreenDark, modifier = Modifier.padding(top = 8.dp))
                    Spacer(Modifier.height(10.dp))
                    BigButton("취소", onClick = { vm.cancelExport() }, modifier = Modifier.fillMaxWidth(), filled = false)
                }
                Phase.ERROR -> Text(e.message, fontSize = 18.sp, color = Warn, lineHeight = 26.sp)
                Phase.DONE -> {
                    Text("✔ 영상이 저장되었습니다", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Green)
                    Spacer(Modifier.height(12.dp))
                    val uri = e.resultUri.orEmpty()
                    BigButton("▶ 영상 보기", onClick = { openVideo(ctx, uri) }, modifier = Modifier.fillMaxWidth(), filled = false)
                    Spacer(Modifier.height(10.dp))
                    BigButton("유튜브로 공유", onClick = { shareVideo(ctx, uri, "com.google.android.youtube") }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    BigButton("카카오톡으로 공유", onClick = { shareVideo(ctx, uri, "com.kakao.talk") }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    BigButton("다른 앱으로 공유", onClick = { shareVideo(ctx, uri, null) }, modifier = Modifier.fillMaxWidth(), filled = false)
                    Spacer(Modifier.height(10.dp))
                    Note("그 앱이 폰에 없으면 일반 공유창이 열립니다.")
                }
            }
        }
    }
}
