package com.example.budgettracker.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/** One GPS coordinate belonging to a shop (chain shops have multiple). */
@Entity(
    tableName = "shop_locations",
    foreignKeys = [ForeignKey(
        entity = Shop::class,
        parentColumns = ["id"],
        childColumns = ["shopId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("shopId")]
)
data class ShopLocation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val shopId: Long,
    val latitude: Double,
    val longitude: Double,
    /** Optional human-readable label, e.g. "City centre" or "West branch". */
    val label: String = ""
)
