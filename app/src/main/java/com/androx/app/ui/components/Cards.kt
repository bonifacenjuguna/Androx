@file:OptIn(ExperimentalMaterial3Api::class)

package com.androx.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.hapticfeedback.HapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.androx.app.R

/** Wordmark: "androx" in a rounded geometric lowercase, drawn as a vector so it tints and scales cleanly. */
@Composable
fun AndroxWordmark(modifier: Modifier = Modifier, height: androidx.compose.ui.unit.Dp = 20.dp, tint: Color = MaterialTheme.colorScheme.onSurface) {
    Icon(
        painterResource(R.drawable.ic_androx_wordmark),
        contentDescription = "Androx",
        modifier = modifier.height(height).width(height * (729f / 195f)),
        tint = tint,
    )
}

// ------------------------------------------------------------------ haptics

class Haptics(private val feedback: HapticFeedback?, private val enabled: Boolean) {
    /** A light tick for toggles and selections. */
    fun tick() {
        if (enabled) feedback?.performHapticFeedback(HapticFeedbackType.TextHandleMove)
    }

    /** A firmer buzz for actions such as running an automation. */
    fun press() {
        if (enabled) feedback?.performHapticFeedback(HapticFeedbackType.LongPress)
    }
}

val LocalHaptics: ProvidableCompositionLocal<Haptics> = staticCompositionLocalOf { Haptics(null, false) }

@Composable
fun ProvideHaptics(enabled: Boolean, content: @Composable () -> Unit) {
    val feedback = LocalHapticFeedback.current
    val haptics = remember(feedback, enabled) { Haptics(feedback, enabled) }
    CompositionLocalProvider(LocalHaptics provides haptics, content = content)
}

// ------------------------------------------------------------------ small pieces

/** Android 14-style switch: shows a check in the thumb when on. */
@Composable
fun AndroxSwitch(checked: Boolean, onCheckedChange: ((Boolean) -> Unit)?, modifier: Modifier = Modifier) {
    val haptics = LocalHaptics.current
    Switch(
        checked = checked,
        onCheckedChange = if (onCheckedChange == null) null else ({ v: Boolean -> haptics.tick(); onCheckedChange(v) }),
        modifier = modifier,
        thumbContent = if (checked) ({
            Icon(Icons.Filled.Check, contentDescription = null, modifier = Modifier.size(SwitchDefaults.IconSize))
        }) else null,
    )
}

/** Small rounded label such as "Succeeded" or "Weekdays". */
@Composable
fun Pill(
    text: String,
    modifier: Modifier = Modifier,
    icon: Painter? = null,
    container: Color = MaterialTheme.colorScheme.surfaceContainerHighest,
    content: Color = MaterialTheme.colorScheme.onSurfaceVariant,
) {
    Row(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(container)
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        if (icon != null) Icon(icon, contentDescription = null, tint = content, modifier = Modifier.size(14.dp))
        Text(text, style = MaterialTheme.typography.labelMedium, color = content)
    }
}

/** Title with an optional text button on the right ("See all"). */
@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, actionLabel: String? = null, onAction: (() -> Unit)? = null) {
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f).padding(vertical = 8.dp),
        )
        if (actionLabel != null && onAction != null) TextButton(onClick = onAction) { Text(actionLabel) }
    }
}

/** A rounded tonal container. Pass [onClick] to make it tappable. */
@Composable
fun TonalCard(
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    container: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: Color = MaterialTheme.colorScheme.onSurface,
    shape: androidx.compose.ui.graphics.Shape = RoundedCornerShape(24.dp),
    body: @Composable ColumnScope.() -> Unit,
) {
    val colors = CardDefaults.cardColors(containerColor = container, contentColor = content)
    if (onClick != null) {
        Card(onClick = onClick, modifier = modifier, shape = shape, colors = colors, content = body)
    } else {
        Card(modifier = modifier, shape = shape, colors = colors, content = body)
    }
}

/** A group of settings-style rows inside one rounded container, like Pixel Settings. */
@Composable
fun PreferenceGroup(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shape = RoundedCornerShape(28.dp),
    ) {
        Column(Modifier.padding(vertical = 8.dp), content = content)
    }
}

/** Big number with a caption. */
@Composable
fun StatTile(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    container: Color = MaterialTheme.colorScheme.surfaceContainer,
    content: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(24.dp))
            .background(container)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(value, style = MaterialTheme.typography.headlineMedium, color = content)
        Text(label, style = MaterialTheme.typography.labelLarge, color = content.copy(alpha = 0.75f))
    }
}

/** A highlighted message with an optional action, for things that need attention. */
@Composable
fun InfoBanner(
    icon: Painter,
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    container: Color = MaterialTheme.colorScheme.errorContainer,
    content: Color = MaterialTheme.colorScheme.onErrorContainer,
) {
    TonalCard(modifier = modifier.fillMaxWidth(), container = container, content = content, onClick = onAction) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(24.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(message, style = MaterialTheme.typography.bodyMedium)
                if (actionLabel != null && onAction != null) {
                    TextButton(onClick = onAction, modifier = Modifier.padding(top = 4.dp)) { Text(actionLabel, color = content) }
                }
            }
        }
    }
}
