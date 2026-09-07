package com.ciallo.hyperbackground.ui.pages

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ciallo.hyperbackground.BuildConfig
import com.ciallo.hyperbackground.R
import com.ciallo.hyperbackground.ui.MainActivity
import com.ciallo.hyperbackground.ui.components.UiCard
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「关于」底栏页：应用标签卡（图标 + 标题 + shields 徽章）+ 关于信息卡。
 * 从设置页迁移而来，更新日志等跳转功能保持不变。
 */
@Composable
fun AboutPage(
    activity: MainActivity,
    modifier: Modifier = Modifier,
    padding: androidx.compose.foundation.layout.PaddingValues = androidx.compose.foundation.layout.PaddingValues(0.dp),
    onOpenChangelog: () -> Unit = {},
) {
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            top = padding.calculateTopPadding() + 12.dp,
            bottom = padding.calculateBottomPadding() + 12.dp,
            start = 12.dp,
            end = 12.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item { AppLabelCard(activity) }
        item { AboutInfoCard(activity, onOpenChangelog) }
    }
}

/** 应用标签卡：大图标 + 应用名 + 副标题 + shields 风格徽章。 */
@Composable
private fun AppLabelCard(activity: MainActivity) {
    UiCard(activity, Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Image(
                painter = painterResource(R.drawable.app_icon),
                contentDescription = null,
                modifier = Modifier
                    .size(96.dp)
                    .clip(RoundedCornerShape(24.dp)),
            )
            Text(
                text = "HyperBackground",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MiuixTheme.colorScheme.onSurface,
            )
            Text(
                text = stringResource(R.string.app_description),
                fontSize = 13.sp,
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Spacer(Modifier.height(4.dp))
            // shields 风格徽章，两列排列
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ShieldBadge(
                        label = "release",
                        value = BuildConfig.VERSION_NAME,
                        valueColor = Color(0xFF2196F3),
                        modifier = Modifier.weight(1f),
                    )
                    ShieldBadge(
                        label = "license",
                        value = "MIT",
                        valueColor = Color(0xFFFF9800),
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ShieldBadge(
                        label = "Platform",
                        value = "Android",
                        valueColor = Color(0xFF3DDC84),
                        modifier = Modifier.weight(1f),
                    )
                    ShieldBadge(
                        label = "Framework",
                        value = "LSPosed",
                        valueColor = Color(0xFF8A2BE2),
                        modifier = Modifier.weight(1f),
                    )
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    ShieldBadge(
                        label = "ROM",
                        value = "HyperOS 3/4",
                        valueColor = Color(0xFFFF9800),
                        modifier = Modifier.weight(1f),
                    )
                    ShieldBadge(
                        label = "Build",
                        value = "Compose",
                        valueColor = Color(0xFF4285F4),
                        modifier = Modifier.weight(1f),
                    )
                }
            }
        }
    }
}

/** shields.io 风格徽章：左侧灰色标签 + 右侧彩色值。 */
@Composable
private fun ShieldBadge(
    label: String,
    value: String,
    valueColor: Color,
    modifier: Modifier = Modifier,
) {
    val labelBg = Color(0xFF555555)
    Row(
        modifier = modifier
            .height(24.dp)
            .background(labelBg, RoundedCornerShape(4.dp)),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .height(24.dp)
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
            )
        }
        Box(
            modifier = Modifier
                .height(24.dp)
                .background(valueColor, RoundedCornerShape(topEnd = 4.dp, bottomEnd = 4.dp))
                .padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = value,
                color = Color.White,
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

/** 关于信息卡：制作者、版本、酷安 / GitHub / 更新日志 / 应用信息。 */
@Composable
private fun AboutInfoCard(activity: MainActivity, onOpenChangelog: () -> Unit) {
    UiCard(activity, Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.author), style = MiuixTheme.textStyles.headline1)
            Text(
                stringResource(R.string.current_version, BuildConfig.VERSION_NAME),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(
                    modifier = Modifier.weight(1f),
                    text = stringResource(R.string.coolapk),
                    onClick = { activity.openUrl("https://www.coolapk.com/u/18795532") },
                )
                TextButton(
                    modifier = Modifier.weight(1f),
                    text = stringResource(R.string.github),
                    onClick = { activity.openUrl("https://github.com/Solomonstery/HyperBackground") },
                )
            }
            TextButton(
                modifier = Modifier.fillMaxWidth(),
                text = stringResource(R.string.changelog),
                colors = ButtonDefaults.textButtonColorsPrimary(),
                onClick = onOpenChangelog,
            )
            TextButton(
                modifier = Modifier.fillMaxWidth(),
                text = stringResource(R.string.open_app_info),
                onClick = {
                    activity.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${activity.packageName}"),
                        )
                    )
                },
            )
        }
    }
}
