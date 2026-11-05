package com.goodseed.shorts.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.core.content.ContextCompat
import com.goodseed.shorts.AppViewModel
import com.goodseed.shorts.logic.Palette
import com.goodseed.shorts.logic.VoiceAudio
import com.goodseed.shorts.data.VoiceMode
import kotlin.math.sqrt

// 0.3.0 내 목소리 ---------------------------------------------------------

/** 녹음 시작 버튼 색(경고색과 같은 빨강 — 흰 글자 대비 4.5:1 이상). */
private val RecordRed = Color(Palette.WARN)

@Composable
private fun ActionButton(text: String, onClick: () -> Unit, color: Color, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier.heightIn(min = 80.dp).clip(RoundedCornerShape(24.dp)).background(if (enabled) color else Color(0xFFB9B5D3))
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, fontSize = 28.sp, fontWeight = FontWeight.ExtraBold, fontFamily = LocalUiFont.current, color = if (enabled) Color.White else Color(0xFF3A384F), textAlign = TextAlign.Center) }
}

/** 소리 크기 막대(녹음 중에 목소리가 들어가고 있는지 눈으로 확인). 작은 소리도 보이게 제곱근으로 펼친다. */
@Composable
fun LevelMeter(level: Float, active: Boolean, modifier: Modifier = Modifier) {
    val shown = if (active) sqrt(level.coerceIn(0f, 1f)) else 0f
    val hot = shown > 0.93f
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("소리 크기", fontSize = 16.sp, color = Muted, modifier = Modifier.width(84.dp))
            Box(Modifier.weight(1f).height(26.dp).clip(RoundedCornerShape(13.dp)).background(Color(0xFFDDD8F5))) {
                Box(Modifier.fillMaxHeight().fillMaxWidth(shown.coerceAtLeast(0.02f)).clip(RoundedCornerShape(13.dp)).background(if (hot) Warn else Accent))
            }
        }
        Text(
            if (!active) "녹음 중에 여기에 목소리 크기가 나옵니다." else if (hot) "소리가 너무 큽니다. 폰을 조금 멀리 두세요." else if (shown < 0.12f) "목소리가 잘 안 들어갑니다. 폰을 입 가까이 두세요." else "잘 들어가고 있습니다.",
            fontSize = 15.sp, color = if (hot || (active && shown < 0.12f)) Warn else Muted, modifier = Modifier.padding(top = 4.dp),
        )
    }
}

fun openAppSettings(ctx: Context) {
    val i = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", ctx.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { ctx.startActivity(i) }
}

fun hasMicPermission(ctx: Context): Boolean = ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED

