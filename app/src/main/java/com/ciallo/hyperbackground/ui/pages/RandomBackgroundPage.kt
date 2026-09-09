package com.ciallo.hyperbackground.ui.pages

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ciallo.hyperbackground.BackgroundContract
import com.ciallo.hyperbackground.HyperBackgroundApp
import com.ciallo.hyperbackground.R
import com.ciallo.hyperbackground.RandomBackgroundFetcher
import com.ciallo.hyperbackground.ui.MainActivity
import com.ciallo.hyperbackground.ui.components.SectionTitle
import com.ciallo.hyperbackground.ui.components.UiCard
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.preference.OverlayDropdownPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog

@Composable
fun RandomBackgroundPage(
    activity: MainActivity,
    modifier: Modifier = Modifier,
    padding: PaddingValues = PaddingValues(0.dp),
) {
    val config = activity.config
    var enabled by remember {
        mutableStateOf(config.getBoolean(BackgroundContract.UI_RANDOM_BG_ENABLED, false))
    }
    var mode by remember {
        mutableIntStateOf(
            config.getInt(BackgroundContract.UI_RANDOM_BG_MODE, BackgroundContract.RANDOM_BG_MODE_MANUAL),
        )
    }
    var category by remember {
        mutableStateOf(config.getString(BackgroundContract.UI_RANDOM_BG_CATEGORY, "") ?: "")
    }
    var slots by remember {
        mutableStateOf(
            config.getStringSet(BackgroundContract.UI_RANDOM_BG_SLOTS, mutableSetOf())?.toSet() ?: emptySet(),
        )
    }
    var showApiDialog by remember { mutableStateOf(false) }
    var slotsExpanded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    val modeOptions = listOf(
        stringResource(R.string.random_mode_manual),
        stringResource(R.string.random_mode_boot),
        stringResource(R.string.random_mode_both),
    )

    val categoryOptions = remember {
        listOf(
            "" to R.string.random_cat_random,
            "acg" to R.string.random_cat_acg,
            "landscape" to R.string.random_cat_landscape,
            "anime" to R.string.random_cat_anime,
            "mobile_wallpaper" to R.string.random_cat_mobile_wallpaper,
            "general_anime" to R.string.random_cat_general_anime,
            // 电脑壁纸置末：手机用不到横图，平板用户可能需要。
            "pc_wallpaper" to R.string.random_cat_pc_wallpaper,
        )
    }
    val categoryLabels = categoryOptions.map { stringResource(it.second) }
    val categoryIndex = categoryOptions.indexOfFirst { it.first == category }.coerceAtLeast(0)

    val slotOptions = listOf(
        BackgroundContract.HOME to R.string.background_home,
        BackgroundContract.DEVICE to R.string.background_device,
        BackgroundContract.GLOBAL to R.string.background_global,
        BackgroundContract.CONTACTS to R.string.background_contacts,
        BackgroundContract.CONTACTS_DIALPAD to R.string.random_slot_dialpad,
        BackgroundContract.RANDOM_SLOT_UI to R.string.module_background,
    )

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
                SwitchPreference(
                    title = stringResource(R.string.random_background),
                    summary = stringResource(R.string.random_enabled_summary),
                    checked = enabled,
                    onCheckedChange = {
                        enabled = it
                        config.edit().putBoolean(BackgroundContract.UI_RANDOM_BG_ENABLED, it).apply()
                        HyperBackgroundApp.updateBootReceiverState(activity)
                        activity.refreshUi()
                    },
                )
            }
        }
        item {
            AnimatedVisibility(
                visible = enabled,
                enter = expandVertically(animationSpec = tween(300)) + fadeIn(animationSpec = tween(220)),
                exit = shrinkVertically(animationSpec = tween(300)) + fadeOut(animationSpec = tween(180)),
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SectionTitle(stringResource(R.string.random_trigger))
                    UiCard(activity, Modifier.fillMaxWidth()) {
                        OverlayDropdownPreference(
                            title = stringResource(R.string.random_mode),
                            items = modeOptions,
                            selectedIndex = mode.coerceIn(modeOptions.indices),
                            onSelectedIndexChange = {
                                mode = it
                                config.edit()
                                    .putInt(BackgroundContract.UI_RANDOM_BG_MODE, it)
                                    .apply()
                                HyperBackgroundApp.updateBootReceiverState(activity)
                            },
                        )
                    }
                    SectionTitle(stringResource(R.string.random_api_title))
                    UiCard(activity, Modifier.fillMaxWidth()) {
                        BasicComponent(
                            title = stringResource(R.string.api_address),
                            summary = config.getString(
                                BackgroundContract.UI_RANDOM_BG_API,
                                RandomBackgroundFetcher.DEFAULT_API,
                            ) ?: RandomBackgroundFetcher.DEFAULT_API,
                            endActions = {
                                Icon(MiuixIcons.Basic.ArrowRight, contentDescription = null)
                            },
                            onClick = { showApiDialog = true },
                        )
                        OverlayDropdownPreference(
                            title = stringResource(R.string.random_category),
                            items = categoryLabels,
                            selectedIndex = categoryIndex,
                            onSelectedIndexChange = { idx ->
                                category = categoryOptions[idx].first
                                config.edit()
                                    .putString(BackgroundContract.UI_RANDOM_BG_CATEGORY, categoryOptions[idx].first)
                                    .apply()
                            },
                        )
                    }
                    SectionTitle(stringResource(R.string.random_slots_title))
                    UiCard(activity, Modifier.fillMaxWidth()) {
                        // 作用范围默认折叠为一行，点击展开各槽位开关，避免平铺占用过多纵向空间。
                        BasicComponent(
                            title = stringResource(R.string.random_slots_title),
                            summary = if (slots.isEmpty()) {
                                stringResource(R.string.random_slots_none)
                            } else {
                                stringResource(R.string.random_slots_count, slots.size)
                            },
                            endActions = {
                                Icon(
                                    MiuixIcons.Basic.ArrowRight,
                                    contentDescription = null,
                                    modifier = Modifier.rotate(if (slotsExpanded) 90f else 0f),
                                )
                            },
                            onClick = { slotsExpanded = !slotsExpanded },
                        )
                        AnimatedVisibility(
                            visible = slotsExpanded,
                            enter = expandVertically(animationSpec = tween(260)) + fadeIn(animationSpec = tween(200)),
                            exit = shrinkVertically(animationSpec = tween(260)) + fadeOut(animationSpec = tween(160)),
                        ) {
                            Column {
                                slotOptions.forEach { (slot, labelRes) ->
                                    SwitchPreference(
                                        title = stringResource(labelRes),
                                        checked = slot in slots,
                                        onCheckedChange = { on ->
                                            slots = if (on) slots + slot else slots - slot
                                            config.edit()
                                                .putStringSet(BackgroundContract.UI_RANDOM_BG_SLOTS, slots.toMutableSet())
                                                .apply()
                                            activity.refreshUi()
                                        },
                                    )
                                }
                            }
                        }
                    }
                    TextButton(
                        text = if (busy) {
                            stringResource(R.string.random_fetching)
                        } else {
                            stringResource(R.string.random_fetch)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.textButtonColorsPrimary(),
                        enabled = !busy,
                        onClick = { fetchAll(activity, slots) { busy = false } },
                    )
                }
            }
        }
    }

    if (showApiDialog) {
        RandomApiDialog(
            initial = config.getString(BackgroundContract.UI_RANDOM_BG_API, RandomBackgroundFetcher.DEFAULT_API)
                ?: RandomBackgroundFetcher.DEFAULT_API,
            onDismiss = { showApiDialog = false },
            onSave = { url ->
                config.edit()
                    .putString(BackgroundContract.UI_RANDOM_BG_API, url.trim().ifBlank { RandomBackgroundFetcher.DEFAULT_API })
                    .apply()
                showApiDialog = false
            },
        )
    }
}

