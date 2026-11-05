package com.goodseed.shorts.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.goodseed.shorts.AppViewModel
import com.goodseed.shorts.BuildConfig
import com.goodseed.shorts.Screen
import com.goodseed.shorts.UpdPhase
import com.goodseed.shorts.logic.Palette
import com.goodseed.shorts.render.Motion
import com.goodseed.shorts.render.Templates

// ③ 스타일(템플릿) 고르기 -----------------------------------------------------
@Composable
fun TemplateScreen(vm: AppViewModel) {
    val p = vm.project
    val sample = p.scenes.firstOrNull()?.text?.take(40) ?: HOME_SAMPLE
    StepScaffold(3, "영상 스타일을 고르세요", onBack = { vm.back() }, primaryText = "다음: 목소리 고르기", onPrimary = { vm.go(Screen.VOICE) }) {
        ScrollBody {
            Note("누르면 바로 적용됩니다. 나중에 미리보기 화면에서도 바꿀 수 있습니다.")
            Spacer(Modifier.height(10.dp))
            Templates.ALL.chunked(2).forEach { row ->
                Row(Modifier.fillMaxWidth().padding(bottom = 14.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    row.forEach { t ->
                        val sel = t.id == p.templateId
                        SoftCard(Modifier.weight(1f).then(if (sel) Modifier.border(BorderStroke(3.dp, Accent), RoundedCornerShape(22.dp)) else Modifier), onClick = { vm.chooseTemplate(t.id) }) {
                            Box {
                                TemplateThumb(t, sample, p.headerTitle, p.headerSource, Modifier.fillMaxWidth().aspectRatio(9f / 16f), outW = 420)
                                if (sel) Box(Modifier.align(Alignment.TopEnd).padding(8.dp).size(36.dp).clip(CircleShape).background(Accent), contentAlignment = Alignment.Center) {
                                    Icon(Icons.Filled.Check, null, tint = Color.White)
                                }
                            }
                            Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp)) {
                                Text(t.name, fontSize = 19.sp, fontFamily = LocalUiFont.current, fontWeight = FontWeight.Bold, color = Ink)
                                Text(t.tagline, fontSize = 15.sp, color = Muted)
                                if (t.motion != Motion.NONE) Text("사진: ${t.motion.label}", fontSize = 14.sp, color = Muted)
                                if (t.header != null) Text("제목·출처 줄 있음", fontSize = 14.sp, color = Muted)
                                if (t.endCard != null) Text("끝 화면 있음", fontSize = 14.sp, color = Muted)
                            }
                        }
                    }
                    if (row.size == 1) Spacer(Modifier.weight(1f))
                }
            }
            SectionTitle("제목과 출처 (선택)")
            Note("제목·출처 줄이 있는 스타일은 영상 위쪽에 이 글자를 보여 주고, 끝 화면이 있는 스타일은 끝에 한 장 붙입니다. 비워 두면 보이지 않습니다.")
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = p.headerTitle, onValueChange = { vm.setHeader(it.take(40), p.headerSource) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text("제목 (예: 오늘의 묵상)") }, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 19.sp))
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(value = p.headerSource, onValueChange = { vm.setHeader(p.headerTitle, it.take(40)) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                label = { Text("출처 (예: 시편 23:1-3)") }, textStyle = androidx.compose.ui.text.TextStyle(fontSize = 19.sp))
            Spacer(Modifier.height(10.dp))
        }
    }
}

// 새 버전 안내 ---------------------------------------------------------------
@Composable
fun UpdateBanner(vm: AppViewModel) {
    val u = vm.update
    val info = u.info ?: return
    SoftCard(Modifier.fillMaxWidth(), color = Color(Palette.BANNER)) {
        Column(Modifier.padding(16.dp)) {
            Text("📢 새 버전(${info.version})이 나왔습니다. 업데이트해 주세요.", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Color(Palette.BANNER_INK), lineHeight = 26.sp)
            Spacer(Modifier.height(6.dp))
            Text("[업데이트]를 누르면 새 버전을 받아 설치 화면이 열립니다.", fontSize = 16.sp, color = Color(Palette.BANNER_INK), lineHeight = 22.sp)
            Spacer(Modifier.height(10.dp))
            when (u.phase) {
                UpdPhase.DOWNLOADING -> {
                    Text(u.message, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color(Palette.BANNER_INK))
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(progress = { u.percent / 100f }, modifier = Modifier.fillMaxWidth().height(14.dp), color = Accent, trackColor = Color(0xFFE5D9B8))
                    Text("${u.percent}%", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = AccentDark, modifier = Modifier.padding(top = 6.dp, bottom = 8.dp))
                    BigButton("받기 취소", onClick = { vm.cancelUpdateDownload() }, modifier = Modifier.fillMaxWidth(), filled = false)
                }
                UpdPhase.READY -> {
                    Text(u.message, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = Color(Palette.BANNER_INK), lineHeight = 24.sp)
                    Spacer(Modifier.height(8.dp))
                    BigButton("설치 화면 다시 열기", onClick = { vm.startUpdate() }, modifier = Modifier.fillMaxWidth(), icon = Icons.Filled.Refresh)
                    Spacer(Modifier.height(8.dp))
                    BigButton("업데이트 방법", onClick = { vm.showUpdateHowTo(true) }, modifier = Modifier.fillMaxWidth(), filled = false)
                }
                else -> {
                    if (u.phase == UpdPhase.ERROR && u.message.isNotBlank()) {
                        Text(u.message, fontSize = 17.sp, color = Warn, fontWeight = FontWeight.SemiBold, lineHeight = 24.sp)
                        Spacer(Modifier.height(8.dp))
                    }
                    BigButton("업데이트", onClick = { vm.startUpdate() }, modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        BigButton("업데이트 방법", onClick = { vm.showUpdateHowTo(true) }, modifier = Modifier.weight(1f), filled = false)
                        BigButton("나중에", onClick = { vm.laterUpdate() }, modifier = Modifier.weight(1f), filled = false)
                    }
                }
            }
        }
    }
}

