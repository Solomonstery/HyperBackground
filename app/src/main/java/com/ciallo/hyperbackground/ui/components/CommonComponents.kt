package com.ciallo.hyperbackground.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.ciallo.hyperbackground.ui.MainActivity
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun UiCard(activity: MainActivity, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier,
        colors = CardDefaults.defaultColors(
            color = MiuixTheme.colorScheme.surfaceContainer.copy(alpha = activity.cardOpacity),
            contentColor = MiuixTheme.colorScheme.onSurfaceContainer,
        ),
        content = content,
    )
}

@Composable
fun PageEntry(
    activity: MainActivity,
    icon: ImageVector,
    title: String,
    summary: String,
    onClick: () -> Unit,
) {
    UiCard(activity, Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            Modifier.padding(horizontal = 18.dp, vertical = 17.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            androidx.compose.foundation.layout.Box(
                Modifier.size(46.dp).clip(RoundedCornerShape(15.dp))
                    .background(MiuixTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(26.dp))
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(title, style = MiuixTheme.textStyles.headline1)
                Text(summary, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        }
    }
}

@Composable
fun SliderPreference(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    suffix: String = "",
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (Float) -> Unit,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(
                text = label,
                fontSize = MiuixTheme.textStyles.headline1.fontSize,
                fontWeight = FontWeight.Medium,
            )
            Text(
                text = "${value.toInt()}$suffix",
                fontSize = MiuixTheme.textStyles.headline1.fontSize,
                fontWeight = FontWeight.Medium,
            )
        }
        Spacer(Modifier.height(8.dp))
        Slider(
            value = value,
            onValueChange = onValueChange,
            onValueChangeFinished = { onValueChangeFinished(value) },
            valueRange = range,
            steps = (range.endInclusive - range.start).toInt().minus(1).coerceAtLeast(0),
        )
    }
}

/**
 * 滑块 + 数值输入框：右上角把纯数值文本换成可编辑输入框，支持精确输入；输入即校正到 range 内并同步滑块。
 * value/onValueChange/onValueChangeFinished 语义与 SliderPreference 一致（拖动实时回调、松手落库）。
 */
@Composable
fun SliderWithInputPreference(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    suffix: String = "",
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (Float) -> Unit,
) {
    // 输入框内容独立于滑块：拖动滑块时同步显示，聚焦编辑时以用户输入为准。用 value 的整数串作初值。
    var text by remember(value.toInt()) { mutableStateOf(value.toInt().toString()) }
    Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = label,
                fontSize = MiuixTheme.textStyles.headline1.fontSize,
                fontWeight = FontWeight.Medium,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.width(52.dp).clip(RoundedCornerShape(10.dp))
                        .background(MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f))
                        .padding(horizontal = 8.dp, vertical = 6.dp),
                ) {
                    BasicTextField(
                        value = text,
                        onValueChange = { input ->
                            // 仅接受数字；空串允许（编辑中间态），有效值即校正并回调。
                            val digits = input.filter { it.isDigit() }
                            text = digits
                            val v = digits.toIntOrNull()
                            if (v != null) {
                                val clamped = v.coerceIn(range.start.toInt(), range.endInclusive.toInt())
                                onValueChange(clamped.toFloat())
                                onValueChangeFinished(clamped.toFloat())
                            }
                        },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        textStyle = MiuixTheme.textStyles.headline1.copy(
                            color = MiuixTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium,
                            textAlign = TextAlign.End,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                if (suffix.isNotEmpty()) {
                    Text(
                        text = suffix,
                        fontSize = MiuixTheme.textStyles.headline1.fontSize,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(start = 2.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Slider(
            value = value,
            onValueChange = {
                onValueChange(it)
                text = it.toInt().toString()
            },
            onValueChangeFinished = { onValueChangeFinished(value) },
            valueRange = range,
            steps = (range.endInclusive - range.start).toInt().minus(1).coerceAtLeast(0),
        )
    }
}

@Composable
fun SectionTitle(title: String) {
    SmallTitle(text = title)
}
