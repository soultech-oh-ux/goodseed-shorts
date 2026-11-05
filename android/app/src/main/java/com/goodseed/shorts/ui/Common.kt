package com.goodseed.shorts.ui

import android.content.Context
import android.graphics.Typeface
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goodseed.shorts.data.MediaRef
import com.goodseed.shorts.data.MediaUtil
import com.goodseed.shorts.render.Fonts
import com.goodseed.shorts.render.SubFont
import com.goodseed.shorts.render.SubSize
import com.goodseed.shorts.render.SubtitleLayout
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

val Green = Color(0xFF1F6B44)
val GreenDark = Color(0xFF14452C)
val GreenSoft = Color(0xFFE6F3EB)
val Paper = Color(0xFFFFFBF5)
val Ink = Color(0xFF1B1B1B)
val Muted = Color(0xFF5D5D5D)
val Warn = Color(0xFFB3261E)

@Composable
fun ShortsTheme(content: @Composable () -> Unit) {
    val scheme = lightColorScheme(primary = Green, onPrimary = Color.White, secondary = GreenDark, background = Paper, surface = Color.White, onSurface = Ink, error = Warn)
    MaterialTheme(colorScheme = scheme, content = content)
}

/** 모든 화면의 틀: 위에 [이전]·단계 표시, 아래에 큰 주 버튼. */
@Composable
fun StepScaffold(
    step: Int, title: String, onBack: () -> Unit,
    primaryText: String, primaryEnabled: Boolean = true, onPrimary: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().background(Paper).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = 56.dp)) { Text("← 이전", fontSize = 20.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) }
            Spacer(Modifier.weight(1f))
            Text("$step / 6 단계", fontSize = 16.sp, color = Muted, modifier = Modifier.padding(end = 12.dp))
        }
        Text(title, fontSize = 26.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = GreenDark, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp), content = content)
        BigButton(primaryText, enabled = primaryEnabled, onClick = onPrimary, modifier = Modifier.fillMaxWidth().padding(16.dp))
    }
}

@Composable
fun BigButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, filled: Boolean = true) {
    if (filled) {
        Button(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 64.dp), shape = RoundedCornerShape(16.dp),
            colors = ButtonDefaults.buttonColors(containerColor = Green)) { Text(text, fontSize = 22.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold) }
    } else {
        OutlinedButton(onClick = onClick, enabled = enabled, modifier = modifier.heightIn(min = 60.dp), shape = RoundedCornerShape(16.dp),
            border = BorderStroke(2.dp, Green)) { Text(text, fontSize = 19.sp, color = GreenDark, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold) }
    }
}

@Composable
fun SectionTitle(text: String) = Text(text, fontSize = 21.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold, color = GreenDark, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))

@Composable
fun Note(text: String, color: Color = Muted) = Text(text, fontSize = 16.sp, color = color, lineHeight = 22.sp)

@Composable
fun ScrollBody(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 12.dp), content = content)

@Composable
fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.heightIn(min = 56.dp).clip(RoundedCornerShape(14.dp))
            .background(if (selected) Green else Color.White)
            .then(Modifier.clickable(onClick = onClick))
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 18.sp, color = if (selected) Color.White else Ink, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, textAlign = TextAlign.Center) }
}

// ---------- 그림 불러오기 ----------
object ThumbCache {
    private val map = object : android.util.LruCache<String, ImageBitmap>(40) {}
    fun get(k: String): ImageBitmap? = map.get(k)
    fun put(k: String, b: ImageBitmap) { map.put(k, b) }
}

@Composable
fun rememberThumb(ref: MediaRef?, maxSide: Int = 480): ImageBitmap? {
    val ctx = LocalContext.current
    val key = ref?.let { "${it.uri}#$maxSide" }
    var bmp by remember(key) { mutableStateOf(key?.let { ThumbCache.get(it) }) }
    LaunchedEffect(key) {
        if (ref != null && bmp == null) {
            val b = withContext(Dispatchers.IO) { MediaUtil.thumbnail(ctx, ref, maxSide)?.asImageBitmap() }
            if (b != null) { ThumbCache.put(key!!, b); bmp = b }
        }
    }
    return bmp
}

@Composable
fun rememberUrlImage(url: String): ImageBitmap? {
    var bmp by remember(url) { mutableStateOf(ThumbCache.get(url)) }
    LaunchedEffect(url) {
        if (bmp == null && url.isNotBlank()) {
            val b = withContext(Dispatchers.IO) {
                runCatching { android.graphics.BitmapFactory.decodeStream(URL(url).openStream())?.asImageBitmap() }.getOrNull()
            }
            if (b != null) { ThumbCache.put(url, b); bmp = b }
        }
    }
    return bmp
}

@Composable
fun Thumb(ref: MediaRef?, modifier: Modifier = Modifier) {
    val b = rememberThumb(ref, 360)
    if (b != null) Image(b, null, modifier, contentScale = ContentScale.Crop)
    else Box(modifier.background(GreenSoft), contentAlignment = Alignment.Center) { Text(if (ref?.isVideo == true) "🎬" else "🖼", fontSize = 24.sp) }
}

/**
 * 9:16 미리보기 한 장면. 합성(ShortsRenderer)과 같은 줄바꿈·위치 규칙으로 자막을 그린다.
 * bg == null 이면 기본 초록 배경.
 */
@Composable
fun ScenePreview(bg: ImageBitmap?, lines: List<String>, font: SubFont, size: SubSize, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    val tf: Typeface = remember(font) { Fonts.typeface(ctx, font) }
    val family = remember(tf) { FontFamily(tf) }
    BoxWithConstraints(modifier.aspectRatio(9f / 16f).clip(RoundedCornerShape(12.dp)).background(Brush.verticalGradient(listOf(Color(0xFF1F6B44), Color(0xFF0E2E1E))))) {
        val scale = constraints.maxWidth.toFloat() / SubtitleLayout.FRAME_W
        if (bg != null) Image(bg, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        val fontSp = with(androidx.compose.ui.platform.LocalDensity.current) { (size.px * scale).toSp() }
        Column(
            Modifier.layout { m, c ->
                val p = m.measure(c.copy(minWidth = 0, minHeight = 0))
                val frac = (1f - SubtitleLayout.BOTTOM_ANCHOR_Y) / 2f
                layout(c.maxWidth, c.maxHeight) { p.place((c.maxWidth - p.width) / 2, (frac * c.maxHeight - p.height / 2f).toInt()) }
            },
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            for (l in lines) Text("${SubtitleLayout.PAD_CHAR}$l${SubtitleLayout.PAD_CHAR}", fontSize = fontSp, fontFamily = family, color = Color.White, softWrap = false,
                modifier = Modifier.background(Color(0xB3000000)))
        }
    }
}
