package com.haise.jiyu.ui.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.compose.AsyncImagePainter
import coil.request.ImageRequest
import coil.transform.Transformation
import coil.transition.Transition
import com.haise.jiyu.R
import com.haise.jiyu.util.ScrambledImageUrl
import compose.icons.TablerIcons
import compose.icons.tablericons.AlertCircle

// ── Stránka s možností opětovného načtení při selhání ────────────────────────

@Composable
fun RetryableAsyncImage(
    url: String,
    contentDescription: String?,
    contentScale: ContentScale,
    modifier: Modifier = Modifier,
    imageModifier: Modifier = Modifier.fillMaxSize(),
    cropBorders: Boolean = false,
    // Skutečná (intrinsic) velikost bitmapy v px - viz [imageDisplayRect]. Bez tohohle by
    // overlay neznal rozdíl mezi rozměrem tohohle Boxu (často fillMaxSize přes celou
    // obrazovku) a skutečně vykresleným obrázkem (letterbox mezery u contentScale jiného
    // než FillBounds), a pozice bublin by driftovaly tím víc, čím dál od okraje stránky.
    onImageSize: ((Size) -> Unit)? = null,
    // Curl čtečky (viz MangaPageCurlReader) zamrazí stránku do bitmapy pro ohýbání JEDNOU, hned
    // jak zná seznam stránek - ne až po dokončení Coilu (síť/dekódování/rozskládání dlaždic přes
    // ScrambledImageUrl níž). Bez tohohle callbacku se tak do zamrazené bitmapy nenávratně "vypálil"
    // buď prázdný/bílý placeholder (obrázek ještě nenačtený), nebo ROZSYPANÉ dlaždice (transformace
    // ScrambledImageUrl ještě neproběhla) - nahlášeno jako "tmavé čáry/kostičky" na KAŽDÉM stylu
    // otáčení, protože všechny sdílí stejné zamrazení bitmapy před ohybem.
    onLoadedChange: ((Boolean) -> Unit)? = null,
    // Curl čtečky zamrazí stránku do bitmapy HNED, jak Coil nahlásí Success - ale globální
    // ImageLoader (viz JiyuApp) má crossfade(true), takže "Success" jen znamená DOKONČENÉ
    // DEKÓDOVÁNÍ, ne že je obrázek na plátně už na 100% neprůhlednosti (prolínací animace
    // pak ještě ~200ms běží). Zamrazená bitmapa tak občas zachytila obrázek uprostřed
    // prolnutí - vybledlý/průsvitný, s "dírou" tam, kde ještě prolnutí sotva začalo (nahlášeno
    // jako "tmavé čáry/kostičky/bílé díry", měnící se podle strany tažení = podle toho, která
    // část stránky se zrovna zamrazovala). Curl čtečky proto crossfade pro svoje 3 rasterizované
    // vrstvy (aktuální/další/předchozí) vypínají - tam se prolnutí stejně nikdy nestihne ani
    // uvidět (2 ze 3 vrstev navíc vůbec nejsou na obrazovce, jen se rasterizují).
    disableCrossfade: Boolean = false,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    var retryTrigger by remember(url) { mutableStateOf(0) }
    var isError by remember(url) { mutableStateOf(false) }

    Box(modifier = modifier) {
        val request = remember(url, retryTrigger, cropBorders, disableCrossfade) {
            val scramble = ScrambledImageUrl.parse(url)
            val transforms = buildList<Transformation> {
                if (cropBorders) add(CropBordersTransformation())
                scramble?.let { add(TileDescrambleTransformation(it.grid, it.seed)) }
            }
            ImageRequest.Builder(context)
                .data(url)
                .apply { if (transforms.isNotEmpty()) transformations(transforms) }
                .apply { if (disableCrossfade) transitionFactory(Transition.Factory.NONE) }
                .build()
        }
        AsyncImage(
            model = request,
            contentDescription = contentDescription,
            contentScale = contentScale,
            modifier = imageModifier,
            onState = { state ->
                isError = state is AsyncImagePainter.State.Error
                if (state is AsyncImagePainter.State.Success) {
                    val painterSize = state.painter.intrinsicSize
                    if (painterSize.isSpecified && painterSize.width > 0f && painterSize.height > 0f) {
                        onImageSize?.invoke(painterSize)
                    }
                }
                onLoadedChange?.invoke(state is AsyncImagePainter.State.Success)
            },
        )
        if (isError) {
            Box(modifier = Modifier.matchParentSize(), contentAlignment = Alignment.Center) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(TablerIcons.AlertCircle, contentDescription = null, tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(32.dp))
                    Spacer(Modifier.height(8.dp))
                    Text(stringResource(R.string.reader_page_load_failed), color = Color.White.copy(alpha = 0.7f), fontSize = 13.sp)
                    Spacer(Modifier.height(8.dp))
                    OutlinedButton(onClick = { isError = false; retryTrigger++ }) {
                        Text(stringResource(R.string.common_retry))
                    }
                }
            }
        }
    }
}
