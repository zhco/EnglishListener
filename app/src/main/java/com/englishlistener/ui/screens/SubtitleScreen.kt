package com.englishlistener.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Headphones
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.englishlistener.ui.theme.*

@Composable
fun SubtitleScreen(
    englishLines: List<String>,
    chineseLines: List<String>,
    isActive: Boolean,
    captureStatus: String = "",
    isSystemCapture: Boolean = false,
    onRequestSystemCapture: () -> Unit = {}
) {
    val listState = rememberLazyListState()

    LaunchedEffect(englishLines.size) {
        if (englishLines.isNotEmpty()) {
            listState.animateScrollToItem(englishLines.size - 1)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(SubtitleBackground)
    ) {
        Surface(modifier = Modifier.fillMaxWidth(), color = SubtitleBackground) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text("实时字幕", color = EnglishText, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    // System capture toggle
                    IconButton(onClick = onRequestSystemCapture, modifier = Modifier.size(32.dp)) {
                        Icon(
                            imageVector = if (isSystemCapture) Icons.Outlined.Campaign else Icons.Outlined.Headphones,
                            contentDescription = if (isSystemCapture) "系统音频" else "电台流",
                            tint = if (isSystemCapture) Primary else EnglishText.copy(alpha = 0.4f),
                            modifier = Modifier.size(20.dp)
                        )
                    }
                    Surface(shape = RoundedCornerShape(12.dp), color = if (isActive) Secondary else Error) {
                        Text(
                            text = if (isActive) "识别中" else "已暂停",
                            color = EnglishText, fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }
        Divider(color = EnglishText.copy(alpha = 0.1f))

        if (captureStatus.isNotEmpty() && captureStatus != "idle" && captureStatus != "stopped") {
            Surface(modifier = Modifier.fillMaxWidth(), color = SubtitleBackground) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = if (isSystemCapture) "系统音频" else "电台流",
                        color = if (isSystemCapture) Primary else ChineseText.copy(alpha = 0.5f),
                        fontSize = 11.sp,
                        fontWeight = if (isSystemCapture) FontWeight.Bold else FontWeight.Normal
                    )
                    Text(
                        text = "| $captureStatus",
                        color = ChineseText.copy(alpha = 0.6f),
                        fontSize = 11.sp
                    )
                }
            }
            Divider(color = EnglishText.copy(alpha = 0.06f))
        }

        if (englishLines.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("等待语音输入...", color = ChineseText, fontSize = 16.sp)
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        if (isSystemCapture) "点击上方喇叭图标可切换回电台流" else "点击上方耳机图标可使用系统音频捕获",
                        color = ChineseText.copy(alpha = 0.5f), fontSize = 13.sp
                    )
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                itemsIndexed(englishLines) { index, english ->
                    SubtitleBubble(
                        english = english,
                        chinese = chineseLines.getOrElse(index) { "" }
                    )
                }
            }
        }
    }
}

@Composable
private fun SubtitleBubble(english: String, chinese: String) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(EnglishText.copy(alpha = 0.05f))
            .padding(14.dp)
    ) {
        Text(english, color = EnglishText, fontSize = 15.sp, lineHeight = 22.sp, fontWeight = FontWeight.Medium)
        if (chinese.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(chinese, color = ChineseText, fontSize = 13.sp, lineHeight = 20.sp)
        }
    }
}
