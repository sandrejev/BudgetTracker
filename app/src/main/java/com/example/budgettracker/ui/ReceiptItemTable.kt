package com.example.budgettracker.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.budgettracker.ui.theme.Negative
import com.example.budgettracker.ui.theme.Positive

// Column widths shared by header and rows
private val QtyWidth = 36.dp
private val DiscountWidth = 64.dp
private val PriceWidth = 72.dp

private val HeaderColor = Color(0xFF888888)
private val DiscountColor = Color(0xFFE0A050)

/** Column titles of a receipt's item table: Item · Qty · Discount · Price. */
@Composable
fun ReceiptItemHeader() {
    Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("Item", color = HeaderColor, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text("Qty", color = HeaderColor, fontSize = 12.sp, textAlign = TextAlign.End, modifier = Modifier.width(QtyWidth))
        Text("Discount", color = HeaderColor, fontSize = 12.sp, textAlign = TextAlign.End, modifier = Modifier.width(DiscountWidth))
        Text("Price", color = HeaderColor, fontSize = 12.sp, textAlign = TextAlign.End, modifier = Modifier.width(PriceWidth))
    }
}

/**
 * One item: name with common name · category below, then quantity, total discount and
 * the price paid (after discount). [priceText] is shown in red when [price] is null
 * (an unparseable price while reviewing).
 */
@Composable
fun ReceiptItemRow(
    name: String,
    commonName: String?,
    category: String?,
    quantity: Double,
    discount: Double?,
    price: Double?,
    priceText: String = price?.let { "€%.2f".format(it) } ?: "",
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(Modifier.weight(1f)) {
            Text(name, color = Color.White, fontSize = 15.sp, fontWeight = FontWeight.Medium)
            if (!commonName.isNullOrBlank()) {
                Text(
                    listOfNotNull(commonName, category).joinToString(" · "),
                    color = Color(0xFF6A9B6A), fontSize = 11.sp, fontStyle = FontStyle.Italic
                )
            }
        }
        Text(
            formatQuantity(quantity),
            color = if (quantity == 1.0) Color(0xFF666666) else Color.White,
            fontSize = 13.sp, textAlign = TextAlign.End, modifier = Modifier.width(QtyWidth)
        )
        Text(
            discount?.takeIf { it > 0 }?.let { "−€%.2f".format(it) } ?: "",
            color = DiscountColor, fontSize = 13.sp, textAlign = TextAlign.End,
            modifier = Modifier.width(DiscountWidth)
        )
        Text(
            if (price != null) "€%.2f".format(price) else priceText,
            color = if (price != null) Positive else Negative,
            fontSize = 14.sp, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End,
            modifier = Modifier.width(PriceWidth)
        )
    }
}
