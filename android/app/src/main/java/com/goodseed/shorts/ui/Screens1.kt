package com.goodseed.shorts.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goodseed.shorts.AppViewModel
import com.goodseed.shorts.UpdPhase
import com.goodseed.shorts.render.Templates
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Check
import com.goodseed.shorts.Screen
import com.goodseed.shorts.data.RecentWork
import com.goodseed.shorts.data.Speed
import com.goodseed.shorts.data.VoiceMode
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

// ① 홈 ------------------------------------------------------------------
@Composable
fun HomeScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    var about by remember { mutableStateOf(false) }
    val u = vm.update
    val showBanner = u.info != null && !u.bannerHidden && u.phase != UpdPhase.IDLE
    LazyColumn(
        Modifier.fillMaxSize().background(PageBrush).statusBarsPadding().navigationBarsPadding().padding(horizontal = 20.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Spacer(Modifier.height(16.dp))
            Text("굿씨드 묵상숏츠", fontSize = 32.sp, fontFamily = LocalUiFont.current, fontWeight = FontWeight.ExtraBold, color = AccentDark)
            Text("글을 붙여넣으면 낭독과 자막이 있는 세로 영상이 됩니다.", fontSize = 17.sp, color = Muted, modifier = Modifier.padding(top = 6.dp))
        }
        if (showBanner) item { UpdateBanner(vm) }
        item { BigButton("새 묵상 영상 만들기", onClick = { vm.startNew() }, modifier = Modifier.fillMaxWidth().height(96.dp), icon = Icons.Filled.Add) }
        item {
            SectionTitle("영상 스타일")
            Note("마음에 드는 스타일을 누르면 그 스타일로 바로 시작합니다.")
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(14.dp), contentPadding = PaddingValues(end = 8.dp, bottom = 8.dp)) {
                items(Templates.ALL, key = { it.id }) { t ->
                    SoftCard(Modifier.width(132.dp), onClick = { vm.startNew(t.id) }) {
                        TemplateThumb(t, HOME_SAMPLE, "", "", Modifier.fillMaxWidth().aspectRatio(9f / 16f))
                        Text(t.name, fontSize = 17.sp, fontFamily = LocalUiFont.current, fontWeight = FontWeight.Bold, color = Ink, modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp), maxLines = 1)
                    }
                }
            }
        }
        item { SectionTitle("최근 작품") }
        if (vm.recents.isEmpty()) item { Note("아직 만든 영상이 없습니다. 위 버튼을 눌러 첫 영상을 만들어 보세요.") }
        else items(vm.recents, key = { it.uri }) { w -> RecentCard(w, onOpen = { openVideo(ctx, w.uri) }, onShare = { shareVideo(ctx, w.uri, null) }) }
        item {
            Spacer(Modifier.height(8.dp))
            if (!showBanner && u.message.isNotBlank()) Note(u.message, if (u.message.startsWith("새 버전을 확인하지")) Warn else Muted)
            BigButton(if (u.checking) "확인하는 중…" else "업데이트 확인", onClick = { vm.checkUpdate(manual = true) }, enabled = !u.checking && u.phase != UpdPhase.DOWNLOADING,
                modifier = Modifier.fillMaxWidth(), filled = false, icon = Icons.Filled.Refresh)
            Note("지금 버전 ${com.goodseed.shorts.BuildConfig.VERSION_NAME}")
            TextButton(onClick = { about = true }, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) {
                Text("굿씨드AI연구소 · 정보와 라이선스", fontSize = 15.sp, color = Muted)
            }
            Spacer(Modifier.height(12.dp))
        }
    }
    if (about) AboutDialog { about = false }
    UpdateDialogs(vm)
}

const val HOME_SAMPLE = "주님은 나의 목자시니 부족함이 없으리로다."

