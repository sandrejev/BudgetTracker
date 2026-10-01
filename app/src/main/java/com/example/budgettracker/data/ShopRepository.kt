package com.example.budgettracker.data

import kotlinx.coroutines.flow.Flow
import kotlin.math.*

class ShopRepository(private val dao: ShopDao) {

    // ── Shops ─────────────────────────────────────────────────────────────────

    fun allShopsWithLocations(): Flow<List<ShopWithLocations>> =
        dao.getAllShopsWithLocations()

    fun allShops(): Flow<List<Shop>> = dao.getAllShops()

    suspend fun allShopsOnce(): List<Shop> = dao.getAllShopsOnce()

    /**
     * Returns the saved spelling of [name] (case-insensitive, so "lidl" becomes "LIDL"),
     * creating the shop if it doesn't exist yet. Blank names give null.
     */
    suspend fun resolveOrCreate(name: String?): String? {
        val trimmed = name?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        dao.getAllShopsOnce().firstOrNull { it.name.equals(trimmed, ignoreCase = true) }
            ?.let { return it.name }
        dao.insertShop(Shop(name = trimmed))
        return trimmed
    }

    suspend fun insertShop(shop: Shop): Long = dao.insertShop(shop)
    suspend fun updateShop(shop: Shop) = dao.updateShop(shop)
    suspend fun deleteShop(shop: Shop) = dao.deleteShop(shop)
    suspend fun findByName(name: String): Shop? = dao.findByName(name)

    // ── Locations ─────────────────────────────────────────────────────────────

    suspend fun getLocationsForShop(shopId: Long): List<ShopLocation> =
        dao.getLocationsForShop(shopId)

    suspend fun insertLocation(location: ShopLocation): Long =
        dao.insertLocation(location)

    suspend fun updateLocation(location: ShopLocation) =
        dao.updateLocation(location)

    suspend fun deleteLocation(location: ShopLocation) =
        dao.deleteLocation(location)

    // ── GPS proximity ─────────────────────────────────────────────────────────

    /**
     * Returns the nearest known shop whose closest stored location is within
     * [radiusMeters] of the given coordinates. Returns null if nothing is near.
     */
    suspend fun findNearbyShop(
        lat: Double,
        lng: Double,
        radiusMeters: Double = 100.0
    ): Shop? {
        val allLocations = dao.getAllLocations()
        val nearest = allLocations
            .map { loc -> loc to haversineMeters(lat, lng, loc.latitude, loc.longitude) }
            .filter { (_, dist) -> dist <= radiusMeters }
            .minByOrNull { (_, dist) -> dist }
            ?.first
            ?: return null

        return dao.getShopById(nearest.shopId)
    }

    // ── Haversine ─────────────────────────────────────────────────────────────

    companion object {
        private const val EARTH_RADIUS_M = 6_371_000.0

        fun haversineMeters(
            lat1: Double, lng1: Double,
            lat2: Double, lng2: Double
        ): Double {
            val dLat = Math.toRadians(lat2 - lat1)
            val dLng = Math.toRadians(lng2 - lng1)
            val a = sin(dLat / 2).pow(2) +
                    cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                    sin(dLng / 2).pow(2)
            return EARTH_RADIUS_M * 2 * asin(sqrt(a))
        }
    }
}
