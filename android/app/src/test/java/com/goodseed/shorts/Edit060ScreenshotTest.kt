package com.goodseed.shorts

import android.app.Application
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.github.takahirom.roborazzi.captureRoboImage
import com.goodseed.shorts.data.*
import com.goodseed.shorts.logic.*
import com.goodseed.shorts.ui.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * 0.6.0 새 화면(최근 작품 [수정]·확인 창·열 수 없는 자료 안내)을 JVM(Robolectric)에서 실제 Compose 로 그려 PNG 로 남긴다. 결과: _evidence/0.6.0
 * 폭 360dp(작은 폰) 기준. 에뮬레이터·실기기가 없어 실제 폰에서 본 것은 아니다.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h780dp-xxhdpi")
class Edit060ScreenshotTest {
    @get:Rule val rule = createComposeRule()

    private val out = File("../../_evidence/0.6.0").also { it.mkdirs() }
    private fun path(n: String) = File(out, n).path
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    @Before fun noStartupNetwork() { AppViewModel.startupChecks = false }

    private fun vm(): AppViewModel {
        val v = AppViewModel(app)
        v.project = Project(scenes = v.sentencesOf("주님은 나의 목자시니 부족함이 없으리로다. 그가 나를 푸른 풀밭에 누이시며 쉴 만한 물가로 인도하시는도다.").mapIndexed { i, t -> Scene(i + 1L, t) })
        return v
    }

    private fun works() = listOf(
        RecentWork("주님은 나의 목자시니 부족함이 없으리로다", "content://media/1", System.currentTimeMillis() - 3_600_000, "/none/1.jpg", "p1", emptyList(), "aaaaaaaa-1.json"),
        RecentWork("사랑은 오래 참고 사랑은 온유하며", "content://media/2", System.currentTimeMillis() - 86_400_000, "/none/2.jpg", "p2", emptyList(), "bbbbbbbb-2.json"),
        RecentWork("여호와는 나의 빛이요 나의 구원이시니 (0.5.0 이전 작품)", "content://media/3", System.currentTimeMillis() - 172_800_000, "/none/3.jpg"),
    )