// ③-b 내 목소리 패널(목소리 단계 안) ----------------------------------------
@Composable
fun ColumnScope.MyVoicePanel(vm: AppViewModel, onPickFile: () -> Unit) {
    val p = vm.project
    val total = p.scenes.size
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        Note("내가 직접 읽은 목소리를 영상에 넣습니다. 녹음은 이 폰 안에만 저장되고 밖으로 보내지 않습니다.")
        Spacer(Modifier.height(10.dp))
        SoftCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("문장마다 녹음하기", fontSize = 22.sp, fontFamily = LocalUiFont.current, fontWeight = FontWeight.Bold, color = AccentDark)
                Text("권장 · 화면에 나온 문장을 보고 한 문장씩 읽습니다. 문장마다 길이가 정해져서 자막이 목소리에 정확히 맞습니다.", fontSize = 16.sp, color = Muted, lineHeight = 22.sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
                Text("녹음됨 ${p.recordedCount} / $total 문장", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = if (p.recordedCount == total && total > 0) Accent else Ink)
                Spacer(Modifier.height(10.dp))
                BigButton(
                    if (p.recordedCount == 0) "● 녹음 시작" else if (p.missingCount > 0) "● 이어서 녹음하기" else "녹음 보기·고치기",
                    onClick = { vm.openRecord() }, modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        Spacer(Modifier.height(12.dp))
        SoftCard(Modifier.fillMaxWidth()) {
            Column(Modifier.padding(16.dp)) {
                Text("녹음 파일 올리기", fontSize = 22.sp, fontFamily = LocalUiFont.current, fontWeight = FontWeight.Bold, color = AccentDark)
                Text("폰에 있는 녹음 파일 1개(m4a, mp3, wav, aac 등 · 녹음기 앱이나 카카오톡으로 받은 파일)를 골라 문장별로 자동으로 나눕니다. 나눈 위치는 다음 화면에서 고칠 수 있습니다.", fontSize = 16.sp, color = Muted, lineHeight = 22.sp, modifier = Modifier.padding(top = 4.dp, bottom = 8.dp))
                if (p.recordedCount > 0) Note("올리면 지금까지 녹음한 것이 이 파일로 바뀝니다.", Warn)
                Spacer(Modifier.height(8.dp))
                if (vm.voiceImport.busy) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(28.dp), color = Accent, strokeWidth = 3.dp)
                        Text(vm.voiceImport.message, fontSize = 17.sp, color = Ink, modifier = Modifier.padding(start = 12.dp))
                    }
                } else {
                    BigButton("녹음 파일 고르기", onClick = onPickFile, modifier = Modifier.fillMaxWidth(), filled = false)
                    if (vm.voiceImport.message.isNotBlank()) Text(vm.voiceImport.message, fontSize = 16.sp, color = if (vm.voiceImport.warn) Warn else Muted, lineHeight = 22.sp, modifier = Modifier.padding(top = 8.dp))
                }
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("문장별 상태", fontSize = 19.sp, fontFamily = LocalUiFont.current, fontWeight = FontWeight.Bold, color = AccentDark)
        Spacer(Modifier.height(6.dp))
        p.scenes.forEachIndexed { i, s ->
            Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(14.dp)).background(CardColor).padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("${i + 1}", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Accent, modifier = Modifier.width(30.dp))
                Text(s.text, fontSize = 16.sp, color = Ink, maxLines = 1, modifier = Modifier.weight(1f))
                if (s.myVoice != null) {
                    Icon(Icons.Filled.Check, null, tint = Accent, modifier = Modifier.size(20.dp))
                    Text(" ${"%.1f".format(s.myVoiceMs / 1000.0)}초", fontSize = 16.sp, color = Accent, fontWeight = FontWeight.Bold)
                } else Text("AI 목소리", fontSize = 15.sp, color = Muted)
            }
        }
        if (p.recordedCount == 0) { Spacer(Modifier.height(8.dp)); Note("먼저 [● 녹음 시작] 또는 [녹음 파일 고르기]로 목소리를 넣어 주세요.") }
        else if (p.missingCount > 0) { Spacer(Modifier.height(8.dp)); Note("녹음하지 않은 ${p.missingCount}개 문장은 [다음]을 누를 때 AI 목소리로 채울지 묻습니다.") }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun FillMissingDialog(vm: AppViewModel) {
    val n = vm.project.missingCount
    val aiOk = vm.voicesState == "ready"
    Dialog(onDismissRequest = { vm.closeFillAsk() }) {
        Surface(shape = RoundedCornerShape(24.dp), color = Color(Palette.PAGE_BOTTOM)) {
            Column(Modifier.padding(20.dp)) {
                Text("녹음하지 않은 문장이 ${n}개 있습니다", fontSize = 24.sp, fontFamily = LocalUiFont.current, fontWeight = FontWeight.Bold, color = AccentDark)
                Spacer(Modifier.height(10.dp))
                Text(
                    if (aiOk) "이 문장들은 AI 목소리로 읽어서 채울까요? 내 목소리와 AI 목소리가 섞여서 들립니다."
                    else "이 폰에서는 AI 목소리를 쓸 수 없어 채울 수 없습니다. 돌아가서 모든 문장을 녹음해 주세요.",
                    fontSize = 18.sp, lineHeight = 26.sp, color = if (aiOk) Ink else Warn,
                )
                Spacer(Modifier.height(14.dp))
                BigButton("AI 목소리로 채우기", onClick = { vm.fillMissingWithAi() }, modifier = Modifier.fillMaxWidth(), enabled = aiOk)
                Spacer(Modifier.height(8.dp))
                BigButton("돌아가서 녹음하기", onClick = { vm.closeFillAsk() }, modifier = Modifier.fillMaxWidth(), filled = false)
            }
        }
    }
}

