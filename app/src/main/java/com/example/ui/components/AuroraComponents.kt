package com.example.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.*

/* ─────────────────────────────────────────────────────────────
 *  Aurora component kit — the shared building blocks used by
 *  every screen. One place to change the card language.
 * ───────────────────────────────────────────────────────────── */

/** Standard obsidian glass card. */
@Composable
fun AuroraCard(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    content: @Composable ColumnScope.() -> Unit
) {
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(20.dp),
        color = Surface1,
        border = BorderStroke(1.dp, accent?.copy(alpha = 0.25f) ?: HairLine)
    ) {
        Column(modifier = Modifier.padding(18.dp), content = content)
    }
}

/** Tiny uppercase section label with a violet tick. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(width = 3.dp, height = 12.dp)
                .clip(CircleShape)
                .background(Brush.verticalGradient(listOf(Violet, Aqua)))
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
            text = text.uppercase(),
            fontSize = 10.sp,
            color = TextFaint,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp
        )
    }
}

/** Pill status chip: colored dot + label. */
@Composable
fun StatusPill(
    text: String,
    color: Color,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null
) {
    val base = modifier
        .clip(RoundedCornerShape(50))
        .background(color.copy(alpha = 0.10f))
        .border(1.dp, color.copy(alpha = 0.35f), RoundedCornerShape(50))
        .let { if (onClick != null) it.clickable { onClick() } else it }
        .padding(horizontal = 12.dp, vertical = 6.dp)

    Row(modifier = base, verticalAlignment = Alignment.CenterVertically) {
        Box(
            modifier = Modifier
                .size(7.dp)
                .clip(CircleShape)
                .background(color)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Text(
            text = text,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            color = color
        )
    }
}

/** Aurora-styled toggle switch colors. */
@Composable
fun auroraSwitchColors() = SwitchDefaults.colors(
    checkedThumbColor = Ink,
    checkedTrackColor = Violet,
    uncheckedThumbColor = TextMuted,
    uncheckedTrackColor = Surface3
)

/** Row with title/description on the left and a switch on the right. */
@Composable
fun ToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 12.dp)) {
            Text(text = title, color = TextBright, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Text(
                text = description,
                color = TextMuted,
                fontSize = 11.sp,
                lineHeight = 15.sp,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange, colors = auroraSwitchColors())
    }
}

/** Permission checklist row: dot indicator + chevron, tappable. */
@Composable
fun PermissionRow(
    title: String,
    description: String,
    granted: Boolean,
    icon: ImageVector? = null,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(if (granted) Mint.copy(alpha = 0.05f) else Surface2)
            .border(
                1.dp,
                if (granted) Mint.copy(alpha = 0.30f) else HairLineSoft,
                RoundedCornerShape(14.dp)
            )
            .clickable { onClick() }
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(34.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background((if (granted) Mint else Violet).copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = if (granted) Mint else Violet,
                    modifier = Modifier.size(18.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = TextBright)
            Text(
                text = if (granted) "Granted" else description,
                fontSize = 11.sp,
                color = if (granted) Mint else TextMuted,
                modifier = Modifier.padding(top = 2.dp)
            )
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .clip(CircleShape)
                    .background(if (granted) Mint else Rose)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = "Open settings",
                tint = TextFaint,
                modifier = Modifier.size(16.dp)
            )
        }
    }
}

/** Gradient progress bar used on app usage cards. */
@Composable
fun GradientProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    danger: Boolean = false
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.06f))
    ) {
        Box(
            modifier = Modifier
                .fillMaxHeight()
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .clip(CircleShape)
                .background(
                    Brush.horizontalGradient(
                        if (danger) listOf(Amber, Rose) else listOf(Violet, Aqua)
                    )
                )
        )
    }
}
