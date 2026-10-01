package com.example.budgettracker.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import com.example.budgettracker.ui.icons.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.example.budgettracker.data.Shop
import com.example.budgettracker.data.ShopLocation
import com.example.budgettracker.data.ShopWithLocations
import com.example.budgettracker.ui.theme.*

// ── Screen ───────────────────────────────────────────────────────────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ShopManagementScreen(
    viewModel: BudgetViewModel,
    onBack: () -> Unit,
    onPickLocation: (shopId: Long, existingLocation: ShopLocation?) -> Unit
) {
    val shopsWithLocations by viewModel.shopsWithLocations.collectAsState()
    var showAddDialog by remember { mutableStateOf(false) }
    var editingShop by remember { mutableStateOf<Shop?>(null) }
    var expandedShopId by remember { mutableStateOf<Long?>(null) }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("Shops", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Filled.ArrowBack, "Back", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = { showAddDialog = true }) {
                        Icon(Icons.Filled.Add, "Add shop", tint = Positive)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDark)
            )
        }
    ) { padding ->
        if (shopsWithLocations.isEmpty()) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Filled.Store,
                        contentDescription = null,
                        tint = Color(0xFF444444),
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(Modifier.height(16.dp))
                    Text("No shops yet", color = Color(0xFF666666), fontSize = 16.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Tap + to add a shop and its location",
                        color = Color(0xFF444444),
                        fontSize = 13.sp
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                items(shopsWithLocations, key = { it.shop.id }) { swl ->
                    ShopItem(
                        shopWithLocations = swl,
                        isExpanded = expandedShopId == swl.shop.id,
                        onToggleExpand = {
                            expandedShopId = if (expandedShopId == swl.shop.id) null else swl.shop.id
                        },
                        onEdit = { editingShop = swl.shop },
                        onDelete = { viewModel.deleteShop(swl.shop) },
                        onAddLocation = { onPickLocation(swl.shop.id, null) },
                        onEditLocation = { loc -> onPickLocation(swl.shop.id, loc) },
                        onDeleteLocation = { loc -> viewModel.deleteShopLocation(loc) }
                    )
                    HorizontalDivider(color = Color(0xFF1E242B))
                }
                item { Spacer(Modifier.height(24.dp)) }
            }
        }
    }

    if (showAddDialog) {
        ShopDialog(
            title = "Add shop",
            initialName = "",
            initialLogoUrl = "",
            onDismiss = { showAddDialog = false },
            onConfirm = { name, logoUri ->
                viewModel.addShop(name, logoUri)
                showAddDialog = false
            }
        )
    }

    editingShop?.let { shop ->
        ShopDialog(
            title = "Edit shop",
            initialName = shop.name,
            initialLogoUrl = shop.logoUri ?: "",
            onDismiss = { editingShop = null },
            onConfirm = { name, logoUri ->
                viewModel.updateShop(shop.copy(name = name, logoUri = logoUri.ifBlank { null }))
                editingShop = null
            }
        )
    }
}

// ── Shop item row ─────────────────────────────────────────────────────────────

@Composable
private fun ShopItem(
    shopWithLocations: ShopWithLocations,
    isExpanded: Boolean,
    onToggleExpand: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onAddLocation: () -> Unit,
    onEditLocation: (ShopLocation) -> Unit,
    onDeleteLocation: (ShopLocation) -> Unit
) {
    val shop = shopWithLocations.shop
    val locations = shopWithLocations.locations

    Column {
        // Main row
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Logo
            ShopLogo(name = shop.name, logoUri = shop.logoUri)
            Spacer(Modifier.width(12.dp))
            // Name + location count
            Column(modifier = Modifier.weight(1f)) {
                Text(shop.name, color = Color.White, fontSize = 16.sp, fontWeight = FontWeight.Medium)
                if (locations.isNotEmpty()) {
                    Text(
                        "${locations.size} location${if (locations.size > 1) "s" else ""}",
                        color = Color(0xFF666666),
                        fontSize = 12.sp
                    )
                }
            }
            // Expand/collapse locations
            IconButton(onClick = onToggleExpand) {
                Icon(
                    imageVector = if (isExpanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = "Locations",
                    tint = Color(0xFF666666)
                )
            }
            IconButton(onClick = onEdit) {
                Icon(Icons.Filled.Edit, "Edit", tint = Color(0xFF666666), modifier = Modifier.size(18.dp))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Filled.Delete, "Delete", tint = Negative, modifier = Modifier.size(18.dp))
            }
        }

        // Expandable locations
        if (isExpanded) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 52.dp, bottom = 8.dp)
            ) {
                locations.forEach { loc ->
                    LocationRow(
                        location = loc,
                        onEdit = { onEditLocation(loc) },
                        onDelete = { onDeleteLocation(loc) }
                    )
                }
                // Add location button
                TextButton(
                    onClick = onAddLocation,
                    contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp)
                ) {
                    Icon(Icons.Filled.AddLocation, null, tint = Positive, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("Add location", color = Positive, fontSize = 13.sp)
                }
            }
        }
    }
}

