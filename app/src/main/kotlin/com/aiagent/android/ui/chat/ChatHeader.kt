package com.aiagent.android.ui.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.aiagent.android.ui.theme.KiroAccentBrush
import com.aiagent.android.ui.theme.KiroColors

@Composable
fun ChatHeader(
    model: String,
    usage: UsageStats,
    quotaCap: Int,
    onModelClick: () -> Unit,
    onQuotaClick: () -> Unit,
    onSettingsClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(56.dp)
            .background(KiroColors.Background.copy(alpha = 0.95f))
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BrandBadge()
            Spacer(Modifier.width(10.dp))
            ModelPill(model = model, onClick = onModelClick)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            QuotaPill(usage = usage, quotaCap = quotaCap, onClick = onQuotaClick)
            Spacer(Modifier.width(6.dp))
            IconButton(onClick = onSettingsClick) {
                Icon(
                    imageVector = Icons.Outlined.Settings,
                    contentDescription = "Настройки",
                    tint = KiroColors.Muted,
                )
            }
        }
    }
}

@Composable
private fun BrandBadge() {
    Box(
        modifier = Modifier
            .size(30.dp)
            .clip(RoundedCornerShape(9.dp))
            .background(KiroAccentBrush),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "K",
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.Bold,
        )
    }
}

@Composable
private fun ModelPill(model: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(KiroColors.Surface)
            .border(1.dp, KiroColors.Border, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Star,
            contentDescription = null,
            tint = KiroColors.Accent,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = kiroModelLabel(model),
            color = KiroColors.Foreground,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
        )
        Spacer(Modifier.width(2.dp))
        Icon(
            imageVector = Icons.Outlined.ExpandMore,
            contentDescription = null,
            tint = KiroColors.Muted,
            modifier = Modifier.size(16.dp),
        )
    }
}

@Composable
private fun QuotaPill(usage: UsageStats, quotaCap: Int, onClick: () -> Unit) {
    val used = formatTokens(usage.totalTokens)
    val cap = if (quotaCap > 0) " / ${formatTokens(quotaCap)}" else ""
    val pct = if (quotaCap > 0) ((usage.totalTokens.toDouble() / quotaCap) * 100).coerceAtMost(100.0) else 0.0
    Row(
        modifier = Modifier
            .clip(CircleShape)
            .background(KiroColors.Surface)
            .border(1.dp, KiroColors.Border, CircleShape)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            imageVector = Icons.Outlined.Speed,
            contentDescription = null,
            tint = KiroColors.Accent2,
            modifier = Modifier.size(14.dp),
        )
        Spacer(Modifier.width(6.dp))
        Text(
            text = "$used$cap",
            color = KiroColors.Foreground,
            fontSize = 12.sp,
            fontWeight = FontWeight.Medium,
        )
        if (quotaCap > 0) {
            Spacer(Modifier.width(4.dp))
            Text(
                text = "(${pct.toInt()}%)",
                color = KiroColors.Muted,
                fontSize = 11.sp,
            )
        }
    }
}

fun formatTokens(n: Int): String = when {
    n >= 1_000_000 -> "${(n / 1_000_000.0)}M".take(5).replace(".0M", "M")
    n >= 1_000 -> "${(n / 1_000.0)}k".take(5).replace(".0k", "k")
    else -> n.toString()
}
