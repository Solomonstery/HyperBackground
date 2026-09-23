package com.ciallo.hyperbackground.ui.pages

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ciallo.hyperbackground.R
import com.ciallo.hyperbackground.ui.MainActivity
import com.ciallo.hyperbackground.ui.components.UiCard
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

/**
 * 「请作者喝咖啡」页：微信赞赏码 + 说明文案。
 *
 * 赞赏码自带绿底与白色卡片，深色模式下本身对比度足够，因此整图原样展示，不做裁剪、着色或
 * 滤镜——二维码被裁掉静区或着色后会直接扫不出来。底部单独一行免责说明，避免引导感过强。
 */
@Composable
fun DonatePage(
    activity: MainActivity,
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(0.dp),
) {
    LazyColumn(
        modifier.fillMaxSize(),
        contentPadding = PaddingValues(
            top = padding.calculateTopPadding() + 12.dp,
            bottom = padding.calculateBottomPadding() + 12.dp,
            start = 12.dp,
            end = 12.dp,
        ),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            UiCard(activity, Modifier.fillMaxWidth()) {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    Text(
                        text = stringResource(R.string.donate_message),
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        color = MiuixTheme.colorScheme.onSurface,
                    )
                    Image(
                        painter = painterResource(R.drawable.wechat_reward_card),
                        contentDescription = stringResource(R.string.donate),
                        modifier = Modifier
                            .width(260.dp)
                            .clip(RoundedCornerShape(16.dp)),
                    )
                    Text(
                        text = stringResource(R.string.donate_hint),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            }
        }
        item {
            Text(
                text = stringResource(R.string.donate_voluntary),
                fontSize = 12.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
        }
    }
}
