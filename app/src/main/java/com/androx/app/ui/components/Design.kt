@file:OptIn(ExperimentalMaterial3Api::class)

package com.androx.app.ui.components

import android.text.format.DateUtils
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.res.painterResource
import com.androx.app.R
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.androx.app.model.ExecutionStatus

/*
 * Androx design system.
 *
 * Android conventions used throughout: Material 3 top app bars and navigation, list rows with a leading
 * icon tile (like system Settings), tonal surfaces instead of heavy cards, icon-led dialogs, modal bottom sheets
 * for pickers and a monochrome palette. Screens compose these pieces; they should not restyle them.
 */

@Composable
fun transparentItemColors() = ListItemDefaults.colors(containerColor = Color.Transparent)

/** Rounded-square icon tile used as the leading element of list rows. */
@Composable
fun IconTile(
    painter: Painter,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    content: Color = MaterialTheme.colorScheme.onSurface,
) {
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painter, contentDescription = null, tint = content, modifier = Modifier.size(22.dp))
    }
}

@Composable
fun IconTile(icon: ImageVector, modifier: Modifier = Modifier) {
    IconTile(rememberVectorPainter(icon), modifier)
}

/** Status of a finished run as an icon tile. */
@Composable
fun StatusTile(status: ExecutionStatus, modifier: Modifier = Modifier) {
    val scheme = MaterialTheme.colorScheme
    val icon = when (status) {
        ExecutionStatus.SUCCESS -> R.drawable.mdi_check_circle_outline
        ExecutionStatus.FAILED -> R.drawable.mdi_alert_circle_outline
        ExecutionStatus.SKIPPED -> R.drawable.mdi_skip_next_circle_outline
    }
    val container = when (status) {
        ExecutionStatus.SUCCESS -> scheme.secondaryContainer
        ExecutionStatus.FAILED -> scheme.errorContainer
        ExecutionStatus.SKIPPED -> scheme.surfaceContainerHighest
    }
    val content = when (status) {
        ExecutionStatus.SUCCESS -> scheme.onSecondaryContainer
        ExecutionStatus.FAILED -> scheme.onErrorContainer
        ExecutionStatus.SKIPPED -> scheme.onSurfaceVariant
    }
    val description = when (status) {
        ExecutionStatus.SUCCESS -> "Succeeded"
        ExecutionStatus.FAILED -> "Failed"
        ExecutionStatus.SKIPPED -> "Skipped"
    }
    Box(
        modifier = modifier
            .size(40.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(container),
        contentAlignment = Alignment.Center,
    ) {
        Icon(painterResource(icon), contentDescription = description, tint = content, modifier = Modifier.size(22.dp))
    }
}

/** Small primary-coloured label above a group of rows, as in system Settings. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 16.dp, top = 20.dp, bottom = 8.dp),
    )
}

/** A settings-style row: optional leading icon tile, title, summary and trailing content. */
@Composable
fun PreferenceRow(
    title: String,
    modifier: Modifier = Modifier,
    summary: String? = null,
    icon: Painter? = null,
    onClick: (() -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) {
    val clickModifier = if (onClick != null) modifier.clickable(enabled = enabled, onClick = onClick) else modifier
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = if (summary != null) ({ Text(summary) }) else null,
        leadingContent = if (icon != null) ({ IconTile(icon) }) else null,
        trailingContent = trailing,
        colors = transparentItemColors(),
        modifier = clickModifier,
    )
}

/** Row with a switch; the whole row toggles, as in Android Settings. */
@Composable
fun SwitchRow(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    summary: String? = null,
    icon: Painter? = null,
) {
    val haptics = LocalHaptics.current
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = if (summary != null) ({ Text(summary) }) else null,
        leadingContent = if (icon != null) ({ IconTile(icon) }) else null,
        trailingContent = { AndroxSwitch(checked = checked, onCheckedChange = null) },
        colors = transparentItemColors(),
        modifier = modifier.toggleable(
            value = checked,
            role = Role.Switch,
            onValueChange = { v -> haptics.tick(); onCheckedChange(v) },
        ),
    )
}

/** Standard confirmation dialog: icon, title, message, text buttons. */
@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    icon: Painter? = null,
    dismissLabel: String = "Cancel",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = if (icon != null) ({ Icon(icon, contentDescription = null) }) else null,
        title = { Text(title) },
        text = { Text(message) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
    )
}

/** Empty list placeholder: icon, title, message and up to two actions. */
@Composable
fun EmptyState(
    icon: Painter,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actions: @Composable () -> Unit = {},
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 32.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(32.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 8.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Column(
            modifier = Modifier.padding(top = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) { actions() }
    }
}

/** Keeps content readable on tablets and in landscape: full width on phones, centred up to 640dp on larger screens. */
@Composable
fun CenteredContent(modifier: Modifier = Modifier, content: @Composable BoxScope.() -> Unit) {
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = 640.dp).fillMaxSize(), content = content)
    }
}

/** Wide screens (tablets, unfolded foldables, landscape) use a navigation rail instead of a bottom bar. */
@Composable
fun rememberIsWide(): Boolean = LocalConfiguration.current.screenWidthDp >= 600

/** "5 minutes ago", "Yesterday"… using the system's localised relative time. */
fun relativeTime(millis: Long): String =
    DateUtils.getRelativeTimeSpanString(millis, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