private fun fetchAll(activity: MainActivity, slots: Set<String>, onDone: () -> Unit) {
    if (slots.isEmpty()) {
        android.widget.Toast.makeText(activity, R.string.random_no_slots, android.widget.Toast.LENGTH_SHORT).show()
        return
    }
    val total = slots.size
    Thread({
        var failed = 0
        slots.forEach { slot ->
            val error = RandomBackgroundFetcher.fetchForSlotBlocking(activity, slot)
            if (error != null) failed++
        }
        activity.runOnUiThread {
            onDone()
            // UI 槽位换图后刷新模块背景；系统槽位重开目标页面后生效（与手动选图一致）。
            activity.refreshUi()
            val msg = if (failed == 0) {
                activity.getString(R.string.random_fetch_done)
            } else {
                activity.getString(R.string.random_fetch_failed, failed, total)
            }
            android.widget.Toast.makeText(activity, msg, android.widget.Toast.LENGTH_SHORT).show()
        }
    }, "RandomBg-FetchAll").start()
}

@Composable
private fun RandomApiDialog(
    initial: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var url by remember { mutableStateOf(initial) }
    WindowDialog(
        title = stringResource(R.string.random_api_title),
        show = true,
        onDismissRequest = onDismiss,
    ) {
        val dismiss = LocalDismissState.current
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                stringResource(R.string.random_api_dialog_summary),
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            RandomInputField(stringResource(R.string.api_address), url) { url = it }
            Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(
                    modifier = Modifier.weight(1f),
                    text = stringResource(R.string.restore_default),
                    onClick = { url = RandomBackgroundFetcher.DEFAULT_API },
                )
                TextButton(
                    modifier = Modifier.weight(1f),
                    text = stringResource(R.string.save),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        onSave(url)
                        dismiss?.invoke()
                    },
                )
            }
        }
    }
}

@Composable
private fun RandomInputField(label: String, value: String, onChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(label, color = MiuixTheme.colorScheme.onSurfaceVariantActions)
        Box(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
                .background(MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f))
                .padding(horizontal = 12.dp, vertical = 11.dp),
        ) {
            BasicTextField(
                value = value,
                onValueChange = onChange,
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                textStyle = MiuixTheme.textStyles.body1.copy(color = MiuixTheme.colorScheme.onSurface),
            )
        }
    }
}
