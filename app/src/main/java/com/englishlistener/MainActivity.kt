package com.englishlistener

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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

    when (uiState.screen) {
        com.englishlistener.ui.AppScreen.SETUP -> {
            SetupScreen(
                downloadState = uiState.downloadState,
                onStartDownload = { viewModel.startDownload() },
                onSkip = { viewModel.skipDownload() }
            )
        }

        com.englishlistener.ui.AppScreen.MAIN -> {
            Scaffold(
                modifier = Modifier.fillMaxSize(),
                bottomBar = {
                    NavigationBar {
                        NavigationBarItem(
                            icon = { Icon(Icons.Default.Home, contentDescription = "首页") },
                            label = { Text("频道") },
                            selected = true,
                            onClick = { }
                        )
                        NavigationBarItem(
                            icon = { Icon(Icons.Default.Subtitles, contentDescription = "字幕") },
                            label = { Text("字幕") },
                            selected = false,
                            onClick = { }
                        )
                    }
                }
            ) { innerPadding ->
                Box(modifier = Modifier.padding(innerPadding)) {
                    HomeScreen(
                        stations = uiState.stations,
                        currentStation = uiState.currentStation,
                        playerState = uiState.playerState,
                        onStationClick = { viewModel.selectStation(it) },
                        onTogglePlayPause = { viewModel.togglePlayPause() }
                    )
                }
            }
        }
    }
}
