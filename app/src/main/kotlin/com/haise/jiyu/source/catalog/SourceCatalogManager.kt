package com.haise.jiyu.source.catalog

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SourceCatalogManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val catalog: List<CatalogSource> by lazy {
        try {
            val json = context.assets.open("source_catalog.json").bufferedReader().readText()
            CatalogSource.listFromJson(json)
        } catch (_: Exception) {
            emptyList()
        }
    }
}
