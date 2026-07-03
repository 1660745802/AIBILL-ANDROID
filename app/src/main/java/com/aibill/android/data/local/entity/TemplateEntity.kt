package com.aibill.android.data.local.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "templates")
data class TemplateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val type: String,
    val amount: Int?,
    val categoryId: Int?,
    val accountId: Int?,
    val description: String?,
    val createdAt: Long = System.currentTimeMillis()
)
