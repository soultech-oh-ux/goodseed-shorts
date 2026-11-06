package com.goodseed.shorts.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.goodseed.shorts.AppViewModel
import com.goodseed.shorts.Screen
import com.goodseed.shorts.logic.CreditText
import com.goodseed.shorts.logic.FreeKind
import com.goodseed.shorts.logic.FreeMedia
import com.goodseed.shorts.logic.KoreanTopics
import com.goodseed.shorts.logic.Palette
import com.goodseed.shorts.logic.SizeCaps

// ⑤-1 키 없이 무료 사진·영상 찾기 (위키미디어 공용 + Openverse) ----------------------------
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FreeFindScreen(vm: AppViewModel) {
    val f = vm.free
    var text by remember { mutableStateOf(f.query) }
    val photo = f.kind == FreeKind.PHOTO
    StepScaffold(5, "무료 사진·영상 찾기", onBack = { vm.back() }, primaryText = "돌아가기 (고른 자료 ${vm.project.media.size}개)", onPrimary = { vm.back() }) {
        ScrollBody {
            Note("API 키 없이 쓸 수 있습니다. 누구나 쓰도록 공개된 자료(CC0 · 공개 도메인 · CC BY)만 보여 줍니다. ※ 권리를 확인한 자료만 쓰세요.", Muted)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                ChoiceChip("사진", photo, { vm.setFreeKind(FreeKind.PHOTO) }, Modifier.weight(1f))
                ChoiceChip("영상", !photo, { vm.setFreeKind(FreeKind.VIDEO) }, Modifier.weight(1f))
            }
            Text("자주 찾는 주제", fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp, bottom = 6.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                KoreanTopics.CHIPS.forEach { (ko, _) ->
                    ChoiceChip(ko, f.query == ko, { text = ko; vm.searchFree(ko) }, Modifier.widthIn(min = 72.dp))
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(value = text, onValueChange = { text = it.take(40) }, singleLine = true, modifier = Modifier.weight(1f),
                    label = { Text("직접 입력 (예: 푸른 하늘)") }, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 18.sp))
                Spacer(Modifier.width(8.dp))
                BigButton("찾기", onClick = { vm.searchFree(text) }, enabled = text.isNotBlank() && !f.busy && f.downloadId == null, icon = Icons.Filled.Search)
            }
            if (f.busy) {
                Row(Modifier.padding(vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(28.dp), color = Accent, strokeWidth = 3.dp)
                    Text("  찾는 중입니다…", fontSize = 18.sp)
                }
            }
            if (f.message.isNotBlank()) { Spacer(Modifier.height(8.dp)); Note(f.message, if (f.items.isEmpty()) Warn else Muted) }
            if (f.otherWaiting && !f.busy) Text("다른 출처(Openverse)도 찾는 중…", fontSize = 15.sp, color = Muted, modifier = Modifier.padding(top = 6.dp))
            if (f.items.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                f.items.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        row.forEach { m -> FreeCard(m, vm.isFreeAdded(m), { vm.previewFree(m) }, Modifier.weight(1f)) }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
                if (f.next != null) BigButton(if (f.moreBusy) "불러오는 중…" else "더 보기", onClick = { vm.freeMore() }, enabled = !f.moreBusy, modifier = Modifier.fillMaxWidth(), filled = false)
            }
            Spacer(Modifier.height(10.dp))
            Text("자료 제공: 위키미디어 공용(Wikimedia Commons)${if (photo) " · Openverse" else ""}. 저작자는 각 자료의 작가이며, 앱이 작가·이용 조건을 자동으로 기록해 영상 끝 글자와 설명란 문구로 만들어 줍니다.", fontSize = 14.sp, color = Muted, lineHeight = 20.sp)
            Spacer(Modifier.height(8.dp))
        }
    }
    f.preview?.let { FreePreviewDialog(vm, it) }
}