@Composable
private fun ShopLogo(name: String, logoUri: String?) {
    val clearbitUrl = if (logoUri.isNullOrBlank()) {
        // Try Clearbit with shop name as domain guess
        null
    } else if (logoUri.startsWith("http")) {
        logoUri
    } else {
        "https://logo.clearbit.com/$logoUri"
    }

    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(Color(0xFF1E242B)),
        contentAlignment = Alignment.Center
    ) {
        if (clearbitUrl != null) {
            AsyncImage(
                model = clearbitUrl,
                contentDescription = name,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
                error = null,   // fall through to initials on error
                fallback = null
            )
        }
        // Fallback initials (always rendered behind image)
        Text(
            text = name.take(1).uppercase(),
            color = Color(0xFF888888),
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun LocationRow(
    location: ShopLocation,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Filled.LocationOn,
            contentDescription = null,
            tint = Color(0xFF555555),
            modifier = Modifier.size(14.dp)
        )
        Spacer(Modifier.width(6.dp))
        Column(modifier = Modifier.weight(1f)) {
            if (location.label.isNotBlank()) {
                Text(location.label, color = Color(0xFFBBBBBB), fontSize = 13.sp)
            }
            Text(
                "%.5f, %.5f".format(location.latitude, location.longitude),
                color = Color(0xFF555555),
                fontSize = 11.sp
            )
        }
        IconButton(onClick = onEdit, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.Edit, "Edit", tint = Color(0xFF555555), modifier = Modifier.size(14.dp))
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
            Icon(Icons.Filled.Close, "Delete", tint = Negative, modifier = Modifier.size(14.dp))
        }
    }
}

// ── Add/Edit shop dialog ──────────────────────────────────────────────────────

@Composable
private fun ShopDialog(
    title: String,
    initialName: String,
    initialLogoUrl: String,
    onDismiss: () -> Unit,
    onConfirm: (name: String, logoUri: String) -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var logoUrl by remember { mutableStateOf(initialLogoUrl) }

    val fieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = Positive,
        focusedLabelColor = Positive,
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White,
        unfocusedBorderColor = Color(0xFF444444),
        cursorColor = Positive
    )

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = CardDark,
        title = { Text(title, color = Color.White) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Shop name") },
                    singleLine = true,
                    colors = fieldColors
                )
                OutlinedTextField(
                    value = logoUrl,
                    onValueChange = { logoUrl = it },
                    label = { Text("Logo URL or domain (optional)") },
                    placeholder = { Text("e.g. lidl.com", color = Color(0xFF555555)) },
                    singleLine = true,
                    colors = fieldColors
                )
                // Preview logo if URL entered
                if (logoUrl.isNotBlank()) {
                    val previewUrl = if (logoUrl.startsWith("http")) logoUrl
                    else "https://logo.clearbit.com/$logoUrl"
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Preview: ", color = Color(0xFF666666), fontSize = 12.sp)
                        AsyncImage(
                            model = previewUrl,
                            contentDescription = "Logo preview",
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF1E242B)),
                            contentScale = ContentScale.Crop
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { if (name.isNotBlank()) onConfirm(name.trim(), logoUrl.trim()) },
                enabled = name.isNotBlank()
            ) { Text("Save", color = Positive) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = Color(0xFF888888)) }
        }
    )
}
