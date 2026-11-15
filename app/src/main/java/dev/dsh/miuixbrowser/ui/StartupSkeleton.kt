package dev.dsh.miuixbrowser.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 冷启动首帧骨架：加密库在后台打开期间先画出来的「首页框架」（背景 + 搜索栏 + 收藏网格占位 + 底栏），
 * 不读库、不碰 BrowserState，所以首帧不会被加密库卡住。库就绪后由 BrowserScreen 接管。
 * 只用主题色，深浅色自动跟随；刻意不做动画，首帧要尽量便宜。
 */
@Composable
fun StartupSkeleton(modifier: Modifier = Modifier) {
    val block = MiuixTheme.colorScheme.surfaceContainer
    Box(modifier.fillMaxSize().background(MiuixTheme.colorScheme.background)) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(Modifier.height(96.dp))
            // 搜索栏占位
            Box(
                Modifier.fillMaxWidth().height(48.dp)
                    .clip(RoundedCornerShape(24.dp)).background(block),
            )
            Spacer(Modifier.height(36.dp))
            // 收藏网格占位：4 列 × 3 行
            repeat(3) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    repeat(4) {
                        Box(
                            Modifier.weight(1f).aspectRatio(1f)
                                .clip(RoundedCornerShape(16.dp)).background(block),
                        )
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
        // 底栏占位（高度与 BrowserBottomBar 的 58dp 对齐）
        Box(
            Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
                .fillMaxWidth().height(58.dp)
                .clip(RoundedCornerShape(29.dp)).background(block),
        )
    }
}
