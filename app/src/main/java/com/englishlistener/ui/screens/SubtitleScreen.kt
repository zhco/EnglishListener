package com.englishlistener.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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

/**
 * 字幕页 —— 实时显示英文原文 + 中文翻译
 * 当前为占位 UI，后续接入 sherpa-onnx ASR + 混元翻译后自动填充
 */
@Composable
fun SubtitleScreen(
    englishLines: List<String>,
    chineseLines: List<String>,
    isActive: Boolean
) {
    val listState = rememberLazyListState()

    // 自动滚动到最新
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
        // 状态栏
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = SubtitleBackground
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "实时字幕",
                    color = EnglishText,
                    fontWeight = FontWeight.Bold,
                    fontSize = 18.sp
                )
                Surface(
                    shape = RoundedCornerShape(12.dp),
                    color = if (isActive) Secondary else Error
                ) {
                    Text(
                        text = if (isActive) "识别中" else "已暂停",
                        color = EnglishText,
                        fontSize = 11.sp,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }
        }

        Divider(color = EnglishText.copy(alpha = 0.1f))

        if (englishLines.isEmpty()) {
            // 空状态
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "等待语音输入...",
                        color = ChineseText,
                        fontSize = 16.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "播放电台后将自动识别并翻译",
                        color = ChineseText.copy(alpha = 0.5f),
                        fontSize = 13.sp
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
        Text(
            text = english,
            color = EnglishText,
            fontSize = 15.sp,
            lineHeight = 22.sp,
            fontWeight = FontWeight.Medium
        )
        if (chinese.isNotEmpty()) {
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = chinese,
                color = ChineseText,
                fontSize = 13.sp,
                lineHeight = 20.sp
            )
        }
    }
}
