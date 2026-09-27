package com.ciallo.hyperbackground.ui.pages

import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.ciallo.hyperbackground.LocalRandomBackgroundStore
import com.ciallo.hyperbackground.R
import com.ciallo.hyperbackground.ui.MainActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.theme.LocalDismissState
import top.yukonga.miuix.kmp.theme.MiuixTheme
import top.yukonga.miuix.kmp.window.WindowDialog
import kotlin.math.roundToInt

@Composable
internal fun LocalLibraryManagerDialog(
    activity: MainActivity,
    onDismiss: () -> Unit,
    onCountChanged: (Int) -> Unit,
) {
    var entries by remember { mutableStateOf(LocalRandomBackgroundStore.listImages(activity)) }
    var selected by remember { mutableStateOf(emptySet<String>()) }
    var deleting by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }
    val gridMaxHeight = (LocalConfiguration.current.screenHeightDp.dp * 0.52f)
        .coerceIn(220.dp, 520.dp)

    WindowDialog(
        title = stringResource(R.string.random_local_manage),
        summary = stringResource(R.string.random_local_manage_summary),
        show = true,
        onDismissRequest = { if (!deleting) onDismiss() },
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(
                        R.string.random_local_selected,
                        selected.size,
                        entries.size,
                    ),
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                )
                if (entries.isNotEmpty()) {
                    TextButton(
                        text = if (selected.size == entries.size) {
                            stringResource(R.string.deselect_all)
                        } else {
                            stringResource(R.string.select_all)
                        },
                        enabled = !deleting,
                        onClick = {
                            selected = if (selected.size == entries.size) {
                                emptySet()
                            } else {
                                entries.mapTo(linkedSetOf()) { it.id }
                            }
                        },
                    )
                }
            }

            if (entries.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxWidth().height(180.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = stringResource(R.string.random_local_empty),
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(92.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(max = gridMaxHeight),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(entries, key = { it.id }) { entry ->
                        LocalLibraryImageTile(
                            entry = entry,
                            selected = entry.id in selected,
                            onClick = {
                                selected = if (entry.id in selected) {
                                    selected - entry.id
                                } else {
                                    selected + entry.id
                                }
                            },
                        )
                    }
                }
            }

            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(
                    modifier = Modifier.weight(1f),
                    text = stringResource(R.string.close),
                    enabled = !deleting,
                    onClick = onDismiss,
                )
                TextButton(
                    modifier = Modifier.weight(1f),
                    text = if (deleting) {
                        stringResource(R.string.random_local_deleting)
                    } else {
                        stringResource(R.string.random_local_delete_selected, selected.size)
                    },
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    enabled = selected.isNotEmpty() && !deleting,
                    onClick = { showDeleteConfirm = true },
                )
            }
        }
    }

    if (showDeleteConfirm) {
        WindowDialog(
            title = stringResource(R.string.random_local_delete_title),
            summary = stringResource(R.string.random_local_delete_confirm, selected.size),
            show = true,
            onDismissRequest = { showDeleteConfirm = false },
        ) {
            val dismiss = LocalDismissState.current
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                TextButton(
                    modifier = Modifier.weight(1f),
                    text = stringResource(R.string.cancel),
                    onClick = { showDeleteConfirm = false },
                )
                TextButton(
                    modifier = Modifier.weight(1f),
                    text = stringResource(R.string.confirm),
                    colors = ButtonDefaults.textButtonColorsPrimary(),
                    onClick = {
                        val ids = selected
                        showDeleteConfirm = false
                        deleting = true
                        dismiss?.invoke()
                        Thread({
                            val result = LocalRandomBackgroundStore.deleteImages(activity, ids)
                            val updated = LocalRandomBackgroundStore.listImages(activity)
                            activity.runOnUiThread {
                                entries = updated
                                selected = emptySet()
                                deleting = false
                                onCountChanged(updated.size)
                                Toast.makeText(
                                    activity,
                                    activity.getString(
                                        R.string.random_local_delete_result,
                                        result.deleted,
                                        result.failed,
                                    ),
                                    Toast.LENGTH_SHORT,
                                ).show()
                            }
                        }, "RandomBg-LocalDelete").start()
                    },
                )
            }
        }
    }
}

@Composable
private fun LocalLibraryImageTile(
    entry: LocalRandomBackgroundStore.ImageEntry,
    selected: Boolean,
    onClick: () -> Unit,
) {
    val targetPixels = with(LocalDensity.current) { 128.dp.toPx().roundToInt() }
    val bitmap by produceState<android.graphics.Bitmap?>(
        initialValue = null,
        key1 = entry.id,
        key2 = entry.lastModified,
        key3 = targetPixels,
    ) {
        value = withContext(Dispatchers.IO) {
            LocalRandomBackgroundStore.loadThumbnail(entry, targetPixels)
        }
    }
    val shape = RoundedCornerShape(14.dp)
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(0.78f)
            .clip(shape)
            .background(MiuixTheme.colorScheme.secondaryContainer)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) {
                    MiuixTheme.colorScheme.primary
                } else {
                    MiuixTheme.colorScheme.onSurface.copy(alpha = 0.12f)
                },
                shape = shape,
            )
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        if (bitmap != null) {
            Image(
                bitmap = requireNotNull(bitmap).asImageBitmap(),
                contentDescription = null,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text("…", color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
        }
        if (selected) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .size(24.dp)
                    .clip(CircleShape)
                    .background(MiuixTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Text("✓", color = Color.White)
            }
        }
    }
}
