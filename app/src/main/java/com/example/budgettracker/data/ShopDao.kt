package com.example.budgettracker.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface ShopDao {

    // ── Shops ─────────────────────────────────────────────────────────────────

    @Transaction
    @Query("SELECT * FROM shops ORDER BY name ASC")
    fun getAllShopsWithLocations(): Flow<List<ShopWithLocations>>

    @Transaction
    @Query("SELECT * FROM shops ORDER BY name ASC")
    suspend fun getAllShopsWithLocationsOnce(): List<ShopWithLocations>

    @Query("SELECT * FROM shops ORDER BY name ASC")
    fun getAllShops(): Flow<List<Shop>>

    @Query("SELECT * FROM shops WHERE id = :id")
    suspend fun getShopById(id: Long): Shop?

    @Query("SELECT * FROM shops WHERE LOWER(name) = LOWER(:name) LIMIT 1")
    suspend fun findByName(name: String): Shop?

    @Insert
    suspend fun insert(shop: Shop): Long

    @Insert
    suspend fun insertShop(shop: Shop): Long

    @Update
    suspend fun updateShop(shop: Shop)

    @Delete
    suspend fun deleteShop(shop: Shop)

    // ── Locations ─────────────────────────────────────────────────────────────

    @Query("SELECT * FROM shop_locations")
    suspend fun getAllLocations(): List<ShopLocation>

    @Query("SELECT * FROM shop_locations WHERE shopId = :shopId ORDER BY id ASC")
    suspend fun getLocationsForShop(shopId: Long): List<ShopLocation>

    @Insert
    suspend fun insertLocation(location: ShopLocation): Long

    @Update
    suspend fun updateLocation(location: ShopLocation)

    @Delete
    suspend fun deleteLocation(location: ShopLocation)
}