// ③-c 문장별 녹음 ----------------------------------------------------------
@Composable
fun RecordScreen(vm: AppViewModel) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val p = vm.project
    val r = vm.rec
    val total = p.scenes.size
    val idx = r.index.coerceIn(0, (total - 1).coerceAtLeast(0))
    val scene = p.scenes.getOrNull(idx)
    val recorded = scene?.myVoice != null
    val last = idx >= total - 1
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> if (ok) vm.startRecording() else vm.micDenied(true) }
    val startRec = { if (hasMicPermission(ctx)) vm.startRecording() else permission.launch(Manifest.permission.RECORD_AUDIO) }
    val busy = r.recording || r.saving

    StepScaffold(4, "내 목소리 녹음", onBack = { vm.back() },
        primaryText = if (last) "녹음 마치기" else "다음 문장", primaryEnabled = !busy && (recorded || last), onPrimary = { vm.recNext() }) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 2.dp, bottom = 6.dp)) {
            Text("${idx + 1} / $total 문장", fontSize = 24.sp, fontFamily = LocalUiFont.current, fontWeight = FontWeight.ExtraBold, color = Ink)
            Spacer(Modifier.weight(1f))
            Text("녹음됨 ${p.recordedCount}", fontSize = 17.sp, color = Accent, fontWeight = FontWeight.Bold)
        }
        Box(Modifier.fillMaxWidth().height(10.dp).clip(RoundedCornerShape(5.dp)).background(Color(0xFFDDD8F5))) {
            Box(Modifier.fillMaxHeight().fillMaxWidth(if (total == 0) 0f else (idx + if (recorded) 1 else 0) / total.toFloat()).background(AccentBrush))
        }
        Spacer(Modifier.height(10.dp))
        SoftCard(Modifier.fillMaxWidth().weight(1f)) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(18.dp), verticalArrangement = Arrangement.Center) {
                Text(scene?.text.orEmpty(), fontSize = 28.sp, lineHeight = 42.sp, color = Ink, fontWeight = FontWeight.SemiBold)
            }
        }
        Spacer(Modifier.height(10.dp))
        LevelMeter(vm.recLevel, r.recording)
        Spacer(Modifier.height(6.dp))
        val status = when {
            r.recording -> "● 녹음 중… ${VoiceAudio.fmtMs(vm.recMillis).substringBefore('.')}  다 읽으면 [■ 멈춤]을 누르세요."
            r.saving -> "녹음을 다듬는 중…"
            recorded -> "✓ 녹음됨 (${"%.1f".format((scene?.myVoiceMs ?: 0) / 1000.0)}초). 마음에 들면 [다음 문장]을 누르세요."
            else -> "[● 녹음]을 누르고 위 문장을 읽으세요. 앞뒤의 조용한 부분은 저절로 잘라 줍니다."
        }
        Text(status, fontSize = 18.sp, lineHeight = 25.sp, fontWeight = FontWeight.SemiBold, color = if (r.recording) Warn else Ink)
        if (r.message.isNotBlank() && !r.saving) Text(r.message, fontSize = 16.sp, lineHeight = 22.sp, color = if (r.warn) Warn else Muted, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(8.dp))
        when {
            r.recording -> ActionButton("■ 멈춤", { vm.stopRecording() }, AccentDark, Modifier.fillMaxWidth())
            recorded -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ActionButton("● 다시 녹음", startRec, RecordRed, Modifier.weight(1f), enabled = !r.saving)
                if (r.playing) BigButton("■ 멈춤", { vm.stopPlayback() }, Modifier.weight(1f).heightIn(min = 80.dp), icon = null)
                else BigButton("들어보기", { vm.playRecorded() }, Modifier.weight(1f).heightIn(min = 80.dp), enabled = !r.saving, icon = Icons.Filled.PlayArrow)
            }
            else -> ActionButton("● 녹음", startRec, RecordRed, Modifier.fillMaxWidth(), enabled = !r.saving)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = { vm.recPrev() }, enabled = idx > 0 && !busy, modifier = Modifier.heightIn(min = MinTouch)) { Text("◀ 이전 문장", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (idx > 0 && !busy) AccentDark else Muted) }
            if (!recorded && !last) TextButton(onClick = { vm.recNext() }, enabled = !busy, modifier = Modifier.heightIn(min = MinTouch)) { Text("이 문장은 건너뛰기 ▶", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = if (!busy) AccentDark else Muted) }
        }
    }
    if (r.micDenied) MicPermissionDialog(
        onSettings = { openAppSettings(ctx) },
        onRetry = { vm.micDenied(false); permission.launch(Manifest.permission.RECORD_AUDIO) },
        onUseAi = { vm.micDenied(false); vm.setVoiceMode(VoiceMode.AI); vm.go(com.goodseed.shorts.Screen.VOICE) },
        onClose = { vm.micDenied(false) },
    )
}

