package com.englishlistener.ui.screens

import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Translate
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.englishlistener.data.Accent
import com.englishlistener.data.Category
import com.englishlistener.data.RadioStation
import com.englishlistener.player.PlayerState
import com.englishlistener.ui.theme.*

/**
 * 首页 —— 频道列表 + 迷你播放器
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    stations: List<RadioStation>,
    currentStation: RadioStation?,
    playerState: PlayerState,
    onStationClick: (RadioStation) -> Unit,
    onTogglePlayPause: () -> Unit
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "EnglishListener",
                        fontWeight = FontWeight.Bold
                    )
                },
                actions = {
                    // 预留字幕开关入口
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        },
        bottomBar = {
            // 迷你播放器 Bar
            if (currentStation != null) {
                MiniPlayerBar(
                    stationName = currentStation.name,
                    isPlaying = playerState.isPlaying,
                    isLoading = playerState.isLoading,
                    onTogglePlayPause = onTogglePlayPause
                )
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            // 分类标题
            Category.entries.forEach { category ->
                val categoryStations = stations.filter { it.category == category }
                if (categoryStations.isNotEmpty()) {
                    item(key = "header_${category.name}") {
                        Text(
                            text = category.label,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f),
                            modifier = Modifier.padding(top = 8.dp, bottom = 4.dp)
                        )
                    }
                    items(categoryStations, key = { it.id }) { station ->
                        StationCard(
                            station = station,
                            isPlaying = currentStation?.id == station.id && playerState.isPlaying,
                            isLoading = currentStation?.id == station.id && playerState.isLoading,
                            onClick = { onStationClick(station) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun StationCard(
    station: RadioStation,
    isPlaying: Boolean,
    isLoading: Boolean,
    onClick: () -> Unit
) {
    val accentColor = accentColor(station.accent)
    val bgColor by animateColorAsState(
        if (isPlaying) accentColor.copy(alpha = 0.1f)
        else MaterialTheme.colorScheme.surface
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isPlaying) 4.dp else 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 口音标签
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(accentColor)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = station.accent.label,
                    color = androidx.compose.ui.graphics.Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = station.name,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = station.description,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 4.dp)
                )
            }

            Spacer(modifier = Modifier.width(8.dp))

            // 播放/加载图标
            if (isPlaying || isLoading) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = accentColor
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Pause,
                        contentDescription = "正在播放",
                        tint = accentColor,
                        modifier = Modifier.size(24.dp)
                    )
                }
            } else {
                Icon(
                    imageVector = Icons.Default.PlayArrow,
                    contentDescription = "播放",
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                    modifier = Modifier.size(24.dp)
                )
            }
        }
    }
}

@Composable
fun MiniPlayerBar(
    stationName: String,
    isPlaying: Boolean,
    isLoading: Boolean,
    onTogglePlayPause: () -> Unit
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 8.dp,
        shadowElevation = 8.dp
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 音频可视化条（占位）
            if (isPlaying) {
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    repeat(4) {
                        Box(
                            modifier = Modifier
                                .width(3.dp)
                                .height((12..20).random().dp)
                                .clip(RoundedCornerShape(2.dp))
                                .background(Primary)
                        )
                    }
                }
                Spacer(modifier = Modifier.width(12.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stationName,
                    style = MaterialTheme.typography.labelLarge,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (isLoading) "加载中..." else if (isPlaying) "正在播放" else "已暂停",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                )
            }

            IconButton(onClick = onTogglePlayPause) {
                if (isLoading) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp
                    )
                } else {
                    Icon(
                        imageVector = if (isPlaying)
                            Icons.Default.Translate // 占位，实际用 Pause
                        else
                            Icons.Default.PlayArrow,
                        contentDescription = if (isPlaying) "暂停" else "播放"
                    )
                }
            }
        }
    }
}

private fun accentColor(accent: Accent) = when (accent) {
    Accent.BRITISH -> AccentBritish
    Accent.AMERICAN -> AccentAmerican
    Accent.CHINESE -> AccentChinese
    Accent.AUSTRALIAN -> AccentAustralian
    Accent.OTHER -> AccentOther
}
