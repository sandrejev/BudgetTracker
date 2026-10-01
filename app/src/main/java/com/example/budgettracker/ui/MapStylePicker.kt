package com.example.budgettracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.example.budgettracker.ui.theme.CardDark
import com.example.budgettracker.ui.theme.Positive

/** Horizontally scrolling row of map style previews; tapping one selects it. */
@Composable
fun MapStylePicker(selected: MapStyle, onSelect: (MapStyle) -> Unit) {
    val context = LocalContext.current
    val shape = RoundedCornerShape(10.dp)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        MapStyle.available.forEach { style ->
            val isSelected = style == selected
            Column(
                modifier = Modifier
                    .width(104.dp)
                    .clip(shape)
                    .clickable { onSelect(style) },
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                AsyncImage(
                    model = remember(style) {
                        ImageRequest.Builder(context)
                            .data(style.previewUrl)
                            .setHeader("User-Agent", MAP_USER_AGENT)
                            .crossfade(true)
                            .build()
                    },
                    contentDescription = style.label,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(104.dp)
                        .clip(shape)
                        .background(CardDark)
                        .border(
                            width = if (isSelected) 3.dp else 1.dp,
                            color = if (isSelected) Positive else Color(0xFF333333),
                            shape = shape
                        )
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    style.label,
                    fontSize = 12.sp,
                    color = if (isSelected) Positive else Color(0xFFCCCCCC),
                    fontWeight = if (isSelected) FontWeight.SemiBold else FontWeight.Normal
                )
            }
        }
    }
}