@Composable
fun MicPermissionDialog(onSettings: () -> Unit, onRetry: () -> Unit, onUseAi: () -> Unit, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose) {
        Surface(shape = RoundedCornerShape(24.dp), color = Color(Palette.PAGE_BOTTOM)) {
            Column(Modifier.padding(20.dp).heightIn(max = 680.dp).verticalScroll(rememberScrollState())) {
                Text("마이크 사용을 허용해 주세요", fontSize = 24.sp, fontFamily = LocalUiFont.current, fontWeight = FontWeight.Bold, color = AccentDark)
                Spacer(Modifier.height(10.dp))
                Text("내 목소리를 녹음하려면 폰의 마이크가 필요합니다. 녹음은 이 폰 안에만 저장되고 밖으로 보내지 않습니다.", fontSize = 18.sp, lineHeight = 26.sp, color = Ink)
                Spacer(Modifier.height(8.dp))
                Text("허용 창이 다시 뜨지 않으면 [설정 열기] → '권한' → '마이크'를 '앱 사용 중에만 허용'으로 바꾼 뒤 돌아와 주세요.", fontSize = 17.sp, lineHeight = 24.sp, color = Muted)
                Spacer(Modifier.height(14.dp))
                BigButton("설정 열기", onClick = onSettings, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                BigButton("다시 허용 요청하기", onClick = onRetry, modifier = Modifier.fillMaxWidth(), filled = false)
                Spacer(Modifier.height(8.dp))
                BigButton("AI 목소리로 만들기", onClick = onUseAi, modifier = Modifier.fillMaxWidth(), filled = false)
                Spacer(Modifier.height(8.dp))
                TextButton(onClick = onClose, modifier = Modifier.fillMaxWidth().heightIn(min = MinTouch)) { Text("닫기", fontSize = 18.sp, color = Muted) }
            }
        }
    }
}

// ③-d 자막 맞추기(올린 녹음 파일의 문장 경계) --------------------------------
@Composable
fun AlignScreen(vm: AppViewModel) {
    val a = vm.align
    val p = vm.project
    if (a == null) { Note("맞출 녹음이 없습니다."); return }
    val total = a.pcm.durationMs
    StepScaffold(4, "자막 맞추기", onBack = { vm.back() }, primaryText = if (a.saving) "저장하는 중…" else "이대로 확정", primaryEnabled = !a.saving, onPrimary = { vm.alignConfirm() }) {
        Note("각 문장의 [들어보기]로 듣고, 시작이 어긋나면 ◀ ▶ 버튼으로 시작 시간을 옮겨 주세요. 한 문장은 다음 문장이 시작할 때까지입니다.")
        if (!a.confident) Text("문장 경계를 확실히 찾지 못해 글자 수로 어림잡았습니다. 모든 문장을 한 번씩 들어 확인해 주세요.", fontSize = 16.sp, lineHeight = 22.sp, color = Warn, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 4.dp))
        if (a.truncated) Text("파일이 10분보다 길어 앞의 10분만 읽었습니다.", fontSize = 16.sp, color = Warn, modifier = Modifier.padding(top = 4.dp))
        if (a.message.isNotBlank()) Text(a.message, fontSize = 17.sp, lineHeight = 24.sp, color = Warn, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
        Spacer(Modifier.height(8.dp))
        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            itemsIndexed(p.scenes, key = { _, s -> s.id }) { i, s ->
                val start = a.starts.getOrElse(i) { 0L }
                val end = a.starts.getOrElse(i + 1) { total }
                SoftCard(Modifier.fillMaxWidth(), color = if (a.playing == i) AccentSoft else CardColor) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(34.dp).clip(CircleShape).background(Accent), contentAlignment = Alignment.Center) { Text("${i + 1}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp) }
                            Text("시작 ${VoiceAudio.fmtMs(start)}  ·  길이 ${"%.1f".format((end - start) / 1000.0)}초", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = AccentDark, modifier = Modifier.padding(start = 10.dp))
                        }
                        Text(s.text, fontSize = 18.sp, lineHeight = 26.sp, color = Ink, maxLines = 3, modifier = Modifier.padding(vertical = 8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            BigButton("◀ 0.5초", { vm.alignShift(i, -500) }, Modifier.weight(1f), filled = false)
                            BigButton("0.5초 ▶", { vm.alignShift(i, 500) }, Modifier.weight(1f), filled = false)
                        }
                        Spacer(Modifier.height(8.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                            BigButton("◀ 0.1초", { vm.alignShift(i, -100) }, Modifier.weight(1f), filled = false)
                            BigButton("0.1초 ▶", { vm.alignShift(i, 100) }, Modifier.weight(1f), filled = false)
                        }
                        Spacer(Modifier.height(8.dp))
                        if (a.playing == i) BigButton("■ 멈춤", { vm.alignPlay(i) }, Modifier.fillMaxWidth())
                        else BigButton("들어보기", { vm.alignPlay(i) }, Modifier.fillMaxWidth(), icon = Icons.Filled.PlayArrow)
                    }
                }
            }
        }
    }
}
