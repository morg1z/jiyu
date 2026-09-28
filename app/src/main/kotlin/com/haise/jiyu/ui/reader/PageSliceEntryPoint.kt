package com.haise.jiyu.ui.reader

import com.haise.jiyu.util.PageSlicer
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Přístup k [PageSlicer] přímo z composable - stejný vzor jako [TextPatchEntryPoint]:
 * `WebtoonPage` žádný ViewModel nedostává a protahovat slicer parametrem přes celý
 * strom by znamenalo měnit podpisy jen kvůli jedné závislosti.
 */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface PageSliceEntryPoint {
    fun pageSlicer(): PageSlicer
}