/** 업데이트 관련 창 3종(방법 안내·설치 허용 안내·업데이트 후 첫 실행 안내). 홈 화면 안에 둔다. */
@Composable
fun UpdateDialogs(vm: AppViewModel) {
    val u = vm.update
    if (u.showHowTo) UpdateHowToDialog(onClose = { vm.showUpdateHowTo(false) }, onDirect = { vm.openReleasePage() })
    if (u.phase == UpdPhase.NEED_PERMISSION) PermissionGuideDialog(onSettings = { vm.openInstallSettings() }, onClose = { vm.closePermissionGuide() })
    vm.whatsNew?.let { WhatsNewDialog(BuildConfig.VERSION_NAME, it, onClose = { vm.dismissWhatsNew() }) }
}

private val HOW_TO_STEPS = listOf(
    "홈 위쪽의 [업데이트] 버튼을 누릅니다.",
    "새 버전을 받는 동안 기다립니다. 진행률(%)이 올라갑니다.",
    "설치 화면이 뜨면 [업데이트] 또는 [설치]를 누릅니다.",
    "'출처를 알 수 없는 앱' 허용 화면이 뜨면 '이 출처 허용'을 켜고 뒤로 돌아옵니다. (처음 한 번만 필요합니다)",
    "설치가 끝나면 [열기]를 누르거나 앱을 다시 엽니다.",
)

@Composable
fun UpdateHowToDialog(onClose: () -> Unit, onDirect: () -> Unit) {
    Dialog(onDismissRequest = onClose) {
        Surface(shape = RoundedCornerShape(24.dp), color = Color(Palette.PAGE_BOTTOM)) {
            Column(Modifier.padding(20.dp).heightIn(max = 640.dp)) {
                Text("업데이트 방법", fontSize = 24.sp, fontFamily = LocalUiFont.current, fontWeight = FontWeight.Bold, color = AccentDark)
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(top = 10.dp)) {
                    HOW_TO_STEPS.forEachIndexed { i, t ->
                        Row(Modifier.padding(vertical = 6.dp), verticalAlignment = Alignment.Top) {
                            Box(Modifier.size(34.dp).clip(CircleShape).background(Accent), contentAlignment = Alignment.Center) {
                                Text("${i + 1}", color = Color.White, fontWeight = FontWeight.Bold, fontSize = 17.sp)
                            }
                            Text(t, fontSize = 18.sp, lineHeight = 26.sp, color = Ink, modifier = Modifier.padding(start = 12.dp).weight(1f))
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text("그래도 안 되면 아래 [직접 받기]로 인터넷 화면을 열어, 가장 위의 .apk 파일을 받아 설치하세요.", fontSize = 16.sp, color = Muted, lineHeight = 22.sp)
                }
                Spacer(Modifier.height(12.dp))
                BigButton("직접 받기 (인터넷 화면 열기)", onClick = onDirect, modifier = Modifier.fillMaxWidth(), filled = false)
                Spacer(Modifier.height(8.dp))
                BigButton("닫기", onClick = onClose, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
fun PermissionGuideDialog(onSettings: () -> Unit, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose) {
        Surface(shape = RoundedCornerShape(24.dp), color = Color(Palette.PAGE_BOTTOM)) {
            Column(Modifier.padding(20.dp)) {
                Text("설치 허용이 필요합니다", fontSize = 24.sp, fontFamily = LocalUiFont.current, fontWeight = FontWeight.Bold, color = AccentDark)
                Spacer(Modifier.height(10.dp))
                Text("이 폰에서 처음 업데이트할 때는 '출처를 알 수 없는 앱 설치'를 허용해야 합니다.\n\n[설정 열기]를 누른 뒤 '이 출처 허용'을 켜고, 뒤로 돌아와 [업데이트]를 다시 눌러 주세요.", fontSize = 18.sp, lineHeight = 26.sp, color = Ink)
                Spacer(Modifier.height(14.dp))
                BigButton("설정 열기", onClick = onSettings, modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(8.dp))
                BigButton("닫기", onClick = onClose, modifier = Modifier.fillMaxWidth(), filled = false)
            }
        }
    }
}

@Composable
fun WhatsNewDialog(version: String, notes: List<String>, onClose: () -> Unit) {
    Dialog(onDismissRequest = onClose) {
        Surface(shape = RoundedCornerShape(24.dp), color = Color(Palette.PAGE_BOTTOM)) {
            Column(Modifier.padding(20.dp).heightIn(max = 640.dp)) {
                Text("새 버전(${version})으로 업데이트되었습니다", fontSize = 24.sp, fontFamily = LocalUiFont.current, fontWeight = FontWeight.Bold, color = AccentDark)
                Text("바뀐 점", fontSize = 19.sp, fontWeight = FontWeight.Bold, color = Ink, modifier = Modifier.padding(top = 10.dp, bottom = 4.dp))
                Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                    notes.forEach { n ->
                        Row(Modifier.padding(vertical = 4.dp)) {
                            Text("•", fontSize = 18.sp, color = Accent, modifier = Modifier.width(18.dp))
                            Text(n, fontSize = 18.sp, lineHeight = 26.sp, color = Ink)
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
                BigButton("확인", onClick = onClose, modifier = Modifier.fillMaxWidth())
            }
        }
    }
}
