package com.englishlistener.ui.screens

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.englishlistener.data.DownloadState
import com.englishlistener.data.Phase
import com.englishlistener.ui.theme.*

/**
 * 首次启动引导页 —— 两张模型下载
 *
 * 1. sherpa-onnx ASR 中英双语（语音→文字，~200MB）
 * 2. 混元 HY-MT1.5 翻译模型（英文→中文，~440MB）
 *
 * 下载完成后自动跳转主页。
 */
@Composable
fun SetupScreen(
    downloadState: DownloadState,
    onStartDownload: () -> Unit,
    onSkip: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            // 标题
            Text(
                text = "EnglishListener",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = Primary
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "听英语，实时懂",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
            )

            Spacer(modifier = Modifier.height(36.dp))

            // ASR 模型卡片
            ModelCard(
                icon = "🎤",
                title = "语音识别引擎",
                subtitle = "sherpa-onnx Zipformer 中英双语",
                features = listOf(
                    "80M 参数" to "流式识别延迟 &lt;300ms",
                    "内置 VAD" to "自动检测语音起止",
                    "中英双语" to "一句话中英混合也能识别",
                    "INT8 量化" to "约 200MB，精度损失极低"
                ),
                size = "~200 MB"
            )

            Spacer(modifier = Modifier.height(16.dp))

            // 翻译模型卡片
            ModelCard(
                icon = "🌐",
                title = "离线翻译引擎",
                subtitle = "腾讯混元 HY-MT1.5-1.8B",
                features = listOf(
                    "33 语种" to "FLORES-200 得分 78%",
                    "1.25bit GGUF" to "仅 440MB，手机流畅运行",
                    "离线运行" to "无需联网，隐私安全",
                    "翻译质量" to "超越 Google / 百度翻译"
                ),
                size = "~440 MB"
            )

            Spacer(modifier = Modifier.height(28.dp))

            // 总计大小
            Text(
                text = "合计约 640 MB，建议在 Wi-Fi 下下载",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.35f)
            )

            Spacer(modifier = Modifier.height(20.dp))

            // 下载按钮 / 进度
            when (downloadState.phase) {
                Phase.IDLE, Phase.CHECKING -> {
                    Button(
                        onClick = onStartDownload,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary)
                    ) {
                        Text(
                            text = "开始下载 (共 640 MB)",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Phase.DOWNLOADING -> {
                    DownloadProgressSection(downloadState)
                }

                Phase.VERIFYING -> {
                    DownloadProgressSection(
                        downloadState.copy(progress = 1f)
                    )
                }

                Phase.COMPLETED -> {
                    Text(
                        text = "下载完成，即将进入...",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Secondary,
                        fontWeight = FontWeight.Medium
                    )
                }

                Phase.FAILED -> {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = downloadState.error ?: "下载失败",
                            color = Error,
                            style = MaterialTheme.typography.bodyMedium,
                            textAlign = TextAlign.Center
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = onStartDownload,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("重试")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (downloadState.phase != Phase.COMPLETED) {
                TextButton(onClick = onSkip) {
                    Text(
                        text = "先体验播放（仅收音，无识别翻译）",
                        color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.4f),
                        fontSize = 13.sp
                    )
                }
            }
        }
    }
}

@Composable
private fun ModelCard(
    icon: String,
    title: String,
    subtitle: String,
    features: List<Pair<String, String>>,
    size: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(text = icon, fontSize = 20.sp)
                Spacer(modifier = Modifier.width(10.dp))
                Column {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold
                    )
                    Text(
                        text = subtitle,
                        style = MaterialTheme.typography.bodySmall,
                        color = Secondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            features.forEach { (label, value) ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 3.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = label,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)
                    )
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.Medium
                    )
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Divider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f))
            Spacer(modifier = Modifier.height(6.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "模型大小",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.45f)
                )
                Text(
                    text = size,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold
                )
            }
        }
    }
}

@Composable
private fun DownloadProgressSection(state: DownloadState) {
    val animatedProgress by animateFloatAsState(
        targetValue = state.progress,
        animationSpec = tween(durationMillis = 300)
    )

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // 当前下载文件名
        if (state.currentFile.isNotEmpty()) {
            Text(
                text = state.currentFile,
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.Medium,
                color = Primary
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        // 进度条
        LinearProgressIndicator(
            progress = { animatedProgress },
            modifier = Modifier
                .fillMaxWidth()
                .height(8.dp)
                .clip(RoundedCornerShape(4.dp)),
            color = Primary,
            trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.1f)
        )

        Spacer(modifier = Modifier.height(12.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = "${(state.progress * 100).toInt()}%",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
                color = Primary
            )
            Text(
                text = "${state.downloadedBytes / 1024 / 1024} / ${state.totalBytes / 1024 / 1024} MB",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
            )
        }
    }
}