    private fun draw(scale: Float?, content: @Composable () -> Unit) = rule.setContent {
        ShortsTheme {
            if (scale == null) content()
            else CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density, fontScale = scale)) { content() }
        }
    }

    private fun shot(name: String, scale: Float? = null, content: @Composable () -> Unit) {
        draw(scale, content)
        rule.waitForIdle(); Thread.sleep(300); rule.waitForIdle()
        rule.onRoot().captureRoboImage(path(name))
        assertTrue(File(path(name)).length() > 5_000)
    }

    private fun touch(name: String, scale: Float? = null, content: @Composable () -> Unit) {
        draw(scale, content)
        rule.waitForIdle(); Thread.sleep(300); rule.waitForIdle()
        val min = with(rule.density) { 56.dp.toPx() } - 1f
        val nodes = rule.onAllNodes(hasClickAction()).fetchSemanticsNodes()
        val bad = nodes.filter { it.size.width < min || it.size.height < min }
        assertTrue("$name: 56dp 미만 터치 칸 ${bad.map { "${it.size.width}x${it.size.height}" }}", bad.isEmpty())
        assertTrue("$name: 누를 수 있는 칸이 없다", nodes.isNotEmpty())
    }

    /** 카드마다 [수정][공유][삭제] 세 버튼이 한 줄에 있고, 글자가 줄바꿈 없이(높이가 한 줄 버튼 높이 이내) 들어가며, 화면 폭 안에 있는지. */
    private fun assertThreeButtonsFit(scale: Float?, cards: Int) {
        val px = with(rule.density) { 1.dp.toPx() }
        val screenW = rule.onRoot().fetchSemanticsNode().size.width
        for (label in listOf("수정", "공유", "삭제")) {
            val nodes = rule.onAllNodes(hasText(label) and hasClickAction()).fetchSemanticsNodes()
            assertEquals("$label 버튼 수", cards, nodes.size)
            nodes.forEach { n ->
                assertTrue("$label 높이 ${n.size.height / px}dp — 한 줄에 들어가지 않고 줄바꿈됨", n.size.height <= 64 * px)
                assertTrue("$label 가로 ${n.size.width / px}dp 가 56dp 미만", n.size.width >= 56 * px - 1f)
                assertTrue("$label 가 화면 밖으로 나감", n.boundsInRoot.right <= screenW + 1f && n.boundsInRoot.left >= 0f)
            }
        }
        // 한 카드의 세 버튼은 같은 줄(위쪽이 같고)·왼쪽부터 수정·공유·삭제 순서
        val edit = rule.onAllNodes(hasText("수정") and hasClickAction()).fetchSemanticsNodes()
        val share = rule.onAllNodes(hasText("공유") and hasClickAction()).fetchSemanticsNodes()
        val del = rule.onAllNodes(hasText("삭제") and hasClickAction()).fetchSemanticsNodes()
        for (i in 0 until cards) {
            assertEquals(edit[i].boundsInRoot.top, share[i].boundsInRoot.top, 1f); assertEquals(share[i].boundsInRoot.top, del[i].boundsInRoot.top, 1f)
            assertTrue(edit[i].boundsInRoot.right <= share[i].boundsInRoot.left + 1f && share[i].boundsInRoot.right <= del[i].boundsInRoot.left + 1f)
        }
    }

    @Test @Config(qualifiers = "w360dp-h1500dp-xxhdpi") fun s1_home_three_buttons_360dp() {
        val v = vm(); v.recents = works(); shot("1-home-recent-three-buttons.png") { HomeScreen(v) }
        assertThreeButtonsFit(null, 3)
        // 스냅샷이 없는 작품(마지막 카드)의 [수정]만 눌러도 반응이 없는 상태(비활성)
        val editNodes = rule.onAllNodes(hasText("수정") and hasClickAction()).fetchSemanticsNodes()
        assertEquals(3, editNodes.size)
        rule.onAllNodes(hasText("0.6.0부터 만든 작품만 고칠 수 있어요.")).fetchSemanticsNodes().let { assertEquals("안내 문구는 스냅샷 없는 카드 1곳", 1, it.size) }
    }

    @Test @Config(qualifiers = "w360dp-h1700dp-xxhdpi") fun s1b_home_three_buttons_big_text() {
        val v = vm(); v.recents = works(); shot("1b-home-three-buttons-big-text-1.3x.png", 1.3f) { HomeScreen(v) }
        assertThreeButtonsFit(1.3f, 3)
    }

    @Test @Config(qualifiers = "w320dp-h1500dp-xxhdpi") fun s1c_home_three_buttons_even_narrower_320dp() {
        val v = vm(); v.recents = works(); shot("1c-home-three-buttons-320dp.png") { HomeScreen(v) }
        assertThreeButtonsFit(null, 3)
    }

    @Test fun s2_edit_confirm_dialog() { val v = vm(); v.recents = works(); v.editAsk = v.recents.first(); shot("2-edit-confirm-dialog.png") { HomeScreen(v) } }

    @Test fun s3_preview_after_edit_with_left_out_notice() {
        val v = vm()
        v.editNotice = Restored(v.project, 2, true, 1).notice()
        shot("3-preview-after-edit-notice.png") { PreviewScreen(v) }
    }

    @Test fun s4_whatsnew_060() { val v = vm(); v.whatsNew = WhatsNew.notesFor("0.6.0"); shot("4-whatsnew-0.6.0.png") { HomeScreen(v) } }

    @Test @Config(qualifiers = "w360dp-h1500dp-xxhdpi") fun s5_home_notice_when_edit_refused() {
        val v = vm(); v.recents = works(); v.homeNotice = "이 작품의 저장 정보를 읽지 못해 고칠 수 없어요."; shot("5-home-edit-refused-notice.png") { HomeScreen(v) }
    }

    @Test @Config(qualifiers = "w360dp-h1500dp-xxhdpi") fun t1_home_touch() { val v = vm(); v.recents = works(); touch("홈(최근 작품 3버튼)") { HomeScreen(v) } }
    @Test @Config(qualifiers = "w360dp-h1700dp-xxhdpi") fun t1b_home_touch_big_text() { val v = vm(); v.recents = works(); touch("홈(큰 글씨)", 1.3f) { HomeScreen(v) } }
    @Test fun t2_edit_dialog_touch() { val v = vm(); v.recents = works(); v.editAsk = v.recents.first(); touch("수정 확인 창") { HomeScreen(v) } }
}
