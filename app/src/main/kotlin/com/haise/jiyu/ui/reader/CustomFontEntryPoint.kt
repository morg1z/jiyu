package com.haise.jiyu.ui.reader

import com.haise.jiyu.translate.CustomFontRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Přístup k [CustomFontRepository] přímo z composable - stejný důvod a stejný vzor jako
 * [TextPatchEntryPoint]: `BubbleOverlayLayer` volají dvě různé čtečky bez společného
 * ViewModelu, protahovat závislost parametrem přes celý strom composables by znamenalo měnit
 * podpisy několika z nich jen kvůli téhle jedné věci.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface CustomFontEntryPoint {
    fun customFontRepository(): CustomFontRepository
}