@Composable
private fun AboutDialog(onClose: () -> Unit) {
    val ctx = LocalContext.current
    val licenses = remember {
        listOf("OFL-Pretendard.txt" to "Pretendard 글꼴", "OFL-GowunBatang.txt" to "고운바탕(Gowun Batang) 글꼴").joinToString("\n\n") { (f, t) ->
            "■ $t — SIL Open Font License 1.1\n" + runCatching { ctx.assets.open("licenses/$f").bufferedReader().readText() }.getOrDefault("(라이선스 파일을 읽지 못했습니다)")
        }
    }
    androidx.compose.ui.window.Dialog(onDismissRequest = onClose) {
        Surface(shape = RoundedCornerShape(20.dp), color = PageBg) {
            Column(Modifier.padding(18.dp).heightIn(max = 640.dp)) {
                Text("굿씨드 묵상숏츠 ${com.goodseed.shorts.BuildConfig.VERSION_NAME}", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = AccentDark)
                Text("굿씨드AI연구소", fontSize = 17.sp, modifier = Modifier.padding(top = 4.dp))
                Column(Modifier.weight(1f, fill = false).verticalScroll(androidx.compose.foundation.rememberScrollState()).padding(top = 8.dp)) {
                    Text("자막 글꼴은 SIL Open Font License 1.1 로 배포되는 오픈 폰트이며 앱에 포함되어 있습니다. 무료 사진·영상 찾기의 자료는 위키미디어 공용·Openverse 가 제공하며(CC0·공개 도메인·CC BY 만 표시) 작가와 이용 조건을 앱이 기록합니다. Pexels 영상 찾기(선택)의 자료는 Pexels 가 제공합니다(Provided by Pexels). 권리를 확인한 자료만 쓰세요.", fontSize = 16.sp, lineHeight = 22.sp)
                    Spacer(Modifier.height(10.dp))
                    Text(licenses, fontSize = 12.sp, lineHeight = 16.sp, color = Muted)
                }
                Spacer(Modifier.height(10.dp))
                BigButton("닫기", onClick = onClose, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun RecentCard(w: RecentWork, onOpen: () -> Unit, onShare: () -> Unit) {
    val thumb = remember(w.thumbPath) { runCatching { android.graphics.BitmapFactory.decodeFile(w.thumbPath)?.asImageBitmap() }.getOrNull() }
    SoftCard(Modifier.fillMaxWidth(), onClick = onOpen) {
        Row(Modifier.padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.width(54.dp).height(96.dp).clip(RoundedCornerShape(10.dp)).background(AccentSoft)) {
                if (thumb != null) androidx.compose.foundation.Image(thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Column(Modifier.weight(1f).padding(horizontal = 12.dp)) {
                Text(w.title, fontSize = 18.sp, fontWeight = FontWeight.SemiBold, maxLines = 2)
                Text(SimpleDateFormat("M월 d일 HH:mm", Locale.KOREA).format(Date(w.savedAtMillis)), fontSize = 15.sp, color = Muted)
            }
            BigButton("공유", onClick = onShare, filled = false, icon = Icons.Filled.Share)
        }
    }
}

// ② 글 붙여넣기 -----------------------------------------------------------
@Composable
fun PasteScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    var text by remember { mutableStateOf(vm.project.rawText) }
    val sentences = remember(text) { vm.sentencesOf(text) }
    StepScaffold(2, "글을 붙여넣으세요", onBack = { vm.back() }, primaryText = "다음: 스타일 고르기", primaryEnabled = sentences.isNotEmpty(),
        onPrimary = { vm.acceptText(text); vm.go(Screen.TEMPLATE) }) {
        ScrollBody {
            OutlinedTextField(
                value = text, onValueChange = { text = it },
                modifier = Modifier.fillMaxWidth().heightIn(min = 220.dp),
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 20.sp, lineHeight = 30.sp),
                placeholder = { Text("여기에 묵상 글을 붙여넣거나 직접 쓰세요.", fontSize = 18.sp) },
            )
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                BigButton("붙여넣기", onClick = { clipboardText(ctx)?.let { text = if (text.isBlank()) it else text + "\n" + it } }, modifier = Modifier.weight(1f), filled = false)
                BigButton("모두 지우기", onClick = { text = "" }, modifier = Modifier.weight(1f), filled = false)
            }
            Spacer(Modifier.height(10.dp))
            if (sentences.isEmpty()) Note("글을 넣으면 문장마다 한 장면으로 나뉩니다.")
            else {
                Text("${sentences.size}개 장면으로 나뉩니다", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = AccentDark)
                Note("한 문장이 자막 한 장, 낭독 한 구간이 됩니다. 마침표·물음표·느낌표와 줄바꿈에서 나뉩니다.")
                if (sentences.size > 30) Note("장면이 ${sentences.size}개로 많습니다. 영상이 길어지고 만드는 시간도 오래 걸립니다.", Warn)
                Spacer(Modifier.height(6.dp))
                sentences.take(6).forEachIndexed { i, s ->
                    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(14.dp)).background(CardColor).padding(12.dp)) {
                        Text("${i + 1}", fontSize = 17.sp, fontWeight = FontWeight.Bold, color = Accent, modifier = Modifier.width(30.dp))
                        Text(s, fontSize = 17.sp)
                    }
                }
                if (sentences.size > 6) Note("… 그 밖에 ${sentences.size - 6}개 장면")
            }
        }
    }
}

// ③ 목소리 고르기 ---------------------------------------------------------
@Composable
fun VoiceScreen(vm: AppViewModel) {
    val ctx = LocalContext.current
    LaunchedEffect(Unit) { vm.loadVoices() }
    val p = vm.project
    val mine = p.voiceMode == VoiceMode.MINE
    val picker = androidx.activity.compose.rememberLauncherForActivityResult(androidx.activity.result.contract.ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importVoiceFile(uri) }
    val ready = if (mine) p.recordedCount > 0 && !vm.voiceImport.busy else vm.voicesState == "ready" && p.voice != null
    StepScaffold(4, "목소리를 고르세요", onBack = { vm.back() }, primaryText = "다음: 배경과 음악", primaryEnabled = ready, onPrimary = { vm.voiceNext() }) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(top = 4.dp, bottom = 8.dp)) {
            ChoiceChip("AI 목소리", !mine, { vm.setVoiceMode(VoiceMode.AI) }, Modifier.weight(1f))
            ChoiceChip("내 목소리", mine, { vm.setVoiceMode(VoiceMode.MINE) }, Modifier.weight(1f))
        }
        if (mine) MyVoicePanel(vm, onPickFile = { picker.launch(arrayOf("audio/*", "video/mp4", "application/ogg")) })
        else AiVoicePanel(vm, ctx)
    }
    if (vm.voiceFillAsk) FillMissingDialog(vm)
}

