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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.ciallo.hyperbackground.R
import com.ciallo.hyperbackground.ui.MainActivity
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.CardDefaults
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Slider
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import java.util.Locale
import kotlin.math.roundToInt

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

/**
 * 统一滑块条目。传 [defaultValue]（页面级滑块）时数值右侧带 ArrowRight，点击条目任意空白区域
 * 弹出数值输入弹窗（范围提示 + 恢复默认/取消/保存）；弹窗内部的滑块不传该参数，保持纯滑条。
 * [decimal] 为小数模式：0.1 步进显示与量化，用于 -50..50 的柔光玻璃百分比刻度。
 */
@Composable
fun SliderPreference(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    suffix: String = "",
    defaultValue: Float? = null,
    decimal: Boolean = false,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: (Float) -> Unit,
) {
    var showInput by remember { mutableStateOf(false) }
    val display = if (decimal) decimalDisplay(value) else value.toInt().toString()
    val quantized: (Float) -> Float = if (decimal) {
        { (it * 10).roundToInt() / 10f }
    } else {
        { it }
    }
    Column(
        Modifier
            .heightIn(min = 56.dp)
            .fillMaxWidth()
            // clickable 放在 padding 之前，点击/悬停反馈覆盖整块条目（与 BasicComponent 一致）。
            .then(if (defaultValue != null) Modifier.clickable { showInput = true } else Modifier)
            .padding(16.dp),
        verticalArrangement = Arrangement.Center,
    ) {
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
                Text(
                    text = display + suffix,
                    fontSize = MiuixTheme.textStyles.headline1.fontSize,
                    fontWeight = FontWeight.Medium,
                )
                if (defaultValue != null) {
                    Icon(
                        imageVector = MiuixIcons.Basic.ArrowRight,
                        contentDescription = null,
                        modifier = Modifier
                            .padding(start = 4.dp)
                            .size(13.dp),
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        Slider(
            value = value,
            onValueChange = { onValueChange(quantized(it)) },
            onValueChangeFinished = { onValueChangeFinished(value) },
            valueRange = range,
            steps = if (decimal) 999 else (range.endInclusive - range.start).toInt().minus(1).coerceAtLeast(0),
        )
    }
    if (defaultValue != null && showInput) {
        SliderInputDialog(
            title = label,
            initial = value,
            range = range,
            suffix = suffix,
            defaultValue = defaultValue,
            decimal = decimal,
            onDismiss = { showInput = false },
            onConfirm = { next ->
                showInput = false
                onValueChange(next)
                onValueChangeFinished(next)
            },
        )
    }
}

/** 滑块数值输入弹窗：范围提示 + 输入框 + 恢复默认（整行）/ 取消 + 保存（强调色）。 */
@Composable
private fun SliderInputDialog(
    title: String,
    initial: Float,
    range: ClosedFloatingPointRange<Float>,
    suffix: String,
    defaultValue: Float,
    decimal: Boolean,
    onDismiss: () -> Unit,
    onConfirm: (Float) -> Unit,
) {
    WindowDialog(title = title, show = true, onDismissRequest = onDismiss) {
        var text by remember { mutableStateOf(formatSliderValue(initial, decimal)) }
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(
                    text = stringResource(
                        R.string.slider_range_hint,
                        formatSliderValue(range.start, decimal),
                        formatSliderValue(range.endInclusive, decimal),
                    ) + if (suffix.isEmpty()) "" else " ($suffix)",
                    color = MiuixTheme.colorScheme.onSurfaceVariantActions,
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f))
                        .padding(horizontal = 12.dp, vertical = 11.dp),
                ) {
                    BasicTextField(
                        value = text,
                        onValueChange = { text = filterNumberInput(it, decimal) },
                        singleLine = true,
                        keyboardOptions = if (range.start >= 0f && !decimal) {
                            KeyboardOptions(keyboardType = KeyboardType.Number)
                        } else {
                            KeyboardOptions.Default
                        },
                        textStyle = MiuixTheme.textStyles.headline1.copy(
                            color = MiuixTheme.colorScheme.onSurface,
                            fontWeight = FontWeight.Medium,
                        ),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            TextButton(
                modifier = Modifier.fillMaxWidth(),
                text = stringResource(R.string.restore_default),
                onClick = { text = formatSliderValue(defaultValue, decimal) },
            )
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                TextButton(
                    modifier = Modifier.weight(1f),
                    text = stringResource(R.string.cancel),
                    onClick = onDismiss,
                )
                TextButton(
                    modifier = Modifier.weight(1f),
                    text = stringResource(R.string.save),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        val parsed = text.toFloatOrNull() ?: initial
                        val clamped = if (decimal) {
                            ((parsed * 10).roundToInt() / 10f).coerceIn(range.start, range.endInclusive)
                        } else {
                            parsed.roundToInt().toFloat().coerceIn(range.start, range.endInclusive)
                        }
                        onConfirm(clamped)
                    },
                )
            }
        }
    }
}

/** 只保留数字、开头负号，小数模式额外允许一个小数点。 */
private fun filterNumberInput(input: String, decimal: Boolean): String {
    val builder = StringBuilder()
    var dot = false
    for (char in input) {
        when {
            char == '-' && builder.isEmpty() -> builder.append(char)
            char.isDigit() -> builder.append(char)
            char == '.' && decimal && !dot &&
                builder.isNotEmpty() && builder.last() != '-' -> {
                dot = true
                builder.append(char)
            }
        }
    }
    return builder.toString()
}

private fun formatSliderValue(value: Float, decimal: Boolean): String =
    if (decimal) String.format(Locale.ROOT, "%.1f", value) else value.roundToInt().toString()

private fun decimalDisplay(value: Float): String {
    val hundredths = String.format(Locale.ROOT, "%.2f", value)
    return if (hundredths.endsWith("0")) String.format(Locale.ROOT, "%.1f", value) else hundredths
}

@Composable
fun SectionTitle(title: String) {
    SmallTitle(text = title)
}
