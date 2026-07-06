package com.haise.jiyu.data.db.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

val CATEGORY_COLORS = listOf(
    "#8B5CF6", "#22D3EE", "#EC4899", "#10B981",
    "#F59E0B", "#EF4444", "#6366F1", "#84CC16",
)

@Entity(tableName = "category")
data class CategoryEntity(
    @PrimaryKey val id: String,
    val name: String,
    val colorHex: String = "#8B5CF6",
)

@Entity(
    tableName = "manga_category",
    primaryKeys = ["mangaId", "categoryId"],
    foreignKeys = [
        ForeignKey(
            entity = MangaEntity::class,
            parentColumns = ["id"],
            childColumns = ["mangaId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["categoryId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("categoryId")],
)
data class MangaCategoryEntity(
    val mangaId: String,
    val categoryId: String,
)
