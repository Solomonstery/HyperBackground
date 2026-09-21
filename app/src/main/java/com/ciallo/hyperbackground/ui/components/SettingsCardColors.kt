package com.ciallo.hyperbackground.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ciallo.hyperbackground.R
import com.ciallo.hyperbackground.appearance.CARD_BACKGROUND_COLOR
import com.ciallo.hyperbackground.appearance.CARD_BACKGROUND_FROST
import com.ciallo.hyperbackground.appearance.CARD_BACKGROUND_SOFT_GLASS
import com.ciallo.hyperbackground.ui.MainActivity
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.ColorPalette
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

/** Separate ARGB values follow Settings' actual light/dark theme, independently of the module UI. */
@Composable
fun SettingsCardColors(activity: MainActivity) {
    val settings = activity.appearance
    val frosted = settings.cardBackgroundMode == CARD_BACKGROUND_FROST
    val softGlass = settings.cardBackgroundMode == CARD_BACKGROUND_SOFT_GLASS
    val glassy = frosted || softGlass
    UiCard(activity, Modifier.fillMaxWidth()) {
        OverlayDropdownPreference(
            title = stringResource(R.string.settings_card_style),
            summary = stringResource(R.string.settings_card_custom_summary),
            items = listOf(
                stringResource(R.string.settings_card_system),
                stringResource(R.string.settings_card_custom),
                stringResource(R.string.settings_card_frost),
                stringResource(R.string.settings_card_soft_glass),
            ),
            selectedIndex = when {
                !settings.customCardEnabled -> 0
                softGlass -> 3
                frosted -> 2
                else -> 1
            },
            onSelectedIndexChange = { index ->
                activity.updateAppearance {
                    it.copy(
                        customCardEnabled = index != 0,
                        cardBackgroundMode = when (index) {
                            1 -> CARD_BACKGROUND_COLOR
                            2 -> CARD_BACKGROUND_FROST
                            3 -> CARD_BACKGROUND_SOFT_GLASS
                            else -> it.cardBackgroundMode
                        },
                    )
                }
            },
        )
        AnimatedVisibility(settings.customCardEnabled) {
            Column {
                CardColorPreference(
                    title = stringResource(if (glassy) R.string.settings_card_light_tint else R.string.settings_card_light),
                    color = if (glassy) settings.lightFrostColor else settings.lightCardColor,
                    onSave = { color ->
                        activity.updateAppearance {
                            if (glassy) it.copy(lightFrostColor = color) else it.copy(lightCardColor = color)
                        }
                    },
                )
                if (glassy) {
                    CardBlurPreference(
                        label = stringResource(R.string.settings_card_light_blur),
                        radius = settings.lightCardBlur,
                        onSave = { value -> activity.updateAppearance { it.copy(lightCardBlur = value) } },
                    )
                }
                CardColorPreference(
                    title = stringResource(if (glassy) R.string.settings_card_dark_tint else R.string.settings_card_dark),
                    color = if (glassy) settings.darkFrostColor else settings.darkCardColor,
                    onSave = { color ->
                        activity.updateAppearance {
                            if (glassy) it.copy(darkFrostColor = color) else it.copy(darkCardColor = color)
                        }
                    },
                )
                if (glassy) {
                    CardBlurPreference(
                        label = stringResource(R.string.settings_card_dark_blur),
                        radius = settings.darkCardBlur,
                        onSave = { value -> activity.updateAppearance { it.copy(darkCardBlur = value) } },
                    )
                }
                when {
                    frosted -> BasicComponent(
                        title = stringResource(R.string.settings_card_frost),
                        summary = stringResource(R.string.settings_card_frost_summary),
                    )
                    softGlass -> BasicComponent(
                        title = stringResource(R.string.settings_card_soft_glass),
                        summary = stringResource(R.string.settings_card_soft_glass_summary),
                    )
                }
                BasicComponent(
                    title = stringResource(R.string.restore_default),
                    onClick = { activity.updateAppearance { it.copy(customCardEnabled = false) } },
                )
            }
        }
    }
}

@Composable
private fun CardBlurPreference(label: String, radius: Int, onSave: (Int) -> Unit) {
    var value by remember(radius) { mutableFloatStateOf(radius.toFloat()) }
    SliderPreference(
        label = label,
        value = value,
        range = 0f..80f,
        onValueChange = { value = it },
        onValueChangeFinished = { onSave(it.toInt()) },
    )
}

@Composable
private fun CardColorPreference(title: String, color: Int, onSave: (Int) -> Unit) {
    var show by remember { mutableStateOf(false) }
    var selected by remember { mutableStateOf(Color(color)) }
    BasicComponent(
        title = title,
        summary = "#${color.toUInt().toString(16).padStart(8, '0').uppercase()}",
        endActions = { CardColorSwatch(Color(color)) },
        onClick = { selected = Color(color); show = true },
    )
    OverlayDialog(title = title, show = show, onDismissRequest = { show = false }) {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                ColorPalette(
                    color = selected,
                    // Editing RGB must not reset the independently chosen opacity.
                    onColorChanged = { selected = it.copy(alpha = selected.alpha) },
                )
                SliderPreference(
                    label = stringResource(R.string.settings_card_opacity),
                    value = selected.alpha * 100f,
                    range = 0f..100f,
                    suffix = "%",
                    onValueChange = { selected = selected.copy(alpha = it / 100f) },
                    onValueChangeFinished = {},
                )
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.Center) {
                    CardColorSwatch(selected)
                }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(
                    text = stringResource(R.string.cancel),
                    modifier = Modifier.weight(1f),
                    onClick = { show = false },
                )
                TextButton(
                    text = stringResource(R.string.save),
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = { onSave(selected.toArgb()); show = false },
                )
            }
        }
    }
}

@Composable
private fun CardColorSwatch(color: Color) {
    val shape = RoundedCornerShape(8.dp)
    Canvas(
        Modifier.size(36.dp).clip(shape)
            .border(1.dp, MiuixTheme.colorScheme.onSurface.copy(alpha = 0.2f), shape),
    ) {
        val tile = size.width / 4f
        for (row in 0..3) for (column in 0..3) {
            drawRect(
                color = if ((row + column) % 2 == 0) Color.White else Color.LightGray,
                topLeft = Offset(column * tile, row * tile),
                size = Size(tile, tile),
            )
        }
        drawRect(color)
    }
}
