package com.goodseed.shorts.ui

import android.graphics.Bitmap
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.goodseed.shorts.data.MediaRef
import com.goodseed.shorts.data.MediaUtil
import com.goodseed.shorts.logic.Palette
import com.goodseed.shorts.render.FrameComposer
import com.goodseed.shorts.render.Fonts
import com.goodseed.shorts.render.SubFont
import com.goodseed.shorts.render.SubSize
import com.goodseed.shorts.render.SubtitleLayout
import com.goodseed.shorts.render.VideoTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URL

val Accent = Color(Palette.ACCENT)
val Accent2 = Color(Palette.ACCENT2)
val AccentDark = Color(Palette.ACCENT_DARK)
val AccentSoft = Color(Palette.ACCENT_SOFT)
val CardColor = Color(Palette.CARD)
val PageBg = Color(Palette.PAGE_BOTTOM)
val Ink = Color(Palette.INK)
val Muted = Color(Palette.MUTED)
val Warn = Color(Palette.WARN)
val PageBrush = Brush.verticalGradient(listOf(Color(Palette.PAGE_TOP), Color(Palette.PAGE_BOTTOM)))
val AccentBrush = Brush.horizontalGradient(listOf(Accent, Accent2))

/** 터치 칸 최소 크기(고령 사용자를 위해 56dp 이상). */
val MinTouch: Dp = 56.dp

/** 제목·버튼 글꼴(Pretendard Bold, SIL OFL 1.1 — 앱에 포함된 글꼴). */
val LocalUiFont = staticCompositionLocalOf<FontFamily> { FontFamily.Default }

@Composable
fun ShortsTheme(content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val ui = remember { runCatching { FontFamily(Font("fonts/Pretendard-Bold.otf", ctx.assets, FontWeight.Bold)) }.getOrDefault(FontFamily.Default) }
    val scheme = lightColorScheme(primary = Accent, onPrimary = Color.White, secondary = AccentDark, background = Color(Palette.PAGE_BOTTOM), surface = CardColor, onSurface = Ink, error = Warn)
    CompositionLocalProvider(LocalUiFont provides ui) { MaterialTheme(colorScheme = scheme, content = content) }
}

/** 둥근 흰 카드 + 부드러운 그림자. */
@Composable
fun SoftCard(modifier: Modifier = Modifier, onClick: (() -> Unit)? = null, color: Color = CardColor, content: @Composable ColumnScope.() -> Unit) {
    val shape = RoundedCornerShape(22.dp)
    val base = modifier.shadow(6.dp, shape, ambientColor = AccentDark.copy(alpha = 0.18f), spotColor = AccentDark.copy(alpha = 0.18f)).clip(shape).background(color)
    Column(if (onClick != null) base.clickable(role = Role.Button, onClick = onClick) else base, content = content)
}

/** 모든 화면의 틀: 위에 [이전]·단계 표시, 아래에 큰 주 버튼. */
@Composable
fun StepScaffold(
    step: Int, title: String, onBack: () -> Unit,
    primaryText: String, primaryEnabled: Boolean = true, onPrimary: () -> Unit,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.fillMaxSize().background(PageBrush).statusBarsPadding().navigationBarsPadding().imePadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack, modifier = Modifier.heightIn(min = MinTouch)) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = AccentDark)
                Spacer(Modifier.width(4.dp))
                Text("이전", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = AccentDark, fontFamily = LocalUiFont.current)
            }
            Spacer(Modifier.weight(1f))
            StepDots(step, TOTAL_STEPS)
            Text("$step / $TOTAL_STEPS", fontSize = 16.sp, color = Muted, modifier = Modifier.padding(start = 8.dp, end = 12.dp))
        }
        Text(title, fontSize = 27.sp, fontFamily = LocalUiFont.current, fontWeight = FontWeight.Bold, color = AccentDark, modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp))
        Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal = 20.dp), content = content)
        BigButton(primaryText, enabled = primaryEnabled, onClick = onPrimary, modifier = Modifier.fillMaxWidth().padding(16.dp))
    }
}

const val TOTAL_STEPS = 7

@Composable
private fun StepDots(step: Int, total: Int) {
    Row(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalAlignment = Alignment.CenterVertically) {
        for (i in 1..total) Box(Modifier.size(width = if (i == step) 22.dp else 8.dp, height = 8.dp).clip(RoundedCornerShape(4.dp)).background(if (i <= step) Accent else Color(0xFFCFC9EE)))
    }
}

