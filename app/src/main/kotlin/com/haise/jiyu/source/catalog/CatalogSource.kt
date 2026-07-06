package com.haise.jiyu.source.catalog

import org.json.JSONArray
import org.json.JSONObject

data class CatalogSource(
    val id: String,
    val name: String,
    val baseUrl: String,
    val language: String,
    val description: String,
    val nsfw: Boolean,
) {
    companion object {
        fun fromJson(obj: JSONObject) = CatalogSource(
            id          = obj.getString("id"),
            name        = obj.getString("name"),
            baseUrl     = obj.getString("baseUrl"),
            language    = obj.optString("language", "en"),
            description = obj.optString("description", ""),
            nsfw        = obj.optBoolean("nsfw", false),
        )

        fun listFromJson(json: String): List<CatalogSource> {
            val array = JSONArray(json)
            return (0 until array.length()).map { fromJson(array.getJSONObject(it)) }
        }
    }
}
