package com.goodseed.shorts

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.Composable
import com.goodseed.shorts.ui.*

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { ShortsTheme { AppRoot(vm) } }
    }
}

@Composable
fun AppRoot(vm: AppViewModel) {
    BackHandler(enabled = vm.screen != Screen.HOME) { vm.back() }
    when (vm.screen) {
        Screen.HOME -> HomeScreen(vm)
        Screen.PASTE -> PasteScreen(vm)
        Screen.VOICE -> VoiceScreen(vm)
        Screen.MEDIA -> MediaScreen(vm)
        Screen.PREVIEW -> PreviewScreen(vm)
        Screen.EXPORT -> ExportScreen(vm)
    }
}