@Composable
fun BigButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, filled: Boolean = true, icon: ImageVector? = null) {
    val shape = RoundedCornerShape(20.dp)
    val font = LocalUiFont.current
    if (filled) {
        Row(
            modifier.heightIn(min = 64.dp).clip(shape)
                .background(if (enabled) AccentBrush else Brush.horizontalGradient(listOf(Color(0xFFB9B5D3), Color(0xFFB9B5D3))))
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(26.dp)); Spacer(Modifier.width(8.dp)) }
            Text(text, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = font, color = if (enabled) Color.White else Color(0xFF3A384F), textAlign = TextAlign.Center)
        }
    } else {
        Row(
            modifier.heightIn(min = 60.dp).clip(shape).background(CardColor).border(BorderStroke(2.dp, if (enabled) Accent else Color(0xFFB9B5D3)), shape)
                .clickable(enabled = enabled, role = Role.Button, onClick = onClick).padding(horizontal = 14.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
        ) {
            if (icon != null) { Icon(icon, null, tint = AccentDark, modifier = Modifier.size(24.dp)); Spacer(Modifier.width(8.dp)) }
            Text(text, fontSize = 19.sp, color = if (enabled) AccentDark else Muted, fontWeight = FontWeight.Bold, fontFamily = font, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun SectionTitle(text: String) = Text(text, fontSize = 21.sp, fontFamily = LocalUiFont.current, fontWeight = FontWeight.Bold, color = AccentDark, modifier = Modifier.padding(top = 16.dp, bottom = 6.dp))

@Composable
fun Note(text: String, color: Color = Muted) = Text(text, fontSize = 16.sp, color = color, lineHeight = 22.sp)

@Composable
fun ScrollBody(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) =
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 12.dp), content = content)

@Composable
fun ChoiceChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val shape = RoundedCornerShape(16.dp)
    Box(
        modifier.heightIn(min = MinTouch).clip(shape)
            .background(if (selected) AccentBrush else Brush.horizontalGradient(listOf(CardColor, CardColor)))
            .then(if (selected) Modifier else Modifier.border(BorderStroke(1.5.dp, Color(0xFFCFC9EE)), shape))
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 10.dp),
        contentAlignment = Alignment.Center,
    ) { Text(label, fontSize = 18.sp, color = if (selected) Color.White else Ink, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.Center) }
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
    else Box(modifier.background(AccentSoft), contentAlignment = Alignment.Center) { Icon(Icons.Filled.Star, null, tint = Accent) }
}

/** 소프트웨어 비트맵으로 바꾼다(하드웨어 비트맵은 Canvas 에 그릴 수 없다). */
private fun ImageBitmap.software(): Bitmap {
    val b = asAndroidBitmap()
    return if (b.config == Bitmap.Config.HARDWARE) b.copy(Bitmap.Config.ARGB_8888, false) else b
}

/**
 * 9:16 미리보기 한 장면. 템플릿의 배경·자막 모양·제목을 합성과 같은 부품(FrameComposer)으로 그린다.
 * bg == null 이면 템플릿 기본 배경.
 */
@Composable
fun ScenePreview(bg: ImageBitmap?, lines: List<String>, font: SubFont, size: SubSize, template: VideoTemplate, title: String, source: String, modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    BoxWithConstraints(modifier.aspectRatio(9f / 16f).clip(RoundedCornerShape(16.dp)).background(Color(0xFF222222))) {
        val wPx = with(androidx.compose.ui.platform.LocalDensity.current) { maxWidth.toPx() }.toInt().coerceIn(180, 720)
        val img = remember(bg, lines, font, size, template, title, source, wPx) {
            FrameComposer.compose(ctx, template, bg?.software(), lines, font, size, title, source, wPx).asImageBitmap()
        }
        Image(img, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
    }
}

/** 템플릿 모양 썸네일(견본 글 한 줄을 그 템플릿으로 그린 그림). */
@Composable
fun TemplateThumb(t: VideoTemplate, sample: String, title: String, source: String, modifier: Modifier = Modifier, outW: Int = 360) {
    val ctx = LocalContext.current
    val img = remember(t.id, sample, title, source, outW) {
        val tf = Fonts.typeface(ctx, t.font)
        val lines = SubtitleLayout.pages(SubtitleLayout.lines(sample, tf, SubSize.NORMAL, t.subtitle.sizeFactor), SubSize.NORMAL).firstOrNull().orEmpty()
        FrameComposer.compose(ctx, t, null, lines, t.font, SubSize.NORMAL, title, source, outW).asImageBitmap()
    }
    Image(img, null, modifier, contentScale = ContentScale.FillBounds)
}
