package com.haise.jiyu.data.db.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import java.util.UUID

/**
 * Uživatelem přidaný zdroj postavený na generické Madara šabloně
 * (WordPress téma používané mnoha manga/manhwa weby bez oficiálního API).
 * Appka proti němu parsuje HTML pomocí Jsoup podle standardních
 * Madara CSS selektorů - viz [com.haise.jiyu.source.madara.MadaraSource].
 */
@Entity(tableName = "custom_source")
data class CustomSourceEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val name: String,
    val baseUrl: String,
)
