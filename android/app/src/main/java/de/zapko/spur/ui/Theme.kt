package de.zapko.spur.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import de.zapko.spur.data.Sync

object C {
    val bg = Color(0xFF000000)
    val s1 = Color(0xFF0E0E0E)
    val s2 = Color(0xFF171717)
    val s3 = Color(0xFF222222)
    val line = Color(0xFF262626)
    val t1 = Color(0xFFF2F2F2)
    val t2 = Color(0xFF8C8C8C)
    val t3 = Color(0xFF5A5A5A)
    val acc = Color(0xFFE8FF47)
    val danger = Color(0xFFFF5C5C)
    val ok = Color(0xFF4ADE80)
    val warn = Color(0xFFFFB020)
    val snap = Color(0xFFFFFC00)
}

val Mono = FontFamily.Monospace

private val typo = Typography(
    displaySmall = TextStyle(fontSize = 34.sp, fontWeight = FontWeight.Black, letterSpacing = (-1).sp),
    headlineSmall = TextStyle(fontSize = 24.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.6).sp),
    titleLarge = TextStyle(fontSize = 20.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.4).sp),
    titleMedium = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.SemiBold),
    bodyLarge = TextStyle(fontSize = 16.sp),
    bodyMedium = TextStyle(fontSize = 14.sp),
    bodySmall = TextStyle(fontSize = 12.sp),
    labelLarge = TextStyle(fontSize = 14.sp, fontWeight = FontWeight.SemiBold),
    labelMedium = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium),
    labelSmall = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp),
)

@Composable
fun SpurTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = C.t1, onPrimary = C.bg, secondary = C.acc, onSecondary = C.bg,
            background = C.bg, onBackground = C.t1, surface = C.s1, onSurface = C.t1,
            surfaceVariant = C.s2, onSurfaceVariant = C.t2, outline = C.line, error = C.danger,
            surfaceContainer = C.s1, surfaceContainerHigh = C.s2, surfaceContainerHighest = C.s3,
            surfaceContainerLow = C.s1, surfaceContainerLowest = C.bg,
        ),
        typography = typo,
        content = content,
    )
}

@Composable
fun Thumb(model: Any?, modifier: Modifier = Modifier, contentScale: ContentScale = ContentScale.Crop) {
    if (model == null) {
        Box(modifier.background(C.s2))
    } else {
        AsyncImage(model = model, contentDescription = null, contentScale = contentScale, modifier = modifier.background(C.s1))
    }
}

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(if (selected) C.t1 else C.s2)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
    ) {
        Text(text, color = if (selected) C.bg else C.t1, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
fun RoundIcon(icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 44.dp,
              tint: Color = C.t1, bg: Color = Color(0x66000000), desc: String? = null) {
    Box(
        modifier.size(size).clip(CircleShape).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, contentDescription = desc, tint = tint, modifier = Modifier.size(size * 0.5f)) }
}

@Composable
fun SyncDot(sync: Int, onServer: Boolean, modifier: Modifier = Modifier) {
    val color = when {
        onServer -> C.ok
        sync == Sync.LOCAL_ONLY -> C.t2
        sync == Sync.ERROR -> C.danger
        else -> C.warn
    }
    Box(modifier.size(7.dp).clip(CircleShape).background(color).border(1.dp, Color(0x66000000), CircleShape))
}

fun syncLabel(sync: Int, onServer: Boolean, hasLocal: Boolean): String = when {
    onServer && hasLocal -> "Handy + Server"
    onServer -> "Nur Server"
    sync == Sync.LOCAL_ONLY -> "Nur Handy"
    sync == Sync.ERROR -> "Upload-Fehler, neuer Versuch folgt"
    else -> "Wartet auf Upload"
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), color = C.t2, style = MaterialTheme.typography.labelSmall,
        modifier = modifier.padding(top = 22.dp, bottom = 8.dp))
}

@Composable
fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.clip(RoundedCornerShape(12.dp)).background(C.s1).border(1.dp, C.line, RoundedCornerShape(12.dp))) {
        content()
    }
}

@Composable
fun InfoRow(label: String, value: String?, mono: Boolean = false, onClick: (() -> Unit)? = null) {
    if (value.isNullOrBlank()) return
    Row(
        Modifier.fillMaxWidth().let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(label, color = C.t2, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.width(118.dp))
        Text(value, color = C.t1, style = MaterialTheme.typography.bodyMedium.copy(fontFamily = if (mono) Mono else null),
            modifier = Modifier.weight(1f))
    }
}

@Composable
fun SettingSwitch(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable { onChange(!checked) }.padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = C.t1)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = C.t2)
        }
        Spacer(Modifier.width(12.dp))
        Switch(
            checked = checked, onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = C.bg, checkedTrackColor = C.acc, uncheckedThumbColor = C.t2,
                uncheckedTrackColor = C.s2, uncheckedBorderColor = C.line,
            ),
        )
    }
}

@Composable
fun SettingItem(title: String, subtitle: String?, onClick: () -> Unit, trailing: String? = null, color: Color = C.t1) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, color = color)
            if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = C.t2,
                maxLines = 3, overflow = TextOverflow.Ellipsis)
        }
        if (trailing != null) Text(trailing, color = C.t2, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
fun PrimaryButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    Box(
        modifier.height(52.dp).clip(RoundedCornerShape(12.dp))
            .background(if (enabled) C.t1 else C.s3)
            .clickable(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (enabled) C.bg else C.t2, style = MaterialTheme.typography.titleMedium) }
}

@Composable
fun GhostButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, color: Color = C.t1) {
    Box(
        modifier.height(48.dp).clip(RoundedCornerShape(12.dp)).border(1.dp, C.line, RoundedCornerShape(12.dp))
            .clickable(onClick = onClick).padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = color, style = MaterialTheme.typography.labelLarge) }
}

@Composable
fun Divider() { Box(Modifier.fillMaxWidth().height(1.dp).background(C.line)) }

@Composable
fun Hstack(modifier: Modifier = Modifier, gap: Dp = 8.dp, content: @Composable () -> Unit) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(gap), verticalAlignment = Alignment.CenterVertically) { content() }
}
