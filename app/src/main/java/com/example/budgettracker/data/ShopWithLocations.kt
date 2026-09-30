package com.example.budgettracker.data

import androidx.room.Embedded
import androidx.room.Relation

/** Room relation helper: a shop together with all its stored GPS locations. */
data class ShopWithLocations(
    @Embedded val shop: Shop,
    @Relation(
        parentColumn = "id",
        entityColumn = "shopId"
    )
    val locations: List<ShopLocation>
)
