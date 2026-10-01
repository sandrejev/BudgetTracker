package com.example.budgettracker.ui

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import com.example.budgettracker.ui.icons.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.example.budgettracker.data.ShopLocation
import com.example.budgettracker.ui.theme.*
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.TileSourcePolicy
import org.osmdroid.tileprovider.tilesource.XYTileSource
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.mylocation.GpsMyLocationProvider
import org.osmdroid.views.overlay.mylocation.MyLocationNewOverlay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import java.io.File

// ── osmdroid setup ───────────────────────────────────────────────────────────

// Follows the OpenStreetMap tile usage policy
// (https://operations.osmfoundation.org/policies/tiles/); otherwise OSM's servers
// answer with "Access blocked" tiles.

private const val OSM_USER_AGENT =
    "BudgetTracker/1.0 (Android budget app; +https://github.com/sandrejev/BudgetTracker)"

/**
 * Same as osmdroid's TileSourceFactory.MAPNIK, minus FLAG_USER_AGENT_NORMALIZED.
 * With that flag osmdroid ignores [OSM_USER_AGENT] and sends "<packageName>/<versionCode>",
 * and OSM blocks every "com.example.*" app as unidentifiable.
 */
private val OsmTiles = XYTileSource(
    "Mapnik", 0, 19, 256, ".png",
    arrayOf("https://tile.openstreetmap.org/"),
    "© OpenStreetMap contributors",
    TileSourcePolicy(
        2,
        TileSourcePolicy.FLAG_NO_BULK or
            TileSourcePolicy.FLAG_NO_PREVENTIVE or
            TileSourcePolicy.FLAG_USER_AGENT_MEANINGFUL
    )
)

/**
 * Sets a unique User-Agent and a persistent tile cache, so tiles aren't
 * downloaded again on every visit. Must run before a MapView is created.
 */
private fun configureOsmdroid(context: Context) {
    val config = Configuration.getInstance()
    config.load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
    config.userAgentValue = OSM_USER_AGENT
    // A fresh cache directory, which also drops "Access blocked" tiles cached
    // before the User-Agent was fixed. Android can clear it when space is low.
    config.osmdroidTileCache = File(context.cacheDir, "map-tiles")
}

// ── Screen ───────────────────────────────────────────────────────────────────

/**
 * Full-screen map picker. The user pans to the desired spot; the crosshair
 * always marks the map centre. Tapping "Confirm" returns the centre coordinate
 * together with an optional label.
 *
 * @param existingLocation  Pre-fill the map at this location when editing.
 * @param onConfirm         Called with (latitude, longitude, label) on confirm.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapPickerScreen(
    existingLocation: ShopLocation?,
    onBack: () -> Unit,
    onConfirm: (lat: Double, lng: Double, label: String) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // osmdroid must be configured before the MapView below is created
    remember { configureOsmdroid(context) }

    // Track the map centre — updated via MapListener
    val initialLat = existingLocation?.latitude ?: 50.85
    val initialLng = existingLocation?.longitude ?: 4.35
    var centerLat by remember { mutableStateOf(initialLat) }
    var centerLng by remember { mutableStateOf(initialLng) }
    var label by remember { mutableStateOf(existingLocation?.label ?: "") }
    var locating by remember { mutableStateOf(false) }

    // MapView reference so we can animate to current location
    var mapViewRef by remember { mutableStateOf<MapView?>(null) }

    val locationPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) locating = true
    }

    // Jump to device location when locating = true
    LaunchedEffect(locating) {
        if (!locating) return@LaunchedEffect
        val fused = LocationServices.getFusedLocationProviderClient(context)
        val loc = runCatching {
            @SuppressLint("MissingPermission")
            suspendCancellableCoroutine<Pair<Double, Double>?> { cont ->
                fused.getCurrentLocation(Priority.PRIORITY_BALANCED_POWER_ACCURACY, null)
                    .addOnSuccessListener { l -> cont.resume(l?.let { it.latitude to it.longitude }) }
                    .addOnFailureListener { cont.resume(null) }
            }
        }.getOrNull()
        loc?.let { (lat, lng) ->
            centerLat = lat
            centerLng = lng
            mapViewRef?.controller?.animateTo(GeoPoint(lat, lng))
        }
        locating = false
    }

    Scaffold(
        containerColor = BackgroundDark,
        topBar = {
            TopAppBar(
                title = { Text("Pick location", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = BackgroundDark)
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            // ── osmdroid MapView ──────────────────────────────────────────────
            AndroidView(
                modifier = Modifier.fillMaxSize(),
                factory = { ctx ->
                    MapView(ctx).also { mv ->
                        mapViewRef = mv
                        mv.setTileSource(OsmTiles)
                        mv.setMultiTouchControls(true)
                        mv.controller.setZoom(17.0)
                        mv.controller.setCenter(GeoPoint(initialLat, initialLng))

                        // Update center state whenever the map moves
                        mv.addMapListener(object : org.osmdroid.events.MapListener {
                            override fun onScroll(event: org.osmdroid.events.ScrollEvent?): Boolean {
                                mv.mapCenter?.let { pt ->
                                    centerLat = pt.latitude
                                    centerLng = pt.longitude
                                }
                                return false
                            }
                            override fun onZoom(event: org.osmdroid.events.ZoomEvent?): Boolean = false
                        })

                        // My-location overlay (dot only, no follow mode)
                        val locationOverlay = MyLocationNewOverlay(GpsMyLocationProvider(ctx), mv)
                        locationOverlay.enableMyLocation()
                        mv.overlays.add(locationOverlay)
                    }
                },
                update = { mv ->
                    // Keep reference current
                    mapViewRef = mv
                }
            )

            // ── Crosshair ─────────────────────────────────────────────────────
            Box(
                modifier = Modifier.align(Alignment.Center),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.AddLocation,
                    contentDescription = "Target",
                    tint = Positive,
                    modifier = Modifier.size(40.dp)
                )
            }

            // ── Current location FAB ──────────────────────────────────────────
            FloatingActionButton(
                onClick = {
                    val perm = Manifest.permission.ACCESS_FINE_LOCATION
                    if (ContextCompat.checkSelfPermission(context, perm) == PackageManager.PERMISSION_GRANTED) {
                        locating = true
                    } else {
                        locationPermissionLauncher.launch(perm)
                    }
                },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(16.dp),
                containerColor = CardDark,
                shape = CircleShape
            ) {
                if (locating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        strokeWidth = 2.dp,
                        color = Positive
                    )
                } else {
                    Icon(Icons.Filled.MyLocation, "My location", tint = Positive)
                }
            }

            // ── Bottom panel: coordinates + label + confirm ───────────────────
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(
                        color = CardDark.copy(alpha = 0.95f),
                        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)
                    )
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Coordinate display
                Text(
                    text = "%.6f, %.6f".format(centerLat, centerLng),
                    color = Color(0xFF888888),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace
                )

                // Label input
                OutlinedTextField(
                    value = label,
                    onValueChange = { label = it },
                    placeholder = { Text("Label (optional, e.g. Main entrance)", color = Color(0xFF555555)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Positive,
                        unfocusedBorderColor = Color(0xFF333333),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        cursorColor = Positive
                    )
                )

                Button(
                    onClick = { onConfirm(centerLat, centerLng, label.trim()) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Positive),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(
                        "Confirm location",
                        color = Color(0xFF0E1116),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
