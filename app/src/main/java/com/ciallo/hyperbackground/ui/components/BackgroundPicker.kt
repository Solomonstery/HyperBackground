package com.ciallo.hyperbackground.ui.components

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import androidx.compose.foundation.Image
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ciallo.hyperbackground.BackgroundContract
import com.ciallo.hyperbackground.R
import com.ciallo.hyperbackground.ui.MainActivity
import java.io.File
import top.yukonga.miuix.kmp.basic.BasicComponent
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.basic.ArrowRight
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.preference.SwitchPreference

@Composable
fun BackgroundPickerPreference(
    activity: MainActivity,
    slot: String? = null,
) {
    val config = activity.config
    val currentFile = if (slot == null) config.uiBackgroundFile else config.backgroundFile(slot)
    val currentMime = if (slot == null) {
        config.getString(BackgroundContract.UI_BG_MIME, "image/*") ?: "image/*"
    } else {
        config.backgroundMime(slot)
    }
    var showDialog by remember { mutableStateOf(false) }
    var selectedUri by remember { mutableStateOf<Uri?>(null) }
    var selectedMime by remember { mutableStateOf<String?>(null) }
    val opacityKey = if (slot == null) BackgroundContract.UI_BG_OPACITY else BackgroundContract.OPACITY_PREFIX + slot
    val blurKey = if (slot == null) BackgroundContract.UI_BG_BLUR_ENABLED else BackgroundContract.BLUR_ENABLED_PREFIX + slot
    val radiusKey = if (slot == null) BackgroundContract.UI_BG_BLUR_RADIUS else BackgroundContract.BLUR_RADIUS_PREFIX + slot
    var opacity by remember(showDialog, activity.revision) {
        mutableFloatStateOf(config.getInt(opacityKey, 100).coerceIn(0, 100).toFloat())
    }
    var blur by remember(showDialog, activity.revision) {
        mutableStateOf(config.getBoolean(blurKey, false))
    }
    var radius by remember(showDialog, activity.revision) {
        mutableFloatStateOf(config.getInt(radiusKey, 20).coerceIn(0, 80).toFloat())
    }

    BasicComponent(
        title = stringResource(R.string.set_background),
        summary = stringResource(R.string.set_background_summary),
        endActions = {
            Icon(imageVector = MiuixIcons.Basic.ArrowRight, contentDescription = null)
        },
        onClick = { showDialog = true },
    )

    WindowDialog(
        title = stringResource(R.string.set_background),
        summary = stringResource(R.string.background_dialog_summary),
        show = showDialog,
        onDismissRequest = {
            showDialog = false
            selectedUri = null
            selectedMime = null
        },
    ) {
        val dismiss = LocalDismissState.current
        Column(
            verticalArrangement = Arrangement.spacedBy(6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            BackgroundPreview(
                activity = activity,
                file = currentFile,
                uri = selectedUri,
                mime = selectedMime ?: currentMime,
                onClick = {
                    if (slot == null) {
                        activity.chooseUiBackground { uri, mime ->
                            selectedUri = uri
                            selectedMime = mime
                        }
                    } else {
                        activity.chooseBackground(slot) { uri, mime ->
                            selectedUri = uri
                            selectedMime = mime
                        }
                    }
                },
            )
            Text(
                text = when {
                    selectedUri != null -> selectedMime.orEmpty()
                    currentFile.isFile -> stringResource(R.string.enabled_size, humanSize(currentFile.length()))
                    else -> stringResource(R.string.system_default)
                },
                color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
            )
            SliderPreference(
                label = stringResource(R.string.opacity),
                value = opacity,
                range = 0f..100f,
                suffix = "%",
                onValueChange = { opacity = it },
                onValueChangeFinished = {},
            )
            SwitchPreference(
                title = stringResource(R.string.background_blur),
                summary = stringResource(R.string.background_blur_summary),
                checked = blur,
                onCheckedChange = { blur = it },
            )
            AnimatedVisibility(
                visible = blur,
                enter = expandVertically(animationSpec = tween(300)) + fadeIn(animationSpec = tween(220)),
                exit = shrinkVertically(animationSpec = tween(300)) + fadeOut(animationSpec = tween(180)),
            ) {
                SliderPreference(
                    label = stringResource(R.string.blur_strength),
                    value = radius,
                    range = 0f..80f,
                    onValueChange = { radius = it },
                    onValueChangeFinished = {},
                )
            }
            androidx.compose.foundation.layout.Spacer(Modifier.height(6.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TextButton(
                    text = stringResource(R.string.restore_default),
                    modifier = Modifier.weight(1f),
                    onClick = {
                        if (slot == null) activity.clearUiBackground() else activity.clearBackground(slot)
                        config.edit()
                            .putInt(opacityKey, 100)
                            .putBoolean(blurKey, false)
                            .putInt(radiusKey, 20)
                            .apply()
                        activity.refreshUi()
                        dismiss?.invoke()
                    },
                )
                TextButton(
                    text = stringResource(R.string.save),
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        val uri = selectedUri
                        val mime = selectedMime
                        if (uri != null && mime != null) {
                            if (slot == null) activity.saveUiBackground(uri, mime)
                            else activity.saveBackground(slot, uri, mime)
                        }
                        config.edit()
                            .putInt(opacityKey, opacity.toInt())
                            .putBoolean(blurKey, blur)
                            .putInt(radiusKey, radius.toInt())
                            .apply()
                        activity.refreshUi()
                        dismiss?.invoke()
                    },
                )
            }
        }
    }
}

@Composable
private fun BackgroundPreview(
    activity: MainActivity,
    file: File,
    uri: Uri?,
    mime: String?,
    onClick: () -> Unit,
) {
    val maxPreviewHeight = (LocalConfiguration.current.screenHeightDp.dp / 2).coerceAtMost(280.dp)
    val bitmap = remember(file.lastModified(), uri, mime) {
        runCatching {
            when {
                mime?.startsWith("video/") == true && (uri != null || file.isFile) -> MediaMetadataRetriever().let { retriever ->
                    try {
                        if (uri != null) retriever.setDataSource(activity, uri)
                        else retriever.setDataSource(file.absolutePath)
                        retriever.getFrameAtTime(0)
                    } finally {
                        retriever.release()
                    }
                }
                uri != null -> activity.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }
                file.isFile -> BitmapFactory.decodeFile(file.absolutePath)
                else -> null
            }
        }.getOrNull()
    }
    Box(
        modifier = Modifier
            .width(200.dp)
            .heightIn(max = maxPreviewHeight)
            .aspectRatio(0.72f)
            .clip(RoundedCornerShape(20.dp))
            .background(MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.55f))
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                text = stringResource(R.string.choose_media),
                modifier = Modifier.padding(24.dp),
                color = MiuixTheme.colorScheme.primary,
            )
        }
    }
}

private fun humanSize(bytes: Long): String = when {
    bytes >= 1_048_576 -> String.format("%.1f MB", bytes / 1_048_576f)
    bytes >= 1_024 -> String.format("%.1f KB", bytes / 1_024f)
    else -> "$bytes B"
}