@Composable
private fun FreeCard(m: FreeMedia, added: Boolean, onClick: () -> Unit, modifier: Modifier) {
    SoftCard(modifier, onClick = onClick) {
        Box(Modifier.fillMaxWidth().aspectRatio(0.85f).background(AccentSoft)) {
            val img = rememberUrlImage(m.thumbUrl)
            if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            if (m.kind == FreeKind.VIDEO) Text("영상 ${m.durationSec}초", Modifier.align(Alignment.BottomStart).padding(6.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xCC000000)).padding(horizontal = 6.dp, vertical = 2.dp), fontSize = 14.sp, color = Color.White)
            if (added) Box(Modifier.align(Alignment.TopEnd).padding(6.dp).size(34.dp).clip(CircleShape).background(Accent), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Check, "추가됨", tint = Color.White) }
        }
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            Text(m.attribution.title, fontSize = 16.sp, maxLines = 2, overflow = TextOverflow.Ellipsis, lineHeight = 20.sp)
            Text(m.attribution.licenseLabel, fontSize = 15.sp, color = AccentDark, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun FreePreviewDialog(vm: AppViewModel, m: FreeMedia) {
    val ctx = LocalContext.current
    val f = vm.free
    val busy = f.downloadId != null
    val a = m.attribution
    Dialog(onDismissRequest = { if (!busy) vm.previewFree(null) }) {
        Surface(shape = RoundedCornerShape(24.dp), color = Color(Palette.PAGE_BOTTOM)) {
            Column(Modifier.padding(18.dp).heightIn(max = 700.dp)) {
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    Box(Modifier.fillMaxWidth().height(260.dp).clip(RoundedCornerShape(16.dp)).background(AccentSoft)) {
                        val img = rememberUrlImage(m.thumbUrl)
                        if (img != null) Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                        if (m.kind == FreeKind.VIDEO) Text("영상 ${m.durationSec}초", Modifier.align(Alignment.BottomStart).padding(8.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xCC000000)).padding(horizontal = 8.dp, vertical = 3.dp), fontSize = 15.sp, color = Color.White)
                    }
                    Text(a.title, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Ink, lineHeight = 26.sp, modifier = Modifier.padding(top = 10.dp))
                    Text("작가: ${a.author}", fontSize = 17.sp, color = Ink, modifier = Modifier.padding(top = 4.dp))
                    Text("이용 조건: ${a.licenseLabel}", fontSize = 18.sp, color = AccentDark, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 4.dp))
                    Text(if (a.requiresCredit) "출처 표기가 필요한 자료입니다. 영상 끝에 작가 이름이 자동으로 들어가고, 설명란용 문구도 복사할 수 있습니다." else "출처 표기 의무가 없는 자료입니다. (그래도 출처를 적어 드립니다.)", fontSize = 15.sp, color = Muted, lineHeight = 21.sp, modifier = Modifier.padding(top = 2.dp))
                    val info = buildString {
                        append("제공: ${a.provider.label}")
                        if (m.width > 0 && m.height > 0) append(" · ${m.width}x${m.height}")
                        if (m.bytes > 0) append(" · 받는 크기 약 ${SizeCaps.mb(m.bytes)}")
                    }
                    Text(info, fontSize = 15.sp, color = Muted, modifier = Modifier.padding(top = 4.dp))
                    if (m.kind == FreeKind.VIDEO && m.mime.contains("webm")) Text("webm 형식 영상입니다. 대부분의 폰에서 되지만, 일부 폰에서는 영상 만들기 단계에서 막힐 수 있습니다(그때는 안내가 나옵니다).", fontSize = 14.sp, color = Muted, lineHeight = 20.sp, modifier = Modifier.padding(top = 4.dp))
                    if (f.message.isNotBlank() && !busy) Text(f.message, fontSize = 16.sp, color = Warn, lineHeight = 22.sp, modifier = Modifier.padding(top = 8.dp))
                }
                Spacer(Modifier.height(10.dp))
                if (busy) {
                    Text(if (f.percent in 1..99) "내려받는 중… ${f.percent}%" else "내려받는 중…", fontSize = 18.sp, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.height(6.dp))
                    if (f.percent in 1..99) LinearProgressIndicator(progress = { f.percent / 100f }, modifier = Modifier.fillMaxWidth().height(12.dp), color = Accent)
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(12.dp), color = Accent)
                    Spacer(Modifier.height(10.dp))
                    BigButton("받기 취소", onClick = { vm.cancelFreeDownload() }, modifier = Modifier.fillMaxWidth(), filled = false)
                } else {
                    val added = vm.isFreeAdded(m)
                    BigButton(if (added) "이미 넣었습니다" else "이 자료 넣기", onClick = { vm.addFree(m) }, enabled = !added, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BigButton("원본 페이지", onClick = { openUrl(ctx, a.sourceUrl) }, modifier = Modifier.weight(1f), filled = false)
                        BigButton("닫기", onClick = { vm.previewFree(null) }, modifier = Modifier.weight(1f), filled = false)
                    }
                }
            }
        }
    }
}

private fun openUrl(ctx: Context, url: String) {
    if (!url.startsWith("https://")) return
    runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
}

fun copyText(ctx: Context, label: String, text: String): Boolean = runCatching {
    (ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText(label, text)); true
}.getOrDefault(false)

/** 사진·영상 출처 표기 설정(영상 끝 글자 넣기/빼기)과 [출처 문구 복사]. 장면에 쓰인 무료 자료가 없으면 아무것도 보이지 않는다. */
@Composable
fun CreditSection(vm: AppViewModel) {
    val used = vm.project.usedAttributions()
    if (used.isEmpty()) return
    val ctx = LocalContext.current
    val p = vm.project
    SectionTitle("사진·영상 출처 표기")
    Note("무료 자료 ${used.size}개를 쓰고 있습니다. 작가와 이용 조건은 앱이 자동으로 기록했습니다.")
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        ChoiceChip("영상 끝에 넣기", p.showCredits, { vm.setShowCredits(true) }, Modifier.weight(1f))
        ChoiceChip("넣지 않기", !p.showCredits, { vm.setShowCredits(false) }, Modifier.weight(1f))
    }
    if (p.showCredits) Text("미리 보기: " + CreditText.onScreen(used), fontSize = 15.sp, color = Muted, lineHeight = 21.sp, modifier = Modifier.padding(top = 6.dp))
    else if (CreditText.anyRequired(used)) Note("CC BY 자료는 출처 표기가 필요합니다. 영상에 넣지 않으면 유튜브 설명란에 아래 [출처 문구 복사]로 붙여 넣어 주세요.", Warn)
    Spacer(Modifier.height(8.dp))
    CreditCopyButton(vm, Modifier.fillMaxWidth())
}

/** [출처 문구 복사] — 유튜브 설명란에 붙여 넣을 문구를 클립보드에 넣는다. */
@Composable
fun CreditCopyButton(vm: AppViewModel, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var msg by remember { mutableStateOf("") }
    val text = vm.creditDescription()
    if (text.isBlank()) return
    Column(modifier) {
        BigButton("출처 문구 복사 (유튜브 설명란용)", onClick = {
            msg = if (copyText(ctx, "출처 문구", text)) "복사했습니다. 유튜브 설명란에 붙여 넣으세요." else "복사하지 못했습니다."
        }, modifier = Modifier.fillMaxWidth(), filled = false)
        if (msg.isNotBlank()) Text(msg, fontSize = 16.sp, color = AccentDark, fontWeight = FontWeight.SemiBold, modifier = Modifier.padding(top = 6.dp))
    }
}
