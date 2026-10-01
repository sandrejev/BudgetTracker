package com.example.budgettracker.ui

import android.content.Context
import androidx.annotation.DrawableRes
import com.example.budgettracker.BuildConfig
import com.example.budgettracker.R
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.tilesource.OnlineTileSourceBase
import org.osmdroid.tileprovider.tilesource.TileSourcePolicy
import org.osmdroid.util.MapTileIndex
import java.io.File

/**
 * Identifies the app to tile servers. OpenStreetMap's volunteer-run servers
 * (https://operations.osmfoundation.org/policies/tiles/) answer with "Access blocked"
 * tiles for generic user agents such as osmdroid's default or "com.example.*".
 */
const val MAP_USER_AGENT =
    "BudgetTracker/1.0 (Android budget app; +https://github.com/sandrejev/BudgetTracker)"

private const val OSM_ATTRIBUTION = "© OpenStreetMap contributors"
private const val MAPTILER_ATTRIBUTION = "© MapTiler © OpenStreetMap contributors"

/**
 * Map styles for the location picker. OpenStreetMap needs no key; the MapTiler
 * styles need MAPTILER_KEY in local.properties (see app/build.gradle.kts).
 */
enum class MapStyle(
    val label: String,
    /** MapTiler map id, or null for the OpenStreetMap standard style. */
    private val mapTilerId: String?,
    /**
     * Preview picture for the style picker, bundled so Settings shows it instantly:
     * one tile of central Frankfurt (Römer and the Main), zoom 15, tile 17174/11097.
     */
    @param:DrawableRes val previewRes: Int,
    private val extension: String = "png",
    private val maxZoom: Int = 20
) {
    OSM("OpenStreetMap", null, R.drawable.map_preview_osm, maxZoom = 19),
    STREETS("Streets", "streets-v2", R.drawable.map_preview_streets),
    STREETS_DARK("Streets dark", "streets-v2-dark", R.drawable.map_preview_streets_dark),
    BASIC("Basic", "basic-v2", R.drawable.map_preview_basic),
    BASIC_DARK("Basic dark", "basic-v2-dark", R.drawable.map_preview_basic_dark),
    BRIGHT("Bright", "bright-v2", R.drawable.map_preview_bright),
    OUTDOOR("Outdoor", "outdoor-v2", R.drawable.map_preview_outdoor),
    SATELLITE("Satellite", "hybrid", R.drawable.map_preview_satellite, extension = "jpg");

    val attribution: String get() = if (mapTilerId == null) OSM_ATTRIBUTION else MAPTILER_ATTRIBUTION

    fun tileUrl(zoom: Int, x: Int, y: Int): String =
        if (mapTilerId == null) "https://tile.openstreetmap.org/$zoom/$x/$y.png"
        else "https://api.maptiler.com/maps/$mapTilerId/256/$zoom/$x/$y.$extension?key=${BuildConfig.MAPTILER_KEY}"

    /** osmdroid tile source for this style; one instance per style so tiles are cached per style. */
    val tileSource: OnlineTileSourceBase by lazy { StyleTileSource(this) }

    private class StyleTileSource(private val style: MapStyle) : OnlineTileSourceBase(
        "BudgetTracker-${style.name}", 0, style.maxZoom, 256, ".${style.extension}",
        arrayOf(style.tileUrl(0, 0, 0)), style.attribution,
        // No FLAG_USER_AGENT_NORMALIZED: with it osmdroid would send "<packageName>/<version>"
        // instead of MAP_USER_AGENT, and OSM blocks "com.example.*".
        TileSourcePolicy(
            2,
            TileSourcePolicy.FLAG_NO_BULK or
                TileSourcePolicy.FLAG_NO_PREVENTIVE or
                TileSourcePolicy.FLAG_USER_AGENT_MEANINGFUL
        )
    ) {
        override fun getTileURLString(pMapTileIndex: Long): String = style.tileUrl(
            MapTileIndex.getZoom(pMapTileIndex),
            MapTileIndex.getX(pMapTileIndex),
            MapTileIndex.getY(pMapTileIndex)
        )
    }

    companion object {
        val hasMapTilerKey: Boolean get() = BuildConfig.MAPTILER_KEY.isNotBlank()

        /** Styles that work in this build: all of them with a MapTiler key, otherwise only OSM. */
        val available: List<MapStyle> get() = if (hasMapTilerKey) entries else listOf(OSM)

        val default: MapStyle get() = if (hasMapTilerKey) STREETS else OSM

        /** The saved style, or [default] if nothing is saved or the saved style needs a missing key. */
        fun fromId(id: String?): MapStyle =
            available.firstOrNull { it.name == id } ?: default
    }
}

/**
 * Sets the User-Agent and a persistent tile cache, so tiles aren't downloaded
 * again on every visit. Must run before a MapView is created.
 */
fun configureOsmdroid(context: Context) {
    val config = Configuration.getInstance()
    config.load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
    config.userAgentValue = MAP_USER_AGENT
    // A fresh cache directory, which also drops "Access blocked" tiles cached
    // before the User-Agent was fixed. Android can clear it when space is low.
    config.osmdroidTileCache = File(context.cacheDir, "map-tiles")
}
