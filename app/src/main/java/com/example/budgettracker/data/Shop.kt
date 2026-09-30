package com.example.budgettracker.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "shops")
data class Shop(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** Clearbit URL, a user-supplied URL, or a local content:// URI. */
    val logoUri: String? = null
)