@Composable
private fun ColumnScope.AiVoicePanel(vm: AppViewModel, ctx: Context) {
    Text("읽는 속도", fontSize = 19.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 6.dp, bottom = 6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Speed.entries.forEach { s -> ChoiceChip(s.label, vm.project.speed == s, { vm.chooseSpeed(s) }, Modifier.weight(1f)) }
    }
    Spacer(Modifier.height(8.dp))
    when (vm.voicesState) {
        "loading" -> Note("폰의 음성 목록을 불러오는 중…")
        "none" -> {
            Note("이 폰에서 한국어 음성을 찾지 못했습니다. 폰 설정의 '텍스트 음성 변환'에서 한국어 음성을 설치한 뒤 다시 들어오세요. (또는 위의 [내 목소리]로 직접 녹음할 수 있습니다.)", Warn)
            Spacer(Modifier.height(8.dp))
            BigButton("음성 설정 열기", onClick = { openTtsSettings(ctx) }, modifier = Modifier.fillMaxWidth(), filled = false)
        }
        else -> LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            items(vm.voices, key = { it.name }) { v ->
                val sel = vm.project.voice?.name == v.name
                SoftCard(Modifier.fillMaxWidth(), onClick = { vm.chooseVoice(v) }, color = if (sel) AccentSoft else CardColor) {
                    Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(v.label, fontSize = 19.sp, fontWeight = FontWeight.SemiBold)
                            if (sel) Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Filled.Check, null, tint = Accent, modifier = Modifier.size(20.dp))
                                Text(" 선택됨", fontSize = 16.sp, color = Accent, fontWeight = FontWeight.Bold)
                            }
                            if (v.needsNetwork) Text("인터넷이 없으면 영상을 만들 수 없습니다", fontSize = 14.sp, color = Warn)
                        }
                        BigButton("들어보기", onClick = { vm.chooseVoice(v); vm.previewVoice() }, filled = false, icon = Icons.Filled.PlayArrow)
                    }
                }
            }
        }
    }
}

fun clipboardText(ctx: Context): String? {
    val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(ctx)?.toString()
}

fun openTtsSettings(ctx: Context) {
    val i = Intent("com.android.settings.TTS_SETTINGS").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { ctx.startActivity(i) }.onFailure { runCatching { ctx.startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
}

fun openVideo(ctx: Context, uri: String) {
    val i = Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(uri), "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { ctx.startActivity(i) }
}

/** pkg 가 있으면 그 앱으로 바로 공유(설치 안 됐으면 일반 공유창). */
fun shareVideo(ctx: Context, uri: String, pkg: String?) {
    val send = Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, Uri.parse(uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    val direct = pkg != null && ctx.packageManager.getLaunchIntentForPackage(pkg) != null
    if (direct) send.setPackage(pkg)
    val chosen = if (direct) send.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) else Intent.createChooser(send, "영상 공유").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    runCatching { ctx.startActivity(chosen) }.onFailure { runCatching { ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, Uri.parse(uri)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "영상 공유").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) } }
}
