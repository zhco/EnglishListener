package com.englishlistener

import android.content.Context
import android.media.projection.MediaProjectionManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.viewmodel.compose.viewModel
import com.englishlistener.ui.MainViewModel
import com.englishlistener.ui.screens.HomeScreen
import com.englishlistener.ui.screens.SetupScreen
import com.englishlistener.ui.screens.SubtitleScreen
import com.englishlistener.ui.theme.EnglishListenerTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EnglishListenerTheme {
                EnglishListenerApp()
            }
        }
    }
}

@Composable
fun EnglishListenerApp(viewModel: MainViewModel = viewModel()) {
    val uiState by viewModel.uiState.collectAsState()
    val context = LocalContext.current
    val mpManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager

    val mediaProjectionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == android.app.Activity.RESULT_OK && result.data != null) {
            val mp = mpManager.getMediaProjection(result.resultCode, result.data!!)
            viewModel.startSystemCapture(mp)
        } else {
            viewModel.onSystemCaptureDenied()
        }
    }

    when (uiState.screen) {
        com.englishlistener.ui.AppScreen.SETUP -> {
            SetupScreen(
                downloadState = uiState.downloadState,
                onStartDownload = { viewModel.startDownload() },
                onSkip = { viewModel.skipDownload() }
            )
        }

        com.englishlistener.ui.AppScreen.MAIN -> {
            var selectedTab by remember { mutableIntStateOf(0) }

            LaunchedEffect(selectedTab) {
                if (selectedTab == 1 && !uiState.subtitleEnabled) {
                    viewModel.toggleSubtitle()
                }
            }

            Scaffold(
                modifier = Modifier.fillMaxSize(),
                bottomBar = {
                    NavigationBar {
                        NavigationBarItem(
                            icon = { Icon(Icons.Default.Home, contentDescription = "首页") },
                            label = { Text("频道") },
                            selected = selectedTab == 0,
                            onClick = { selectedTab = 0 }
                        )
                        NavigationBarItem(
                            icon = { Icon(Icons.Default.Subtitles, contentDescription = "字幕") },
                            label = { Text("字幕") },
                            selected = selectedTab == 1,
                            onClick = { selectedTab = 1 }
                        )
                    }
                }
            ) { innerPadding ->
                Box(modifier = Modifier.padding(innerPadding)) {
                    if (selectedTab == 0) {
                        HomeScreen(
                            stations = uiState.stations,
                            currentStation = uiState.currentStation,
                            playerState = uiState.playerState,
                            onStationClick = { viewModel.selectStation(it) },
                            onTogglePlayPause = { viewModel.togglePlayPause() }
                        )
                    } else {
                        SubtitleScreen(
                            englishLines = uiState.englishSubtitles,
                            chineseLines = uiState.chineseSubtitles,
                            isActive = uiState.subtitleEnabled,
                            captureStatus = uiState.captureStatus,
                            isSystemCapture = uiState.captureMode == com.englishlistener.ui.CaptureMode.SYSTEM,
                            onRequestSystemCapture = {
                                val intent = mpManager.createScreenCaptureIntent()
                                mediaProjectionLauncher.launch(intent)
                            }
                        )
                    }
                }
            }
        }
    }
}
