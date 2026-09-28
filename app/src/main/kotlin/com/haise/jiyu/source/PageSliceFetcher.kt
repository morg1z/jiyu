package com.haise.jiyu.source

import android.graphics.drawable.BitmapDrawable
import coil.ImageLoader
import coil.decode.DataSource
import coil.fetch.DrawableResult
import coil.fetch.FetchResult
import coil.fetch.Fetcher
import coil.request.Options
import com.haise.jiyu.util.PageSliceRequest
import com.haise.jiyu.util.PageSlicer
import java.io.IOException

/**
 * Coil Fetcher pro jeden řez extrémně vysoké stránky (viz [PageSliceRequest] a
 * [PageSlicer]). Dekóduje jen svůj výřez přes `BitmapRegionDecoder` v nativním
 * rozlišení - celá stránka se tak nikdy nematerializuje jako jedna bitmapa
 * (800x30000 px = ~96 MB, nad GPU texture limit ~8192 px se ani nevykreslí).
 *
 * Vrací rovnou [DrawableResult] (nejde přes Decoder fázi): řez už je finální bitmapa,
 * žádné Transformace se na něj NEAPLIKUJÍ - případný ořez okrajů už vyřešil plán
 * (řezy se počítají z content rectu), scramble/lazy stránky se do řezů nikdy nedostanou.
 */
class PageSliceFetcher(
    private val data: PageSliceRequest,
    private val options: Options,
    private val slicer: PageSlicer,
) : Fetcher {

    override suspend fun fetch(): FetchResult {
        val bitmap = slicer.decodeSlice(data)
            ?: throw IOException("Řez ${data.sliceIndex} stránky se nepodařil dekódovat: ${data.pageUrl}")
        return DrawableResult(
            drawable = BitmapDrawable(options.context.resources, bitmap),
            // Dekódujeme nativně, nikdy nevzorkovaně - označit "sampled" by jen kazilo
            // metriky; zoom čtečky čte z téhle bitmapy plné rozlišení.
            isSampled = false,
            dataSource = DataSource.DISK,
        )
    }

    class Factory(private val slicer: PageSlicer) : Fetcher.Factory<PageSliceRequest> {
        override fun create(data: PageSliceRequest, options: Options, imageLoader: ImageLoader): Fetcher =
            PageSliceFetcher(data, options, slicer)
    }
}
